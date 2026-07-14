package com.termux.app.activities

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.termux.app.compose.AboutScreen
import com.termux.app.compose.KiTerminalTheme

class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KiTerminalTheme {
                AboutScreen(onBack = { finish() })
            }
        }
    }
}