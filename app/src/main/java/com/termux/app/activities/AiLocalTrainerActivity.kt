package com.termux.app.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.termux.app.compose.AiLocalTrainerScreen

class AiLocalTrainerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AiLocalTrainerScreen(onBack = { finish() })
        }
    }
}
