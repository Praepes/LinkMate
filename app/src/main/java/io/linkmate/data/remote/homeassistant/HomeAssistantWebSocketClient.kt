package io.linkmate.data.remote.homeassistant

import android.util.Log
import io.linkmate.data.local.HaConfigEntity
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.CancellationException

private const val TAG = "HaWebSocketClient"
private const val RECONNECT_INTERVAL_MS = 5000L // 5 seconds
private const val PING_INTERVAL_MS = 30000L // Send a ping every 30 seconds

// Define an enum to represent the expected result type
enum class ExpectedResponseType {
    ENTITY_STATES,
    UNIT_RESULT,
    // Add other types if needed (e.g., for call_service specific responses)
}

// Store a wrapper object in pendingResults
data class PendingRequest(
    val deferred: CompletableDeferred<Any>,
    val expectedType: ExpectedResponseType
)

@Singleton
class HomeAssistantWebSocketClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) : CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = Dispatchers.IO + job

    private var webSocket: WebSocket? = null
    private var haConfig: HaConfigEntity? = null
    private val messageIdCounter = AtomicInteger(1)
    private var reconnectionJob: Job? = null
    private var pingJob: Job? = null // Added for periodic ping
    private val pendingResults = ConcurrentHashMap<Int, PendingRequest>() // Modified to store PendingRequest
    private var authenticatedDeferred: CompletableDeferred<Unit>? = null
    private var subscriptionDeferred: CompletableDeferred<Unit>? = null // Added

    // Keep track of the ID for the subscribe_events message
    private var subscribeEventsMessageId: Int? = null
    private var subscribeServiceCallsMessageId: Int? = null
    
    // Flag to track authentication failure and prevent auto-reconnect
    private var hasAuthFailed = false
    private var authFailureMessage: String? = null

    private val _entityStateUpdates = MutableSharedFlow<HaEntityState>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val entityStateUpdates: SharedFlow<HaEntityState> = _entityStateUpdates.asSharedFlow()

    private val _allEntityStates = MutableSharedFlow<List<HaEntityState>>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val allEntityStates: SharedFlow<List<HaEntityState>> = _allEntityStates.asSharedFlow()

    private val _connectionStatus = MutableStateFlow(false)
    val connectionStatus: StateFlow<Boolean> = _connectionStatus.asStateFlow()
    
    // StateFlow to expose authentication error message
    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val connectionMutex = Mutex()

    suspend fun connect(config: HaConfigEntity, isReconnect: Boolean = false) {
        connectionMutex.withLock {
            // 检查连接配置是否改变（只比�?baseUrl �?token，因�?selectedEntities 改变不需要重新连接）
            val currentConfig = haConfig
            val configChanged = currentConfig == null || 
                               currentConfig.baseUrl != config.baseUrl || 
                               currentConfig.token != config.token
            
            // 如果连接配置未改变且已连接，跳过连接
            if (!configChanged && _connectionStatus.value) {
                Log.d(TAG, "Already connected with the same config (baseUrl and token). Skipping new connection.")
                // 即使配置对象不同，只�?baseUrl �?token 相同，就更新配置对象（以更新 selectedEntities 等字段）
                haConfig = config
                return
            }

            // Reset auth failure flag when connecting with a new config
            if (configChanged) {
                hasAuthFailed = false
                authFailureMessage = null
                _authError.value = null
            }

            haConfig = config
            
            if (!isReconnect) { // Only cancel existing reconnection job if this is a direct connect
                reconnectionJob?.cancel()
                reconnectionJob = null
            }

            // connectInternal will now suspend until auth is done or fails.
            // Authentication errors are now handled gracefully without throwing exceptions.
            try {
                connectInternal()
            } catch (e: Throwable) {
                // Log the error but don't propagate it to prevent app crash
                Log.e(TAG, "Connection attempt failed: ${e.message}", e)
            }
        }
    }

    private suspend fun connectInternal() {
        val config = haConfig
        if (config == null) {
            Log.e(TAG, "HA config is null, cannot connect WebSocket.")
            return
        }

        pendingResults.forEach { (_, pendingRequest) ->
            if (!pendingRequest.deferred.isCompleted) pendingRequest.deferred.cancel(CancellationException("New connection replacing previous one."))
        }
        pendingResults.clear()

        authenticatedDeferred?.let {
            if (!it.isCompleted) it.cancel(CancellationException("Reinitializing auth deferred"))
        }
        authenticatedDeferred = CompletableDeferred()

        subscriptionDeferred?.let {
            if (!it.isCompleted) it.cancel(CancellationException("Reinitializing subscription deferred"))
        }
        subscriptionDeferred = CompletableDeferred()
        
        subscribeEventsMessageId = null // Reset subscription ID on new connection
        subscribeServiceCallsMessageId = null
        
        // Reset auth failure flag for new connection attempt (unless it's a reconnect after auth failure)
        if (!hasAuthFailed) {
            authFailureMessage = null
            _authError.value = null
        }

        messageIdCounter.set(1)

        // Cancel any existing ping job before a new connection attempt
        pingJob?.cancel()
        pingJob = null

        webSocket?.let {
            try {
                it.close(1000, "Initiating new connection")
            } catch (t: Throwable) {
                Log.w(TAG, "Error closing previous WebSocket: ${t.message}")
            } finally {
                webSocket = null
            }
        }

        val wsUrl = when {
            config.baseUrl.startsWith("https://", ignoreCase = true) -> config.baseUrl.replaceFirst("https://", "wss://").trimEnd('/') + "/api/websocket"
            config.baseUrl.startsWith("http://", ignoreCase = true) -> config.baseUrl.replaceFirst("http://", "ws://").trimEnd('/') + "/api/websocket"
            else -> config.baseUrl.trimEnd('/') + "/api/websocket"
        }

        val request = Request.Builder().url(wsUrl).build()

        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket Opened: ${response.message}")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.v(TAG, "Received: $text")
                try {
                    handleWebSocketMessage(webSocket, text)
                } catch (t: Throwable) {
                    Log.e(TAG, "Error handling WS message: ${t.message}", t)
                }catch (e:Throwable){
                    Log.e(TAG, "Unknown error handling WS message: ${e.message}", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket Closing: $code / $reason")
                try {
                    webSocket.close(1000, null)
                } catch (t: Throwable) {
                    Log.w(TAG, "Error while closing WS: ${t.message}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket Closed: $code / $reason")
                _connectionStatus.value = false
                pingJob?.cancel() // Cancel ping job on close
                val ex = CancellationException("WebSocket closed: $code / $reason")
                
                // Log pending results before cancelling them for debugging
                if (pendingResults.isNotEmpty()) {
                    Log.d(TAG, "Cancelling ${pendingResults.size} pending requests due to WebSocket close")
                }
                
                pendingResults.forEach { (id, pendingRequest) ->
                    if (!pendingRequest.deferred.isCompleted) {
                        Log.v(TAG, "Cancelling pending request with id: $id")
                        pendingRequest.deferred.cancel(ex)
                    }
                }
                pendingResults.clear()
                
                authenticatedDeferred?.let {
                    if (!it.isCompleted) {
                        Log.v(TAG, "Cancelling authenticatedDeferred due to WebSocket close")
                        it.cancel(ex)
                    }
                }
                subscriptionDeferred?.let {
                    if (!it.isCompleted) {
                        Log.v(TAG, "Cancelling subscriptionDeferred due to WebSocket close")
                        it.cancel(ex)
                    }
                }
                subscribeEventsMessageId = null // Clear subscription ID
                subscribeServiceCallsMessageId = null
                
                // Only schedule reconnect if authentication didn't fail
                // This prevents infinite reconnection attempts with invalid credentials
                if (!hasAuthFailed) {
                    Log.d(TAG, "Scheduling reconnect after WebSocket close (code: $code, reason: $reason)")
                    scheduleReconnect() // Trigger reconnect when closed
                } else {
                    Log.d(TAG, "Authentication failed previously, not scheduling reconnect. Please update configuration.")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket Failure: ${t.message}", t)
                _connectionStatus.value = false
                pingJob?.cancel() // Cancel ping job on failure

                val ex = CancellationException(t.message ?: "WebSocket failure", t)
                pendingResults.forEach { (_, pendingRequest) ->
                    if (!pendingRequest.deferred.isCompleted) pendingRequest.deferred.cancel(ex)
                }
                pendingResults.clear()
                authenticatedDeferred?.let {
                    if (!it.isCompleted) it.cancel(ex)
                }
                subscriptionDeferred?.let {
                    if (!it.isCompleted) it.cancel(ex)
                }
                subscribeEventsMessageId = null // Clear subscription ID
                subscribeServiceCallsMessageId = null

                try {
                    webSocket.close(1000, null)
                } catch (ignored: Throwable) {}
                
                // Only schedule reconnect if authentication didn't fail
                if (!hasAuthFailed) {
                    scheduleReconnect() // Trigger reconnect on failure
                } else {
                    Log.d(TAG, "Authentication failed previously, not scheduling reconnect after failure. Please update configuration.")
                }
            }
        })

        Log.d(TAG, "WebSocket connection attempt started to $wsUrl")

        try {
            // Wait for authentication to complete before connectInternal returns
            authenticatedDeferred?.await()
            Log.d(TAG, "Authentication successful after connectInternal call.")
            
            // Clear auth error on successful authentication
            hasAuthFailed = false
            authFailureMessage = null
            _authError.value = null

            // Start periodic ping ONLY after successful authentication
            startPeriodicPing()

        } catch (e: CancellationException) {
            // Check if this is due to auth failure (not a normal cancellation)
            if (hasAuthFailed) {
                Log.e(TAG, "Authentication failed (cancelled due to auth error): ${authFailureMessage ?: e.message}")
                // Don't throw - let the method return gracefully
                webSocket?.close(1000, "Authentication failed")
                webSocket = null
                _connectionStatus.value = false
                // Don't schedule reconnect for auth failures
                return
            } else {
                Log.w(TAG, "Authentication deferred cancelled during connectInternal: ${e.message}")
                // Ensure WebSocket is closed if authentication was cancelled
                webSocket?.close(1000, "Authentication cancelled")
                webSocket = null
                _connectionStatus.value = false
                // Only re-throw if it's not an auth failure
                throw e
            }
        } catch (e: Throwable) {
            // Check if this is an authentication failure
            if (hasAuthFailed) {
                Log.e(TAG, "Authentication failed: ${authFailureMessage ?: e.message}", e)
                // Don't throw - let the method return gracefully
                webSocket?.close(1000, "Authentication failed")
                webSocket = null
                _connectionStatus.value = false
                // Don't schedule reconnect for auth failures
                return
            } else {
                Log.e(TAG, "Error during authentication in connectInternal: ${e.message}", e)
                webSocket?.close(1000, "Authentication failed")
                webSocket = null
                _connectionStatus.value = false
                // Only re-throw for non-auth failures
                throw e
            }
        }
    }

    fun disconnect() {
        launch {
            connectionMutex.withLock {
                Log.d(TAG, "Disconnecting WebSocket (user requested).")
                reconnectionJob?.cancel()
                reconnectionJob = null
                pingJob?.cancel() // Cancel ping job on manual disconnect

                try {
                    webSocket?.close(1000, "User disconnected")
                } catch (t: Throwable) {
                    Log.w(TAG, "Error closing webSocket in disconnect: ${t.message}")
                }
                finally {
                    webSocket = null
                }

                _connectionStatus.value = false

                val ex = CancellationException("User disconnected.")
                pendingResults.forEach { (_, pendingRequest) ->
                    if (!pendingRequest.deferred.isCompleted) pendingRequest.deferred.cancel(ex)
                }
                pendingResults.clear()
                authenticatedDeferred?.let {
                    if (!it.isCompleted) it.cancel(ex)
                }
                subscriptionDeferred?.let {
                    if (!it.isCompleted) it.cancel(ex)
                }
                subscribeEventsMessageId = null // Clear subscription ID
                subscribeServiceCallsMessageId = null
                
                // Reset auth failure flag on manual disconnect (user might want to try again)
                hasAuthFailed = false
                authFailureMessage = null
                _authError.value = null

                job.cancelChildren() // Cancel all children coroutines of this scope
            }
        }
    }

    private fun scheduleReconnect() {
        // Don't schedule reconnect if authentication failed
        if (hasAuthFailed) {
            Log.d(TAG, "Authentication failed, not scheduling reconnect. Please update configuration.")
            return
        }
        
        if (reconnectionJob?.isActive == true) {
            Log.d(TAG, "Reconnect already scheduled, skipping.")
            return
        }
        
        reconnectionJob = launch {
            Log.d(TAG, "Scheduling WebSocket reconnection in ${RECONNECT_INTERVAL_MS / 1000} seconds.")
            delay(RECONNECT_INTERVAL_MS)

            val currentConfig = haConfig
            if (currentConfig == null) {
                Log.d(TAG, "HA config is null, not attempting reconnection.")
                return@launch
            }
            if (_connectionStatus.value) {
                Log.d(TAG, "WebSocket already connected, no need to reconnect.")
                return@launch
            }
            if (hasAuthFailed) {
                Log.d(TAG, "Authentication failed, not attempting reconnect.")
                return@launch
            }
            try {
                // The \'connect\' function itself will acquire connectionMutex
                connect(currentConfig, isReconnect = true) 
            } catch (t: Throwable) {
                Log.e(TAG, "Reconnect attempt failed: ${t.message}", t)
                // If reconnect attempt failed and it's not due to auth failure, re-schedule another one after a delay
                if (!hasAuthFailed) {
                    scheduleReconnect()
                }
            }
        }
    }

    private fun startPeriodicPing() { // Removed webSocket parameter
        pingJob?.cancel() // Cancel any existing ping job first
        pingJob = launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                connectionMutex.withLock { // Acquire lock before accessing webSocket
                    val ws = webSocket // Capture webSocket safely
                    if (ws == null) {
                        Log.w(TAG, "WebSocket is null, cannot send ping.")
                        return@withLock // Exit lock block and continue while loop
                    }
                    val id = messageIdCounter.getAndIncrement()
                    val pingMessage = PingMessage(id = id)
                    val json = gson.toJson(pingMessage)
                    ws.send(json)
                    Log.d(TAG, "Sent ping command with id: $id")

                    // For ping, we might want to store a CompletableDeferred<Unit> if we expect a 'result' message back.
                    // If not, we don't need to add it to pendingResults.
                    // Home Assistant typically responds to ping with a 'pong' or a simple 'result' success.
                    // Let's add it to pendingResults as a UNIT_RESULT so it's acknowledged.
                    val deferred = CompletableDeferred<Unit>()
                    @Suppress("UNCHECKED_CAST")
                    pendingResults[id] = PendingRequest(deferred as CompletableDeferred<Any>, ExpectedResponseType.UNIT_RESULT)
                }
            }
        }
    }

    private fun handleWebSocketMessage(webSocket: WebSocket, message: String) {
        val messageType = gson.fromJson(message, WebSocketMessageType::class.java)

        when (messageType.type) {
            "auth_required" -> sendAuthMessage(webSocket)
            "auth_ok" -> {
                Log.d(TAG, "Authentication successful.")
                authenticatedDeferred?.complete(Unit)
                subscribeToStates(webSocket)
                subscribeToServiceCalls(webSocket) // 订阅服务调用事件
                _connectionStatus.value = true
            }
            "auth_invalid" -> {
                val authErrorMsg = gson.fromJson(message, AuthResultMessage::class.java)
                val errorMessage = authErrorMsg.message ?: "Invalid access token or password"
                Log.e(TAG, "Authentication failed: $errorMessage")
                
                // Mark authentication as failed to prevent auto-reconnect
                hasAuthFailed = true
                authFailureMessage = errorMessage
                _authError.value = errorMessage
                
                // Cancel the deferred without throwing exception to prevent app crash
                // We'll use CancellationException so connectInternal can detect it's an auth failure
                val cancelException = CancellationException("Authentication failed: $errorMessage")
                authenticatedDeferred?.let {
                    if (!it.isCompleted) it.cancel(cancelException)
                }
                subscriptionDeferred?.let {
                    if (!it.isCompleted) it.cancel(cancelException)
                }
                try {
                    webSocket.close(1000, "Auth invalid")
                } catch (t: Throwable) {}
                _connectionStatus.value = false
            }
            "result" -> {
                val result = gson.fromJson(message, ResultMessage::class.java)
                val id = result.id

                when (id) {
                    subscribeEventsMessageId -> {
                        // This is the response to subscribe_events
                        if (result.success) {
                            subscriptionDeferred?.complete(Unit)
                            Log.d(TAG, "Subscription to state_changed events successful.")
                        } else {
                            val errorMsg = result.error?.message ?: "Unknown subscription error"
                            subscriptionDeferred?.completeExceptionally(RuntimeException("Subscription failed: $errorMsg"))
                            Log.e(TAG, "Subscription to state_changed events failed: $errorMsg")
                        }
                        // Remove from pendingResults if it was ever added, though it\'s not strictly a "pending result" for external calls
                        // We added the pending request for ping, so we need to remove it from pendingResults.
                        pendingResults.remove(id)
                    }
                    subscribeServiceCallsMessageId -> {
                        // This is the response to subscribe_events for call_service
                        if (result.success) {
                            Log.d(TAG, "�?订阅 call_service 事件成功！订阅ID: $id")
                        } else {
                            val errorMsg = result.error?.message ?: "Unknown subscription error"
                            Log.e(TAG, "�?订阅 call_service 事件失败: $errorMsg")
                        }
                        pendingResults.remove(id)
                    }
                    else -> {
                        val pendingRequest = pendingResults.remove(id) // Retrieve PendingRequest
                        if (pendingRequest != null) {
                            val deferred = pendingRequest.deferred
                            if (result.success) {
                                when (pendingRequest.expectedType) {
                                    ExpectedResponseType.ENTITY_STATES -> {
                                        try {
                                            val states: List<HaEntityState> = if (result.result != null) {
                                                val entityStatesType = object : TypeToken<List<HaEntityState>>() {}.type
                                                gson.fromJson(gson.toJson(result.result), entityStatesType) ?: emptyList()
                                            } else emptyList()
                                            @Suppress("UNCHECKED_CAST")
                                            (deferred as CompletableDeferred<List<HaEntityState>>).complete(states)
                                            launch { _allEntityStates.emit(states) }
                                            Log.d(TAG, "Initial ${states.size} entity states received for id: $id.")
                                        } catch (e: Exception) {
                                            deferred.completeExceptionally(RuntimeException("Failed to parse List<HaEntityState> for id: $id. Error: ${e.message}"))
                                            Log.e(TAG, "Failed to parse List<HaEntityState> for id: $id: ${e.message}")
                                        }
                                    }
                                    ExpectedResponseType.UNIT_RESULT -> {
                                        @Suppress("UNCHECKED_CAST")
                                        (deferred as CompletableDeferred<Unit>).complete(Unit)
                                        Log.d(TAG, "Completed deferred of type Unit for id: $id.")
                                    }
                                    // Add cases for other ExpectedResponseType if needed
                                }
                            } else {
                                deferred.completeExceptionally(RuntimeException(result.error?.message ?: "Unknown error for id: $id"))
                                Log.e(TAG, "Result failed for id: $id: ${result.error?.message ?: "Unknown error"}")
                            }
                        } else {
                            Log.w(TAG, "Received result for unknown or already handled ID: $id. Message: $message")
                        }
                    }
                }
            }
            "event" -> {
                // 先尝试解析为通用事件来获取事件类�?
                try {
                    val eventTypeCheck = gson.fromJson(message, EventTypeCheck::class.java)
                    val eventType = eventTypeCheck.event?.eventType
                    
                    Log.d(TAG, "📨 收到事件，类�? $eventType")
                    
                    when (eventType) {
                        "state_changed" -> {
                            val eventMessage = gson.fromJson(message, EventMessage::class.java)
                            val newState = eventMessage.event.data.newState
                            if (newState != null) {
                                Log.d(TAG, "📡 WebSocket state_changed event received for ${newState.entityId}: ${newState.state}")
                                launch { _entityStateUpdates.emit(newState) }
                            } else {
                                Log.w(TAG, "WebSocket event received but newState is null")
                            }
                        }
                        "call_service" -> {
                            // 处理服务调用事件
                            Log.d(TAG, "🔧 收到 call_service 事件，开始处�?..")
                            handleServiceCallEvent(message)
                        }
                        else -> {
                            Log.d(TAG, "📨 WebSocket event type: $eventType (未处�?")
                            // 打印原始消息以便调试
                            if (eventType != null && eventType.contains("service", ignoreCase = true)) {
                                Log.d(TAG, "📨 原始事件消息: ${message.take(500)}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "�?解析事件消息失败: ${e.message}", e)
                    Log.d(TAG, "原始消息: ${message.take(500)}")
                }
            }
            else -> {
                Log.d(TAG, "📨 未处理的消息类型: ${messageType.type}")
                // 如果是事件但不是我们处理的类型，打印原始消息以便调试
                if (messageType.type == "event") {
                    Log.d(TAG, "📨 原始事件消息: ${message.take(1000)}")
                }
            }
        }
    }

    private fun sendAuthMessage(webSocket: WebSocket) {
        val token = haConfig?.token
        if (token.isNullOrBlank()) {
            Log.e(TAG, "HA Token is null or blank, cannot send auth message.")
            return
        }
        val authMessage = AuthMessage("auth", token)
        val json = gson.toJson(authMessage)
        webSocket.send(json)
        Log.d(TAG, "Sent auth message.")
    }

    private fun subscribeToStates(webSocket: WebSocket) {
        val id = messageIdCounter.getAndIncrement()
        subscribeEventsMessageId = id // Store the ID for subscription
        val subscribeMessage = SubscribeStatesMessage(
            id = id
        )
        val json = gson.toJson(subscribeMessage)
        webSocket.send(json)
        Log.d(TAG, "Sent subscribe_events for state_changed with id: ${subscribeMessage.id}")
    }
    
    private fun subscribeToServiceCalls(webSocket: WebSocket) {
        val id = messageIdCounter.getAndIncrement()
        subscribeServiceCallsMessageId = id // Store the ID for subscription
        val subscribeMessage = SubscribeStatesMessage(
            id = id,
            event_type = "call_service"
        )
        val json = gson.toJson(subscribeMessage)
        webSocket.send(json)
        Log.d(TAG, "📤 发送订�?call_service 事件请求，ID: ${subscribeMessage.id}, JSON: $json")
    }
    
    /**
     * 处理服务调用事件
     */
    private fun handleServiceCallEvent(rawMessage: String) {
        try {
            Log.d(TAG, "🔧 开始处理服务调用事件，原始消息: ${rawMessage.take(500)}")
            
            // 解析服务调用数据
            val serviceCallData = gson.fromJson(rawMessage, ServiceCallEventMessage::class.java)
            val serviceData = serviceCallData.event.data
            
            Log.d(TAG, "🔧 解析成功: domain=${serviceData.domain}, service=${serviceData.service}")
            Log.d(TAG, "🔧 service_data: ${serviceData.serviceData}")
            Log.d(TAG, "🔧 target: ${serviceData.target}")
            
            // �?target �?service_data 中获�?entity_id（Home Assistant 新版本使�?target�?
            val entityIdFromTarget = serviceData.target?.get("entity_id") as? String
            val entityIdFromTargetList = serviceData.target?.get("entity_id") as? List<*>
            val entityIdFromServiceData = serviceData.serviceData?.get("entity_id") as? String
            val entityIdFromServiceDataList = serviceData.serviceData?.get("entity_id") as? List<*>
            
            // 优先使用 target 中的 entity_id
            val actualEntityId = when {
                entityIdFromTarget != null -> entityIdFromTarget
                entityIdFromTargetList != null && entityIdFromTargetList.isNotEmpty() -> entityIdFromTargetList.firstOrNull() as? String
                entityIdFromServiceData != null -> entityIdFromServiceData
                entityIdFromServiceDataList != null && entityIdFromServiceDataList.isNotEmpty() -> entityIdFromServiceDataList.firstOrNull() as? String
                else -> null
            }
            
            Log.d(TAG, "🔧 提取的实体ID: $actualEntityId")
            
            // 检查设备ID
            val deviceIdFromConfig = haConfig?.let { 
                // 从配置中无法直接获取设备ID，需要从应用上下文获�?
                // 这里我们通过检查实体ID是否包含已知的设备ID模式来判�?
                actualEntityId?.contains("_screen_brightness") == true || 
                actualEntityId?.contains("_keep_screen_on") == true
            } ?: false
            
            Log.d(TAG, "🔧 实体ID匹配检�? $actualEntityId (包含屏幕相关: $deviceIdFromConfig)")
            
            if (actualEntityId != null && (actualEntityId.contains("_screen_brightness") || actualEntityId.contains("_keep_screen_on"))) {
                // 创建虚拟�?HaEntityState 来传递服务调用信�?
                val virtualState = createVirtualEntityStateFromServiceCall(serviceData, actualEntityId)
                if (virtualState != null) {
                    Log.d(TAG, "�?创建虚拟状态成�? ${virtualState.entityId}, state=${virtualState.state}, brightness=${virtualState.attributes["brightness"]}")
                    launch { _entityStateUpdates.emit(virtualState) }
                } else {
                    Log.w(TAG, "⚠️ 创建虚拟状态失�? domain=${serviceData.domain}, service=${serviceData.service}, entityId=$actualEntityId")
                }
            } else {
                Log.d(TAG, "⏭️ 服务调用实体ID不匹配我们的屏幕实体: $actualEntityId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "�?处理服务调用事件失败: ${e.message}", e)
            Log.e(TAG, "�?异常堆栈: ${e.stackTraceToString()}")
            Log.d(TAG, "原始消息: $rawMessage")
        }
    }
    
    /**
     * 从服务调用创建虚拟的实体状�?
     */
    private fun createVirtualEntityStateFromServiceCall(
        serviceData: ServiceCallData,
        entityId: String
    ): HaEntityState? {
        return when {
            // 处理 light.turn_on 服务调用
            serviceData.domain == "light" && serviceData.service == "turn_on" -> {
                val brightness = serviceData.serviceData?.get("brightness") as? Number
                val brightnessPct = serviceData.serviceData?.get("brightness_pct") as? Number
                
                // 计算亮度�?
                val finalBrightness = when {
                    brightness != null -> brightness.toInt()
                    brightnessPct != null -> (brightnessPct.toInt() * 2.55).toInt().coerceIn(0, 255)
                    else -> 255 // 默认最大亮�?
                }
                
                val attributes = mutableMapOf<String, Any>(
                    "brightness" to finalBrightness,
                    "supported_features" to 1,
                    "supported_color_modes" to listOf("brightness"),
                    "color_mode" to "brightness",
                    "friendly_name" to "屏幕亮度"
                )
                
                HaEntityState(
                    entityId = entityId,
                    state = "on",
                    attributes = attributes,
                    lastUpdated = System.currentTimeMillis().toString(),
                    lastChanged = System.currentTimeMillis().toString(),
                    context = mapOf(
                        "id" to "service_call_${System.currentTimeMillis()}",
                        "from_service_call" to true  // 标识这是来自 call_service 的虚拟状�?
                    )
                )
            }
            // 处理 light.turn_off 服务调用
            serviceData.domain == "light" && serviceData.service == "turn_off" -> {
                HaEntityState(
                    entityId = entityId,
                    state = "off",
                    attributes = mapOf(
                        "supported_features" to 1,
                        "supported_color_modes" to listOf("brightness"),
                        "friendly_name" to "屏幕亮度"
                    ),
                    lastUpdated = System.currentTimeMillis().toString(),
                    lastChanged = System.currentTimeMillis().toString(),
                    context = mapOf(
                        "id" to "service_call_${System.currentTimeMillis()}",
                        "from_service_call" to true  // 标识这是来自 call_service 的虚拟状�?
                    )
                )
            }
            // 处理 switch.turn_on 服务调用
            serviceData.domain == "switch" && serviceData.service == "turn_on" -> {
                HaEntityState(
                    entityId = entityId,
                    state = "on",
                    attributes = mapOf(
                        "friendly_name" to "屏幕常亮",
                        "icon" to "mdi:monitor-screenshot"
                    ),
                    lastUpdated = System.currentTimeMillis().toString(),
                    lastChanged = System.currentTimeMillis().toString(),
                    context = mapOf(
                        "id" to "service_call_${System.currentTimeMillis()}",
                        "from_service_call" to true  // 标识这是来自 call_service 的虚拟状�?
                    )
                )
            }
            // 处理 switch.turn_off 服务调用
            serviceData.domain == "switch" && serviceData.service == "turn_off" -> {
                HaEntityState(
                    entityId = entityId,
                    state = "off",
                    attributes = mapOf(
                        "friendly_name" to "屏幕常亮",
                        "icon" to "mdi:monitor-screenshot"
                    ),
                    lastUpdated = System.currentTimeMillis().toString(),
                    lastChanged = System.currentTimeMillis().toString(),
                    context = mapOf(
                        "id" to "service_call_${System.currentTimeMillis()}",
                        "from_service_call" to true  // 标识这是来自 call_service 的虚拟状�?
                    )
                )
            }
            else -> null
        }
    }

    suspend fun requestAllEntityStates(): List<HaEntityState> {
        // 等待认证完成，如果认证被取消（可能是连接重建），等待新连接建�?
        var authAttempts = 0
        val maxAuthAttempts = 3
        
        while (authAttempts < maxAuthAttempts) {
            val currentAuthDeferred = connectionMutex.withLock { authenticatedDeferred }
            
            if (currentAuthDeferred == null) {
                // 如果认证 deferred �?null，等待一小段时间让连接建�?
                Log.d(TAG, "Authentication deferred is null, waiting for connection to establish...")
                kotlinx.coroutines.delay(100)
                authAttempts++
                continue
            }
            
            try {
                currentAuthDeferred.await()
                Log.d(TAG, "Authentication completed, proceeding with request.")
                break // 认证成功，跳出循�?
            } catch (e: CancellationException) {
                // 认证被取消，可能是连接重�?
                Log.w(TAG, "Authentication was cancelled (attempt ${authAttempts + 1}/$maxAuthAttempts), checking if reconnection is in progress...")
                authAttempts++
                
                // 检查连接状态，如果连接正在进行中，等待并重�?
                val isConnecting = connectionMutex.withLock {
                    val config = haConfig
                    val isConnected = _connectionStatus.value
                    config != null && config.baseUrl.isNotBlank() && config.token.isNotBlank() && !isConnected
                }
                
                if (isConnecting && authAttempts < maxAuthAttempts) {
                    // 连接正在进行中，等待一段时间后重试
                    Log.d(TAG, "Connection is in progress, waiting before retry...")
                    kotlinx.coroutines.delay(500)
                } else if (authAttempts >= maxAuthAttempts) {
                    Log.e(TAG, "Authentication failed after $maxAuthAttempts attempts.")
                    return emptyList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Authentication failed, cannot request states: ${e.message}")
                return emptyList()
            }
        }
        
        if (authAttempts >= maxAuthAttempts) {
            Log.e(TAG, "Failed to authenticate after $maxAuthAttempts attempts.")
            return emptyList()
        }
        
        // 等待订阅完成，如果订阅未完成或已取消，尝试重新订�?
        val currentSubscriptionDeferred: CompletableDeferred<Unit>? = connectionMutex.withLock {
            // 检查连接状�?
            if (!_connectionStatus.value) {
                Log.e(TAG, "Connection status is false, cannot request states.")
                return@withLock null
            }
            
            val ws = webSocket
            if (ws == null) {
                Log.e(TAG, "WebSocket is null, cannot request states.")
                return@withLock null
            }
            
            // 检查订阅状态，如果订阅未完成或已取消，尝试重新订阅
            val currentSub = subscriptionDeferred
            val needsResubscribe = currentSub == null || currentSub.isCancelled
            
            if (needsResubscribe) {
                // 如果订阅 deferred �?null 或已取消，需要重新订�?
                Log.d(TAG, "Subscription not active (null=${currentSub == null}, cancelled=${currentSub?.isCancelled}), recreating subscription deferred and resubscribing.")
                currentSub?.let {
                    if (!it.isCompleted) it.cancel(CancellationException("Recreating subscription"))
                }
                val newDeferred = CompletableDeferred<Unit>()
                subscriptionDeferred = newDeferred
                subscribeEventsMessageId = null
                subscribeServiceCallsMessageId = null
                subscribeToStates(ws)
                subscribeToServiceCalls(ws)
                newDeferred
            } else {
                // 订阅正在进行中或已完成，使用现有�?deferred
                currentSub
            }
        }
        
        // 如果无法获取订阅 deferred，返回空列表
        if (currentSubscriptionDeferred == null) {
            return emptyList()
        }
        
        // 在锁外等待订阅完成（如果还未完成�?
        if (!currentSubscriptionDeferred.isCompleted) {
            try {
                currentSubscriptionDeferred.await()
                Log.d(TAG, "Subscription completed, proceeding with get_states request.")
            } catch (e: CancellationException) {
                Log.e(TAG, "Subscription was cancelled, cannot request states.")
                return emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Subscription failed, cannot request states: ${e.message}")
                return emptyList()
            }
        } else {
            Log.d(TAG, "Subscription already completed, proceeding with get_states request.")
        }
        
        // 重新获取锁，发�?get_states 请求
        return connectionMutex.withLock {
            // 再次检查连接状�?
            if (!_connectionStatus.value) {
                Log.e(TAG, "Connection status is false after waiting for subscription, cannot request states.")
                return@withLock emptyList()
            }
            
            val ws = webSocket
            if (ws == null) {
                Log.e(TAG, "WebSocket is null after waiting for subscription, cannot request states.")
                return@withLock emptyList()
            }

            val deferred = CompletableDeferred<List<HaEntityState>>()
            val id = messageIdCounter.getAndIncrement()
            // Store the deferred with the correct type using PendingRequest
            @Suppress("UNCHECKED_CAST")
            pendingResults[id] = PendingRequest(deferred as CompletableDeferred<Any>, ExpectedResponseType.ENTITY_STATES)

            val getStatesMessage = GetStatesMessage(id = id, type = "get_states")
            val json = gson.toJson(getStatesMessage)
            
            try {
                ws.send(json)
                Log.d(TAG, "Sent get_states command with id: $id")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send get_states command: ${e.message}")
                pendingResults.remove(id)
                return@withLock emptyList()
            }

            try {
                deferred.await()
            } catch (t: CancellationException) {
                Log.w(TAG, "get_states request was cancelled: ${t.message}")
                pendingResults.remove(id)
                emptyList()
            } catch (t: Throwable) {
                Log.e(TAG, "Error awaiting get_states result: ${t.message}")
                pendingResults.remove(id)
                emptyList()
            }
        }
    }

    fun callService(
        domain: String,
        service: String,
        entityId: String,
        serviceData: Map<String, Any>? = null
    ): Boolean {
        launch {
            try {
                authenticatedDeferred?.await()
                // Added: Wait for subscription to be ready as well
                subscriptionDeferred?.await()

                val payload = serviceData?.toMutableMap()?.apply {
                    put("entity_id", entityId)
                } ?: mutableMapOf("entity_id" to entityId)

                val id = messageIdCounter.getAndIncrement() // Get ID for service call
                val callServiceMessage = CallServiceMessage(
                    id = id,
                    domain = domain,
                    service = service,
                    serviceData = payload
                )
                val json = gson.toJson(callServiceMessage)

                // Store a deferred for the service call result if we expect one
                val deferred = CompletableDeferred<Unit>()
                @Suppress("UNCHECKED_CAST")
                pendingResults[id] = PendingRequest(deferred as CompletableDeferred<Any>, ExpectedResponseType.UNIT_RESULT)


                // Access webSocket within the lock
                connectionMutex.withLock { 
                    val ws = webSocket // Capture webSocket inside the lock
                    if (ws == null) {
                        Log.e(TAG, "WebSocket not connected, cannot call service.")
                        // If no WebSocket, complete deferred exceptionally
                        deferred.completeExceptionally(IllegalStateException("WebSocket not connected."))
                    } else {
                        ws.send(json)
                    }
                }
                Log.d(TAG, "Sent service call: $domain.$service for $entityId with id: $id")

                // Await the result of the service call
                deferred.await() // This will suspend until the result is received or fails
                Log.d(TAG, "Service call $domain.$service for $entityId completed successfully.")

            } catch (t: Throwable) {
                Log.e(TAG, "Error while trying to call service: ${t.message}", t)
            }
        }
        return true
    }
    
    /**
     * 更新实体状�?用于上报设备状�?
     * 使用 states.set_state 服务
     */
    suspend fun updateEntityState(
        entityId: String,
        stateData: Map<String, Any>
    ): Boolean {
        return try {
            authenticatedDeferred?.await()
            subscriptionDeferred?.await()

            val id = messageIdCounter.getAndIncrement()
            val serviceDataMap = mutableMapOf<String, Any>(
                "entity_id" to entityId
            )
            stateData["state"]?.let { serviceDataMap["state"] = it }
            (stateData["attributes"] as? Map<*, *>)?.let { 
                @Suppress("UNCHECKED_CAST")
                serviceDataMap["attributes"] = it as Map<String, Any>
            }
            
            val updateStateMessage = CallServiceMessage(
                id = id,
                type = "call_service",
                domain = "states",
                service = "set_state",
                serviceData = serviceDataMap
            )
            val json = gson.toJson(updateStateMessage)

            val deferred = CompletableDeferred<Unit>()
            @Suppress("UNCHECKED_CAST")
            pendingResults[id] = PendingRequest(deferred as CompletableDeferred<Any>, ExpectedResponseType.UNIT_RESULT)

            connectionMutex.withLock {
                val ws = webSocket
                if (ws == null) {
                    Log.e(TAG, "WebSocket not connected, cannot update entity state.")
                    deferred.completeExceptionally(IllegalStateException("WebSocket not connected."))
                    return@withLock
                }
                ws.send(json)
            }
            Log.d(TAG, "Sent update entity state for $entityId with id: $id")

            deferred.await()
            Log.d(TAG, "Update entity state for $entityId completed successfully.")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to update entity state for $entityId: ${t.message}", t)
            false
        }
    }

    // --- Message Models ---
    data class WebSocketMessageType(val type: String)
    data class AuthMessage(val type: String = "auth", val access_token: String)
    data class AuthResultMessage(val type: String, val message: String?)
    data class SubscribeStatesMessage(
        val id: Int,
        val type: String = "subscribe_events",
        @SerializedName("event_type") val event_type: String = "state_changed"
    )
    data class GetStatesMessage(val id: Int, val type: String = "get_states")
    data class CallServiceMessage(
        val id: Int,
        val type: String = "call_service",
        val domain: String,
        val service: String,
        @SerializedName("service_data") val serviceData: Map<String, Any>
    )
    data class PingMessage(val id: Int, val type: String = "ping") // Added PingMessage
    data class ResultMessage(
        val id: Int,
        val type: String,
        val success: Boolean,
        val error: ErrorMessage?,
        val result: Any?
    )
    data class ErrorMessage(val code: String, val message: String)
    data class EventData(
        @SerializedName("entity_id") val entityId: String?,
        @SerializedName("old_state") val oldState: HaEntityState?,
        @SerializedName("new_state") val newState: HaEntityState?
    )
    data class ServiceCallData(
        val domain: String,
        val service: String,
        @SerializedName("service_data") val serviceData: Map<String, Any>?,
        val target: Map<String, Any>? = null  // Home Assistant 的新格式可能使用 target
    )
    data class Event( @SerializedName("event_type") val eventType: String, val data: EventData)
    data class ServiceCallEvent(
        @SerializedName("event_type") val eventType: String,
        val data: ServiceCallData
    )
    data class EventMessage(val id: Int?, val type: String, val event: Event)
    data class ServiceCallEventMessage(val id: Int?, val type: String, val event: ServiceCallEvent)
    data class EventTypeCheck(val event: EventTypeInfo?)
    data class EventTypeInfo(@SerializedName("event_type") val eventType: String)
}