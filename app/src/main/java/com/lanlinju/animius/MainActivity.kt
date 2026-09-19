package com.lanlinju.animius

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.navigation.compose.rememberNavController
import com.lanlinju.animius.presentation.navigation.AnimeNavHost
import com.lanlinju.animius.presentation.navigation.Screen
import com.lanlinju.animius.presentation.screen.crash.CrashActivity
import com.lanlinju.animius.presentation.theme.AnimeTheme
import com.lanlinju.animius.util.getCrashLogInfo
import com.lanlinju.animius.util.logCrashToFile
import dagger.hilt.android.AndroidEntryPoint
import kotlin.system.exitProcess

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 设置全局异常捕获处理
        setGlobalExceptionHandler()

        installSplashScreen()
        enableEdgeToEdge()

        //https://github.com/android/compose-samples/issues/1256
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets -> insets }

        setContent {
            AnimeTheme {
                App()
            }
        }
    }

    private fun setGlobalExceptionHandler() {
        if (BuildConfig.DEBUG) return // 调试模式下使用控制台查看崩溃日志
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            logCrashToFile(e)
            launchCrashActivity(e)
        }
    }

    private fun launchCrashActivity(e: Throwable) {
        val crashLog = getCrashLogInfo(e)
        val intent = Intent(this, CrashActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("crash_log", crashLog)
        }
        startActivity(intent)
        finish()
        exitProcess(0)
    }
}

@Composable
private fun App(modifier: Modifier = Modifier) {
    val navController = rememberNavController()

    // TV 键盘/遥控器长按会导致按键重复(KeyDown repeat)，若组件未过滤重复事件，
    // 一次按压会连发数十次 navigate，把返回栈压满同一路由页——表现为"按返回原地跳转"。
    // 这里对所有导航统一做 500ms 去抖兜底（人手不可能 500ms 内触发两次同一路由导航，
    // 手机端行为不受影响）。
    val lastNavigateTimeMs = remember { mutableLongStateOf(0L) }
    val navigateDebounced: (Any) -> Unit = { route ->
        val now = SystemClock.uptimeMillis()
        if (now - lastNavigateTimeMs.longValue >= 500) {
            lastNavigateTimeMs.longValue = now
            navController.navigate(route)
        }
    }

    AnimeNavHost(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        navController = navController,
        onNavigateToAnimeDetail = { detailUrl, mode ->
            navigateDebounced(Screen.AnimeDetail(detailUrl, mode))
        },
        onNavigateToVideoPlay = { parameters ->
            navigateDebounced(Screen.VideoPlayer(parameters))
        },
        onBackClick = {
            navController.popBackStack()
        },
        onNavigateToHistory = {
            navigateDebounced(Screen.HistoryScreen)
        },
        onNavigateToDownload = {
            navigateDebounced(Screen.Download)
        },
        onNavigateToDownloadDetail = { detailUrl, title ->
            navigateDebounced(Screen.DownloadDetail(detailUrl, title))
        },
        onNavigateToSearch = {
            navigateDebounced(Screen.Search)
        },
        onNavigateToAppearance = {
            navigateDebounced(Screen.Appearance)
        },
        onNavigateToDanmakuSettings = {
            navigateDebounced(Screen.DanmakuSettings)
        },
    )
}



