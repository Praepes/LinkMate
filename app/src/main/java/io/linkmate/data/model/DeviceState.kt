package io.linkmate.data.model

/**
 * 设备状态数据模型
 */
data class DeviceState(
    // 电池信息
    val batteryLevel: Int,              // 电池电量 (0-100)
    val isCharging: Boolean,            // 是否正在充电
    val chargingType: String,           // 充电类型 (ac/usb/wireless/none)
    
    // 屏幕信息
    val screenBrightness: Int,          // 屏幕亮度 (0-255)
    val isScreenOn: Boolean,            // 屏幕是否亮起
    
    // GPS 位置
    val latitude: Double?,              // 纬度
    val longitude: Double?,             // 经度
    val locationAccuracy: Float?,       // 位置精度 (米)
    
    // 系统信息
    val timestamp: Long = System.currentTimeMillis()  // 时间戳
)

/**
 * 转换为 Home Assistant 传感器数据格式
 */
fun DeviceState.toHomeAssistantSensors(deviceName: String): Map<String, Any> {
    return mapOf(
        "battery_level" to mapOf(
            "state" to batteryLevel,
            "attributes" to mapOf(
                "unit_of_measurement" to "%",
                "device_class" to "battery",
                "friendly_name" to "$deviceName 电池电量"
            )
        ),
        "battery_charging" to mapOf(
            "state" to if (isCharging) "charging" else "not_charging",
            "attributes" to mapOf(
                "charging_type" to chargingType,
                "friendly_name" to "$deviceName 充电状态"
            )
        ),
        "screen_brightness" to mapOf(
            "state" to screenBrightness,
            "attributes" to mapOf(
                "unit_of_measurement" to "level",
                "friendly_name" to "$deviceName 屏幕亮度"
            )
        ),
        "screen_state" to mapOf(
            "state" to if (isScreenOn) "on" else "off",
            "attributes" to mapOf(
                "friendly_name" to "$deviceName 屏幕状态"
            )
        ),
        "location" to mapOf(
            "state" to if (latitude != null && longitude != null) "home" else "unknown",
            "attributes" to mapOf(
                "latitude" to latitude,
                "longitude" to longitude,
                "gps_accuracy" to locationAccuracy,
                "friendly_name" to "$deviceName 位置"
            )
        )
    )
}
