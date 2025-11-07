package io.linkmate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import io.linkmate.navigation.AppNavGraph
import io.linkmate.ui.theme.LinkmateTheme
import io.linkmate.ui.viewmodels.HomeViewModel
import dagger.hilt.android.AndroidEntryPoint
import android.content.Intent
import android.os.Build
import io.linkmate.service.WebServerService
import io.linkmate.data.device.ScreenBrightnessManager
import io.linkmate.data.device.ScreenKeepOnManager
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var screenBrightnessManager: ScreenBrightnessManager
    
    @Inject
    lateinit var screenKeepOnManager: ScreenKeepOnManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 注册当前 Activity
        screenBrightnessManager.setActivity(this)
        screenKeepOnManager.setActivity(this)
        
        // 启动前台 Web 服务器服�?
        try {
            val intent = Intent(this, WebServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error starting WebServerService: ${e.message}", e)
            e.printStackTrace()
        }
        setContent {
            val homeViewModel: HomeViewModel = hiltViewModel()
            val settings by homeViewModel.settings.collectAsState()
            
            LinkmateTheme(settings = settings) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    LocationPermissionRequester()
                    AppNavGraph(navController = navController)
                }
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 确保 Activity 恢复时重新注�?
        try {
            if (::screenBrightnessManager.isInitialized) {
                screenBrightnessManager.setActivity(this)
            }
            if (::screenKeepOnManager.isInitialized) {
                screenKeepOnManager.setActivity(this)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error in onResume: ${e.message}", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Activity 暂停时不清除 Activity 引用，保持亮度设�?
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Activity 销毁时清除引用
        try {
            if (::screenBrightnessManager.isInitialized) {
                screenBrightnessManager.setActivity(null)
            }
            if (::screenKeepOnManager.isInitialized) {
                screenKeepOnManager.setActivity(null)
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error in onDestroy: ${e.message}", e)
        }
    }
}

@Composable
fun LocationPermissionRequester(homeViewModel: HomeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fineLocationGranted || coarseLocationGranted) {
            // 如果至少一个位置权限被授予，则通知 ViewModel
            homeViewModel.onPermissionsGranted()
        } else {
            // 权限被拒绝，可以在这里显示一个消息给用户
            // 或者导航到设置页面�?
            // Log.d("Permissions", "Location permissions denied")
        }
    }

    LaunchedEffect(Unit) {
        val fineLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val coarseLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fineLocationPermission || !coarseLocationPermission) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            // 权限已经被授予，直接通知 ViewModel
            homeViewModel.onPermissionsGranted()
        }
    }
}
