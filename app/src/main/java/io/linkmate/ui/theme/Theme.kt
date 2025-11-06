package io.linkmate.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.linkmate.data.local.SettingsEntity

// CompositionLocal 用于在主题中访问设置
val LocalThemeSettings = compositionLocalOf<SettingsEntity?> { null }

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

@Composable
fun LinkmateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    settings: SettingsEntity? = null,
    content: @Composable () -> Unit
) {
    val themeSettings = LocalThemeSettings.current ?: settings
    val context = LocalContext.current
    val colorScheme = remember(
        darkTheme,
        themeSettings?.colorThemeMode,
        themeSettings?.customPrimaryColor,
        context
    ) {
        rememberColorScheme(
            darkTheme = darkTheme,
            settings = themeSettings,
            context = context
        )
    }

    CompositionLocalProvider(LocalThemeSettings provides themeSettings) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/**
 * 基于主色生成完整的颜色方�?
 * Material 3 会自动从 primary 生成其他颜色，我们只需要设�?primary 相关的核心颜�?
 */
private fun createColorSchemeFromPrimary(
    primary: Color,
    isDark: Boolean
): androidx.compose.material3.ColorScheme {
    // Material 3 �?lightColorScheme �?darkColorScheme 如果只传�?primary
    // 会自动计算其他颜色（包括 primaryContainer, onPrimaryContainer 等）
    // 但为了确�?TopAppBar 等组件正确显示，我们需要明确设�?primaryContainer
    
    // 计算 primaryContainer：用�?TopAppBar 等容器背�?
    val primaryContainer = if (isDark) {
        // 深色模式：primaryContainer �?primary 的变亮版本（混合白色使其更浅�?
        Color(
            red = (primary.red * 0.6f + 0.4f).coerceIn(0f, 1f),
            green = (primary.green * 0.6f + 0.4f).coerceIn(0f, 1f),
            blue = (primary.blue * 0.6f + 0.4f).coerceIn(0f, 1f)
        )
    } else {
        // 浅色模式：primaryContainer �?primary 的变浅版本（降低饱和度并提高亮度�?
        // 使用更柔和的方式：降低主色的强度
        Color(
            red = (primary.red * 0.2f + 0.8f).coerceIn(0f, 1f),
            green = (primary.green * 0.2f + 0.8f).coerceIn(0f, 1f),
            blue = (primary.blue * 0.2f + 0.8f).coerceIn(0f, 1f)
        )
    }
    
    // onPrimaryContainer：放�?primaryContainer 上的文本颜色
    // 在深色模式下使用较亮�?primary，浅色模式下使用较暗�?primary
    val onPrimaryContainer = if (isDark) {
        // 深色模式：使用主色本身（已足够亮�?
        primary
    } else {
        // 浅色模式：使用主色（�?primaryContainer 形成对比�?
        primary
    }
    
    // tertiary 在连接成功提示中使用，所以也需要基于主题生�?
    // secondary 完全没用，使用默认值即�?
    val tertiary = if (isDark) {
        // 深色模式：tertiary 稍微调整色调
        Color(
            red = (primary.red * 1.05f).coerceIn(0f, 1f),
            green = (primary.green * 0.95f).coerceIn(0f, 1f),
            blue = (primary.blue * 0.9f).coerceIn(0f, 1f)
        )
    } else {
        // 浅色模式：tertiary 稍微调整色调
        Color(
            red = (primary.red * 0.95f).coerceIn(0f, 1f),
            green = (primary.green * 1.05f).coerceIn(0f, 1f),
            blue = (primary.blue * 1.05f).coerceIn(0f, 1f)
        )
    }
    
    if (isDark) {
        return darkColorScheme(
            primary = primary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            tertiary = tertiary
            // secondary 完全没用，使用默认�?
        )
    } else {
        return lightColorScheme(
            primary = primary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            tertiary = tertiary
            // secondary 完全没用，使用默认�?
        )
    }
}

private fun rememberColorScheme(
    darkTheme: Boolean,
    settings: SettingsEntity?,
    context: android.content.Context
): androidx.compose.material3.ColorScheme {
    val themeMode = settings?.colorThemeMode ?: 1 // 默认动态系�?
    
    return when (themeMode) {
        0 -> { // 自定义颜色：直接使用选择的颜色作为主�?
            val primaryColor = Color(settings?.customPrimaryColor ?: 0xFF6650a4)
            // 直接使用选择的颜色，不进行任何变�?
            createColorSchemeFromPrimary(primaryColor, isDark = darkTheme)
        }
        1 -> { // 动态系�?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                // Android 12 以下回退到静态颜�?
                if (darkTheme) DarkColorScheme else LightColorScheme
            }
        }
        else -> {
            // 默认使用动态系统或静态颜�?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                if (darkTheme) DarkColorScheme else LightColorScheme
            }
        }
    }
}