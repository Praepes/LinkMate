package io.linkmate.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_settings")
data class SettingsEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val enableProximityWake: Boolean = false,
    val heFengApiKey: String = "", 
    val weatherLocationSource: String = "GPS", 
    val manualWeatherLocation: String = "", 
    val weatherRefreshIntervalMinutes: Int = 60, // Default to 60 minutes (1 hour)
    val gpsUpdateIntervalMinutes: Int = 60, // Default to 60 minutes (1 hour)
    val enableDeviceStateReporting: Boolean = false, // 是否启用设备状态上�?
    val deviceStateReportIntervalMinutes: Int = 5, // 设备状态上报间�?(默认 5 分钟)
    val gridColumns: Int = 4, // 网格列数 (默认 4 �?
    val gridRows: Int = -1, // 网格行数 (-1 表示无限行，默认无限�?
    val reminderWidth: Int = 4, // 提醒卡片宽度 (默认 4，范�?1-20)
    val reminderHeight: Int = 1, // 提醒卡片高度 (默认 1，范�?1-4)
    val weatherDisplayMode: Int = 1, // 天气显示模式 (1=垂直完整, 2=1x1紧凑, 3=横向排列，默�?)
    val colorThemeMode: Int = 1, // 颜色主题模式 (0=自定义颜�? 1=动态系统，默认1)
    val customPrimaryColor: Long = 0xFF6650a4 // 自定义颜色模式的基础主色 (ARGB Long)，系统自动计算浅色和深色版本
)
