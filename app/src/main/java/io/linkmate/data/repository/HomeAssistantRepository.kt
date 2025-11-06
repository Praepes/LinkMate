package io.linkmate.data.repository

import android.util.Log
import io.linkmate.data.local.dao.HaConfigDao
import io.linkmate.data.local.HaConfigEntity
import io.linkmate.data.remote.homeassistant.HaEntityState
import io.linkmate.data.remote.homeassistant.HomeAssistantService
import io.linkmate.data.remote.homeassistant.HomeAssistantWebSocketClient
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach // Import onEach
import kotlinx.coroutines.flow.first
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeAssistantRepository @Inject constructor(
    private val haConfigDao: HaConfigDao,
    private val webSocketClient: HomeAssistantWebSocketClient,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {

    private val TAG = "HaRepository"

    // Removed _lastConnectedHaConfig as the ViewModel manages fetching triggers more directly.

    // Expose the entity state updates and connection status from the WebSocket client.
    val entityStateUpdates: Flow<HaEntityState> = webSocketClient.entityStateUpdates
    val connectionStatus: Flow<Boolean> = webSocketClient.connectionStatus.onEach { isConnected ->
        Log.d(TAG, "WebSocket Connection Status Flow emitted: $isConnected")
    }
    val authError: Flow<String?> = webSocketClient.authError.onEach { error ->
        if (error != null) {
            Log.d(TAG, "WebSocket Authentication Error: $error")
        }
    }

    /**
     * Retrieves the stored Home Assistant configuration from the database.
     * @return A Flow emitting the HaConfigEntity or null if none is stored.
     */
    fun getHaConfig(): Flow<HaConfigEntity?> {
        return haConfigDao.getHaConfig().onEach { config ->
            Log.d(TAG, "HaConfig Flow emitted: $config (baseUrl: ${config?.baseUrl}, token: ${config?.token?.take(5)}...)")
        }
    }

    /**
     * Saves the provided Home Assistant configuration to the database and attempts to connect
     * the WebSocket client if the configuration is valid.
     * @param haConfig The HaConfigEntity to save.
     */
    suspend fun saveHaConfig(haConfig: HaConfigEntity) {
        // Save the configuration to the database first.
        haConfigDao.insertHaConfig(haConfig)

        // Always attempt to connect if the config is valid.
        // The HomeAssistantWebSocketClient's connect() method handles the logic of whether to establish a new connection or re-establish an existing one.
        if (haConfig.baseUrl.isNotBlank() && haConfig.token.isNotBlank()) {
            Log.d(TAG, "HA config saved. Attempting to connect/reconnect WebSocket.")
            try {
                webSocketClient.connect(haConfig)
            } catch (e: Throwable) {
                // Connection errors are now handled gracefully in the WebSocket client,
                // but we catch here as an extra safety measure to prevent app crashes
                Log.e(TAG, "Error connecting WebSocket after saving config: ${e.message}", e)
            }
        } else {
            // If the configuration is invalid (e.g., empty URL or token), disconnect the WebSocket.
            Log.d(TAG, "HA config is blank. Disconnecting WebSocket.")
            webSocketClient.disconnect()
        }
    }

    /**
     * 尝试在不写入数据库的情况下建立或恢复 WebSocket 连接�?
     * 用于前端检测断线后主动触发连接，避免重复写库导致的 Flow 级联触发与重连风暴�?
     */
    suspend fun connectIfPossible(haConfig: HaConfigEntity?) {
        if (haConfig == null) return
        if (haConfig.baseUrl.isNotBlank() && haConfig.token.isNotBlank()) {
            Log.d(TAG, "connectIfPossible: attempting WebSocket connect without DB write")
            try {
                webSocketClient.connect(haConfig)
            } catch (e: Throwable) {
                // Connection errors are now handled gracefully in the WebSocket client,
                // but we catch here as an extra safety measure to prevent app crashes
                Log.e(TAG, "Error connecting WebSocket in connectIfPossible: ${e.message}", e)
            }
        }
    }

    /**
     * Requests the initial list of all entity states from Home Assistant via the WebSocket.
     * This is typically called when the app starts and a connection is established.
     * @return A list of HaEntityState objects.
     */
    suspend fun getInitialEntityStates(): List<HaEntityState> {
        Log.d(TAG, "Requesting initial HA entity states via WebSocket.")
        return webSocketClient.requestAllEntityStates()
    }

    /**
     * Calls a specific service on a Home Assistant entity.
     * @param domain The domain of the service (e.g., "light", "switch").
     * @param service The name of the service to call (e.g., "turn_on", "toggle").
     * @param entityId The ID of the entity to control (e.g., "light.living_room").
     * @param serviceData Optional map of service data.
     * @return True if the service call was successfully sent, false otherwise.
     */
    suspend fun callService(
        domain: String,
        service: String,
        entityId: String,
        serviceData: Map<String, Any>? = null
    ): Boolean {
        Log.d(TAG, "Calling HA service via WebSocket: domain=$domain, service=$service, entityId=$entityId, serviceData=$serviceData")
        return try {
            // Delegate the service call to the WebSocket client.
            webSocketClient.callService(domain, service, entityId, serviceData)
            true // Indicate that the call was sent successfully.
        } catch (e: Exception) {
            // Log any exceptions that occur during the service call.
            Log.e(TAG, "Exception during HA service call via WebSocket for $entityId: ${e.message}", e)
            false // Indicate that the call failed.
        }
    }
    
    /**
     * 上报设备状态到 Home Assistant (使用 REST API)
     * @param entityId 实体 ID (�?sensor.android_device_battery)
     * @param state 状态�?
     * @param attributes 属�?
     * @return 是否成功
     */
    suspend fun reportDeviceState(
        entityId: String,
        state: Any,
        attributes: Map<String, Any?>
    ): Boolean {
        return try {
            val haConfig = try {
                haConfigDao.getHaConfig().first()
            } catch (e: Exception) {
                null
            } ?: run {
                Log.e(TAG, "Cannot report device state: HA not configured")
                return false
            }
            
            // 创建动�?Retrofit 实例
            val retrofit = Retrofit.Builder()
                .baseUrl(haConfig.baseUrl.ensureTrailingSlash())
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
            
            val service = retrofit.create(HomeAssistantService::class.java)
            
            // 构建请求�?
            val stateData = mapOf(
                "state" to state,
                "attributes" to attributes
            )
            val json = gson.toJson(stateData)
            val requestBody = json.toRequestBody("application/json".toMediaType())
            
            Log.d(TAG, "📤 上报设备状�? entityId=$entityId, state=$state")
            Log.d(TAG, "📤 请求JSON: $json")
            
            // 调用 REST API
            val bearerToken = "Bearer ${haConfig.token}"
            val response = service.updateEntityState(bearerToken, entityId, requestBody)
            
            if (response.isSuccessful) {
                val responseBody = response.body()
                if (responseBody != null) {
                    Log.d(TAG, "�?成功上报设备状�? entityId=$entityId")
                    Log.d(TAG, "�?响应状�? ${responseBody.state}, lastUpdated=${responseBody.lastUpdated}")
                    true
                } else {
                    Log.w(TAG, "⚠️ 上报成功但响应体为空: entityId=$entityId")
                    true // 即使响应体为空，也认为成功（某些HA版本可能不返回）
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "�?上报设备状态失�? entityId=$entityId")
                Log.e(TAG, "�?HTTP状态码: ${response.code()}, 消息: ${response.message()}")
                Log.e(TAG, "�?错误响应�? $errorBody")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report device state for $entityId: ${e.message}", e)
            false
        }
    }
    
    /**
     * 获取单个实体的状态（通过 REST API�?
     * 用于定期检查亮度控制实体状态，避免频繁获取所有实�?
     */
    suspend fun getEntityState(entityId: String): HaEntityState? {
        return try {
            val haConfig = try {
                haConfigDao.getHaConfig().first()
            } catch (e: Exception) {
                null
            } ?: run {
                Log.e(TAG, "Cannot get entity state: HA not configured")
                return null
            }
            
            // 创建动�?Retrofit 实例
            val retrofit = Retrofit.Builder()
                .baseUrl(haConfig.baseUrl.ensureTrailingSlash())
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
            
            val service = retrofit.create(HomeAssistantService::class.java)
            
            // 调用 REST API（直接返�?HaEntityState，不�?Response�?
            val bearerToken = "Bearer ${haConfig.token}"
            val entityState = service.getEntityState(bearerToken, entityId)
            
            Log.d(TAG, "Successfully retrieved entity state for $entityId: ${entityState.state}")
            entityState
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get entity state for $entityId: ${e.message}", e)
            null
        }
    }
    
    private fun String.ensureTrailingSlash(): String {
        return if (this.endsWith("/")) this else "$this/"
    }
}
