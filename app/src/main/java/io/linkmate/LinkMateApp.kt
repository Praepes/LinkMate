package io.linkmate

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class LinkMateApp : Application() {
    // This class will be used by Hilt to generate the necessary dependency injection components.
    
    override fun onCreate() {
        try {
            super.onCreate()
            Log.d("LinkMateApp", "Application onCreate completed")
        } catch (e: Exception) {
            Log.e("LinkMateApp", "Critical error in Application onCreate: ${e.message}", e)
            e.printStackTrace()
            // 重新抛出异常，让系统知道应用启动失败
            throw e
        }
    }
}

