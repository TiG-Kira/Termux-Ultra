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
import com.termux.app.x11.X11SettingsScreen

/**
 * Termux:X11 设置页宿主（独立 Activity）。
 *
 * 取代 lorie AAR 的 LoriePreferences（英文 XML 偏好页）作为主设置入口：UI 为 miuix 化的
 * [X11SettingsScreen]，文案全中文并跟随宿主语言；设置项仍读写 lorie 的同一份
 * SharedPreferences，改动后广播 ACTION_PREFERENCES_CHANGED 让 :x11 进程即时应用。
 */
class X11SettingsActivity : ComponentActivity() {

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
                    X11SettingsScreen(onBack = { finish() })
                }
            }
        }
    }
}
