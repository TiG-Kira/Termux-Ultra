package com.termux.app.activities

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.compose.ConsoleSettingsScreen
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.NavigationHelper

/**
 * 控制台设置页宿主（独立 Activity）。
 *
 * 承载字体大小、光标闪烁、光标样式、文本闪烁、滚动缓冲区五项。返回键由系统 Activity 栈处理
 * （finish 回到设置页），旋转等配置变更时 Composable 重建，偏好走 ComposeTerminalSettings
 * 的 StateFlow，改动即时生效、无需保存按钮。
 */
class ConsoleSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = Color.TRANSPARENT
        // 缺这一行时状态栏会保留主题里的半透明遮罩，与其它独立页面不一致
        window.statusBarColor = Color.TRANSPARENT
        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                KiTerminalTheme {
                    ConsoleSettingsScreen(onBack = { finish() })
                }
            }
        }
    }
}
