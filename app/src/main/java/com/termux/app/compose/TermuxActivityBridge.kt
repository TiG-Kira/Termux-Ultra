package com.termux.app.compose

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.TermuxActivity
import com.termux.view.TerminalView

/**
 * Bridge helpers used by [TermuxActivity] (Java) to invoke Compose-only
 * APIs (setContent, KiTerminalTheme, etc.) that are awkward or impossible
 * to call directly from Java.
 */
object TermuxActivityBridge {

    /**
     * Replace the current Activity window content with the Compose-based
     * TerminalDetailScreen, wrapped by KiTerminalTheme (Miuix theme).
     */
    @JvmStatic
    fun setTerminalDetailContent(
        activity: TermuxActivity,
        terminalView: TerminalView,
        onBack: Runnable,
    ) {
        activity.setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                KiTerminalTheme(
                    manageSystemBars = false,
                    content = {
                        TerminalDetailScreen(
                            activity = activity,
                            terminalView = terminalView,
                            onBack = { onBack.run() },
                            overlayMode = false,
                        )
                    }
                )
            }
        }
    }
}
