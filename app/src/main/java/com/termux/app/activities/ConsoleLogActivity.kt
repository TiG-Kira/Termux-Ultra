package com.termux.app.activities

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.compose.ConsoleLogScreen
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.NavigationHelper

/**
 * 控制台日志页宿主（独立 Activity）。
 *
 * 承载终端按键日志与日志级别两项。返回键由系统 Activity 栈处理（finish 回到设置页），
 * 按键日志走 ComposeTerminalSettings 的 StateFlow，日志级别走 TermuxAppSharedPreferences。
 */
class ConsoleLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = Color.TRANSPARENT
        window.statusBarColor = Color.TRANSPARENT
        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                KiTerminalTheme {
                    ConsoleLogScreen(onBack = { finish() })
                }
            }
        }
    }
}
