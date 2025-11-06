package io.linkmate.data.device

import android.app.Activity
import android.os.PowerManager
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ScreenKeepOnManager"

/**
 * 屏幕常亮管理�?
 * 用于控制应用的屏幕常亮状态（防止屏幕自动息屏�?
 */
@Singleton
class ScreenKeepOnManager @Inject constructor() {
    
    private var currentActivity: Activity? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isKeepOnEnabled = false
    
    /**
     * 注册当前 Activity
     */
    fun setActivity(activity: Activity?) {
        currentActivity = activity
        // 如果已启用常亮，应用到新 Activity
        if (activity != null && isKeepOnEnabled) {
            applyKeepOn(activity, true)
        } else if (activity == null) {
            // Activity 被销毁，释放 WakeLock
            releaseWakeLock()
        }
    }
    
    /**
     * 设置屏幕常亮状�?
     * @param keepOn true 表示保持屏幕常亮，false 表示允许自动息屏
     */
    fun setKeepOn(keepOn: Boolean) {
        isKeepOnEnabled = keepOn
        currentActivity?.let {
            applyKeepOn(it, keepOn)
            Log.d(TAG, "Screen keep on set to: $keepOn")
        } ?: Log.w(TAG, "No activity set, keep on will be applied when activity is available")
    }
    
    /**
     * 获取当前常亮状�?
     */
    fun isKeepOn(): Boolean = isKeepOnEnabled
    
    /**
     * 应用常亮状态到指定 Activity
     */
    private fun applyKeepOn(activity: Activity, keepOn: Boolean) {
        try {
            if (keepOn) {
                // 启用屏幕常亮
                activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                
                // 同时获取 WakeLock（作为备用方案）
                val powerManager = activity.getSystemService(Activity.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "HPApp::ScreenKeepOn"
                ).apply {
                    acquire(10 * 60 * 60 * 1000L) // 10小时超时
                }
                Log.d(TAG, "Screen keep on enabled for activity: ${activity::class.simpleName}")
            } else {
                // 禁用屏幕常亮
                activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                releaseWakeLock()
                Log.d(TAG, "Screen keep on disabled for activity: ${activity::class.simpleName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply keep on: ${e.message}", e)
        }
    }
    
    /**
     * 释放 WakeLock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
            wakeLock = null
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        releaseWakeLock()
        currentActivity = null
        isKeepOnEnabled = false
    }
}

