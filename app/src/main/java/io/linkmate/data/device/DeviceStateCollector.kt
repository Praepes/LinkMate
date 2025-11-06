package io.linkmate.data.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import io.linkmate.data.model.DeviceState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DeviceStateCollector"

/**
 * 设备状态收集器
 * 负责收集设备的各种状态信�?
 */
@Singleton
class DeviceStateCollector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    /**
     * 收集当前设备状�?
     */
    fun collectDeviceState(): DeviceState {
        // 统一�?ACTION_BATTERY_CHANGED 获取所有电池信息，确保数据一致�?
        val batteryInfo = getBatteryInfo()
        
        Log.d(TAG, "📱 收集设备状�? 电量=${batteryInfo.level}%, 充电=${batteryInfo.isCharging}, " +
                "充电类型=${batteryInfo.chargingType}, 屏幕=${isScreenOn()}")
        
        return DeviceState(
            batteryLevel = batteryInfo.level,
            isCharging = batteryInfo.isCharging,
            chargingType = batteryInfo.chargingType,
            screenBrightness = getScreenBrightness(),
            isScreenOn = isScreenOn(),
            latitude = getLastKnownLocation()?.latitude,
            longitude = getLastKnownLocation()?.longitude,
            locationAccuracy = getLastKnownLocation()?.accuracy
        )
    }
    
    /**
     * 电池信息数据�?
     */
    private data class BatteryInfo(
        val level: Int,
        val isCharging: Boolean,
        val chargingType: String
    )
    
    /**
     * �?ACTION_BATTERY_CHANGED 获取所有电池信�?
     * 这个方法使用 sticky broadcast，确保获取到最新的电池状�?
     */
    private fun getBatteryInfo(): BatteryInfo {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, intentFilter)
            
            if (batteryStatus == null) {
                Log.e(TAG, "⚠️ 无法获取电池状�? registerReceiver 返回 null")
                return BatteryInfo(level = -1, isCharging = false, chargingType = "unknown")
            }
            
            // 获取电量 (0-100)
            val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val batteryLevel = if (level >= 0 && scale > 0) {
                (level * 100 / scale).coerceIn(0, 100)
            } else {
                // 如果无法�?EXTRA_LEVEL 获取，尝试使�?BatteryManager API
                try {
                    val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                } catch (e: Exception) {
                    Log.w(TAG, "无法�?BatteryManager 获取电量: ${e.message}")
                    -1
                }
            }
            
            // 获取充电状�?
            val status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                           status == BatteryManager.BATTERY_STATUS_FULL
            
            // 获取充电类型
            val chargePlug = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val chargingType = when (chargePlug) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> if (isCharging) "unknown" else "none"
            }
            
            Log.d(TAG, "🔋 电池信息: level=$level, scale=$scale, 计算电量=$batteryLevel%, " +
                    "status=$status, chargePlug=$chargePlug, isCharging=$isCharging")
            
            BatteryInfo(
                level = batteryLevel,
                isCharging = isCharging,
                chargingType = chargingType
            )
        } catch (e: Exception) {
            Log.e(TAG, "�?获取电池信息失败: ${e.message}", e)
            BatteryInfo(level = -1, isCharging = false, chargingType = "unknown")
        }
    }
    
    /**
     * 获取屏幕亮度 (0-255)
     */
    private fun getScreenBrightness(): Int {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get screen brightness", e)
            -1
        }
    }
    
    /**
     * 检查屏幕是否亮�?
     */
    private fun isScreenOn(): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                powerManager.isInteractive
            } else {
                @Suppress("DEPRECATION")
                powerManager.isScreenOn
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check screen state", e)
            false
        }
    }
    
    /**
     * 获取最后已知位�?
     */
    private fun getLastKnownLocation(): Location? {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // 尝试�?GPS 获取
            val gpsLocation = try {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } catch (e: SecurityException) {
                Log.w(TAG, "No GPS permission", e)
                null
            }
            
            // 尝试从网络获�?
            val networkLocation = try {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } catch (e: SecurityException) {
                Log.w(TAG, "No network location permission", e)
                null
            }
            
            // 返回最新的位置
            when {
                gpsLocation != null && networkLocation != null -> {
                    if (gpsLocation.time > networkLocation.time) gpsLocation else networkLocation
                }
                gpsLocation != null -> gpsLocation
                networkLocation != null -> networkLocation
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get location", e)
            null
        }
    }
}

