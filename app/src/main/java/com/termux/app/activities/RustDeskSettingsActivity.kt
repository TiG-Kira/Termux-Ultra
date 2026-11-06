package com.termux.app.activities

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.NavigationHelper
import com.termux.app.compose.RustDeskSettingsScreen

/**
 * 「RustDesk 设置」页宿主（独立 Activity）。
 *
 * 与 [GuiSettingsActivity] 同构：miuix 化的 Compose 页面 + 玻璃顶栏，返回键直接 finish 回设置页。
 * 所有设置项读写 SharedPreferences 即时生效，页面本身不持有会话状态。
 */
class RustDeskSettingsActivity : ComponentActivity() {

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
                    RustDeskSettingsScreen(onBack = { finish() })
                }
            }
        }
    }
}
