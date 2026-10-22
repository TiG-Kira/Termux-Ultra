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
import com.termux.app.compose.NoticeBoardScreen

/**
 * 公告板页宿主（独立 Activity）。
 *
 * 完整渲染仓库根目录 NOTICE.md（GitHub Markdown，与系统更新 release 说明同款渲染），
 * 内容经 [com.termux.app.compose.NoticeBoardRepository] 从 GitHub raw 拉取。
 */
class NoticeBoardActivity : ComponentActivity() {

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
                    NoticeBoardScreen(onBack = { finish() })
                }
            }
        }
    }
}
