package com.termux.app.activities

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat

import com.termux.app.compose.AboutScreen
import com.termux.app.compose.KiTerminalTheme

/**
 * HyperCeiler-style AboutActivity (Compose + miuix).
 */
class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        setContent {
            KiTerminalTheme {
                AboutScreen(onBack = { finish() })
            }
        }
    }
}
