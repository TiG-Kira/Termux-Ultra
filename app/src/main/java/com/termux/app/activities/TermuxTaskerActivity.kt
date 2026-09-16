package com.termux.app.activities

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.NavigationHelper
import com.termux.app.compose.TermuxTaskerScreen
import com.termux.shared.logger.Logger

class TermuxTaskerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val intent = intent
        val localeBundle = intent?.getBundleExtra("com.twofortyfouram.locale.Intent.EXTRA_BUNDLE")

        var initialExecutable = ""
        var initialArguments = ""
        var initialWorkingDirectory = ""
        var initialInTerminal = false

        if (localeBundle != null) {
            runCatching {
                initialExecutable = localeBundle.getString("executable") ?: ""
                initialArguments = localeBundle.getString("arguments") ?: ""
                initialWorkingDirectory = localeBundle.getString("working_directory") ?: ""
                initialInTerminal = localeBundle.getBoolean("in_terminal")
            }
        }

        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                KiTerminalTheme {
                    TermuxTaskerScreen(
                        onBack = {
                            setResult(RESULT_OK, Intent())
                            finish()
                        }
                    )
                }
            }
        }
    }

    override fun finish() {
        super.finish()
    }
}
