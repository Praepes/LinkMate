package io.linkmate.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.linkmate.data.local.HaConfigEntity
import io.linkmate.data.local.SettingsEntity
import io.linkmate.data.repository.HomeAssistantRepository
import io.linkmate.data.repository.SettingsRepository
import io.linkmate.data.repository.WebServerConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log // Added for logging
import android.content.Context

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val homeAssistantRepository: HomeAssistantRepository,
    private val settingsRepository: SettingsRepository,
    private val webServerConfigRepository: WebServerConfigRepository
) : ViewModel() {

    private val _haConfig = MutableStateFlow(HaConfigEntity(baseUrl = "", token = ""))
    val haConfig: StateFlow<HaConfigEntity> = _haConfig.asStateFlow()

    private val _settings = MutableStateFlow(SettingsEntity())
    val settings: StateFlow<SettingsEntity> = _settings.asStateFlow()

    private val _haConnectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val haConnectionStatus: StateFlow<ConnectionStatus> = _haConnectionStatus.asStateFlow()

    private val _webPort = MutableStateFlow(8080)
    val webPort: StateFlow<Int> = _webPort.asStateFlow()
    private val _webPassword = MutableStateFlow("")
    val webPassword: StateFlow<String> = _webPassword.asStateFlow()

    init {
        loadSettings()
        // 监听 HomeAssistantRepository 提供的连接状态
        viewModelScope.launch {
            homeAssistantRepository.connectionStatus.collect { isConnected ->
                _haConnectionStatus.value = if (isConnected) ConnectionStatus.Connected else ConnectionStatus.Idle
                // Note: Error state will be handled by HomeViewModel if connection fails,
                // or can be explicitly set here if SettingsViewModel needs to show specific errors.
                // For now, rely on HomeViewModel's error handling for actual connection failures.
            }
        }

        viewModelScope.launch {
            webServerConfigRepository.portFlow().collect { p -> _webPort.value = p }
        }
        viewModelScope.launch {
            webServerConfigRepository.passwordFlow().collect { pwd -> _webPassword.value = pwd }
        }
    }

    private fun loadSettings() {
        // 持续观察 HA 配置变化
        viewModelScope.launch {
            homeAssistantRepository.getHaConfig().collect { haConfigEntity ->
                haConfigEntity?.let {
                    _haConfig.value = it
                }
            }
        }
        
        // 持续观察设置变化（包括从 HomeViewModel 或数据库的其他更新）
        viewModelScope.launch {
            settingsRepository.getSettings()
                .distinctUntilChanged()
                .collect { settingsEntity ->
                    settingsEntity?.let {
                        _settings.value = it
                        Log.d("SettingsViewModel", "设置已更新")
                    } ?: run {
                        // 如果没有设置，保存默认
                        settingsRepository.saveSettings(_settings.value)
                    }
                }
        }
    }

    fun updateHaBaseUrl(url: String) {
        _haConfig.value = _haConfig.value.copy(baseUrl = url)
        // Reset to Idle when URL is updated, as connection might change
        _haConnectionStatus.value = ConnectionStatus.Idle
    }

    fun updateHaToken(token: String) {
        _haConfig.value = _haConfig.value.copy(token = token)
        // Reset to Idle when Token is updated, as connection might change
        _haConnectionStatus.value = ConnectionStatus.Idle
    }

    fun updateEnableProximityWake(enabled: Boolean) {
        _settings.value = _settings.value.copy(enableProximityWake = enabled)
    }

    fun updateHeFengApiKey(apiKey: String) {
        _settings.value = _settings.value.copy(heFengApiKey = apiKey)
    }

    fun updateWeatherLocationSource(source: String) {
        _settings.value = _settings.value.copy(weatherLocationSource = source)
    }

    fun updateManualWeatherLocation(location: String) {
        _settings.value = _settings.value.copy(manualWeatherLocation = location)
    }

    fun updateWeatherRefreshIntervalMinutes(minutes: Int) {
        _settings.value = _settings.value.copy(weatherRefreshIntervalMinutes = minutes)
    }

    fun updateGpsUpdateIntervalMinutes(minutes: Int) {
        _settings.value = _settings.value.copy(gpsUpdateIntervalMinutes = minutes)
    }
    
    fun updateEnableDeviceStateReporting(enabled: Boolean) {
        _settings.value = _settings.value.copy(enableDeviceStateReporting = enabled)
    }
    
    fun updateDeviceStateReportIntervalMinutes(minutes: Int) {
        _settings.value = _settings.value.copy(deviceStateReportIntervalMinutes = minutes)
    }
    
    fun updateGridColumns(columns: Int) {
        _settings.value = _settings.value.copy(gridColumns = columns.coerceIn(1, 10))
    }
    
    fun updateGridRows(rows: Int) {
        _settings.value = _settings.value.copy(gridRows = if (rows <= 0) -1 else rows.coerceIn(1, 20))
    }
    
    fun updateReminderWidth(width: Int) {
        // 允许 1-20 的范围，默认值是 4
        _settings.value = _settings.value.copy(reminderWidth = width.coerceIn(1, 20))
    }
    
    fun updateReminderHeight(height: Int) {
        // 允许 1-4 的范围，默认值是 1
        _settings.value = _settings.value.copy(reminderHeight = height.coerceIn(1, 4))
    }
    
    fun updateWeatherDisplayMode(mode: Int) {
        // 允许 1-3 的范围，默认值是 1
        _settings.value = _settings.value.copy(weatherDisplayMode = mode.coerceIn(1, 3))
    }
    
    fun updateColorThemeMode(mode: Int) {
        // 允许 0-1 的范围(0=自定义颜色 1=动态
        _settings.value = _settings.value.copy(colorThemeMode = mode.coerceIn(0, 1))
    }
    
    fun updateCustomPrimaryColor(color: Long) {
        _settings.value = _settings.value.copy(customPrimaryColor = color)
    }

    fun saveHaConfigAndTestConnection(onComplete: () -> Unit) {
        viewModelScope.launch {
            val currentConfig = _haConfig.value
            val configToSave = currentConfig.copy(selectedEntities = "", selectedDevices = "", selectedSensors = "")
            homeAssistantRepository.saveHaConfig(configToSave)
            _haConfig.value = configToSave
            // 连接状态的更新将由 HomeAssistantRepository.connectionStatus 的监听器自动处理
            onComplete()
        }
    }

    fun saveAppSettings(onComplete: () -> Unit) {
        viewModelScope.launch {
            val settingsToSave = _settings.value
            Log.d("SettingsViewModel", "保存设置: weatherDisplayMode=${settingsToSave.weatherDisplayMode}, reminderWidth=${settingsToSave.reminderWidth}, reminderHeight=${settingsToSave.reminderHeight}")
            settingsRepository.saveSettings(settingsToSave)
            onComplete()
        }
    }

    fun updateWebPort(port: Int) {
        viewModelScope.launch {
            val safe = port.coerceIn(1024, 65535)
            webServerConfigRepository.setPort(safe)
        }
    }

    fun updateWebPassword(password: String) {
        if (password.length !in 4..12) return
        viewModelScope.launch {
            webServerConfigRepository.setPassword(password)
        }
    }

}

sealed class ConnectionStatus {
    object Idle : ConnectionStatus()
    object Connecting : ConnectionStatus() // 暂时无用 HomeAssistantRepository 不直接更新这个
    object Connected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}