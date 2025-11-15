package io.linkmate.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image // Import for local images
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit // for climate
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.TouchApp // for button
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.WbSunny // for brightness
import androidx.compose.material.icons.filled.Whatshot // for heat mode
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox // for dropdown
import androidx.compose.material3.ExposedDropdownMenuDefaults // for dropdown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField // for dropdown
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider // for brightness
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.linkmate.data.local.HaConfigEntity
import io.linkmate.data.local.ReminderEntity
import io.linkmate.data.remote.homeassistant.HaEntityState
import io.linkmate.navigation.Screen
import io.linkmate.ui.viewmodels.HomeViewModel
import android.util.Log
import androidx.annotation.DrawableRes // For resource annotation
import androidx.compose.ui.res.painterResource // Import for local images
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.material3.AlertDialog
import io.linkmate.ui.components.DraggableColumn
import io.linkmate.ui.components.DraggableGrid
import io.linkmate.ui.components.DraggableItem
import io.linkmate.ui.components.WidgetSize
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.ln

// Home Assistant supported_features bitmask for light brightness and color_temp
private const val SUPPORT_BRIGHTNESS_BITMASK = 1
private const val SUPPORT_COLOR_TEMP_BITMASK = 2

private const val TAG = "HomeScreen"

/**
 * 根据色温（Kelvin）计算对应的 RGB 颜色
 * 色温范围通常为 2000K（暖白）到 6500K（冷白）
 */
@Composable
private fun calculateColorFromColorTemperature(kelvin: Float): Color {
    // 将色温转换为 RGB 颜色
    // 使用近似算法：基于黑体辐射的颜色
    val temp = kelvin / 100f
    
    // 红色分量
    val red = when {
        temp <= 66 -> 255f
        else -> {
            val redCalc = 329.698727446 * Math.pow((temp - 60).toDouble(), -0.1332047592)
            redCalc.coerceIn(0.0, 255.0).toFloat()
        }
    }
    
    // 绿色分量
    val green = when {
        temp <= 66 -> {
            val greenCalc = 99.4708025861 * ln(temp.toDouble()) - 161.1195681661
            greenCalc.coerceIn(0.0, 255.0).toFloat()
        }
        else -> {
            val greenCalc = 288.1221695283 * Math.pow((temp - 60).toDouble(), -0.0755148492)
            greenCalc.coerceIn(0.0, 255.0).toFloat()
        }
    }
    
    // 蓝色分量
    val blue = when {
        temp >= 66 -> 255f
        temp <= 19 -> 0f
        else -> {
            val blueCalc = 138.5177312231 * ln((temp - 10).toDouble()) - 305.0447927307
            blueCalc.coerceIn(0.0, 255.0).toFloat()
        }
    }
    
    return Color(
        red = red / 255f,
        green = green / 255f,
        blue = blue / 255f
    )
}

/**
 * 计算灯实体的图标颜色
 * - 关灯：返回默认颜色（灰色或主题色）
 * - 开灯：根据亮度和色温计算颜色
 */
@Composable
private fun calculateLightIconColor(entity: HaEntityState): Color {
    val isOn = entity.state == "on"
    
    if (!isOn) {
        // 关灯时返回默认灰色
        return MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }
    
    // 获取亮度和色温
    // 如果没有 brightness 属性，假设是满亮度（255）
    val brightness = (entity.attributes["brightness"] as? Number)?.toInt() ?: 255
    
    // 尝试获取色温（支持 color_temp_kelvin 和 color_temp）
    val colorTempKelvin = (entity.attributes["color_temp_kelvin"] as? Number)?.toFloat()
        ?: (entity.attributes["color_temp"] as? Number)?.toFloat()?.let { 
            // 如果色温是 mireds 格式（100-500），转换为 Kelvin
            if (it in 100f..500f) {
                1000000f / it
            } else {
                it
            }
        } ?: 4000f // 默认中性白色温（4000K）
    
    // 确保色温在合理范围内
    val clampedColorTemp = colorTempKelvin.coerceIn(2000f, 6500f)
    
    // 根据色温计算基础颜色
    val baseColor = calculateColorFromColorTemperature(clampedColorTemp)
    
    // 根据亮度调整颜色的明度
    // brightness 范围是 0-255，转换为 0-1 的亮度因子
    val brightnessFactor = (brightness / 255f).coerceIn(0f, 1f)
    
    // 使用更真实的亮度混合：低亮度时保持色温颜色但降低饱和度，高亮度时增强亮度
    // 为了视觉效果，我们使用亮度因子来混合基础色温和白色
    val minBrightness = 0.3f // 最小亮度，确保即使在低亮度时也能看到颜色
    val adjustedBrightness = minBrightness + (brightnessFactor * (1f - minBrightness))
    
    // 将色温颜色与白色混合，根据亮度调整混合比例
    // 低亮度时更偏向色温颜色，高亮度时更偏向白色（更亮）
    val whiteMix = brightnessFactor * 0.4f // 高亮度时混合一些白色让颜色更亮
    
    return Color(
        red = ((baseColor.red * (1f - whiteMix) + whiteMix) * adjustedBrightness).coerceIn(0f, 1f),
        green = ((baseColor.green * (1f - whiteMix) + whiteMix) * adjustedBrightness).coerceIn(0f, 1f),
        blue = ((baseColor.blue * (1f - whiteMix) + whiteMix) * adjustedBrightness).coerceIn(0f, 1f)
    )
}

// 新增的辅助函数：根据天气描述获取和风天气图标代码
fun getIconCodeFromDescription(description: String): String {
    return when {
        description.contains("晴") -> "100"
        description.contains("多云") -> "101"
        description.contains("阴") -> "102"
        description.contains("阵雨") -> "300"
        description.contains("雷阵雨") -> "302"
        description.contains("小雨") -> "305"
        description.contains("中雨") -> "306"
        description.contains("大雨") -> "307"
        description.contains("暴雨") -> "308"
        description.contains("雪") -> "400"
        description.contains("雾") -> "501"
        description.contains("霾") -> "502"
        description.contains("沙") || description.contains("尘") -> "507"
        else -> "999" // 默认未知图标
    }
}

/**
 * 根据和风天气图标代码获取对应的本地 drawable 资源 ID。
 * 优先查找 `ic_weather_XXX_fill`，如果不存在则查找 `ic_weather_XXX`。
 * 假设本地图标文件名为 `ic_weather_XXX.xml` 和 `ic_weather_XXX_fill.xml` (由 SVG 转换而来) 存储在 drawable 目录下。
 */
@DrawableRes
@Composable
fun getWeatherIconResId(iconCode: String): Int {
    val context = LocalContext.current
    val packageName = context.packageName
    val resources = context.resources

    // 1. 尝试查找带 "_fill" 后缀的图标

    val fillResourceName = "ic_weather_${iconCode}_fill"
    val fillResId = resources.getIdentifier(fillResourceName, "drawable", packageName)

    if (fillResId != 0) {
        return fillResId
    }

    // 2. 如果带 "_fill" 后缀的图标不存在，尝试查找普通图标

    val regularResourceName = "ic_weather_$iconCode"
    val regularResId = resources.getIdentifier(regularResourceName, "drawable", packageName)

    if (regularResId != 0) {
        return regularResId
    }

    // 3. 如果两种图标都不存在，返回默认占位符并记录错误

    Log.e(TAG, "Could not find local icon for code: $iconCode (neither _fill nor regular version)")
    return android.R.drawable.ic_menu_help // 替换为默认图标
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: HomeViewModel
) {
    val context = LocalContext.current

    val weatherState by viewModel.weatherState.collectAsStateWithLifecycle()
    val haState by viewModel.haState.collectAsStateWithLifecycle() // Observe the new haState
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val widgetOrder by viewModel.widgetOrder.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val climateLastValidModes by viewModel.climateLastValidModes.collectAsStateWithLifecycle()
    
    // 从设置中获取网格配置
    val gridColumns = settings?.gridColumns ?: 4
    val gridRows = settings?.gridRows ?: -1
    val reminderWidth = settings?.reminderWidth ?: 4
    val reminderHeight = settings?.reminderHeight ?: 1
    val weatherDisplayMode = settings?.weatherDisplayMode ?: 1

    // 调试：打印设置值

    LaunchedEffect(settings) {
        Log.d(TAG, "设置更新: weatherDisplayMode=${settings?.weatherDisplayMode}, reminderWidth=${settings?.reminderWidth}, reminderHeight=${settings?.reminderHeight}")
    }

    var hasLocationPermission by remember { mutableStateOf(false) }
    var hasBackgroundLocationPermission by remember { mutableStateOf(false) }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                               permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        hasBackgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions[Manifest.permission.ACCESS_BACKGROUND_LOCATION] == true
        } else {
            true
        }

        if (hasLocationPermission && hasBackgroundLocationPermission) {
            viewModel.onPermissionsGranted() // Call the new single entry point
        }
    }

    LaunchedEffect(Unit) {
        // API < 23 (Android 5.0-5.1) 权限在安装时授予，直接检查即可
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // 对于 API 21-22，权限在安装时已授予
            hasLocationPermission = true
            hasBackgroundLocationPermission = true
            viewModel.onPermissionsGranted()
        } else {
            // API 23+ 需要运行时权限请求
            val fineLocationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarseLocationGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            hasLocationPermission = fineLocationGranted || coarseLocationGranted

            hasBackgroundLocationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }

            val permissionsToRequest = mutableListOf<String>()
            if (!hasLocationPermission) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
                permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            // Request background location only if needed and not granted
            if (!hasBackgroundLocationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }

            if (permissionsToRequest.isNotEmpty()) {
                requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
            } else {
                // If all necessary permissions are already granted, start the updates.
                viewModel.onPermissionsGranted()
            }
        }
    }

    // Detect new reminders and show floating window
    var lastReminderMaxId by remember { mutableStateOf(0) }
    var showFloatingReminder by remember { mutableStateOf(false) }
    var newReminder by remember { mutableStateOf<ReminderEntity?>(null) }
    
    LaunchedEffect(reminders) {
        val activeReminders = reminders.filter { it.isActive }
        if (activeReminders.isNotEmpty()) {
            val latestReminder = activeReminders.maxByOrNull { it.timestamp }
            if (latestReminder != null && latestReminder.id > lastReminderMaxId) {
                // New reminder detected (based on ID increase)
                val currentTime = System.currentTimeMillis()
                if (latestReminder.timestamp > currentTime - 10000) {
                    // Only show if reminder was created in the last 10 seconds
                    newReminder = latestReminder
                    showFloatingReminder = true
                    lastReminderMaxId = latestReminder.id
                } else {
                    lastReminderMaxId = latestReminder.id
                }
            }
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            // Removed topBar to make content full screen
        ) { _ ->
            // 使用DraggableGrid将天气、提醒、每个HA实体都作为独立卡片展示

            when (val currentHaState = haState) {
                is HomeViewModel.HaState.Success -> {
                    val displayedEntities = currentHaState.entities
                    // 创建一个能反映实体状态变化的 key,直接计算而不使用 remember
                    val entitiesStateKey = displayedEntities.joinToString("|") { 
                        "${it.entityId}:${it.state}:${it.lastUpdated}" 
                    }
                    Log.d(TAG, "entitiesStateKey 更新: ${entitiesStateKey.take(100)}...")
                    Log.d(TAG, "准备创建 draggableItems: weatherDisplayMode=$weatherDisplayMode, reminderWidth=$reminderWidth, reminderHeight=$reminderHeight")
                    val draggableItems = remember(
                        widgetOrder, 
                        weatherState, 
                        reminders,
                        entitiesStateKey,  // 使用状态 key 而不是 displayedEntities

                        reminderWidth,
                        reminderHeight,
                        weatherDisplayMode
                    ) {
                        Log.d(TAG, "draggableItems remember 块被重新计算")
                        Log.d(TAG, "依赖项值: widgetOrder=$widgetOrder, weatherDisplayMode=$weatherDisplayMode, reminderWidth=$reminderWidth, reminderHeight=$reminderHeight")
                        val items = mutableListOf<DraggableItem<Unit>>()
                        
                        // 将widgetOrder解析为实际ID列表
                        // 收集所有已经在widgetOrder中明确列出的实体ID（排除特殊项）

                        val explicitEntityIds = widgetOrder.filter { 
                            it !in listOf("WEATHER", "REMINDERS", "SETTINGS", "HA_GRID") 
                        }.toSet()
                        
                        val expandedOrder = widgetOrder.flatMap { itemId ->
                            when (itemId) {
                                "HA_GRID" -> {
                                    // 展开HA_GRID时，只包含未在widgetOrder中明确列出的实体
                                    displayedEntities.map { it.entityId }
                                        .filterNot { it in explicitEntityIds }
                                }
                                else -> listOf(itemId)
                            }
                        }
                        
                        Log.d(TAG, "HomeScreen: widgetOrder=$widgetOrder, displayedEntities=${displayedEntities.size}, explicitEntityIds=$explicitEntityIds, expandedOrder=$expandedOrder")
                        
                        // 根据expandedOrder构建draggableItems
                        expandedOrder.forEach { itemId ->
                            when (itemId) {
                                "WEATHER" -> {
                                    // 根据显示模式确定尺寸
                                    val weatherSize = when (weatherDisplayMode) {
                                        1 -> WidgetSize.Size1x2 // 模式1：垂直完整模式
                                        2 -> WidgetSize.Size1x1 // 模式2：紧凑模式
                                        3 -> WidgetSize(2, 1) // 模式3：横向排列（宽2高1）
                                        else -> WidgetSize.Size1x2
                                    }
                                    Log.d(TAG, "天气卡片尺寸设置: displayMode=$weatherDisplayMode, size=${weatherSize.width}x${weatherSize.height}")
                                    val weatherItem = DraggableItem("WEATHER", Unit, weatherSize) {
                                        WeatherCard(weatherState = weatherState, displayMode = weatherDisplayMode)
                                    }
                                    Log.d(TAG, "创建的天气项: id=${weatherItem.id}, size=${weatherItem.size.width}x${weatherItem.size.height}")
                                    items.add(weatherItem)
                                }
                                "REMINDERS" -> items.add(DraggableItem("REMINDERS", Unit, WidgetSize(reminderWidth, reminderHeight)) {
                                    ReminderCard(reminders = reminders)
                                })
                                "SETTINGS" -> items.add(DraggableItem("SETTINGS", Unit, WidgetSize.Size1x1) {
                                    SettingsIconCard(navController = navController)
                                })
                                else -> {
                                    // 这是HA实体ID,直接使用(widgetOrder中应该只包含纯实体ID)
                                    displayedEntities.find { it.entityId == itemId }?.let { entity ->
                                        // 根据实体类型确定尺寸
                                        val entityDomain = entity.entityId.split(".").firstOrNull()
                                        val size = when (entityDomain) {
                                            "climate" -> WidgetSize.Size1x2
                                            else -> WidgetSize.Size1x1
                                        }
                                        // 使用包含状态和时间戳的唯一 ID
                                        val uniqueId = "${entity.entityId}_${entity.state}_${entity.lastUpdated}"
                                        items.add(DraggableItem(uniqueId, Unit, size) {
                                            HaEntityCard(
                                                entity = entity,
                                                onToggleDevice = { entityId, targetState ->
                                                    viewModel.toggleHaDeviceState(entityId, targetState)
                                                },
                                                onPressButton = { entityId ->
                                                    viewModel.pressHaButton(entityId)
                                                },
                                                onSetClimateTemperature = { entityId, temperature ->
                                                    viewModel.setClimateTemperature(entityId, temperature)
                                                },
                                                onSetClimateHvacMode = { entityId, mode ->
                                                    viewModel.setClimateHvacMode(entityId, mode)
                                                },
                                                onSetLightBrightness = { entityId, brightnessPct ->
                                                    viewModel.setLightBrightness(entityId, brightnessPct)
                                                },
                                                onSetLightColorTemperature = { entityId, colorTempKelvin ->
                                                    viewModel.setLightColorTemperature(entityId, colorTempKelvin)
                                                },
                                                onSetInputSelectOption = { entityId, option ->
                                                    viewModel.setInputSelectOption(entityId, option)
                                                },
                                                climateLastValidMode = climateLastValidModes[entity.entityId]
                                            )
                                        })
                                    }
                                }
                            }
                        }
                        
                        Log.d(TAG, "draggableItems 创建完成，共 ${items.size} ")
                        items.forEach { item ->
                            Log.d(TAG, "  项: ${item.id}, 尺寸: ${item.size.width}x${item.size.height}")
                        }
                        items
                    }
                    
                    // 获取位置信息
                    val widgetPositions by viewModel.widgetPositions.collectAsStateWithLifecycle()
                    
                    // 检测天气卡片尺寸变化导致的冲突，并自动调整位置
                    LaunchedEffect(weatherDisplayMode, widgetPositions, draggableItems, gridColumns, reminderWidth, reminderHeight) {
                        val weatherItem = draggableItems.find { it.id == "WEATHER" }
                        if (weatherItem != null) {
                            val weatherPosition = widgetPositions["WEATHER"]
                            if (weatherPosition != null) {
                                val weatherSize = weatherItem.size

                                // 计算天气卡片占用的网格

                                val weatherOccupied = mutableSetOf<Pair<Int, Int>>()
                                for (dy in 0 until weatherSize.height) {
                                    for (dx in 0 until weatherSize.width) {
                                        if (weatherPosition.x + dx < gridColumns) {
                                            weatherOccupied.add(Pair(weatherPosition.x + dx, weatherPosition.y + dy))
                                        }
                                    }
                                }
                                
                                // 检查其他卡片是否与天气卡片冲突
                                val conflicts = mutableListOf<Pair<String, io.linkmate.ui.components.WidgetPosition>>()
                                
                                widgetPositions.forEach { (itemId, position) ->
                                    if (itemId != "WEATHER") {
                                        // 从 draggableItems 中获取该项的实际尺寸

                                        val draggableItem = draggableItems.find { 
                                            // 对于HA实体，需要匹配原始ID
                                            val originalId = if (it.id.contains(".") && it.id.count { c -> c == '_' } >= 2) {
                                                val parts = it.id.split("_")
                                                if (parts.size >= 3) {
                                                    parts.dropLast(2).joinToString("_")
                                                } else {
                                                    it.id
                                                }
                                            } else {
                                                it.id
                                            }
                                            originalId == itemId || it.id == itemId
                                        }
                                        
                                        val itemSize = draggableItem?.size?.let { it.width to it.height } ?: when (itemId) {
                                            "REMINDERS" -> reminderWidth to reminderHeight
                                            else -> 1 to 1
                                        }
                                        
                                        // 检查该项是否与天气卡片冲突
                                        val itemOccupied = mutableSetOf<Pair<Int, Int>>()
                                        for (dy in 0 until itemSize.second) {
                                            for (dx in 0 until itemSize.first) {
                                                if (position.x + dx < gridColumns) {
                                                    itemOccupied.add(Pair(position.x + dx, position.y + dy))
                                                }
                                            }
                                        }
                                        
                                        if (itemOccupied.intersect(weatherOccupied).isNotEmpty()) {
                                            conflicts.add(itemId to position)
                                        }
                                    }
                                }
                                
                                // 如果有冲突，重新分配冲突项的位置
                                if (conflicts.isNotEmpty()) {
                                    Log.d(TAG, "检测到 ${conflicts.size} 个项与天气卡片冲突，开始重新分配位置")
                                    val occupied = weatherOccupied.toMutableSet()
                                    
                                    // 先标记所有非冲突项占用的位置
                                    widgetPositions.forEach { (itemId, pos) ->
                                        if (itemId != "WEATHER" && conflicts.none { it.first == itemId }) {
                                            val draggableItem = draggableItems.find { 
                                                val originalId = if (it.id.contains(".") && it.id.count { c -> c == '_' } >= 2) {
                                                    val parts = it.id.split("_")
                                                    if (parts.size >= 3) {
                                                        parts.dropLast(2).joinToString("_")
                                                    } else {
                                                        it.id
                                                    }
                                                } else {
                                                    it.id
                                                }
                                                originalId == itemId || it.id == itemId
                                            }
                                            val size = draggableItem?.size?.let { it.width to it.height } ?: when (itemId) {
                                                "REMINDERS" -> reminderWidth to reminderHeight
                                                else -> 1 to 1
                                            }
                                            for (dy in 0 until size.second) {
                                                for (dx in 0 until size.first) {
                                                    if (pos.x + dx < gridColumns) {
                                                        occupied.add(Pair(pos.x + dx, pos.y + dy))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    
                                    conflicts.forEach { (conflictItemId, oldPosition) ->
                                        val draggableItem = draggableItems.find { 
                                            val originalId = if (it.id.contains(".") && it.id.count { c -> c == '_' } >= 2) {
                                                val parts = it.id.split("_")
                                                if (parts.size >= 3) {
                                                    parts.dropLast(2).joinToString("_")
                                                } else {
                                                    it.id
                                                }
                                            } else {
                                                it.id
                                            }
                                            originalId == conflictItemId || it.id == conflictItemId
                                        }
                                        
                                        val conflictItemSize = draggableItem?.size?.let { it.width to it.height } ?: when (conflictItemId) {
                                            "REMINDERS" -> reminderWidth to reminderHeight
                                            else -> 1 to 1
                                        }
                                        
                                        // 查找新位置（从天气卡片右侧开始，然后下一行）
                                        var found = false
                                        var newX = weatherPosition.x + weatherSize.width
                                        var newY = weatherPosition.y

                                        while (!found && newY < 100) { // 最多检查100行

                                            if (newX + conflictItemSize.first > gridColumns) {
                                                newX = 0
                                                newY++
                                                continue
                                            }

                                            // 检查是否可以放置



                                            val wouldOccupy = mutableSetOf<Pair<Int, Int>>()
                                            for (dy in 0 until conflictItemSize.second) {
                                                for (dx in 0 until conflictItemSize.first) {
                                                    if (newX + dx < gridColumns) {
                                                        wouldOccupy.add(Pair(newX + dx, newY + dy))
                                                    }
                                                }
                                            }
                                            
                                            if (wouldOccupy.intersect(occupied).isEmpty()) {
                                                found = true
                                                val newPosition = io.linkmate.ui.components.WidgetPosition(newX, newY)
                                                Log.d(TAG, "将 $conflictItemId 从 (${oldPosition.x}, ${oldPosition.y}) 移动到 (${newX}, ${newY})")
                                                viewModel.updateWidgetPosition(conflictItemId, newPosition)
                                                occupied.addAll(wouldOccupy)
                                            } else {
                                                newX++
                                            }
                                        }
                                        
                                        if (!found) {
                                            Log.w(TAG, "无法为 $conflictItemId 找到新位置")
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 将原始ID的位置映射转换为唯一ID的位置映射

                    // 因为 draggableItems 中的 item.id 是唯一ID（包含状态和时间戳）
                    // 只包含有位置信息的项，没有位置的项让 DraggableGrid 自动分配
                    val itemPositionsForDisplay = remember(draggableItems, widgetPositions) {
                        val result = draggableItems.mapNotNull { item ->
                            val originalId = if (item.id.contains(".") && item.id.count { it == '_' } >= 2) {
                                // 提取原始entityId（去掉 _state_timestamp）

                                val parts = item.id.split("_")
                                if (parts.size >= 3) {
                                    parts.dropLast(2).joinToString("_")
                                } else {
                                    item.id
                                }
                            } else {
                                item.id
                            }
                            // 使用原始ID查找位置，如果没有则使用唯一ID查找（向后兼容）
                            val position = widgetPositions[originalId] ?: widgetPositions[item.id]
                            if (position != null) {
                                item.id to position
                            } else {
                                null // 没有位置信息，让 DraggableGrid 自动分配
                            }
                        }.toMap()
                        Log.d(TAG, "itemPositionsForDisplay 重新计算: ${result.size} ")
                        result.forEach { (id, pos) ->
                            Log.d(TAG, "  $id -> (${pos.x}, ${pos.y})")
                        }
                        result
                    }
                    
                    DraggableGrid(
                        items = draggableItems,
                        itemPositions = itemPositionsForDisplay,
                        onPositionUpdate = { itemId, position ->
                            // 更新位置时，需要将唯一ID（包含状态和时间戳）转换为原始ID
                            val originalItemId = if (itemId.contains(".") && itemId.count { it == '_' } >= 2) {
                                // 提取原始entityId（去掉 _state_timestamp）

                                val parts = itemId.split("_")
                                if (parts.size >= 3) {
                                    parts.dropLast(2).joinToString("_")
                                } else {
                                    itemId
                                }
                            } else {
                                itemId
                            }
                            
                            viewModel.updateWidgetPosition(originalItemId, position)
                        },
                        maxColumns = gridColumns, // 从设置中获取列数
                        maxRows = if (gridRows > 0) gridRows else null, // 从设置中获取行数（-1表示无限）

                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                }
                else -> {
                    // 非Success状态，显示简化版本

                    Log.d(TAG, "进入 else 分支（非Success状态），weatherDisplayMode=$weatherDisplayMode")
                    val draggableItems = remember(widgetOrder, weatherState, reminders, reminderWidth, reminderHeight, weatherDisplayMode) {
                        Log.d(TAG, "else 分支的 draggableItems remember 块被重新计算, weatherDisplayMode=$weatherDisplayMode")
                        val items = mutableListOf<DraggableItem<Unit>>()
                        
                        widgetOrder.forEach { type ->
                            when (type) {
                                "WEATHER" -> {
                                    // 根据显示模式确定尺寸
                                    val weatherSize = when (weatherDisplayMode) {
                                        1 -> WidgetSize.Size1x2 // 模式1：垂直完整模式
                                        2 -> WidgetSize.Size1x1 // 模式2：紧凑模式
                                        3 -> WidgetSize(2, 1) // 模式3：横向排列（宽2高1）
                                        else -> WidgetSize.Size1x2
                                    }
                                    Log.d(TAG, "else 分支: 天气卡片尺寸设置: displayMode=$weatherDisplayMode, size=${weatherSize.width}x${weatherSize.height}")
                                    items.add(DraggableItem("WEATHER", Unit, weatherSize) {
                                        WeatherCard(weatherState = weatherState, displayMode = weatherDisplayMode)
                                    })
                                }
                                "REMINDERS" -> items.add(DraggableItem("REMINDERS", Unit, WidgetSize(reminderWidth, reminderHeight)) {
                                    ReminderCard(reminders = reminders)
                                })
                                "SETTINGS" -> items.add(DraggableItem("SETTINGS", Unit, WidgetSize.Size1x1) {
                                    SettingsIconCard(navController = navController)
                                })
                                "HA_GRID" -> items.add(DraggableItem("HA_GRID", Unit, WidgetSize.Size1x1) {
                                    HaGridCard(haState = haState, viewModel = viewModel)
                                })
                            }
                        }
                        Log.d(TAG, "else 分支: draggableItems 创建完成，共 ${items.size} 项")
                        items.forEach { item ->
                            Log.d(TAG, "else 分支: 项: ${item.id}, 尺寸: ${item.size.width}x${item.size.height}")
                        }
                        items
                    }
                    
                    // 获取位置信息
                    val widgetPositions by viewModel.widgetPositions.collectAsStateWithLifecycle()
                    
                    DraggableGrid(
                        items = draggableItems,
                        itemPositions = widgetPositions,
                        onPositionUpdate = { itemId, position ->
                            viewModel.updateWidgetPosition(itemId, position)
                        },
                        maxColumns = gridColumns, // 从设置中获取列数
                        maxRows = if (gridRows > 0) gridRows else null, // 从设置中获取行数（-1表示无限）

                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                }
            }
        }
        
        // Floating reminder window - overlay on top
        if (showFloatingReminder && newReminder != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(1000f)
                    .clickable { showFloatingReminder = false }
            ) {
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clickable(enabled = false) {},
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "新提醒",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(onClick = { showFloatingReminder = false }) {
                                Icon(Icons.Default.Close, contentDescription = "关闭")
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = newReminder!!.message,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        Text(
                            text = dateFormat.format(java.util.Date(newReminder!!.timestamp)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
} 

@Composable
fun WeatherCard(
    weatherState: HomeViewModel.WeatherState,
    displayMode: Int = 1 // 1=垂直完整, 2=1x1紧凑, 3=横向排列
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(), // 填充容器的高度（两个单元格的高度）

        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 12.dp), // 减小水平padding，让显示区域更宽
            verticalArrangement = Arrangement.Top, // 从顶部开始排列

            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (weatherState) {
                is HomeViewModel.WeatherState.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("加载中...")

                    }
                }
                is HomeViewModel.WeatherState.Success -> {
                    val weather = weatherState.weather
                    // 如果 weather.weatherIcon 为空，则根据 weatherDescription 获取图标代码
                    val actualIconCode = if (weather.weatherIcon.isNullOrBlank()) {
                        getIconCodeFromDescription(weather.weatherDescription)
                    } else {
                        weather.weatherIcon
                    }

                    // 获取本地 drawable 资源 ID
                    val iconResId = getWeatherIconResId(actualIconCode)

                    // 根据显示模式渲染不同的布局
                    when (displayMode) {
                        1 -> {
                            // 模式1：垂直完整模式
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // 天气图标（顶部）
                                if (iconResId != android.R.drawable.ic_menu_help) {
                                    Image(
                                        painter = painterResource(id = iconResId),
                                        contentDescription = weather.weatherDescription,
                                        modifier = Modifier.size(64.dp)
                                    )
                                } else {
                                    Icon(Icons.Default.WbSunny, contentDescription = "Weather Icon Placeholder", modifier = Modifier.size(64.dp))
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // 天气状态
                                Text(
                                    text = weather.weatherDescription, 
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                // 温度（大号显示）
                                Text(
                                    text = "${weather.temperature}°C", 
                                    style = MaterialTheme.typography.displaySmall,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                                
                                Spacer(modifier = Modifier.height(8.dp))

                                // 空气质量（底部，完整显示）

                                Text(
                                    text = "空气质量: ${weather.airQuality}", 
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    maxLines = 2
                                )
                            }
                        }
                        2 -> {
                            // 模式2：1x1紧凑模式（只显示图标和温度）

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                // 天气图标
                                if (iconResId != android.R.drawable.ic_menu_help) {
                                    Image(
                                        painter = painterResource(id = iconResId),
                                        contentDescription = weather.weatherDescription,
                                        modifier = Modifier.size(48.dp)
                                    )
                                } else {
                                    Icon(Icons.Default.WbSunny, contentDescription = "Weather Icon Placeholder", modifier = Modifier.size(48.dp))
                                }
                                
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                // 温度（小字）
                                Text(
                                    text = "${weather.temperature}°C", 
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        3 -> {
                            // 模式3：横向排列模式（2x1，横向显示）
                            Row(
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                // 左侧：图标和状态

                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (iconResId != android.R.drawable.ic_menu_help) {
                                        Image(
                                            painter = painterResource(id = iconResId),
                                            contentDescription = weather.weatherDescription,
                                            modifier = Modifier.size(48.dp)
                                        )
                                    } else {
                                        Icon(Icons.Default.WbSunny, contentDescription = "Weather Icon Placeholder", modifier = Modifier.size(48.dp))
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = weather.weatherDescription,
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                
                                // 右侧：温度和空气质量
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "${weather.temperature}°C",
                                        style = MaterialTheme.typography.headlineSmall,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "空气: ${weather.airQuality}",
                                        style = MaterialTheme.typography.bodySmall,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                        else -> {
                            // 默认使用模式1
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (iconResId != android.R.drawable.ic_menu_help) {
                                    Image(
                                        painter = painterResource(id = iconResId),
                                        contentDescription = weather.weatherDescription,
                                        modifier = Modifier.size(64.dp)
                                    )
                                } else {
                                    Icon(Icons.Default.WbSunny, contentDescription = "Weather Icon Placeholder", modifier = Modifier.size(64.dp))
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = weather.weatherDescription, 
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "${weather.temperature}°C", 
                                    style = MaterialTheme.typography.displaySmall,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "空气质量: ${weather.airQuality}", 
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
                is HomeViewModel.WeatherState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = weatherState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReminderCard(reminders: List<ReminderEntity>) {
    val activeReminders = reminders.filter { it.isActive }.sortedByDescending { it.timestamp }
    var currentIndex by remember { mutableStateOf(0) }
    var showFullScreenDialog by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 50.dp.toPx() }
    var totalVerticalDrag by remember { mutableFloatStateOf(0f) }
    
    // Reset index when reminders list changes
    LaunchedEffect(activeReminders.size) {
        if (currentIndex >= activeReminders.size && activeReminders.isNotEmpty()) {
            currentIndex = 0
        }
    }
    
    if (activeReminders.isEmpty()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(), // 填充容器的高度（一个单元格的高度）
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("暂无提醒", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        return
    }
    
    val currentReminder = activeReminders[currentIndex]
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight() // 填充容器的高度（一个单元格的高度）
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = {
                        totalVerticalDrag = 0f
                    },
                    onDragEnd = {
                        if (kotlin.math.abs(totalVerticalDrag) > swipeThreshold) {
                            if (totalVerticalDrag > 0) {
                                // Swipe down - go to previous
                                currentIndex = (currentIndex - 1).coerceAtLeast(0)
                            } else {
                                // Swipe up - go to next
                                currentIndex = (currentIndex + 1).coerceAtMost(activeReminders.size - 1)
                            }
                        }
                        totalVerticalDrag = 0f
                    },
                    onDragCancel = {
                        totalVerticalDrag = 0f
                    },
                    onVerticalDrag = { _, dragAmount ->
                        totalVerticalDrag += dragAmount
                    }
                )
            }
            .combinedClickable(
                onClick = { showFullScreenDialog = true }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center, // 垂直居中内容
            horizontalAlignment = Alignment.Start
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${currentIndex + 1}/${activeReminders.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = currentReminder.message,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            Text(
                text = dateFormat.format(java.util.Date(currentReminder.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    
    // Full screen dialog
    if (showFullScreenDialog) {
        AlertDialog(
            onDismissRequest = { showFullScreenDialog = false },
            title = {
                Text("提醒详情")
            },
            text = {
                Column {
                    Text(
                        text = currentReminder.message,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    Text(
                        text = "时间: ${dateFormat.format(java.util.Date(currentReminder.timestamp))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { showFullScreenDialog = false }) {
                    Text("关闭")
                }
            }
        )
    }
}

@Composable
fun HaGridCard(
    haState: HomeViewModel.HaState,
    viewModel: HomeViewModel
) {
    val climateLastValidModes by viewModel.climateLastValidModes.collectAsStateWithLifecycle()
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            when (haState) {
                is HomeViewModel.HaState.NotConfigured -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Home Assistant 未配置",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                is HomeViewModel.HaState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("加载中...")

                    }
                }
                is HomeViewModel.HaState.Error -> {
                    Text(
                        text = haState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is HomeViewModel.HaState.Success -> {
                    val displayedEntities = haState.entities
                    if (displayedEntities.isEmpty()) {
                        Text(
                            text = "无匹配项。请检查设置中选择的设备/传感器ID，或确保Home Assistant服务器正常工作。",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 100.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(displayedEntities) { entity ->
                                HaEntityCard(
                                    entity = entity,
                                    onToggleDevice = { entityId, targetState ->
                                        viewModel.toggleHaDeviceState(entityId, targetState)
                                    },
                                    onPressButton = { entityId ->
                                        viewModel.pressHaButton(entityId)
                                    },
                                    onSetClimateTemperature = { entityId, temperature ->
                                        viewModel.setClimateTemperature(entityId, temperature)
                                    },
                                    onSetClimateHvacMode = { entityId, mode ->
                                        viewModel.setClimateHvacMode(entityId, mode)
                                    },
                                    onSetLightBrightness = { entityId, brightnessPct ->
                                        viewModel.setLightBrightness(entityId, brightnessPct)
                                    },
                                    onSetLightColorTemperature = { entityId, colorTempKelvin ->
                                        viewModel.setLightColorTemperature(entityId, colorTempKelvin)
                                    },
                                    onSetInputSelectOption = { entityId, option ->
                                        viewModel.setInputSelectOption(entityId, option)
                                    },
                                    climateLastValidMode = climateLastValidModes[entity.entityId]
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HaDevicesAndSensorsCard(
    haState: HomeViewModel.HaState,
    onToggleDevice: (String, String) -> Unit,
    onPressButton: (String) -> Unit,
    onSetClimateTemperature: (String, Float) -> Unit,
    @Suppress("UNUSED_PARAMETER") onSetClimateHvacMode: (String, String) -> Unit,
    onSetLightBrightness: (String, Int) -> Unit,
    onSetLightColorTemperature: (String, Int) -> Unit, // 新增回调
    onSetInputSelectOption: (String, String) -> Unit, // input_select 回调
    climateLastValidModes: Map<String, String> = emptyMap() // climate 实体的最后有效模式
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Removed Text(text = "Home Assistant", style = MaterialTheme.typography.headlineSmall)
                // Removed status text logic
            }
            Spacer(modifier = Modifier.height(8.dp))

            when (haState) {
                is HomeViewModel.HaState.NotConfigured -> {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Home Assistant 未配置。请在设置中填写服务器地址和 Token 以连接。", // Modified text
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                                .padding(bottom = 8.dp)
                        )
                        // Removed OutlinedTextFields for baseUrl and token
                    }
                }
                is HomeViewModel.HaState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                        Text("加载中...")

                    }
                }
                is HomeViewModel.HaState.Error -> {
                    Text(
                        text = haState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is HomeViewModel.HaState.Success -> {
                    val displayedEntities = haState.entities
                    if (displayedEntities.isEmpty()) {
                        Text("无匹配项。请检查设置中选择的设备/传感器ID，或确保Home Assistant服务器正常工作。", style = MaterialTheme.typography.bodyLarge)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 100.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(displayedEntities) { entity ->
                                HaEntityCard(
                                    entity = entity,
                                    onToggleDevice = onToggleDevice,
                                    onPressButton = onPressButton,
                                    onSetClimateTemperature = onSetClimateTemperature,
                                    onSetClimateHvacMode = onSetClimateHvacMode,
                                    onSetLightBrightness = onSetLightBrightness,
                                    onSetLightColorTemperature = onSetLightColorTemperature,
                                    onSetInputSelectOption = onSetInputSelectOption,
                                    climateLastValidMode = climateLastValidModes[entity.entityId]
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HaEntityCard(
    entity: HaEntityState,
    onToggleDevice: (String, String) -> Unit,
    onPressButton: (String) -> Unit,
    onSetClimateTemperature: (String, Float) -> Unit,
    onSetClimateHvacMode: (String, String) -> Unit,
    onSetLightBrightness: (String, Int) -> Unit,
    onSetLightColorTemperature: (String, Int) -> Unit, // 新增回调
    onSetInputSelectOption: (String, String) -> Unit, // input_select 回调
    climateLastValidMode: String? = null // climate 实体的最后有效模式
) {
    val entityDomain = entity.entityId.split(".").firstOrNull()
    val friendlyName = entity.attributes["friendly_name"] as? String ?: entity.entityId

    var expandedControls by remember { mutableStateOf(false) } // State to manage expanded controls

    Card(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(
                onClick = { if (entityDomain == "light") expandedControls = !expandedControls }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 获取当前 HVAC 模式
            val hvacMode = entity.attributes["hvac_mode"] as? String ?: entity.state
            val modeLower = hvacMode.lowercase()
            
            val icon = when (entityDomain) {
                "light" -> Icons.Default.Lightbulb
                "switch" -> Icons.Default.ToggleOn
                "sensor" -> Icons.Default.Sensors
                "button" -> Icons.Default.TouchApp
                "climate" -> {
                    // 根据 HVAC 模式选择图标
                    when (modeLower) {
                        "cool", "cooling" -> Icons.Default.AcUnit // 制冷模式
                        "heat", "heating" -> Icons.Default.Whatshot // 制热模式
                        "off" -> {
                            // 关闭模式：根据最后有效模式显示
                            Log.d(TAG, "Climate ${entity.entityId} 关闭状态，climateLastValidMode=$climateLastValidMode")
                            val iconToShow = when (climateLastValidMode) {
                                "heat", "heating" -> {
                                    Icons.Default.Whatshot // 制热->关闭
                                }
                                "cool", "cooling" -> {
                                    Icons.Default.AcUnit // 制冷->关闭
                                }
                                else -> {
                                    Icons.Default.AcUnit // 默认显示冷气图标
                                }
                            }
                            iconToShow
                        }
                        else -> Icons.Default.AcUnit // 其他模式：默认空调
                    }
                }
                "fan" -> Icons.Default.Notifications
                "input_select" -> Icons.Default.Settings
                else -> Icons.Default.Notifications
            }
            
            // 计算灯实体的图标颜色
            val iconColor = if (entityDomain == "light") {
                calculateLightIconColor(entity)
            } else {
                MaterialTheme.colorScheme.onSurface // 其他实体使用默认颜色
            }
            
            Icon(
                imageVector = icon,
                contentDescription = entity.entityId,
                modifier = Modifier.size(32.dp),
                tint = iconColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = friendlyName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))

            when (entityDomain) {
                "light", "switch", "fan" -> {
                    Switch(
                        checked = entity.state == "on",
                        onCheckedChange = { targetState ->
                            onToggleDevice(entity.entityId, if (targetState) "on" else "off")
                        }
                    )
                }
                "sensor" -> {
                    val unit = entity.attributes["unit_of_measurement"] as? String ?: ""
                    Text(
                        text = "${entity.state} $unit",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
                "button" -> {
                    Button(
                        onClick = { onPressButton(entity.entityId) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("单击", style = MaterialTheme.typography.bodySmall)
                    }
                }
                "climate" -> {
                    val currentTemp = entity.attributes["current_temperature"] as? Number
                    val targetTemp = entity.attributes["temperature"] as? Number
                    val temperatureUnit = entity.attributes["temperature_unit"] as? String ?: "°C"
                    val hvacMode = entity.attributes["hvac_mode"] as? String ?: entity.state
                    @Suppress("UNCHECKED_CAST")
                    val hvacModes = (entity.attributes["hvac_modes"] as? List<String>) ?: emptyList()
                    var expandedMode by remember { mutableStateOf(false) }
                    
                    Text(
                        text = "${currentTemp?.toFloat()?.let { "%.0f".format(it) } ?: "N/A"}$temperatureUnit",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "目标: ${targetTemp?.toFloat()?.let { "%.0f".format(it) } ?: "N/A"}$temperatureUnit",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // HVAC 模式选择
                    if (hvacModes.isNotEmpty()) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "模式: $hvacMode",
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandedMode = true }
                                    .padding(vertical = 2.dp)
                            )
                            
                            DropdownMenu(
                                expanded = expandedMode,
                                onDismissRequest = { expandedMode = false },
                                modifier = Modifier
                                    .width(maxWidth)
                                    .clip(RoundedCornerShape(16.dp))
                            ) {
                                hvacModes.forEach { mode ->
                                    DropdownMenuItem(
                                        text = { 
                                            Text(
                                                mode, 
                                                style = MaterialTheme.typography.bodySmall,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            ) 
                                        },
                                        onClick = {
                                            onSetClimateHvacMode(entity.entityId, mode)
                                            expandedMode = false
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                targetTemp?.toFloat()?.let { current ->
                                    onSetClimateTemperature(entity.entityId, current - 1f)
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("-", style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(
                            onClick = {
                                targetTemp?.toFloat()?.let { current ->
                                    onSetClimateTemperature(entity.entityId, current + 1f)
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("+", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                "input_select" -> {
                    @Suppress("UNCHECKED_CAST")
                    val options = (entity.attributes["options"] as? List<String>) ?: emptyList()
                    val currentOption = entity.state
                    var expanded by remember { mutableStateOf(false) }
                    
                    if (options.isNotEmpty()) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = currentOption,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expanded = true }
                                    .padding(vertical = 4.dp)
                            )
                            
                            // 下拉菜单
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false },
                                modifier = Modifier
                                    .width(maxWidth)
                                    .clip(RoundedCornerShape(16.dp))
                            ) {
                                options.forEach { option ->
                                    DropdownMenuItem(
                                        text = { 
                                            Text(
                                                option, 
                                                style = MaterialTheme.typography.bodySmall,
                                                textAlign = TextAlign.Center,
                                                modifier = Modifier.fillMaxWidth()
                                            ) 
                                        },
                                        onClick = {
                                            onSetInputSelectOption(entity.entityId, option)
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = currentOption,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                else -> {
                    Text(
                        text = entity.state,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
    
    // Light expanded controls dialog
    if (entityDomain == "light" && expandedControls) {
        val isChecked = entity.state == "on"
        val supportedFeatures = (entity.attributes["supported_features"] as? Int) ?: 0
        @Suppress("UNCHECKED_CAST")
        val supportedColorModes = (entity.attributes["supported_color_modes"] as? List<String>) ?: emptyList()

        val supportsBrightness = (supportedFeatures and SUPPORT_BRIGHTNESS_BITMASK) != 0 ||
                                 supportedColorModes.contains("brightness") ||
                                 supportedColorModes.contains("hs") ||
                                 supportedColorModes.contains("rgb") ||
                                 supportedColorModes.contains("xy") ||
                                 entity.attributes.containsKey("brightness")

        val supportsColorTemperature = (supportedFeatures and SUPPORT_COLOR_TEMP_BITMASK) != 0 || supportedColorModes.contains("color_temp")

        val currentBrightness = (entity.attributes["brightness"] as? Number)?.toFloat() ?: 0f
        var sliderBrightness by remember { mutableFloatStateOf(currentBrightness / 2.55f) }

        val minColorTempKelvin = (entity.attributes["min_color_temp_kelvin"] as? Number)?.toFloat() ?: 2000f
        val maxColorTempKelvin = (entity.attributes["max_color_temp_kelvin"] as? Number)?.toFloat() ?: 6500f
        val currentColorTemp = (entity.attributes["color_temp_kelvin"] as? Number)?.toFloat() ?: minColorTempKelvin
        var sliderColorTemp by remember { mutableFloatStateOf(currentColorTemp) }
        
        AlertDialog(
            onDismissRequest = { expandedControls = false },
            title = { Text(friendlyName) },
            text = {
                Column {
                    if (supportsBrightness && isChecked) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WbSunny, contentDescription = "Brightness", modifier = Modifier.size(20.dp))
                            Text(text = "亮度", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 4.dp))
                            Slider(
                                value = sliderBrightness,
                                onValueChange = { newBrightness -> sliderBrightness = newBrightness },
                                onValueChangeFinished = {
                                    val brightnessToSend = sliderBrightness.toInt()
                                    Log.d(TAG, "Setting brightness for ${entity.entityId} to $brightnessToSend%")
                                    onSetLightBrightness(entity.entityId, brightnessToSend)
                                },
                                valueRange = 0f..100f,
                                steps = 99,
                                modifier = Modifier.weight(1f)
                            )
                            Text(text = "${sliderBrightness.toInt()}%", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(40.dp))
                        }
                    }
                    if (supportsColorTemperature && isChecked) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.WbSunny, contentDescription = "Color Temperature", modifier = Modifier.size(20.dp))
                            Text(text = "色温", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 4.dp))
                            Slider(
                                value = sliderColorTemp,
                                onValueChange = { newColorTemp -> sliderColorTemp = newColorTemp },
                                onValueChangeFinished = {
                                    val colorTempToSend = sliderColorTemp.toInt()
                                    Log.d(TAG, "Setting color temperature for ${entity.entityId} to ${colorTempToSend}K")
                                    onSetLightColorTemperature(entity.entityId, colorTempToSend)
                                },
                                valueRange = minColorTempKelvin..maxColorTempKelvin,
                                steps = ((maxColorTempKelvin - minColorTempKelvin) / 50).toInt().coerceAtLeast(0),
                                modifier = Modifier.weight(1f)
                            )
                            Text(text = "${sliderColorTemp.toInt()}K", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(50.dp))
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { expandedControls = false }) {
                    Text("关闭")
                }
            }
        )
        
        LaunchedEffect(entity.attributes["brightness"]) {
            val newBrightness = (entity.attributes["brightness"] as? Number)?.toFloat() ?: 0f
            sliderBrightness = newBrightness / 2.55f
        }
        LaunchedEffect(entity.attributes["color_temp_kelvin"]) {
            val newColorTemp = (entity.attributes["color_temp_kelvin"] as? Number)?.toFloat() ?: minColorTempKelvin
            sliderColorTemp = newColorTemp
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsIconCard(navController: NavController) {
    Card(
        modifier = Modifier
            .fillMaxSize()
            .combinedClickable(
                onClick = { navController.navigate(Screen.SETTINGS) }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 8.dp), // 保持较小的padding以充分利用空间
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center // 居中排列
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "设置",
                modifier = Modifier.size(48.dp), // 适中的图标尺寸，确保文字能完整显示
                tint = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp)) // 减小间距以节省空间
            Text(
                text = "设置",
                style = MaterialTheme.typography.bodySmall, // 使用较小的字体样式以确保完整显示
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
