package io.linkmate.ui.viewmodels

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.linkmate.data.local.HaConfigEntity
import io.linkmate.data.local.ReminderEntity
import io.linkmate.data.local.SettingsEntity
import io.linkmate.data.local.WeatherCacheEntity
import io.linkmate.data.remote.homeassistant.HaEntityState
import io.linkmate.data.repository.HomeAssistantRepository
import io.linkmate.data.repository.LayoutRepository
import io.linkmate.data.repository.ReminderRepository
import io.linkmate.data.repository.SettingsRepository
import io.linkmate.data.repository.WeatherRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.linkmate.data.model.HomeAssistantEntity

private const val TAG = "HomeViewModel"

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val application: Application,
    private val weatherRepository: WeatherRepository,
    private val homeAssistantRepository: HomeAssistantRepository,
    private val settingsRepository: SettingsRepository,
    private val reminderRepository: ReminderRepository,
    private val layoutRepository: LayoutRepository,
    private val deviceStateCollector: io.linkmate.data.device.DeviceStateCollector,
    private val screenBrightnessManager: io.linkmate.data.device.ScreenBrightnessManager,
    private val screenKeepOnManager: io.linkmate.data.device.ScreenKeepOnManager
) : ViewModel() {

    private val _weatherState = MutableStateFlow<WeatherState>(WeatherState.Loading)
    val weatherState: StateFlow<WeatherState> = _weatherState.asStateFlow()

    private val _haState = MutableStateFlow<HaState>(HaState.Loading)
    val haState: StateFlow<HaState> = _haState.asStateFlow()

    private val _allHaEntities = MutableStateFlow<List<HomeAssistantEntity>>(emptyList())
    val allHaEntities: StateFlow<List<HomeAssistantEntity>> = _allHaEntities.asStateFlow()

    private val _selectedHaEntities = MutableStateFlow<List<String>>(emptyList())
    val selectedHaEntities: StateFlow<List<String>> = _selectedHaEntities.asStateFlow()

    private val _reminders = MutableStateFlow<List<ReminderEntity>>(emptyList())
    val reminders: StateFlow<List<ReminderEntity>> = _reminders.asStateFlow()

    private val _settings = MutableStateFlow<SettingsEntity?>(null)
    val settings: StateFlow<SettingsEntity?> = _settings.asStateFlow()

    private val _haConfig = MutableStateFlow<HaConfigEntity?>(null)
    val haConfig: StateFlow<HaConfigEntity?> = _haConfig.asStateFlow()

    // Layout configuration
    private val _widgetOrder = MutableStateFlow<List<String>>(listOf("WEATHER", "REMINDERS", "SETTINGS", "HA_GRID"))
    val widgetOrder: StateFlow<List<String>> = _widgetOrder.asStateFlow()
    
    // Widget positions (坐标系统)
    private val _widgetPositions = MutableStateFlow<Map<String, io.linkmate.ui.components.WidgetPosition>>(emptyMap())
    val widgetPositions: StateFlow<Map<String, io.linkmate.ui.components.WidgetPosition>> = _widgetPositions.asStateFlow()
    
    // 存储每个 climate 实体的最后有效模式（用于关闭时显示正确图标）
    private val _climateLastValidModes = MutableStateFlow<Map<String, String>>(emptyMap())
    val climateLastValidModes: StateFlow<Map<String, String>> = _climateLastValidModes.asStateFlow()

    // Flag to prevent concurrent fetching of HA entities
    private var isFetchingEntities = false
    // Timestamp of last fetch attempt to prevent rapid retries
    private var lastFetchAttemptTime = 0L
    private val MIN_FETCH_INTERVAL_MS = 3000L // Minimum 3 seconds between fetch attempts
    
    private val gson = Gson()

    private val _weatherUpdateRequests = Channel<Unit>(Channel.CONFLATED)
    private var weatherPollingJob: Job? = null
    private var deviceStateReportingJob: Job? = null
    private var screenControlPollingJob: Job? = null
    private var lastLocation: Location? = null

    private val locationManager: LocationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager: SensorManager = application.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var proximitySensor: Sensor? = null
    private var isProximityNear by mutableStateOf(false)
    
    // 设备ID，用于创建实体ID
    private val deviceId = android.provider.Settings.Secure.getString(
        application.contentResolver,
        android.provider.Settings.Secure.ANDROID_ID
    ).take(8)
    
    // 记录最近一次上报状态的时间，用于避免处理自己上报的状态
    private var lastReportedBrightnessTime = 0L
    private var lastReportedKeepOnTime = 0L
    
    // 记录上次轮询时的状态，用于检测变化
    private var lastPolledBrightness: Float? = null
    private var lastPolledBrightnessLastUpdated: String? = null
    private var lastPolledKeepOn: Boolean? = null
    private var lastPolledKeepOnLastUpdated: String? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            Log.d(TAG, "位置更新: ${location.latitude}, ${location.longitude}")
            lastLocation = location
            viewModelScope.launch { _weatherUpdateRequests.send(Unit) } // Request immediate update for new location
        }

        @Deprecated("Deprecated in API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {} // Deprecated, but keep for compatibility
        override fun onProviderEnabled(provider: String) {} // Not directly used for weather updates
        override fun onProviderDisabled(provider: String) {} // Not directly used for weather updates
    }

    private val proximitySensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
                isProximityNear = event.values[0] < event.sensor.maximumRange
                Log.d(TAG, "距离传感器变化: isNear=$isProximityNear")
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {} // Not directly used for weather updates
    }

    init {
        observeSettings()
        // 替换 observeHaConfig() 和 observeHaConnectionStatus() 为新的组合观察者
        observeCombinedHaState()
        observeReminders()
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        // observeHaConnectionStatus() // Removed as observeCombinedHaState handles it
        // observeHaConfig() // Removed as observeCombinedHaState handles it
        observeHaEntityStateUpdates()
        observeWeatherUpdateRequests()
        observeLayoutConfig()
        startScreenControlPolling()

        Log.d(TAG, "设备ID: $deviceId")
    }
    
    /**
     * 启动屏幕控制状态轮询（作为 call_service 事件的备用方案）
     */
    private fun startScreenControlPolling() {
        screenControlPollingJob?.cancel()
        
        screenControlPollingJob = viewModelScope.launch {
            Log.d(TAG, "启动屏幕控制状态轮询")

            while (true) {
                delay(5000L) // 每5秒检查一次
                
                // 只在已连接时轮询
                if (homeAssistantRepository.connectionStatus.firstOrNull() == true) {
                    try {
                        val screenBrightnessLightId = "light.${deviceId}_screen_brightness"
                        val screenKeepOnSwitchId = "switch.${deviceId}_keep_screen_on"

                        // 获取屏幕亮度实体状态
                        val brightnessState = homeAssistantRepository.getEntityState(screenBrightnessLightId)
                        if (brightnessState != null) {
                            val reportedBrightness = brightnessState.attributes["brightness"] as? Number
                            val reportedBrightnessFloat = reportedBrightness?.toInt()?.div(255f) ?: -1f
                            val lastUpdated = brightnessState.lastUpdated
                            
                            // 检查时间戳是否变化（说明状态在 HA 中被改变了）
                            val timestampChanged = lastPolledBrightnessLastUpdated != null && 
                                                  lastPolledBrightnessLastUpdated != lastUpdated

                            // 检查数值是否变化
                            val valueChanged = lastPolledBrightness != null && 
                                             lastPolledBrightness != reportedBrightnessFloat
                            
                            // 检查状态是否与当前设备设置不一致
                            val currentBrightness = screenBrightnessManager.getBrightness()
                            val valueDiffersFromDevice = reportedBrightnessFloat >= 0 && 
                                                         kotlin.math.abs(currentBrightness - reportedBrightnessFloat) > 0.05f

                            if (valueDiffersFromDevice) {
                                Log.d(TAG, "屏幕亮度不一致: HA=${reportedBrightnessFloat}, 设备=${currentBrightness}")
                                handleScreenBrightnessControl(brightnessState)
                            }
                            
                            // 更新记录
                            lastPolledBrightness = reportedBrightnessFloat
                            lastPolledBrightnessLastUpdated = lastUpdated
                        }
                        
                        // 获取屏幕常亮实体状态
                        val keepOnState = homeAssistantRepository.getEntityState(screenKeepOnSwitchId)
                        if (keepOnState != null) {
                            val reportedKeepOn = keepOnState.state == "on"
                            val lastUpdated = keepOnState.lastUpdated
                            
                            // 检查时间戳是否变化
                            val timestampChanged = lastPolledKeepOnLastUpdated != null && 
                                                  lastPolledKeepOnLastUpdated != lastUpdated
                            
                            // 检查数值是否变化
                            val valueChanged = lastPolledKeepOn != null && 
                                             lastPolledKeepOn != reportedKeepOn
                            
                            // 检查状态是否与当前设备设置不一致
                            val currentKeepOn = screenKeepOnManager.isKeepOn()
                            val valueDiffersFromDevice = currentKeepOn != reportedKeepOn

                            Log.d(TAG, " 轮询屏幕常亮: HA中=${reportedKeepOn}, 设备中=${currentKeepOn}, " +
                                    "时间戳变化=${timestampChanged}, 数值变化=${valueChanged}, 与设备不一致=${valueDiffersFromDevice}")

                            // 如果时间戳变化或数值变化，且与设备设置不一致，应用新状态

                            // 注意：即使时间戳和数值都没变化，如果与设备设置不一致，也应用（因为HA中的只读实体可能不会更新时间戳）
                            if (valueDiffersFromDevice) {
                                if (timestampChanged || valueChanged || lastPolledKeepOn == null) {
                                    Log.d(TAG, " 检测到屏幕常亮状态变化，应用新状态: $reportedKeepOn (当前设备: $currentKeepOn)")
                                } else {
                                    Log.d(TAG, " 检测到屏幕常亮状态不一致（HA可能未更新时间戳），应用新状态: $reportedKeepOn (当前设备: $currentKeepOn)")
                                }
                                handleScreenKeepOnControl(keepOnState)
                            }
                            
                            // 更新记录
                            lastPolledKeepOn = reportedKeepOn
                            lastPolledKeepOnLastUpdated = lastUpdated
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "轮询屏幕控制实体状态失败: ${e.message}", e)
                    }
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeWeatherUpdateRequests() {
        viewModelScope.launch {
            _weatherUpdateRequests
                .receiveAsFlow()
                .debounce(1000L) // Debounce rapid requests within 1 second to coalesce multiple triggers
                .collect { 
                    Log.d(TAG, "处理天气更新请求")
                    fetchAndHandleWeather() // Unified function to fetch weather after debounce
                }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.getSettings()
                .distinctUntilChanged() // Only emit when the settings object actually changes to prevent redundant triggers
                .collect {
                    val oldSettings = _settings.value
                    val newSettings = it ?: SettingsEntity()
                    Log.d(TAG, "加载设置: 天气显示模式=${newSettings.weatherDisplayMode}, 提醒宽度=${newSettings.reminderWidth}, 提醒高度=${newSettings.reminderHeight}")
                    _settings.value = newSettings

                    if (_settings.value?.enableProximityWake == true) registerProximitySensorListener()
                    else unregisterProximitySensorListener()

                    // Handle GPS listener state directly based on settings here
                    if (_settings.value?.weatherLocationSource == "GPS") {
                        startGpsListener() // Start GPS listener if enabled in settings
                    } else {
                        stopGpsListener() // Stop GPS listener if not in GPS mode
                    }

                    // Check for relevant changes to weather settings
                    val weatherSettingsChanged = oldSettings?.weatherLocationSource != _settings.value?.weatherLocationSource ||
                                                 oldSettings?.manualWeatherLocation != _settings.value?.manualWeatherLocation ||
                                                 oldSettings?.heFengApiKey != _settings.value?.heFengApiKey ||
                                                 oldSettings?.weatherRefreshIntervalMinutes != _settings.value?.weatherRefreshIntervalMinutes ||
                                                 oldSettings?.gpsUpdateIntervalMinutes != _settings.value?.gpsUpdateIntervalMinutes

                    if (weatherSettingsChanged) {
                        Log.d(TAG, "天气设置已更改，重启轮询")
                        viewModelScope.launch { _weatherUpdateRequests.send(Unit) }
                        startWeatherPolling()
                    } else if (_settings.value?.weatherRefreshIntervalMinutes ?: 0 > 0 && weatherPollingJob == null) {
                        Log.d(TAG, "启动天气轮询")
                        viewModelScope.launch { _weatherUpdateRequests.send(Unit) }
                        startWeatherPolling()
                    }
                    
                    // 处理设备状态上报设置
                    val deviceReportingSettingsChanged = oldSettings?.enableDeviceStateReporting != _settings.value?.enableDeviceStateReporting ||
                                                         oldSettings?.deviceStateReportIntervalMinutes != _settings.value?.deviceStateReportIntervalMinutes
                    
                    if (deviceReportingSettingsChanged) {
                        Log.d(TAG, "Device state reporting settings changed, restarting reporting.")
                        startDeviceStateReporting()
                    } else if (_settings.value?.enableDeviceStateReporting == true && deviceStateReportingJob == null) {
                        Log.d(TAG, "Device state reporting enabled, starting reporting.")
                        startDeviceStateReporting()
                    }
                    
                }
        }
    }

    // 真正的数据源应该是Repository，而不是ViewModel内部的StateFlow
    private fun observeCombinedHaState() {
        viewModelScope.launch {
            // 直接从Repository观察配置Flow，并将其与连接状态Flow结合
            homeAssistantRepository.getHaConfig().combine(homeAssistantRepository.connectionStatus) { config, isConnected ->
                // 更新ViewModel内部的配置状态，以便其他部分同步访问
                _haConfig.value = config
                // 每当任一Flow发出新值时，都会发出一个配对
                Pair(config, isConnected)
            }.collect { (config, isConnected) ->
                Log.d(
                    TAG,
                    "observeCombinedHaState - Collected: Config=$config, baseUrl=${config?.baseUrl}, token=${config?.token?.take(5)}..." +
                            ", Connected=$isConnected, isFetchingEntities=$isFetchingEntities"
                )

                if (config != null && config.baseUrl.isNotBlank() && config.token.isNotBlank()) {
                    // 配置有效
                    _selectedHaEntities.value = config.selectedEntities.split(",").filter { it.isNotBlank() }

                    if (isConnected) {
                        // 连接已激活
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastAttempt = currentTime - lastFetchAttemptTime
                        
                        if (!isFetchingEntities && timeSinceLastAttempt >= MIN_FETCH_INTERVAL_MS) {
                            Log.d(TAG, "observeCombinedHaState: 配置有效且已连接，当前未在获取实体。调用 fetchHomeAssistantEntities。")
                            lastFetchAttemptTime = currentTime
                            fetchHomeAssistantEntities()
                        } else if (isFetchingEntities) {
                            Log.d(TAG, "observeCombinedHaState: 正在获取实体，跳过重复调用。")
                        } else {
                            Log.d(TAG, "observeCombinedHaState: 距离上次获取尝试仅 ${timeSinceLastAttempt}ms，跳过以避免频繁重试。")
                        }
                    } else {
                        // 配置有效，但未连接。需要触发连接
                        Log.d(TAG, "observeCombinedHaState: 配置有效但已断开。触发直接连接尝试(不写库)。")
                        _haState.value = HaState.Loading
                        // 直接尝试连接而不写库，避免 Flow 级联触发重复重连

                        viewModelScope.launch { homeAssistantRepository.connectIfPossible(config) }
                        isFetchingEntities = false // 如果断开连接，则重置标志
                    }
                } else {
                    // 配置为null或无效

                    Log.d(TAG, "observeCombinedHaState: HA 配置为null或无效。重置状态。")
                    _haState.value = HaState.NotConfigured
                    _allHaEntities.value = emptyList()
                    _selectedHaEntities.value = emptyList()
                    isFetchingEntities = false // 重置标志
                }
            }
        }
    }

    private fun observeReminders() {
        viewModelScope.launch {
            reminderRepository.getAllReminders()
                .distinctUntilChanged()
                .collect { remindersList ->
                    _reminders.value = remindersList
                    Log.d(TAG, "提醒更新: ${remindersList.size} 条")
                }
        }
    }



    private fun observeHaEntityStateUpdates() {
        viewModelScope.launch {
            homeAssistantRepository.entityStateUpdates.collect { updatedEntityState ->
                Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                Log.d(TAG, "收到 WebSocket 更新:")
                Log.d(TAG, "   实体ID: ${updatedEntityState.entityId}")
                Log.d(TAG, "   状态 ${updatedEntityState.state}")
                Log.d(TAG, "   属性 ${updatedEntityState.attributes.keys.joinToString(", ")}")

                // 处理屏幕亮度 light 实体的控制命令
                val screenBrightnessLightId = "light.${deviceId}_screen_brightness"
                if (updatedEntityState.entityId == screenBrightnessLightId) {
                    // 检查是否是来自 call_service 的虚拟状态

                    val isFromServiceCall = updatedEntityState.context["from_service_call"] == true
                    
                    if (isFromServiceCall) {
                        // 来自 call_service 的虚拟状态，直接处理（跳过时间检查）
                        Log.d(TAG, "检测到来自 call_service 的屏幕亮度控制命令，直接处理")
                        handleScreenBrightnessControl(updatedEntityState)
                    } else {
                        // 检查是否是最近自己上报的状态（避免循环处理）

                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastReport = currentTime - lastReportedBrightnessTime
                        Log.d(TAG, "检测到屏幕亮度实体更新: entityId=$screenBrightnessLightId, 距离上次上报=${timeSinceLastReport}ms")
                        if (timeSinceLastReport > 3000 || lastReportedBrightnessTime == 0L) { 
                            // 如果距离上次上报超过3秒，或者从未上报过，认为是外部控制
                            Log.d(TAG, "判定为外部控制，处理屏幕亮度变更")
                            handleScreenBrightnessControl(updatedEntityState)
                        } else {
                            Log.d(TAG, "忽略自己上报的屏幕亮度状态变更（距离上次上报仅 ${timeSinceLastReport}ms）")
                        }
                    }
                }

                // 处理屏幕常亮 switch 实体的控制命令

                val screenKeepOnSwitchId = "switch.${deviceId}_keep_screen_on"
                if (updatedEntityState.entityId == screenKeepOnSwitchId) {
                    // 检查是否是来自 call_service 的虚拟状态

                    val isFromServiceCall = updatedEntityState.context["from_service_call"] == true
                    
                    if (isFromServiceCall) {
                        // 来自 call_service 的虚拟状态，直接处理（跳过时间检查）
                        Log.d(TAG, "检测到来自 call_service 的屏幕常亮控制命令，直接处理")
                        handleScreenKeepOnControl(updatedEntityState)
                    } else {
                        // 检查是否是最近自己上报的状态（避免循环处理）
                        val currentTime = System.currentTimeMillis()
                        val timeSinceLastReport = currentTime - lastReportedKeepOnTime
                        Log.d(TAG, "检测到屏幕常亮实体更新: entityId=$screenKeepOnSwitchId, 距离上次上报=${timeSinceLastReport}ms")
                        if (timeSinceLastReport > 3000 || lastReportedKeepOnTime == 0L) { 
                            // 如果距离上次上报超过3秒，或者从未上报过，认为是外部控制
                            Log.d(TAG, "判定为外部控制，处理屏幕常亮变更")
                            handleScreenKeepOnControl(updatedEntityState)
                        } else {
                            Log.d(TAG, "忽略自己上报的屏幕常亮状态变更（距离上次上报仅 ${timeSinceLastReport}ms）")
                        }
                    }
                }

                // 如果正在获取初始实体列表，跳过更新 _allHaEntities，避免重复
                // 初始获取完成后会一次性设置所有实体
                if (!isFetchingEntities) {
                    // Update _allHaEntities regardless of the current _haState.value.
                    // This ensures we always have the latest known state for all entities fetched.
                    val currentAllHaEntities = _allHaEntities.value.toMutableList()
                    val haEntityIndex = currentAllHaEntities.indexOfFirst { it.id == updatedEntityState.entityId }
                    val entityType = updatedEntityState.entityId.substringBefore(".")
                    val friendlyName = updatedEntityState.attributes["friendly_name"] as? String ?: updatedEntityState.entityId

                    val newHomeAssistantEntity = HomeAssistantEntity(
                        id = updatedEntityState.entityId,
                        name = friendlyName,
                        type = entityType,
                        area = null // Assuming area is not available or not used here
                    )

                    if (haEntityIndex != -1) {
                        currentAllHaEntities[haEntityIndex] = newHomeAssistantEntity
                        Log.d(TAG, "   更新 _allHaEntities 中的实体 (索引: $haEntityIndex)")
                    } else {
                        currentAllHaEntities.add(newHomeAssistantEntity)
                        Log.d(TAG, "   添加新实体到 _allHaEntities")
                    }
                    // 使用 distinctBy 确保列表中没有重复的实体（以防万一
                    _allHaEntities.value = currentAllHaEntities.distinctBy { it.id }
                } else {
                    Log.d(TAG, "   正在获取初始实体列表，跳过 _allHaEntities 更新以避免重复")
                }

                // 更新 climate 实体的最后有效模式
                val entityDomain = updatedEntityState.entityId.split(".").firstOrNull()
                if (entityDomain == "climate") {
                    val hvacMode = updatedEntityState.attributes["hvac_mode"] as? String ?: updatedEntityState.state
                    val modeLower = hvacMode.lowercase()
                    if (modeLower in listOf("cool", "cooling", "heat", "heating")) {
                        val currentModes = _climateLastValidModes.value.toMutableMap()
                        currentModes[updatedEntityState.entityId] = modeLower
                        _climateLastValidModes.value = currentModes
                        Log.d(TAG, "    更新 climate 最后有效模式 ${updatedEntityState.entityId} -> $modeLower")
                    }
                }
                
                // Update the *displayed* entities (_haState.value.entities) only if the connection is successful
                // and the current state is Success.
                val currentHaState = _haState.value
                Log.d(TAG, "   当前 HA 状态 ${currentHaState::class.simpleName}")
                
                if (currentHaState is HaState.Success) {
                    val updatedDisplayedEntities = currentHaState.entities.toMutableList()
                    val index = updatedDisplayedEntities.indexOfFirst { it.entityId == updatedEntityState.entityId }
                    
                    Log.d(TAG, "   显示列表中的实体数量: ${updatedDisplayedEntities.size}")
                    Log.d(TAG, "   在显示列表中找到索引: $index")
                    
                    if (index != -1) {
                        // If the entity exists in the currently displayed list, update its state.
                        val oldState = updatedDisplayedEntities[index].state
                        updatedDisplayedEntities[index] = updatedEntityState
                        _haState.value = HaState.Success(updatedDisplayedEntities.toList())
                        Log.d(TAG, "   成功更新显示实体: $oldState ${updatedEntityState.state}")
                    } else {
                        // If the entity was not found in the *currently displayed* list (index == -1), 
                        // it means it's either not selected by the user, or the list was cleared due to disconnection.
                        Log.w(TAG, "   实体 ${updatedEntityState.entityId} 不在显示列表中")
                        Log.w(TAG, "   显示列表实体: ${updatedDisplayedEntities.map { it.entityId }.joinToString(", ")}")
                    }
                } else {
                    // If the state is not Success (e.g., disconnected, loading, error), 
                    // we don't update the displayed entities list (_haState.value.entities).
                    Log.w(TAG, "   HA 状态不是 Success，跳过显示列表更新")
                }
            }
        }
    }
    
    /**
     * 处理屏幕亮度控制命令
     */
    private fun handleScreenBrightnessControl(entityState: HaEntityState) {
        try {
            val state = entityState.state
            val brightness = entityState.attributes["brightness"] as? Number
            
            Log.d(TAG, "处理屏幕亮度控制: state=$state, brightness=$brightness")
            
            var brightnessInt = 0
            var brightnessFloat = 0f
            
            if (state == "on" && brightness != null) {
                // 将 Home Assistant 的亮度值 (0-255) 转换为 float (0.0-1.0)

                brightnessInt = brightness.toInt().coerceIn(0, 255)
                brightnessFloat = brightnessInt / 255f
                screenBrightnessManager.setBrightness(brightnessFloat)
                Log.d(TAG, "屏幕亮度已设置为: $brightnessFloat (${brightnessInt}/255)")
            } else if (state == "off") {
                // 关闭屏幕亮度（设置为最低）
                brightnessInt = 0
                brightnessFloat = 0f
                screenBrightnessManager.setBrightness(0f)
                Log.d(TAG, "屏幕亮度已关闭")
            } else {
                Log.w(TAG, "屏幕亮度控制参数无效: state=$state, brightness=$brightness")
                return
            }

            // 立即上报新状态到 HA，并更新轮询记录，防止轮询覆盖

            viewModelScope.launch {
                reportScreenBrightnessState(brightnessInt)
                // 更新轮询记录，让轮询知道当前设备状态已经是新的
                lastPolledBrightness = brightnessFloat
                lastPolledBrightnessLastUpdated = System.currentTimeMillis().toString()
                Log.d(TAG, "已上报屏幕常亮状态并更新轮询记录，防止轮询覆盖")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理屏幕亮度控制失败: ${e.message}", e)
        }
    }
    
    /**
     * 处理屏幕常亮控制命令
     */
    private fun handleScreenKeepOnControl(entityState: HaEntityState) {
        try {
            val state = entityState.state
            val keepOn = state == "on"
            screenKeepOnManager.setKeepOn(keepOn)
            Log.d(TAG, "屏幕常亮已设置为: $keepOn")
            
            // 立即上报新状态到 HA，并更新轮询记录，防止轮询覆盖
            viewModelScope.launch {
                reportScreenKeepOnState(keepOn)
                // 更新轮询记录，让轮询知道当前设备状态已经是新的
                lastPolledKeepOn = keepOn
                lastPolledKeepOnLastUpdated = System.currentTimeMillis().toString()
                Log.d(TAG, "已上报屏幕常亮状态并更新轮询记录，防止轮询覆盖")
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理屏幕常亮控制失败: ${e.message}", e)
        }
    }
    
    /**
     * 上报屏幕亮度状态（用于同步）
     */
    private suspend fun reportScreenBrightnessState(brightness: Int) {
        try {
            lastReportedBrightnessTime = System.currentTimeMillis()
            val deviceName = android.os.Build.MODEL ?: "Android Device"
            val screenBrightnessLightId = "light.${deviceId}_screen_brightness"
            
            homeAssistantRepository.reportDeviceState(
                entityId = screenBrightnessLightId,
                state = if (brightness > 0) "on" else "off",
                attributes = mapOf(
                    "brightness" to brightness,
                    "supported_features" to 1, // SUPPORT_BRIGHTNESS
                    "supported_color_modes" to listOf("brightness"),
                    "color_mode" to "brightness",
                    "friendly_name" to "$deviceName 屏幕亮度"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "上报屏幕亮度状态失败${e.message}", e)
        }
    }
    
    /**
     * 上报屏幕常亮状态（用于同步）
     */
    private suspend fun reportScreenKeepOnState(keepOn: Boolean) {
        try {
            lastReportedKeepOnTime = System.currentTimeMillis()
            val deviceName = android.os.Build.MODEL ?: "Android Device"
            val screenKeepOnSwitchId = "switch.${deviceId}_keep_screen_on"
            
            homeAssistantRepository.reportDeviceState(
                entityId = screenKeepOnSwitchId,
                state = if (keepOn) "on" else "off",
                attributes = mapOf(
                    "friendly_name" to "$deviceName 屏幕常亮",
                    "icon" to "mdi:monitor-screenshot"
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "上报屏幕常亮状态失败 ${e.message}", e)
        }
    }

    fun onPermissionsGranted() {
        Log.d(TAG, "权限已授予，重启天气轮询")
        viewModelScope.launch { _weatherUpdateRequests.send(Unit) } // Request immediate update after permissions granted
        startWeatherPolling() // Start/restart periodic polling
    }

    private fun startWeatherPolling() {
        weatherPollingJob?.cancel() // Cancel any existing job to prevent multiple concurrent polling tasks

        val settings = _settings.value ?: return

        val refreshIntervalMinutes = settings.weatherRefreshIntervalMinutes.coerceAtLeast(1)
        val refreshIntervalMillis = refreshIntervalMinutes * 60 * 1000L

        if (refreshIntervalMinutes > 0) {
            weatherPollingJob = viewModelScope.launch {
                Log.d(TAG, "启动天气轮询，间隔: $refreshIntervalMinutes 分钟")
                
                while (true) {
                    delay(refreshIntervalMillis)
                    Log.d(TAG, "执行定期天气获取")
                    viewModelScope.launch { _weatherUpdateRequests.send(Unit) }
                }
            }
        } else {
            Log.d(TAG, "天气刷新间隔无效，不启动轮询")
        }
    }

    private fun fetchAndHandleWeather() {
        val settings = _settings.value ?: run {
            Log.e(TAG, "Settings are not loaded yet. Cannot fetch weather.")
            _weatherState.value = WeatherState.Error("设置未加载，请稍后重试。")
            return
        }

        val apiKey = settings.heFengApiKey
        val locationSource = settings.weatherLocationSource
        val manualLocation = settings.manualWeatherLocation

        if (apiKey.isNullOrBlank()) {
            Log.e(TAG, "Weather API Key is missing. Cannot fetch weather.")
            _weatherState.value = WeatherState.Error("和风天气 API Key 未设置。请前往设置页面填写。")
            return
        }

        val locationString = when (locationSource) {
            "GPS" -> {
                lastLocation?.let { "${it.longitude},${it.latitude}" } ?: run {
                    Log.d(TAG, "Waiting for GPS location... Cannot fetch weather yet.")
                    _weatherState.value = WeatherState.Error("正在等待 GPS 信号...")
                    null
                }
            }
            "Manual" -> {
                manualLocation.takeIf { it.isNotBlank() } ?: run {
                    Log.d(TAG, "Manual location is not set. Cannot fetch weather.")
                    _weatherState.value = WeatherState.Error("请在设置中输入手动位置。")
                    null
                }
            }
            else -> {
                Log.e(TAG, "Unknown weather location source: $locationSource. Cannot fetch weather.")
                _weatherState.value = WeatherState.Error("未知的定位方式。")
                null
            }
        }

        if (locationString == null) {
            return // Exit if location is not available or valid yet
        }

        viewModelScope.launch {
            Log.d(TAG, "Attempting to fetch weather for location: $locationString")
            _weatherState.value = WeatherState.Loading
            try {
                val weather = weatherRepository.fetchAndCacheWeather(locationString, apiKey)
                if (weather != null) {
                    _weatherState.value = WeatherState.Success(weather)
                    Log.d(TAG, "Weather fetched successfully.")
                } else {
                    _weatherState.value = WeatherState.Error("天气数据加载失败，请检查网络或 API Key。")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching weather: ${e.message}", e)
                _weatherState.value = WeatherState.Error("天气 API 调用时发生错误：${e.message}")
            }
        }
    }

    private fun startGpsListener() {
        if (_settings.value?.weatherLocationSource != "GPS") return

        if (ContextCompat.checkSelfPermission(application, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val gpsUpdateIntervalMillis = (_settings.value?.gpsUpdateIntervalMinutes?.coerceAtLeast(1) ?: 1) * 60 * 1000L
                Log.d(TAG, "Requesting location updates with interval: ${gpsUpdateIntervalMillis / 1000} seconds.")
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, gpsUpdateIntervalMillis, 10f, locationListener, Looper.getMainLooper())
                // Always try to get last known location, update lastLocation, but DO NOT immediately send Unit to channel here.
                // The onLocationChanged callback or periodic polling will handle the actual weather fetch.
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let {
                    Log.d(TAG, "Got last known location, updating lastLocation.")
                    lastLocation = it
                    // Removed: viewModelScope.launch { _weatherUpdateRequests.send(Unit) } // This was a source of duplicate immediate calls
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException requesting location updates.", e)
                _weatherState.value = WeatherState.Error("获取位置权限被拒绝。")
            }
            catch (e: Exception) {
                Log.e(TAG, "Exception requesting location updates.", e)
                _weatherState.value = WeatherState.Error("位置服务启动失败：${e.message}")
            }
        } else {
            Log.e(TAG, "Location permission not granted.")
            _weatherState.value = WeatherState.Error("未授予位置权限。请在系统设置中开启。")
        }
    }

    private fun stopGpsListener() {
        Log.d(TAG, "Removing location updates listener.")
        // It's safe to call removeUpdates even if the listener is not currently registered
        locationManager.removeUpdates(locationListener)
    }

    suspend fun fetchHomeAssistantEntities() {
        // Directly use _haConfig.value to ensure we always have the latest configuration
        val config = _haConfig.value 
        if (config == null || config.baseUrl.isBlank() || config.token.isBlank()) {
            _haState.value = HaState.NotConfigured
            _allHaEntities.value = emptyList()
            _selectedHaEntities.value = emptyList()
            Log.d(TAG, "fetchHomeAssistantEntities: HA not configured. Skipping fetch.")
            isFetchingEntities = false // Reset flag
            return
        }

        // Prevent concurrent fetches
        if (isFetchingEntities) {
            Log.d(TAG, "fetchHomeAssistantEntities: Already fetching entities, skipping duplicate call.")
            return
        }
        isFetchingEntities = true // Set flag

        _haState.value = HaState.Loading
        Log.d(TAG, "fetchHomeAssistantEntities: Setting state to Loading.")

        try {
            val entitiesFromWebSocket = homeAssistantRepository.getInitialEntityStates()

            if (entitiesFromWebSocket.isNotEmpty()) {
                val haEntities = entitiesFromWebSocket.map { haEntityState ->
                    val entityId = haEntityState.entityId
                    val type = entityId.substringBefore(".")
                    val name = haEntityState.attributes["friendly_name"] as? String ?: entityId
                    HomeAssistantEntity(id = entityId, name = name, type = type, area = null)
                }
                // 使用 distinctBy 确保列表中没有重复的实体（按 entityId 去重）
                val uniqueHaEntities = haEntities.distinctBy { it.id }
                if (uniqueHaEntities.size != haEntities.size) {
                    Log.w(TAG, "fetchHomeAssistantEntities: 检测到重复实体，已去重。原始数量: ${haEntities.size}, 去重后: ${uniqueHaEntities.size}")

                }
                _allHaEntities.value = uniqueHaEntities
                Log.d(TAG, "fetchHomeAssistantEntities: Fetched ${uniqueHaEntities.size} total HA entities.")

                // Use the latest _haConfig.value for selected entities, as it's the source of truth
                val currentSelectedEntityIds = _haConfig.value?.selectedEntities?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                _selectedHaEntities.value = currentSelectedEntityIds
                Log.d(TAG, "fetchHomeAssistantEntities: User selected entities: $currentSelectedEntityIds")

                val displayedEntities = entitiesFromWebSocket.filter { currentSelectedEntityIds.contains(it.entityId) }
                
                // 初始化climate 实体的最后有效模式
                val initialModes = mutableMapOf<String, String>()
                displayedEntities.forEach { entity ->
                    val entityDomain = entity.entityId.split(".").firstOrNull()
                    if (entityDomain == "climate") {
                        val hvacMode = entity.attributes["hvac_mode"] as? String ?: entity.state
                        val modeLower = hvacMode.lowercase()
                        if (modeLower in listOf("cool", "cooling", "heat", "heating")) {
                            initialModes[entity.entityId] = modeLower
                            Log.d(TAG, "初始化climate 实体的最后有效模式 ${entity.entityId} -> $modeLower")
                        }
                    }
                }
                if (initialModes.isNotEmpty()) {
                    _climateLastValidModes.value = initialModes
                }
                
                _haState.value = HaState.Success(displayedEntities)
                Log.d(TAG, "fetchHomeAssistantEntities: Set HA state to Success with ${displayedEntities.size} displayed entities.")
                
                // 补充缺失的实体ID到widgetOrder
                val currentOrder = _widgetOrder.value.toMutableList()
                val displayedEntityIds = displayedEntities.map { it.entityId }
                val missingEntityIds = displayedEntityIds.filter { !currentOrder.contains(it) }
                if (missingEntityIds.isNotEmpty()) {
                    Log.d(TAG, "Adding missing entity IDs to widgetOrder: $missingEntityIds")
                    currentOrder.addAll(missingEntityIds)
                    _widgetOrder.value = currentOrder
                    val json = gson.toJson(currentOrder)
                    layoutRepository.saveLayoutConfig(json)
                    Log.d(TAG, "Updated widgetOrder with missing entities: $currentOrder")
                }

            } else {
                Log.w(TAG, "fetchHomeAssistantEntities: Home Assistant returned an empty list of entities or failed to fetch.")
                _haState.value = HaState.Error("Home Assistant 数据加载失败或无可用实体。请检查配置和网络连接。")
                _allHaEntities.value = emptyList() 
                Log.d(TAG, "fetchHomeAssistantEntities: Set HA state to Error due to empty entity list.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching HA entities in fetchHomeAssistantEntities: ${e.message}", e)
            val errorMessage = e.message ?: "未知错误"
            val userFriendlyMessage = when {
                errorMessage.contains("WebSocket closed", ignoreCase = true) ->
                    "WebSocket 连接已断开。请检查网络连接或稍后重试。"
                errorMessage.contains("CancellationException", ignoreCase = true) ->
                    "操作被取消。如果问题持续，请重新连接。"
                else -> "Home Assistant API 调用时发生错误：$errorMessage"
            }
            _haState.value = HaState.Error(userFriendlyMessage)
            _allHaEntities.value = emptyList()
            Log.d(TAG, "fetchHomeAssistantEntities: Set HA state to Error due to exception.")
        }
        finally {
            isFetchingEntities = false // Reset flag regardless of outcome
            Log.d(TAG, "fetchHomeAssistantEntities: Resetting isFetchingEntities flag.")
        }
    }

    fun saveHaConfiguration() {
        viewModelScope.launch {
            _haConfig.value?.let { config ->
                homeAssistantRepository.saveHaConfig(config)
                // After saving config (which triggers connection attempt in repository),
                // wait for the connection to be established before fetching entities.
                homeAssistantRepository.connectionStatus.firstOrNull { it == true } // Suspend until connected
                Log.d(TAG, "saveHaConfiguration: Connection confirmed after saving config. Attempting to fetch entities.")
                fetchHomeAssistantEntities()
            }
        }
    }

    // New functions to update Home Assistant configuration
    fun updateHaBaseUrl(baseUrl: String) {
        viewModelScope.launch {
            val currentConfig = _haConfig.value ?: HaConfigEntity()
            val updatedConfig = currentConfig.copy(baseUrl = baseUrl)
            _haConfig.value = updatedConfig
            saveHaConfiguration() // Save the updated configuration
        }
    }

    fun updateHaToken(token: String) {
        viewModelScope.launch {
            val currentConfig = _haConfig.value ?: HaConfigEntity()
            val updatedConfig = currentConfig.copy(token = token)
            _haConfig.value = updatedConfig
            saveHaConfiguration() // Save the updated configuration
        }
    }


    fun updateSelectedHomeAssistantEntities(selectedEntityIds: List<String>) {
        viewModelScope.launch {
            val currentConfig = _haConfig.value ?: return@launch
            val updatedConfig = currentConfig.copy(
                selectedEntities = selectedEntityIds.joinToString(",")
            )
            homeAssistantRepository.saveHaConfig(updatedConfig)
            _selectedHaEntities.value = selectedEntityIds
            // Fetch entities again to reflect the new selection immediately after saving config
            // This ensures that if the connection is already active, the UI updates promptly.
            if (_haState.value is HaState.Success) { // Only fetch if we are already in a connected state
                fetchHomeAssistantEntities()
            }
        }
    }

    fun toggleHaDeviceState(entityId: String, targetState: String) {
        viewModelScope.launch {
            _haConfig.value ?: return@launch
            val domain = entityId.split(".").firstOrNull() ?: return@launch
            val service = if (targetState == "on") "turn_on" else "turn_off"

            // 保存当前状态，以便失败时回滚
            val currentHaState = _haState.value

            // 乐观更新：立即更新 UI 状态
            if (currentHaState is HaState.Success) {
                val currentTime = System.currentTimeMillis().toString()
                val updatedEntities = currentHaState.entities.map { entity ->
                    if (entity.entityId == entityId) {
                        entity.copy(
                            state = targetState,
                            lastUpdated = currentTime
                        )
                    } else {
                        entity
                    }
                }
                _haState.value = HaState.Success(updatedEntities)
                Log.d(TAG, "乐观更新: $entityId 状态设置为 $targetState (lastUpdated: $currentTime)")
            }

            val success = homeAssistantRepository.callService(domain, service, entityId)
            if (success) {
                Log.d(TAG, "Service call for $entityId succeeded. Awaiting WebSocket update.")
            } else {
                // 如果调用失败，回滚状态
                if (currentHaState is HaState.Success) {
                    _haState.value = currentHaState
                    Log.e(TAG, "Service call failed for $entityId, 回滚状态")
                }
                _haState.value = HaState.Error("Home Assistant 服务调用失败。")
            }
        }
    }

    fun pressHaButton(entityId: String) {
        viewModelScope.launch {
            _haConfig.value ?: return@launch
            val domain = "button"
            val service = "press"

            val success = homeAssistantRepository.callService(domain, service, entityId)
            if (success) {
                Log.d(TAG, "Button press service call for $entityId succeeded.")
            }
            else {
                _haState.value = HaState.Error("Home Assistant 按钮服务调用失败。")
            }
        }
    }

    fun setClimateTemperature(entityId: String, temperature: Float) {
        viewModelScope.launch {
            _haConfig.value ?: return@launch
            val domain = "climate"
            val service = "set_temperature"
            val serviceData = mapOf("temperature" to temperature)

            val success = homeAssistantRepository.callService(domain, service, entityId, serviceData)
            if (success) {
                Log.d(TAG, "Set climate temperature service call for $entityId succeeded. Awaiting WebSocket update.")
            }
            else {
                _haState.value = HaState.Error("Home Assistant 空调服务调用失败。")
            }
        }
    }

    fun setClimateHvacMode(entityId: String, mode: String) {
        viewModelScope.launch {
            _haConfig.value ?: return@launch
            val domain = "climate"
            val service = "set_hvac_mode"
            val serviceData = mapOf("hvac_mode" to mode)

            // 保存当前状态，以便失败时回滚
            val currentHaState = _haState.value

            // 乐观更新：立即更新 UI 状态
            if (currentHaState is HaState.Success) {
                val currentTime = System.currentTimeMillis().toString()
                val modeLower = mode.lowercase()
                
                // 如果新模式是有效的制热或制冷模式，更新最后有效模式
                if (modeLower in listOf("cool", "cooling", "heat", "heating")) {
                    val currentModes = _climateLastValidModes.value.toMutableMap()
                    currentModes[entityId] = modeLower
                    _climateLastValidModes.value = currentModes
                    Log.d(TAG, "乐观更新: 更新 climate 最后有效模式 $entityId -> $modeLower")
                }
                
                val updatedEntities = currentHaState.entities.map { entity ->
                    if (entity.entityId == entityId) {
                        val newAttributes = entity.attributes.toMutableMap()
                        newAttributes["hvac_mode"] = mode
                        entity.copy(
                            attributes = newAttributes,
                            lastUpdated = currentTime
                        )
                    } else {
                        entity
                    }
                }
                _haState.value = HaState.Success(updatedEntities)
                Log.d(TAG, "乐观更新: $entityId HVAC 模式设置$mode (lastUpdated: $currentTime)")
            }

            val success = homeAssistantRepository.callService(domain, service, entityId, serviceData)
            if (success) {
                Log.d(TAG, "Set climate HVAC mode service call for $entityId succeeded. Awaiting WebSocket update.")
            }
            else {
                // 如果调用失败，回滚状态
                if (currentHaState is HaState.Success) {
                    _haState.value = currentHaState
                    Log.e(TAG, "Set HVAC mode failed for $entityId, 回滚")
                }
                _haState.value = HaState.Error("Home Assistant 空调模式设置失败。")
            }
        }
    }

    fun setLightBrightness(entityId: String, brightnessPct: Int) {
        viewModelScope.launch {
            _haConfig.value ?: return@launch
            val domain = "light"
            val service = "turn_on"
            val serviceData = mapOf("brightness_pct" to brightnessPct)

            //  保存当前状态
            val currentHaState = _haState.value
            
            // 乐观更新：立即更新亮度
            if (currentHaState is HaState.Success) {
                val currentTime = System.currentTimeMillis().toString()
                val updatedEntities = currentHaState.entities.map { entity ->
                    if (entity.entityId == entityId) {
                        val newAttributes = entity.attributes.toMutableMap()
                        newAttributes["brightness"] = (brightnessPct * 2.55).toInt() // 转换为 0-255
                        entity.copy(
                            attributes = newAttributes,
                            lastUpdated = currentTime
                        )
                    } else {
                        entity
                    }
                }
                _haState.value = HaState.Success(updatedEntities)
                Log.d(TAG, "乐观更新: $entityId 亮度设置为 $brightnessPct% (lastUpdated: $currentTime)")
            }

            val success = homeAssistantRepository.callService(domain, service, entityId, serviceData)
            if (success) {
                Log.d(TAG, "Set light brightness service call for $entityId succeeded. Awaiting WebSocket update.")
            } else {
                // 如果调用失败，回滚状态
                if (currentHaState is HaState.Success) {
                    _haState.value = currentHaState
                    Log.e(TAG, "Set brightness failed for $entityId, 回滚")
                }
                _haState.value = HaState.Error("Home Assistant 灯光亮度设置失败")
            }
        }
    }

    fun setInputSelectOption(entityId: String, option: String) {
        viewModelScope.launch {
            _haConfig.value ?: return@launch
            val domain = "input_select"
            val service = "select_option"
            val serviceData = mapOf("option" to option)

            // 保存当前状态态，以便失败时回滚
            val currentHaState = _haState.value
            
            // 乐观更新：立即更新UI状态
            if (currentHaState is HaState.Success) {
                val currentTime = System.currentTimeMillis().toString()
                val updatedEntities = currentHaState.entities.map { entity ->
                    if (entity.entityId == entityId) {
                        entity.copy(
                            state = option,
                            lastUpdated = currentTime
                        )
                    } else {
                        entity
                    }
                }
                _haState.value = HaState.Success(updatedEntities)
                Log.d(TAG, "乐观更新: $entityId 选项设置$option (lastUpdated: $currentTime)")
            }

            val success = homeAssistantRepository.callService(domain, service, entityId, serviceData)
            if (success) {
                Log.d(TAG, "Set input_select option service call for $entityId succeeded. Awaiting WebSocket update.")
            } else {
                // 如果调用失败，回滚状态
                if (currentHaState is HaState.Success) {
                    _haState.value = currentHaState
                    Log.e(TAG, "Set input_select option failed for $entityId, 回滚")
                }
                _haState.value = HaState.Error("Home Assistant input_select 选项设置失败")
            }
        }
    }

    fun setLightColorTemperature(entityId: String, colorTempKelvin: Int) {
        viewModelScope.launch {
            _haConfig.value ?: return@launch
            val domain = "light"
            val service = "turn_on"
            val serviceData = mapOf("color_temp_kelvin" to colorTempKelvin)

            // 保存当前状态
            val currentHaState = _haState.value
            
            // 乐观更新：立即更新色温
            if (currentHaState is HaState.Success) {
                val currentTime = System.currentTimeMillis().toString()
                val updatedEntities = currentHaState.entities.map { entity ->
                    if (entity.entityId == entityId) {
                        val newAttributes = entity.attributes.toMutableMap()
                        newAttributes["color_temp_kelvin"] = colorTempKelvin
                        entity.copy(
                            attributes = newAttributes,
                            lastUpdated = currentTime
                        )
                    } else {
                        entity
                    }
                }
                _haState.value = HaState.Success(updatedEntities)
                Log.d(TAG, "乐观更新: $entityId 色温设置${colorTempKelvin}K (lastUpdated: $currentTime)")
            }

            val success = homeAssistantRepository.callService(domain, service, entityId, serviceData)
            if (success) {
                Log.d(TAG, "Set light color temperature service call for $entityId succeeded. Awaiting WebSocket update.")
            } else {
                // 如果调用失败，回滚状态
                if (currentHaState is HaState.Success) {
                    _haState.value = currentHaState
                    Log.e(TAG, "Set color temperature failed for $entityId, 回滚")
                }
                _haState.value = HaState.Error("Home Assistant 灯光色温设置失败")
            }
        }
    }


    private fun observeLayoutConfig() {
        viewModelScope.launch {
            layoutRepository.getLayoutConfig()
                .collect { config ->
                    Log.d(TAG, "Layout config from DB: ${config?.widgetOrder}")
                    val order = try {
                        if (config?.widgetOrder.isNullOrBlank()) {
                            Log.d(TAG, "Using default layout order")
                            listOf("WEATHER", "REMINDERS", "SETTINGS", "HA_GRID")
                        } else {
                            val type = object : TypeToken<List<String>>() {}.type
                            val parsed = gson.fromJson<List<String>>(config?.widgetOrder, type) 
                                ?: listOf("WEATHER", "REMINDERS", "SETTINGS", "HA_GRID")
                            
                            // 检测旧版本的布局数据
                            // 新版本的布局应该包含：
                            // 1. HA_GRID（用于显示未明确列出的实体）
                            // 2. 或者包含 HA 实体（任何 domain.entity_id 格式的项，如 binary_sensor.*, light.* 等）
                            // 3. 或者包含 button.* 或 conversation.*（特殊的 HA 实体类型）
                            // 如果都不满足，说明是旧版本的遗留数据，需要重置
                            val hasHaGrid = parsed.contains("HA_GRID")
                            val hasHaEntities = parsed.any { itemId ->
                                // 检查是否是 HA 实体（格式：domain.entity_id）
                                itemId.contains(".") && 
                                itemId !in listOf("WEATHER", "REMINDERS", "SETTINGS", "HA_GRID") &&
                                (itemId.split(".").size >= 2)
                            }
                            val hasButtonOrConversation = parsed.any { 
                                it.startsWith("button.") || it.startsWith("conversation.") 
                            }
                            
                            if (!hasHaGrid && !hasHaEntities && !hasButtonOrConversation) {
                                Log.w(TAG, "Detected legacy layout without HA_GRID or HA entities, resetting to default")
                                layoutRepository.deleteLayoutConfig()
                                listOf("WEATHER", "REMINDERS", "SETTINGS", "HA_GRID")
                            } else {
                                parsed
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing layout config: ${e.message}", e)
                        listOf("WEATHER", "REMINDERS", "SETTINGS", "HA_GRID")
                    }
                    // 去重,防止旧数据中有重复项
                    val uniqueOrder = order.distinct()
                    if (uniqueOrder.size != order.size) {
                        Log.w(TAG, "Detected duplicate items in layout order, removing duplicates")
                        // 保存去重后的数据
                        val json = gson.toJson(uniqueOrder)
                        layoutRepository.saveLayoutConfig(json)
                    }
                    _widgetOrder.value = uniqueOrder
                    Log.d(TAG, "Layout order loaded: $uniqueOrder")

                    // 更新位置信息：为新项生成位置，保持已有项的位置
                    val currentPositions = _widgetPositions.value.toMutableMap()
                    
                    // 找出没有位置的项
                    val itemsWithoutPosition = uniqueOrder.filter { itemId ->
                        currentPositions[itemId] == null
                    }
                    
                    if (itemsWithoutPosition.isNotEmpty()) {
                        // 获取已有项占用的位置
                        val occupied = mutableSetOf<Pair<Int, Int>>()
                        val maxColumns = _settings.value?.gridColumns ?: 4
                        
                        // 收集已有项占用的网格
                        uniqueOrder.forEach { itemId ->
                            val position = currentPositions[itemId]
                            if (position != null) {
                                val (width, height) = getItemSize(itemId)
                                for (dy in 0 until height) {
                                    for (dx in 0 until width) {
                                        if (position.x + dx < maxColumns) {
                                            occupied.add(Pair(position.x + dx, position.y + dy))
                                        }
                                    }
                                }
                            }
                        }

                        // 为新项生成位置
                        itemsWithoutPosition.forEach { itemId ->
                            val (width, height) = getItemSize(itemId)
                            val position = findAvailablePosition(width, height, maxColumns, occupied)
                            if (position != null) {
                                currentPositions[itemId] = position
                                
                                // 标记占用
                                for (dy in 0 until height) {
                                    for (dx in 0 until width) {
                                        if (position.x + dx < maxColumns) {
                                            occupied.add(Pair(position.x + dx, position.y + dy))
                                        }
                                    }
                                }
                            }
                        }
                        
                        _widgetPositions.value = currentPositions
                        Log.d(TAG, "为新项生成位置 $itemsWithoutPosition")
                    } else if (currentPositions.isEmpty()) {
                        // 如果完全没有位置信息，生成所有项的位置
                        val autoPositions = generatePositionsFromOrder(uniqueOrder)
                        _widgetPositions.value = autoPositions
                        Log.d(TAG, "自动生成所有位置信息 $autoPositions")
                    }
                }
        }
    }
    
    /**
     * 获取项的大小（辅助函数，供多个地方使用）
     */
    private fun getItemSize(itemId: String): Pair<Int, Int> {
        return when (itemId) {
            "WEATHER" -> Pair(1, 2) // 1x2
            "REMINDERS" -> Pair(4, 1) // 4x1 (从设置中读取，默认4x1)
            "SETTINGS" -> Pair(1, 1) // 1x1
            "HA_GRID" -> Pair(1, 1) // 1x1
            else -> {
                // HA 实体，根据实体类型判断
                if (itemId.contains("climate.")) {
                    Pair(1, 2) // climate 实体1x2
                } else {
                    Pair(1, 1) // 默认 1x1
                }
            }
        }
    }
    
    /**
     * 查找可用位置
     */
    private fun findAvailablePosition(
        width: Int,
        height: Int,
        maxColumns: Int,
        occupied: MutableSet<Pair<Int, Int>>
    ): io.linkmate.ui.components.WidgetPosition? {
        var x = 0
        var y = 0
        var attempts = 0
        val maxAttempts = 200
        
        while (attempts < maxAttempts) {
            if (x + width > maxColumns) {
                x = 0
                y++
            }
            
            // 检查是否可以摆放
            val canPlace = (0 until height).all { dy ->
                (0 until width).all { dx ->
                    Pair(x + dx, y + dy) !in occupied
                }
            }
            
            if (canPlace) {
                return io.linkmate.ui.components.WidgetPosition(x, y)
            }
            
            x++
            attempts++
        }
        
        return null
    }
    
    /**
     * 根据顺序列表自动生成位置信息（向后兼容）
     * 考虑每个项的实际大小，确保不重叠
     */
    private fun generatePositionsFromOrder(order: List<String>): Map<String, io.linkmate.ui.components.WidgetPosition> {
        val positions = mutableMapOf<String, io.linkmate.ui.components.WidgetPosition>()
        val maxColumns = _settings.value?.gridColumns ?: 4 // 使用设置中的列数
        val occupied = mutableSetOf<Pair<Int, Int>>()
        
        
        order.forEach { itemId ->
            val (width, height) = getItemSize(itemId)
            
            // 查找可以放置的位置
            var found = false
            var x = 0
            var y = 0
            var attempts = 0
            val maxAttempts = 200
            
            while (!found && attempts < maxAttempts) {
                // 检查是否可以放置（考虑宽度）
                if (x + width > maxColumns) {
                    x = 0
                    y++
                    attempts++
                    continue
                }
                
                // 检查该位置是否被占用
                val canPlace = (0 until height).all { dy ->
                    (0 until width).all { dx ->
                        Pair(x + dx, y + dy) !in occupied
                    }
                }
                
                if (canPlace) {
                    found = true
                    positions[itemId] = io.linkmate.ui.components.WidgetPosition(x, y)
                    
                    // 标记占用的网格
                    for (dy in 0 until height) {
                        for (dx in 0 until width) {
                            occupied.add(Pair(x + dx, y + dy))
                        }
                    }
                } else {
                    x++
                    attempts++
                }
            }
            
            if (!found) {
                Log.w(TAG, "无法为项 $itemId 找到位置，使用默认位置(0, 0)")
                // 如果找不到位置，至少标记占用，避免后续项重叠
                positions[itemId] = io.linkmate.ui.components.WidgetPosition(0, y)
            }
        }
        
        return positions
    }
    
    /**
     * 更新 widget 的位置
     */
    fun updateWidgetPosition(itemId: String, position: io.linkmate.ui.components.WidgetPosition) {
        viewModelScope.launch {
            val currentPositions = _widgetPositions.value.toMutableMap()
            currentPositions[itemId] = position
            _widgetPositions.value = currentPositions
            
            Log.d(TAG, "Widget position updated: $itemId -> (${position.x}, ${position.y})")
            
            // 保存位置信息到数据库（暂时先只保存顺序，位置信息可以后续扩展）
            // TODO: 扩展数据库结构以支持位置信息存储
        }
    }

    fun updateWidgetOrder(itemIds: List<String>) {
        Log.d(TAG, "updateWidgetOrder called with itemIds: $itemIds")
        viewModelScope.launch {
            // 去重,保持顺序
            val uniqueItemIds = itemIds.distinct()
            _widgetOrder.value = uniqueItemIds
            // 保存到数据库
            val json = gson.toJson(uniqueItemIds)
            layoutRepository.saveLayoutConfig(json)
            Log.d(TAG, "Widget order updated (after deduplication): $uniqueItemIds")
        }
    }
    
    fun updateWidgetOrder(fromIndex: Int, toIndex: Int) {
        Log.d(TAG, "updateWidgetOrder called: fromIndex=$fromIndex, toIndex=$toIndex")
        viewModelScope.launch {
            val currentOrder = _widgetOrder.value.toMutableList()
            
            Log.d(TAG, "updateWidgetOrder: fromIndex=$fromIndex, toIndex=$toIndex, currentOrder=$currentOrder")
            
            if (fromIndex in currentOrder.indices && toIndex in currentOrder.indices) {
                val item = currentOrder.removeAt(fromIndex)
                currentOrder.add(toIndex, item)
                _widgetOrder.value = currentOrder
                
                // 保存到数据库
                val json = gson.toJson(currentOrder)
                layoutRepository.saveLayoutConfig(json)
                Log.d(TAG, "Widget order updated: $currentOrder")
            } else {
                Log.e(TAG, "Invalid indices: fromIndex=$fromIndex, toIndex=$toIndex, orderSize=${currentOrder.size}")
            }
        }
    }

    private fun registerProximitySensorListener() {
        proximitySensor?.let { sensorManager.registerListener(proximitySensorListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    private fun unregisterProximitySensorListener() {
        sensorManager.unregisterListener(proximitySensorListener)
    }
    
    /**
     * 启动设备状态上报
     */
    private fun startDeviceStateReporting() {
        deviceStateReportingJob?.cancel()
        
        val settings = _settings.value ?: return
        
        if (!settings.enableDeviceStateReporting) {
            Log.d(TAG, "设备状态上报已禁用")
            return
        }
        
        val reportIntervalMinutes = settings.deviceStateReportIntervalMinutes.coerceAtLeast(1)
        val reportIntervalMillis = reportIntervalMinutes * 60 * 1000L
        
        deviceStateReportingJob = viewModelScope.launch {
            Log.d(TAG, "启动设备状态上报，间隔: $reportIntervalMinutes 分钟")
            
            // 立即执行一次上上报
            Log.d(TAG, " 立即执行首次设备状态上上报")
            reportDeviceState()
            
            // 然后按间隔定期上上报
            while (true) {
                delay(reportIntervalMillis)
                Log.d(TAG, " 执行定期设备状态上报")
                reportDeviceState()
            }
        }
    }
    
    /**
     * 上报设备状态到 Home Assistant
     */
    private suspend fun reportDeviceState() {
        try {
            // 检查是否已连接Home Assistant
            val haConfig = _haConfig.value
            if (haConfig == null) {
                Log.w(TAG, "无法上报设备状态: Home Assistant 未配置")
                return
            }
            
            Log.d(TAG, "开始收集并上报设备状态...")
            
            // 收集设备状态
            val deviceState = deviceStateCollector.collectDeviceState()
            Log.d(TAG, "收集到的设备状态: 电量=${deviceState.batteryLevel}%, 充电=${deviceState.isCharging}, 屏幕=${deviceState.isScreenOn}")
            
            // 获取设备名称
            val deviceName = android.os.Build.MODEL ?: "Android Device"
            val deviceId = android.provider.Settings.Secure.getString(
                application.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ).take(8)
            
            Log.d(TAG, "设备ID: $deviceId, 设备名称: $deviceName")
            
            // 上报电池电量
            Log.d(TAG, "上报电池电量: sensor.${deviceId}_battery_level = ${deviceState.batteryLevel}%")
            val batterySuccess = homeAssistantRepository.reportDeviceState(
                entityId = "sensor.${deviceId}_battery_level",
                state = deviceState.batteryLevel,
                attributes = mapOf(
                    "unit_of_measurement" to "%",
                    "device_class" to "battery",
                    "friendly_name" to "$deviceName 电池电量",
                    "charging" to deviceState.isCharging,
                    "charging_type" to deviceState.chargingType
                )
            )
            if (batterySuccess) {
                Log.d(TAG, "电池电量上报成功")
            } else {
                Log.e(TAG, "电池电量上报失败")
            }

            Log.d(TAG, " 上报屏幕状态 binary_sensor.${deviceId}_screen = ${if (deviceState.isScreenOn) "on" else "off"}")
            val screenSuccess = homeAssistantRepository.reportDeviceState(
                entityId = "binary_sensor.${deviceId}_screen",
                state = if (deviceState.isScreenOn) "on" else "off",
                attributes = mapOf(
                    "device_class" to "power",
                    "friendly_name" to "$deviceName 屏幕状态"                )
            )
            if (screenSuccess) {
                Log.d(TAG, "屏幕状态上报成功")
            } else {
                Log.e(TAG, "屏幕状态上报失败")
            }


            val currentBrightnessFloat = screenBrightnessManager.getBrightness()
            val currentBrightnessInt = (currentBrightnessFloat * 255).toInt().coerceIn(0, 255)
            Log.d(TAG, "上报屏幕亮度: light.${deviceId}_screen_brightness = $currentBrightnessInt/255")
            reportScreenBrightnessState(currentBrightnessInt)
            
            val isKeepOn = screenKeepOnManager.isKeepOn()
            Log.d(TAG, "上报屏幕常亮: switch.${deviceId}_keep_screen_on = ${if (isKeepOn) "on" else "off"}")
            reportScreenKeepOnState(isKeepOn)
            
            // 上报充电状态（独立传感器）
            Log.d(TAG, " 上报充电状态 binary_sensor.${deviceId}_charging = ${if (deviceState.isCharging) "on" else "off"}")
            val chargingSuccess = homeAssistantRepository.reportDeviceState(
                entityId = "binary_sensor.${deviceId}_charging",
                state = if (deviceState.isCharging) "on" else "off",
                attributes = mapOf(
                    "device_class" to "battery_charging",
                    "friendly_name" to "$deviceName 充电状态",
                    "charging_type" to deviceState.chargingType
                )
            )
            if (chargingSuccess) {
                Log.d(TAG, "充电状态上报成功")
            } else {
                Log.e(TAG, "充电状态上报失败")
            }
            
            // 上报位置(如果可用)
            if (deviceState.latitude != null && deviceState.longitude != null) {
                Log.d(TAG, "上报位置: device_tracker.${deviceId} = (${deviceState.latitude}, ${deviceState.longitude})")
                val locationSuccess = homeAssistantRepository.reportDeviceState(
                    entityId = "device_tracker.${deviceId}",
                    state = "home",
                    attributes = mapOf(
                        "latitude" to deviceState.latitude,
                        "longitude" to deviceState.longitude,
                        "gps_accuracy" to deviceState.locationAccuracy,
                        "friendly_name" to "$deviceName 位置",
                        "source_type" to "gps"
                    )
                )
                if (locationSuccess) {
                    Log.d(TAG, "位置上报成功")
                } else {
                    Log.e(TAG, "位置上报失败")
                }
            } else {
                Log.d(TAG, "跳过位置上报（位置信息不可用）")
            }

            Log.d(TAG, "设备状态上报完成")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report device state: ${e.message}", e)
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopGpsListener()
        weatherPollingJob?.cancel() // Cancel the polling job
        deviceStateReportingJob?.cancel() // Cancel device state reporting
        screenControlPollingJob?.cancel() // Cancel screen control polling
        _weatherUpdateRequests.close() // Close the channel
        unregisterProximitySensorListener()
    }

    sealed class WeatherState {
        object Loading : WeatherState()
        data class Success(val weather: WeatherCacheEntity) : WeatherState()
        data class Error(val message: String) : WeatherState()
    }

    sealed class HaState {
        object Loading : HaState()
        data class Success(val entities: List<HaEntityState>) : HaState()
        data class Error(val message: String) : HaState()
        object NotConfigured : HaState()
    }
}
