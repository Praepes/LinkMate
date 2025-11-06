package io.linkmate.data.device

import android.app.Activity
import android.util.Log
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ScreenBrightnessManager"

/**
 * 屏幕亮度管理�?
 * 用于控制应用的窗口亮度（不影响系统全局亮度�?
 */
@Singleton
class ScreenBrightnessManager @Inject constructor() {
    
    private var currentActivity: Activity? = null
    private var currentBrightness: Float = -1f // -1 表示使用系统默认�?.0-1.0 表示自定义亮�?
    
    /**
     * 注册当前 Activity
     */
    fun setActivity(activity: Activity?) {
        currentActivity = activity
        // 如果已有亮度设置，应用到�?Activity
        if (activity != null && currentBrightness >= 0) {
            applyBrightness(activity, currentBrightness)
        }
    }
    
    /**
     * 设置应用窗口亮度
     * @param brightness 亮度值，范围 0.0-1.0�?1 表示使用系统默认
     */
    fun setBrightness(brightness: Float) {
        val normalizedBrightness = when {
            brightness < 0 -> -1f
            brightness > 1f -> 1f
            else -> brightness
        }
        
        currentBrightness = normalizedBrightness
        currentActivity?.let {
            applyBrightness(it, normalizedBrightness)
            Log.d(TAG, "Screen brightness set to: $normalizedBrightness (${(normalizedBrightness * 100).toInt()}%)")
        } ?: Log.w(TAG, "No activity set, brightness will be applied when activity is available")
    }
    
    /**
     * 获取当前亮度设置
     * @return 亮度值，范围 0.0-1.0�?1 表示使用系统默认
     */
    fun getBrightness(): Float = currentBrightness
    
    /**
     * 应用亮度到指�?Activity
     */
    private fun applyBrightness(activity: Activity, brightness: Float) {
        try {
            val window = activity.window
            val layoutParams = window.attributes
            
            if (brightness < 0) {
                // 使用系统默认亮度
                layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            } else {
                // 设置自定义亮度（0.0 �?1.0�?
                layoutParams.screenBrightness = brightness
            }
            
            window.attributes = layoutParams
            
            // 同时设置 WindowInsetsController 的亮度（Android 11+�?
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = brightness < 0.5f // 暗色状态栏
            }
            
            Log.d(TAG, "Applied brightness: $brightness to activity: ${activity::class.simpleName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply brightness: ${e.message}", e)
        }
    }
    
    /**
     * �?0-255 的亮度值转换为 0.0-1.0
     */
    fun brightnessToFloat(brightness: Int): Float {
        return when {
            brightness < 0 -> -1f
            brightness > 255 -> 1f
            else -> brightness / 255f
        }
    }
    
    /**
     * �?0.0-1.0 的亮度值转换为 0-255
     */
    fun brightnessToInt(brightness: Float): Int {
        return when {
            brightness < 0 -> -1
            brightness > 1f -> 255
            else -> (brightness * 255).toInt()
        }
    }
}

