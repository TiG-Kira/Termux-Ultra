package com.termux.app.activities

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.compose.AgentPawSettingsScreen
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.NavigationHelper

/**
 * AgentPaw 设置页宿主。
 *
 * 原为 Dialog 形态，改为独立 Activity 后返回键由系统的 Activity 栈处理（finish 回到
 * 来源页），旋转等配置变更时 Composable 重建，草稿字段由 [androidx.compose.runtime.saveable.rememberSaveable] 恢复。
 */
class AgentPawSettingsActivity : ComponentActivity() {

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
                    AgentPawSettingsScreen(onBack = { finish() })
                }
            }
        }
    }
}