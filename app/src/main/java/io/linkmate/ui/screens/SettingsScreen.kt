package io.linkmate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.linkmate.navigation.Screen
import io.linkmate.ui.viewmodels.ConnectionStatus
import io.linkmate.ui.viewmodels.SettingsViewModel
import io.linkmate.ui.viewmodels.HomeViewModel
import androidx.compose.runtime.LaunchedEffect // Import LaunchedEffect
import io.linkmate.util.NetworkUtils
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val haConfig by settingsViewModel.haConfig.collectAsState()
    val settings by settingsViewModel.settings.collectAsState()
    val selectedHaEntities by homeViewModel.selectedHaEntities.collectAsState()
    val haConnectionStatus by settingsViewModel.haConnectionStatus.collectAsState()
    val webPort by settingsViewModel.webPort.collectAsState()
    val webPassword by settingsViewModel.webPassword.collectAsState()

    // Local states for text fields that are updated on Save
    var heFengApiKeyInput by rememberSaveable { mutableStateOf(settings.heFengApiKey) }
    var weatherRefreshIntervalInput by rememberSaveable { mutableStateOf(settings.weatherRefreshIntervalMinutes.toString()) }
    var gpsUpdateIntervalInput by rememberSaveable { mutableStateOf(settings.gpsUpdateIntervalMinutes.toString()) }
    var deviceStateReportIntervalInput by rememberSaveable { mutableStateOf(settings.deviceStateReportIntervalMinutes.toString()) }
    var gridColumnsInput by rememberSaveable { mutableStateOf(settings.gridColumns.toString()) }
    var gridRowsInput by rememberSaveable { mutableStateOf(if (settings.gridRows == -1) "" else settings.gridRows.toString()) }
    var reminderWidthInput by rememberSaveable { mutableStateOf(settings.reminderWidth.toString()) }
    var reminderHeightInput by rememberSaveable { mutableStateOf(settings.reminderHeight.toString()) }
    var weatherDisplayMode by rememberSaveable { mutableStateOf(settings.weatherDisplayMode.toFloat()) }
    var colorThemeMode by rememberSaveable { mutableStateOf(settings.colorThemeMode.toFloat()) }

    // Synchronize local states with settings.value changes after initial composition
    LaunchedEffect(settings) {
        heFengApiKeyInput = settings.heFengApiKey
        weatherRefreshIntervalInput = settings.weatherRefreshIntervalMinutes.toString()
        gpsUpdateIntervalInput = settings.gpsUpdateIntervalMinutes.toString()
        deviceStateReportIntervalInput = settings.deviceStateReportIntervalMinutes.toString()
        gridColumnsInput = settings.gridColumns.toString()
        gridRowsInput = if (settings.gridRows == -1) "" else settings.gridRows.toString()
        reminderWidthInput = settings.reminderWidth.toString()
        reminderHeightInput = settings.reminderHeight.toString()
        weatherDisplayMode = settings.weatherDisplayMode.toFloat()
        colorThemeMode = settings.colorThemeMode.toFloat()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "和风天气设置",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = heFengApiKeyInput,
                onValueChange = { heFengApiKeyInput = it },
                label = { Text("和风天气 API Key") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text("天气定位方式", style = MaterialTheme.typography.bodyLarge)
            Row(Modifier.selectableGroup()) {
                Row(
                    Modifier
                        .weight(1f)
                        .selectable(
                            selected = settings.weatherLocationSource == "GPS",
                            onClick = { settingsViewModel.updateWeatherLocationSource("GPS") },
                            role = Role.RadioButton
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.weatherLocationSource == "GPS",
                        onClick = null
                    )
                    Text(
                        text = "GPS 自动定位",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Row(
                    Modifier
                        .weight(1f)
                        .selectable(
                            selected = settings.weatherLocationSource == "Manual",
                            onClick = { settingsViewModel.updateWeatherLocationSource("Manual") },
                            role = Role.RadioButton
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.weatherLocationSource == "Manual",
                        onClick = null
                    )
                    Text(
                        text = "手动输入位置",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            if (settings.weatherLocationSource == "Manual") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.manualWeatherLocation,
                    onValueChange = { settingsViewModel.updateManualWeatherLocation(it) },
                    label = { Text("手动输入位置 (城市ID或经纬度, e.g., 101010100 或 39.9,116.4)") },

                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = weatherRefreshIntervalInput,
                onValueChange = { weatherRefreshIntervalInput = it },
                label = { Text("天气刷新间隔 (分钟, 至少1分钟，留空则使用默认值 60)") },

                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            if (settings.weatherLocationSource == "GPS") {
                OutlinedTextField(
                    value = gpsUpdateIntervalInput,
                    onValueChange = { gpsUpdateIntervalInput = it },
                    label = { Text("GPS 更新间隔 (分钟, 至少1分钟，留空则使用默认值 60)") },

                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            Text(
                text = "Home Assistant 设置",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = haConfig.baseUrl,
                onValueChange = { settingsViewModel.updateHaBaseUrl(it) },
                label = { Text("Home Assistant 服务器地址 (e.g., http://192.168.1.100:8123)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = haConfig.token,
                onValueChange = { settingsViewModel.updateHaToken(it) },
                label = { Text("Home Assistant Long-Lived Token") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            when (haConnectionStatus) {
                is ConnectionStatus.Idle -> Text(
                    text = "等待连接...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                is ConnectionStatus.Connecting -> Text(
                    text = "正在连接...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                is ConnectionStatus.Connected -> Text(
                    text = "连接成功",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                is ConnectionStatus.Error -> Text(
                    text = "连接失败: ${(haConnectionStatus as ConnectionStatus.Error).message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { settingsViewModel.saveHaConfigAndTestConnection {} },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "保存 Home Assistant 设置并测试连接")
            }
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { navController.navigate(Screen.ENTITY_SELECTION) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "选择 Home Assistant 实体 (${selectedHaEntities.size} 已选择)")
            }
            Spacer(modifier = Modifier.height(24.dp))

            // Web 服务器设置
            Text(
                text = "Web服务器设置",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "本机局域网 IP: ${NetworkUtils.getLocalIpAddress()}",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            var webPortInput by rememberSaveable { mutableStateOf(webPort.toString()) }
            LaunchedEffect(webPort) { webPortInput = webPort.toString() }
            OutlinedTextField(
                value = webPortInput,
                onValueChange = { webPortInput = it },
                label = { Text("Web 服务器端口 (1024-65535)") },

                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    val p = webPortInput.toIntOrNull()
                    if (p != null) settingsViewModel.updateWebPort(p)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存 Web 端口并重启服务器")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "当前 Web 密码（明文）: ${webPassword}",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            var webPwdInput by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = webPwdInput,
                onValueChange = { webPwdInput = it },
                label = { Text("设置 Web 密码 (4-12位") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (webPwdInput.length in 4..12) settingsViewModel.updateWebPassword(webPwdInput)
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存 Web 密码")
            }
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "应用通用设置",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("启用靠近唤醒", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.enableProximityWake,
                    onCheckedChange = { settingsViewModel.updateEnableProximityWake(it) }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("启用设备状态上报", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.enableDeviceStateReporting,
                    onCheckedChange = { settingsViewModel.updateEnableDeviceStateReporting(it) }
                )
            }
            
            if (settings.enableDeviceStateReporting) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = deviceStateReportIntervalInput,
                    onValueChange = { deviceStateReportIntervalInput = it },
                    label = { Text("设备状态上报间隔 (分钟, 至少1分钟，默认5分钟)") },

                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "将上报电池电量、充电状态、屏幕状态、GPS位置等信息到 Home Assistant",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "网格布局设置",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = gridColumnsInput,
                onValueChange = { gridColumnsInput = it },
                label = { Text("网格列数 (1-10，默认4)") },

                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "设置主屏幕网格的列数",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = gridRowsInput,
                onValueChange = { gridRowsInput = it },
                label = { Text("网格行数 (留空表示无限行，或1-20)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "设置主屏幕网格的行数。留空或输入 0 表示无限行（可以无限向下滚动）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "提醒卡片尺寸设置",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            OutlinedTextField(
                value = reminderWidthInput,
                onValueChange = { reminderWidthInput = it },
                label = { Text("提醒卡片宽度 (1-20，默认4)") },

                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "设置提醒卡片的宽度（列数），范围 1-20，默认值为4",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = reminderHeightInput,
                onValueChange = { reminderHeightInput = it },
                label = { Text("提醒卡片高度 (1-4，默认1)") },

                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "设置提醒卡片的高度（行数），范围 1-4，默认值为1",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "天气显示模式",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = when (weatherDisplayMode.toInt()) {
                    1 -> "模式 1: 垂直完整模式（图标、状态、温度、空气质量）"
                    2 -> "模式 2: 紧凑模式（1x1，仅图标和温度）"
                    3 -> "模式 3: 横向排列模式（2x1，横向显示）"
                    else -> "模式 ${weatherDisplayMode.toInt()}"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Slider(
                value = weatherDisplayMode,
                onValueChange = { weatherDisplayMode = it },
                valueRange = 1f..3f,
                steps = 1,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "模式1",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "模式2",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "模式3",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "通过滑块切换天气卡片的显示模式",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            // 颜色主题设置
            Text(
                text = "颜色主题",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = when (colorThemeMode.toInt()) {
                    0 -> "自定义颜色：选择一个基础颜色，系统自动生成浅色和深色主题"
                    1 -> "动态系统：跟随系统壁纸颜色（Android 12+）"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Slider(
                value = colorThemeMode,
                onValueChange = { newValue ->
                    colorThemeMode = newValue
                },
                onValueChangeFinished = {
                    // 滑动结束时吸附到最近的值

                    val snapValue = if (colorThemeMode < 0.5f) 0f else 1f
                    colorThemeMode = snapValue
                    settingsViewModel.updateColorThemeMode(snapValue.toInt().coerceIn(0, 1))
                },
                valueRange = 0f..1f,
                steps = 0,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "自定义",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "动态系统",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // 自定义颜色模式的颜色选择器

            if (colorThemeMode.toInt() == 0) {
                Text(
                    text = "自定义颜色",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "基础主色",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    ColorPickerButton(
                        color = Color(settings.customPrimaryColor),
                        onColorSelected = { color ->
                            settingsViewModel.updateCustomPrimaryColor(color.toArgb().toLong())
                        }
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // 显示主题色预览

                    val selectedColor = Color(settings.customPrimaryColor)
                    val isDarkMode = isSystemInDarkTheme()

                    // 计算主题色

                    val primary = selectedColor
                    val primaryContainer = if (isDarkMode) {
                        Color(
                            red = (primary.red * 0.6f + 0.4f).coerceIn(0f, 1f),
                            green = (primary.green * 0.6f + 0.4f).coerceIn(0f, 1f),
                            blue = (primary.blue * 0.6f + 0.4f).coerceIn(0f, 1f)
                        )
                    } else {
                        Color(
                            red = (primary.red * 0.2f + 0.8f).coerceIn(0f, 1f),
                            green = (primary.green * 0.2f + 0.8f).coerceIn(0f, 1f),
                            blue = (primary.blue * 0.2f + 0.8f).coerceIn(0f, 1f)
                        )
                    }
                    // 使用HSV调整tertiary
                    val tertiaryHsv = FloatArray(3)
                    android.graphics.Color.colorToHSV(primary.toArgb(), tertiaryHsv)
                    tertiaryHsv[0] = (tertiaryHsv[0] + 15f) % 360f
                    if (!isDarkMode) {
                        tertiaryHsv[2] = (tertiaryHsv[2] * 0.9f).coerceIn(0f, 1f)
                    }
                    val tertiary = Color(android.graphics.Color.HSVToColor(tertiaryHsv))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        listOf(primary, primaryContainer, tertiary).forEach { color ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(color, RoundedCornerShape(8.dp))
                                        .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                )
                                Text(
                                    text = String.format("#%06X", color.toArgb() and 0xFFFFFF),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "左边：主色 | 中间：容器色 | 右边：强调色",

                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    // Update ViewModel with local input values before saving all settings
                    settingsViewModel.updateHeFengApiKey(heFengApiKeyInput)

                    val weatherInterval = weatherRefreshIntervalInput.toIntOrNull()?.coerceAtLeast(1) ?: 60
                    settingsViewModel.updateWeatherRefreshIntervalMinutes(weatherInterval)

                    val gpsInterval = gpsUpdateIntervalInput.toIntOrNull()?.coerceAtLeast(1) ?: 60
                    settingsViewModel.updateGpsUpdateIntervalMinutes(gpsInterval)
                    
                    val deviceReportInterval = deviceStateReportIntervalInput.toIntOrNull()?.coerceAtLeast(1) ?: 5
                    settingsViewModel.updateDeviceStateReportIntervalMinutes(deviceReportInterval)
                    
                    val gridColumns = gridColumnsInput.toIntOrNull()?.coerceIn(1, 10) ?: 4
                    settingsViewModel.updateGridColumns(gridColumns)
                    
                    val gridRows = if (gridRowsInput.isBlank() || gridRowsInput == "0") {
                        -1
                    } else {
                        gridRowsInput.toIntOrNull()?.coerceIn(1, 20) ?: -1
                    }
                    settingsViewModel.updateGridRows(gridRows)
                    
                    val reminderWidth = reminderWidthInput.toIntOrNull()?.coerceIn(1, 20) ?: 4
                    settingsViewModel.updateReminderWidth(reminderWidth)
                    
                    val reminderHeight = reminderHeightInput.toIntOrNull()?.coerceIn(1, 4) ?: 1
                    settingsViewModel.updateReminderHeight(reminderHeight)
                    
                    val weatherMode = weatherDisplayMode.toInt().coerceIn(1, 3)
                    settingsViewModel.updateWeatherDisplayMode(weatherMode)
                    
                    val colorMode = colorThemeMode.toInt().coerceIn(0, 1)
                    settingsViewModel.updateColorThemeMode(colorMode)

                    settingsViewModel.saveAppSettings { navController.popBackStack() }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存所有应用设置")
            }
        }
    }
}

@Composable
fun ColorPickerButton(
    color: Color,
    onColorSelected: (Color) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(color, CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable { showDialog = true }
    )
    
    if (showDialog) {
        ColorPickerDialog(
            initialColor = color,
            onColorSelected = { selectedColor ->
                onColorSelected(selectedColor)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
fun ColorPickerDialog(
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    // 将 Color 转换为 HSV 并初始化状态

    val initialHsv = remember(initialColor) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), hsv)
        hsv
    }
    
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    
    // 根据 HSV 计算当前颜色
    val isDark = isSystemInDarkTheme()
    val currentColor = remember(hue, saturation, value) {
        Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
    }

    // 计算主题色

    val themeColors = remember(currentColor, isDark) {
        val primary = currentColor
        val primaryContainer = if (isDark) {
            Color(
                red = (primary.red * 0.6f + 0.4f).coerceIn(0f, 1f),
                green = (primary.green * 0.6f + 0.4f).coerceIn(0f, 1f),
                blue = (primary.blue * 0.6f + 0.4f).coerceIn(0f, 1f)
            )
        } else {
            Color(
                red = (primary.red * 0.2f + 0.8f).coerceIn(0f, 1f),
                green = (primary.green * 0.2f + 0.8f).coerceIn(0f, 1f),
                blue = (primary.blue * 0.2f + 0.8f).coerceIn(0f, 1f)
            )
        }
        // 使用HSV调整tertiary，让色调偏移约15度

        val tertiaryHsv = FloatArray(3)
        android.graphics.Color.colorToHSV(primary.toArgb(), tertiaryHsv)
        tertiaryHsv[0] = (tertiaryHsv[0] + 15f) % 360f // 色相偏移15度

        if (!isDark) {
            tertiaryHsv[2] = (tertiaryHsv[2] * 0.9f).coerceIn(0f, 1f) // 浅色模式稍微降低亮度
        }
        val tertiary = Color(android.graphics.Color.HSVToColor(tertiaryHsv))
        listOf(primary, primaryContainer, tertiary)
    }
    
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择颜色") },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 色相（Hue）滑块

                Column {
                    var hueSliderWidthPx by remember { mutableStateOf(0f) }
                    val density = LocalDensity.current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(30.dp)
                            .onSizeChanged { size ->
                                hueSliderWidthPx = size.width.toFloat()
                            }
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    colors = (0..6).map { i ->
                                        Color(android.graphics.Color.HSVToColor(floatArrayOf(i * 60f, 1f, 1f)))
                                    }
                                )
                            )
                            .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    ) {
                        // 饱和度和亮度选择器

                        if (hueSliderWidthPx > 0f) {
                            val indicatorOffsetX = with(density) { (hue / 360f * hueSliderWidthPx - 8f).toDp() }
                            Box(
                                modifier = Modifier
                                    .offset(x = indicatorOffsetX)
                                    .size(16.dp)
                                    .background(Color.White, CircleShape)
                                    .border(2.dp, Color.Black, CircleShape)
                            )
                        }
                        
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) {
                                    detectHorizontalDragGestures { _, dragAmount ->
                                        val currentHueRatio = hue / 360f
                                        val newHueRatio = (currentHueRatio + dragAmount / hueSliderWidthPx).coerceIn(0f, 1f)
                                        hue = newHueRatio * 360f
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectTapGestures { offset ->
                                        val x = offset.x.coerceIn(0f, hueSliderWidthPx)
                                        hue = (x / hueSliderWidthPx * 360f).coerceIn(0f, 360f)
                                    }
                                }
                        )
                    }
                }

                // 饱和度和亮度选择器

                SaturationBrightnessPicker(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onSaturationBrightnessChange = { s, v ->
                        saturation = s
                        value = v
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                onColorSelected(currentColor)
            }) {
                Text("确定")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 方形饱和度/亮度选择器组件
 * X轴：饱和度（0-1）
 * Y轴：亮度（1-0，从上到下）
 */
@Composable
fun SaturationBrightnessPicker(
    hue: Float,
    saturation: Float,
    value: Float,
    onSaturationBrightnessChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var size by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { canvasSize -> size = androidx.compose.ui.geometry.Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()) }
        ) {
            // 绘制饱和度/亮度方形渐变

            // 从左上角（高亮度、低饱和度）到右下角（低亮度、高饱和度）
            val steps = 50 // 减少绘制步骤，提高性能
            
            val canvasWidth = size.width
            val canvasHeight = size.height
            
            for (x in 0..steps) {
                for (y in 0..steps) {
                    val s = x.toFloat() / steps
                    val v = 1f - y.toFloat() / steps
                    
                    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, s, v)))
                    
                    drawRect(
                        color = color,
                        topLeft = Offset(
                            x * canvasWidth / steps,
                            y * canvasHeight / steps
                        ),
                        size = androidx.compose.ui.geometry.Size(canvasWidth / steps + 1f, canvasHeight / steps + 1f)
                    )
                }
            }

            // 绘制指示器

            val indicatorX = saturation * canvasWidth
            val indicatorY = (1f - value) * canvasHeight
            
            drawCircle(
                color = Color.White,
                radius = 12f,
                center = Offset(indicatorX, indicatorY)
            )
            drawCircle(
                color = Color.Black,
                radius = 10f,
                center = Offset(indicatorX, indicatorY),
                style = Stroke(width = 2f)
            )
        }
        
        // 触摸事件处理
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(size) {
                    detectDragGestures(
                        onDrag = { change, _ ->
                            val x = change.position.x.coerceIn(0f, size.width)
                            val y = change.position.y.coerceIn(0f, size.height)
                            val s = (x / size.width).coerceIn(0f, 1f)
                            val v = (1f - y / size.height).coerceIn(0f, 1f)
                            onSaturationBrightnessChange(s, v)
                        }
                    )
                }
                .pointerInput(size) {
                    detectTapGestures { offset ->
                        val x = offset.x.coerceIn(0f, size.width)
                        val y = offset.y.coerceIn(0f, size.height)
                        val s = (x / size.width).coerceIn(0f, 1f)
                        val v = (1f - y / size.height).coerceIn(0f, 1f)
                        onSaturationBrightnessChange(s, v)
                    }
                }
        )
    }
}
