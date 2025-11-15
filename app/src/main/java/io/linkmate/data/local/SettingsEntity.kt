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
    val weatherRefreshIntervalMinutes: Int = 60, // 天气刷新间隔(分钟)，默认60
    val gpsUpdateIntervalMinutes: Int = 60, // GPS 更新间隔(分钟)，默认60
    val enableDeviceStateReporting: Boolean = false, // 是否启用设备状态上报
    val deviceStateReportIntervalMinutes: Int = 5, // 设备状态上报间隔(分钟)，默认5
    val gridColumns: Int = 4, // 网格列数，范围1-25，默认4
    val gridRows: Int = -1, // 网格行数，-1表示无限行，默认无限
    val reminderWidth: Int = 4, // 提醒卡片宽度，范围1-20，默认4
    val reminderHeight: Int = 1, // 提醒卡片高度，范围1-4，默认1
    val weatherDisplayMode: Int = 1, // 天气显示模式 1=垂直完整 2=1x1紧凑 3=横向排列，默认1
    val colorThemeMode: Int = 1, // 颜色主题模式 0=自定义颜色 1=动态系统，默认1
    val customPrimaryColor: Long = 0xFF6650a4 // 自定义颜色模式的基础主色(ARGB Long)，系统自动计算浅色/深色
)
