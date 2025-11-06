package io.linkmate.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 根据深色计算对应的浅色版本（用于半自动模式）
 * 使用HSL颜色空间，增加亮度并稍微调整饱和�?
 */
fun calculateLightColorFromDark(darkColor: Color): Color {
    val red = darkColor.red
    val green = darkColor.green
    val blue = darkColor.blue
    
    // 转换�?HSL
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    
    // 计算亮度 (Lightness)
    var lightness = (max + min) / 2f
    
    // 计算饱和�?(Saturation)
    var saturation = if (delta == 0f) 0f else delta / (1f - kotlin.math.abs(2f * lightness - 1f))
    
    // 计算色相 (Hue)
    var hue = when {
        delta == 0f -> 0f
        max == red -> ((green - blue) / delta + (if (green < blue) 6f else 0f)) / 6f
        max == green -> ((blue - red) / delta + 2f) / 6f
        else -> ((red - green) / delta + 4f) / 6f
    }
    
    // 调整亮度：深色变浅色，增加亮度到 70-80%
    lightness = lightness.coerceIn(0f, 1f)
    val targetLightness = if (lightness < 0.5f) {
        // 如果原色较暗，提升到 75%
        0.75f
    } else {
        // 如果原色已经较亮，提升到 85%
        0.85f
    }
    
    // 稍微降低饱和度，让浅色更柔和
    saturation = (saturation * 0.7f).coerceIn(0f, 1f)
    
    // 转换�?RGB
    val c = (1f - kotlin.math.abs(2f * targetLightness - 1f)) * saturation
    val x = c * (1f - kotlin.math.abs((hue * 6f) % 2f - 1f))
    val m = targetLightness - c / 2f
    
    val (r, g, b) = when {
        hue < 1f / 6f -> Triple(c, x, 0f)
        hue < 2f / 6f -> Triple(x, c, 0f)
        hue < 3f / 6f -> Triple(0f, c, x)
        hue < 4f / 6f -> Triple(0f, x, c)
        hue < 5f / 6f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f)
    )
}

/**
 * �?Long (ARGB) 转换�?Color
 */
fun Long.toColor(): Color {
    return Color(this)
}

/**
 * Color 转换�?Long (ARGB)
 */
fun Color.toLong(): Long {
    val a = (alpha * 255).toInt() and 0xFF
    val r = (red * 255).toInt() and 0xFF
    val g = (green * 255).toInt() and 0xFF
    val b = (blue * 255).toInt() and 0xFF
    return ((a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
}
