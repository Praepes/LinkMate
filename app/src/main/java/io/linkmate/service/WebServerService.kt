package io.linkmate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import io.linkmate.R
import io.linkmate.data.local.HaConfigEntity
import io.linkmate.data.repository.HomeAssistantRepository
import io.linkmate.data.repository.ReminderRepository
import io.linkmate.data.repository.WebServerConfigRepository
import io.linkmate.data.repository.SettingsRepository
import io.linkmate.data.local.SettingsEntity
import dagger.hilt.android.AndroidEntryPoint
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Cookie
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.application.call
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import javax.inject.Inject

@AndroidEntryPoint
class WebServerService : Service() {

    @Inject lateinit var webServerConfigRepository: WebServerConfigRepository
    @Inject lateinit var homeAssistantRepository: HomeAssistantRepository
    @Inject lateinit var reminderRepository: ReminderRepository
    @Inject lateinit var settingsRepository: SettingsRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var server: ApplicationEngine? = null
    private var portCollectJob: Job? = null
    private var passwordCollectJob: Job? = null
    @Volatile private var cachedPassword: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            // 确保依赖注入已完成
            if (!::webServerConfigRepository.isInitialized) {
                Log.e(TAG, "Dependency injection not completed, delaying initialization")
                // 延迟初始化，等待 Hilt 完成注入
                serviceScope.launch {
                    var retries = 0
                    while (!::webServerConfigRepository.isInitialized && retries < 10) {
                        kotlinx.coroutines.delay(100)
                        retries++
                    }
                    if (::webServerConfigRepository.isInitialized) {
                        initializeService()
                    } else {
                        Log.e(TAG, "Failed to initialize dependencies after retries")
                    }
                }
                return
            }
            initializeService()
        } catch (e: Exception) {
            Log.e(TAG, "Critical error in onCreate: ${e.message}", e)
            e.printStackTrace()
            // 即使出错也要启动前台服务，避免服务被系统杀死
            try {
                startForeground(NOTIFICATION_ID, createNotification("服务启动中..."))
            } catch (ex: Exception) {
                Log.e(TAG, "Failed to start foreground service: ${ex.message}", ex)
            }
        }
    }
    
    private fun initializeService() {
        try {
            startForeground(NOTIFICATION_ID, createNotification("正在启动本地 Web 服务"))
            observePasswordAndCache()
            observePortAndRunServer()
            // Ensure default password (4-digit random) on first start
            serviceScope.launch {
                try {
                    val pwd = try {
                        withTimeout(2000L) {
                            webServerConfigRepository.passwordFlow().firstOrNull()
                        } ?: ""
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading password on init: ${e.message}", e)
                        ""
                    }
                    if (pwd.isBlank()) {
                        val def = (1000..9999).random().toString()
                        webServerConfigRepository.setPassword(def)
                        Log.d(TAG, "Default web password generated: $def")
                        cachedPassword = def
                    } else {
                        cachedPassword = pwd
                        Log.d(TAG, "Password loaded from storage: ${pwd.take(2)}**")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in password initialization: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in initializeService: ${e.message}", e)
            e.printStackTrace()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service is sticky to keep running
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observePortAndRunServer() {
        portCollectJob?.cancel()
        portCollectJob = serviceScope.launch {
            try {
                if (!::webServerConfigRepository.isInitialized) {
                    Log.e(TAG, "webServerConfigRepository not initialized in observePortAndRunServer")
                    return@launch
                }
                webServerConfigRepository.portFlow().collectLatest { port ->
                    try {
                        Log.d(TAG, "Web server port update detected: $port, restarting...")
                        updateNotification("Web 服务运行端口 $port")
                        restartServer(port)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in port flow collection: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in observePortAndRunServer: ${e.message}", e)
            }
        }
    }

    private fun observePasswordAndCache() {
        passwordCollectJob?.cancel()
        passwordCollectJob = serviceScope.launch {
            try {
                if (!::webServerConfigRepository.isInitialized) {
                    Log.e(TAG, "webServerConfigRepository not initialized in observePasswordAndCache")
                    return@launch
                }
                webServerConfigRepository.passwordFlow().collect { pwd ->
                    try {
                        cachedPassword = pwd
                        Log.d(TAG, "Password cached: ${pwd.take(2)}**")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in password flow collection: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in observePasswordAndCache: ${e.message}", e)
            }
        }
    }

    private fun restartServer(port: Int) {
        stopServer()
        startServer(port)
    }

    private fun startServer(port: Int) {
        Log.d(TAG, "Starting embedded server on 0.0.0.0:$port")
        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(ContentNegotiation) {
                gson()
            }
            routing {
                get("/health") {
                    Log.d(TAG, "GET /health")
                    call.respondText("OK")
                }
                get("/raw") {
                    Log.d(TAG, "GET /raw")
                    call.respondText("hello")
                }
                head("/") {
                    Log.d(TAG, "HEAD /")
                    call.respond(HttpStatusCode.OK)
                }
                get("/") {
                    Log.d(TAG, "GET / - entering handler")
                    try {
                        Log.d(TAG, "GET / - checking auth directly")
                        var authed = false
                        try {
                            // 直接读取HTTP headers 读取 Cookie，避免Ktor cookies API 可能导致的阻塞
                            val cookieHeader = call.request.headers["Cookie"]
                            Log.d(TAG, "GET / - Cookie header: ${cookieHeader?.take(50)}")
                            val cookieValue = cookieHeader?.split(";")?.find { it.trim().startsWith("ws_auth=") }
                                ?.substringAfter("=")?.trim()
                            Log.d(TAG, "GET / - extracted cookie value: $cookieValue")
                            if (cookieValue != null && cookieValue == cachedPassword && cachedPassword.isNotBlank()) {
                                authed = true
                                Log.d(TAG, "GET / - authenticated")
                            } else {
                                Log.d(TAG, "GET / - not authenticated (cookie=$cookieValue, cached=${cachedPassword.take(2)}**)")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "GET / - error checking auth: ${e.message}", e)
                            e.printStackTrace()
                            authed = false
                        }
                        val currentPort = port
                        Log.d(TAG, "GET / authed=$authed, port=$currentPort")
                        val html = if (!authed) {
                            """
                            <!DOCTYPE html>
                            <html>
                              <head><meta charset="utf-8"><title>HP Smart Panel</title></head>
                              <body>
                                <h2>Web 登录</h2>
                                <form method="post" action="/login">
                                  <div>
                                    <label>Web 密码 (4-12位):</label>
                                    <input type="text" name="password" value="" />
                                  </div>
                                  <button type="submit">进入</button>
                                </form>
                              </body>
                            </html>
                            """.trimIndent()
                        } else {
                            try {
                                val currentCfg = homeAssistantRepository.getHaConfig().firstOrNull()
                                val baseUrl = currentCfg?.baseUrl ?: ""
                                val tokenMasked = currentCfg?.token?.take(5)?.plus("****") ?: ""
                                val currentSettings = settingsRepository.getSettings().firstOrNull() ?: SettingsEntity()
                                val apiKeyMasked = if (currentSettings.heFengApiKey.isNotBlank()) currentSettings.heFengApiKey.take(5) + "****" else ""
                                val proximity = if (currentSettings.enableProximityWake) "开启" else "关闭"
                                val currentPwd = cachedPassword
                                val weatherRefreshInterval = currentSettings.weatherRefreshIntervalMinutes
                                val gpsUpdateInterval = currentSettings.gpsUpdateIntervalMinutes
                                
                                // 获取最近的提醒列表（最10条）
                                val reminders = try {
                                    reminderRepository.getAllReminders().firstOrNull()?.take(10) ?: emptyList()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error loading reminders: ${e.message}", e)
                                    emptyList()
                                }
                                
                                val remindersHtml = if (reminders.isEmpty()) {
                                    "<p>暂无提醒</p>"
                                } else {
                                    reminders.joinToString("<br>") { reminder ->
                                        val dateStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                            .format(java.util.Date(reminder.timestamp))
                                        val status = if (reminder.isActive) "活跃" else "关闭"
                                        "<p><strong>[$dateStr]</strong> $status: ${reminder.message}</p>"
                                    }
                                }
                                
                                """
                                <!DOCTYPE html>
                                <html>
                                  <head><meta charset="utf-8"><title>HP Smart Panel</title></head>
                                  <body>
                                    <h2>添加提醒</h2>
                                    <form method="post" action="/add_reminder_form">
                                      <div>
                                        <label>提醒内容:</label>
                                        <input type="text" name="message" placeholder="请输入提醒内容" required style="width: 300px;" />
                                      </div>
                                      <button type="submit">发送提醒到手机</button>
                                    </form>
                                    
                                    <hr>
                                    
                                    <h2>最近提醒/h2>
                                    $remindersHtml
                                    
                                    <hr>
                                    
                                    <h2>当前配置（只读展示）</h2>
                                    <p>Web 端口: $currentPort</p>
                                    <p>HA Base URL: $baseUrl</p>
                                    <p>HA Token: $tokenMasked</p>
                                    <p>和风天气 API Key: $apiKeyMasked</p>
                                    <p>靠近唤醒: $proximity</p>
                                    <p>天气刷新间隔: ${weatherRefreshInterval} 分钟</p>
                                    <p>GPS 更新间隔: ${gpsUpdateInterval} 分钟</p>
                                    <p>Web 密码: $currentPwd</p>

                                    <h2>更新配置</h2>
                                    <form method="post" action="/config">
                                      <div>
                                        <label>HA Base URL:</label>
                                        <input type="text" name="ha_base_url" placeholder="http://192.168.x.x:8123" value="" />
                                      </div>
                                      <div>
                                        <label>HA Token:</label>
                                        <input type="text" name="ha_token" placeholder="Long-Lived Token" value="" />
                                      </div>
                                      <div>
                                        <label>Web 端口:</label>
                                        <input type="number" name="web_port" placeholder="$currentPort" value="" />
                                      </div>
                                      <div>
                                        <label>和风天气 API Key:</label>
                                        <input type="text" name="hefeng_api_key" placeholder="${apiKeyMasked}" value="" />
                                      </div>
                                      <div>
                                        <label>启用靠近唤醒:</label>
                                        <input type="checkbox" name="enable_proximity_wake" />
                                      </div>
                                      <div>
                                        <label>天气刷新间隔 (分钟, 至少1分钟):</label>
                                        <input type="number" name="weather_refresh_interval" placeholder="$weatherRefreshInterval" value="" min="1" />
                                      </div>
                                      <div>
                                        <label>GPS 更新间隔 (分钟, 至少1分钟):</label>
                                        <input type="number" name="gps_update_interval" placeholder="$gpsUpdateInterval" value="" min="1" />
                                      </div>
                                      <div>
                                        <label>Web 密码 (4-12位):</label>
                                        <input type="text" name="web_password" placeholder="$currentPwd" value="" />
                                      </div>
                                      <button type="submit">保存</button>
                                    </form>
                                  </body>
                                </html>
                                """.trimIndent()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error generating HTML: ${e.message}", e)
                                "<html><body><h1>Error loading config: ${e.message}</h1></body></html>"
                            }
                        }
                        Log.d(TAG, "GET / - html generated, length=${html.length}, responding now")
                        call.respondText(html, contentType = ContentType.Text.Html)
                        Log.d(TAG, "GET / - response sent")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in GET /: ${e.message}", e)
                        call.respondText("<html><body><h1>Server Error: ${e.message}</h1></body></html>", contentType = ContentType.Text.Html)
                    }
                }
                get("{...}") {
                    val p = call.request.path()
                    Log.d(TAG, "GET fallback path=$p")
                    call.respondText("404 Not Found: $p", status = HttpStatusCode.NotFound)
                }

                post("/login") {
                    Log.d(TAG, "POST /login")
                    try {
                        val params = call.receiveParameters()
                        val input = params["password"]?.trim().orEmpty()
                        val saved = cachedPassword
                        if (input.isNotBlank() && input == saved) {
                            call.response.cookies.append(
                                Cookie(
                                    name = "ws_auth",
                                    value = saved,
                                    httpOnly = false,
                                    secure = false,
                                    path = "/",
                                    extensions = mapOf("SameSite" to "Lax")
                                )
                            )
                            call.respondRedirect("/")
                        } else {
                            call.respond(HttpStatusCode.Unauthorized, "密码错误")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in POST /login: ${e.message}", e)
                        call.respond(HttpStatusCode.InternalServerError, "服务器错误 ${e.message}")
                    }
                }

                post("/config") {
                    Log.d(TAG, "POST /config - entering")
                    try {
                        val cookieHeader = call.request.headers["Cookie"]
                        val cookieValue = cookieHeader?.split(";")?.find { it.trim().startsWith("ws_auth=") }
                            ?.substringAfter("=")?.trim()
                        val isAuth = cookieValue != null && cookieValue == cachedPassword && cachedPassword.isNotBlank()
                        Log.d(TAG, "POST /config - auth check: isAuth=$isAuth, cookie=${cookieValue?.take(2)}**, cached=${cachedPassword.take(2)}**")
                        
                        if (!isAuth) {
                            Log.d(TAG, "POST /config - unauthorized")
                            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                            return@post
                        }
                        
                        val params = call.receiveParameters()
                        Log.d(TAG, "POST /config - params received")
                        val haBaseUrl = params["ha_base_url"]?.trim().orEmpty()
                        val haToken = params["ha_token"]?.trim().orEmpty()
                        val webPort = params["web_port"]?.toIntOrNull()
                        val heFengApiKey = params["hefeng_api_key"]?.trim().orEmpty()
                        val enableProximityWake = params.contains("enable_proximity_wake")
                        val weatherRefreshInterval = params["weather_refresh_interval"]?.toIntOrNull()?.coerceAtLeast(1)
                        val gpsUpdateInterval = params["gps_update_interval"]?.toIntOrNull()?.coerceAtLeast(1)
                        val webPassword = params["web_password"]?.trim().orEmpty()

                        // 更新 HA 配置
                        if (haBaseUrl.isNotBlank() || haToken.isNotBlank()) {
                            val current = homeAssistantRepository.getHaConfig().firstOrNull() ?: HaConfigEntity()
                            val updated = current.copy(
                                baseUrl = if (haBaseUrl.isNotBlank()) haBaseUrl else current.baseUrl,
                                token = if (haToken.isNotBlank()) haToken else current.token
                            )
                            homeAssistantRepository.saveHaConfig(updated)
                        }

                        // 更新 Web 端口（触发服务重启监听）
                        if (webPort != null && webPort in 1024..65535) {
                            webServerConfigRepository.setPort(webPort)
                        }

                        // 更新应用设置
                        if (heFengApiKey.isNotBlank() || params.contains("enable_proximity_wake") || weatherRefreshInterval != null || gpsUpdateInterval != null) {
                            val currentSettings = settingsRepository.getSettings().firstOrNull() ?: SettingsEntity()
                            val updatedSettings = currentSettings.copy(
                                heFengApiKey = if (heFengApiKey.isNotBlank()) heFengApiKey else currentSettings.heFengApiKey,
                                enableProximityWake = enableProximityWake,
                                weatherRefreshIntervalMinutes = weatherRefreshInterval ?: currentSettings.weatherRefreshIntervalMinutes,
                                gpsUpdateIntervalMinutes = gpsUpdateInterval ?: currentSettings.gpsUpdateIntervalMinutes
                            )
                            settingsRepository.saveSettings(updatedSettings)
                        }

                        // 更新 Web 密码
                        if (webPassword.isNotBlank() && webPassword.length in 4..12) {
                            webServerConfigRepository.setPassword(webPassword)
                            call.response.cookies.append(
                                Cookie(
                                    name = "ws_auth",
                                    value = webPassword,
                                    httpOnly = false,
                                    secure = false,
                                    path = "/",
                                    extensions = mapOf("SameSite" to "Lax")
                                )
                            )
                        }

                        Log.d(TAG, "POST /config - responding with success")
                        call.respondRedirect("/")
                    } catch (e: Exception) {
                        Log.e(TAG, "POST /config - error: ${e.message}", e)
                        e.printStackTrace()
                        call.respondText("<html><body><h1>Error: ${e.message}</h1><a href=\"/\">返回</a></body></html>", contentType = ContentType.Text.Html)
                    }
                }

                post("/add_reminder") {
                    Log.d(TAG, "POST /add_reminder")
                    data class ReminderPayload(val message: String, val timestamp: Long? = null, val isActive: Boolean? = true)
                    val payload = try { call.receive<ReminderPayload>() } catch (e: Exception) { null }
                    if (payload == null || payload.message.isBlank()) {
                        call.respond(io.ktor.http.HttpStatusCode.BadRequest, mapOf("error" to "invalid payload"))
                        return@post
                    }
                    val reminder = io.linkmate.data.local.ReminderEntity(
                        message = payload.message,
                        timestamp = payload.timestamp ?: System.currentTimeMillis(),
                        isActive = payload.isActive ?: true
                    )
                    reminderRepository.insertReminder(reminder)
                    call.respond(mapOf("status" to "saved"))
                }

                post("/add_reminder_form") {
                    Log.d(TAG, "POST /add_reminder_form")
                    try {
                        // 检查认证（因为表单在认证后的页面）
                        val cookieHeader = call.request.headers["Cookie"]
                        val cookieValue = cookieHeader?.split(";")?.find { it.trim().startsWith("ws_auth=") }
                            ?.substringAfter("=")?.trim()
                        val isAuth = cookieValue != null && cookieValue == cachedPassword && cachedPassword.isNotBlank()
                        
                        if (!isAuth) {
                            Log.d(TAG, "POST /add_reminder_form - unauthorized")
                            call.respond(HttpStatusCode.Unauthorized, "未授权，请先登录")
                            return@post
                        }
                        
                        val params = call.receiveParameters()
                        val message = params["message"]?.trim().orEmpty()
                        
                        if (message.isBlank()) {
                            call.respondText("<html><body><h1>错误：提醒内容不能为空/h1><a href=\"/\">返回</a></body></html>", contentType = ContentType.Text.Html)
                            return@post
                        }
                        
                        val reminder = io.linkmate.data.local.ReminderEntity(
                            message = message,
                            timestamp = System.currentTimeMillis(),
                            isActive = true
                        )
                        reminderRepository.insertReminder(reminder)
                        Log.d(TAG, "Reminder added: $message")
                        
                        // 重定向回主页
                        call.respondRedirect("/")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in POST /add_reminder_form: ${e.message}", e)
                        call.respondText("<html><body><h1>错误: ${e.message}</h1><a href=\"/\">返回</a></body></html>", contentType = ContentType.Text.Html)
                    }
                }
            }
        }.start(false)
    }

    private fun isAuthenticated(call: ApplicationCall): Boolean {
        return try {
            Log.d(TAG, "isAuthenticated: step 1")
            val cookies = call.request.cookies
            Log.d(TAG, "isAuthenticated: step 2, got cookies object")
            val cookie: String?
            try {
                cookie = cookies["ws_auth"]
                Log.d(TAG, "isAuthenticated: step 2.5, accessed cookie: $cookie")
            } catch (e: Exception) {
                Log.e(TAG, "isAuthenticated: Error accessing cookie ws_auth: ${e.message}", e)
                return false
            }
            Log.d(TAG, "isAuthenticated: step 3, cookie=$cookie")
            if (cookie == null) {
                Log.d(TAG, "isAuthenticated: no cookie found, returning false")
                return false
            }
            Log.d(TAG, "isAuthenticated: step 4, getting cached password")
            val saved = cachedPassword
            Log.d(TAG, "isAuthenticated: step 5, comparing. cookie=${cookie.take(2)}**, cached=${saved.take(2)}**")
            val result = saved.isNotBlank() && cookie == saved
            Log.d(TAG, "isAuthenticated: step 6, result=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error in isAuthenticated: ${e.message}", e)
            e.printStackTrace()
            false
        }
    }

    private fun stopServer() {
        try {
            server?.stop(1000, 2000)
            server = null
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping server: ${t.message}")
        }
    }

    private fun ensureChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Web Server",
                    NotificationManager.IMPORTANCE_LOW
                )
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification channel: ${e.message}", e)
        }
    }

    private fun createNotification(text: String): Notification {
        try {
            ensureChannel()
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("HP Smart Panel Web 服务")
                .setContentText(text)
                .setOngoing(true)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification: ${e.message}", e)
            // 返回一个基本的通知，避免崩溃
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Web 服务")
                .setContentText(text)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification(text))
    }

    companion object {
        private const val TAG = "WebServerService"
        private const val CHANNEL_ID = "web_server_channel"
        private const val NOTIFICATION_ID = 1002
    }
}


