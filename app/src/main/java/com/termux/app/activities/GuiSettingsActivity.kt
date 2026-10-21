package com.termux.app.activities

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.compose.GuiSettingsScreen
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.NavigationHelper
import com.termux.gui.Settings

/**
 * Termux:GUI 设置页宿主（独立 Activity）。
 *
 * 取代 vendor 里的旧式 GUIConfigActivity（XML 表单），UI 为 miuix 化的 [GuiSettingsScreen]。
 * 设置项写入 [Settings] 单例即时生效；返回键由系统 Activity 栈处理（finish 回到「配置集成工具」页）。
 */
class GuiSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TermuxApplication 启动时已按开关加载过，这里再 load 一次兜底
        Settings.instance.load(this)
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
                    GuiSettingsScreen(onBack = { finish() })
                }
            }
        }
    }
}
