package com.termux.app.activities

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.compose.ConsolePersonalizationScreen
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.NavigationHelper

/**
 * 控制台个性化页宿主（独立 Activity）。
 *
 * 承载自动启动终端控制台、编辑器工具、新会话自动执行、编辑欢迎文本四项。返回键由系统 Activity
 * 栈处理（finish 回到设置页）。自动启动走 LaunchPrefs 的 StateFlow；编辑器工具与新会话命令
 * 走 termux_preferences；欢迎文本通过内置编辑器或 vim 打开 motd 文件。
 */
class ConsolePersonalizationActivity : ComponentActivity() {

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
                    ConsolePersonalizationScreen(onBack = { finish() })
                }
            }
        }
    }
}
