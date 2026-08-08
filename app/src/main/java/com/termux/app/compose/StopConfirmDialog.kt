package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.termux.app.TermuxService
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/**
 * Helper for MainActivity: when activity is launched with EXTRA_TRIGGER_STOP_SERVICE
 * or EXTRA_TRIGGER_QUIT_APP from the notification buttons, detect QEMU/proot processes
 * and show a data-loss warning OverlayDialog if dangerous processes are running.
 *
 * For stop service mode: user confirms → send ACTION_STOP_SERVICE_FORCE (kill sessions only).
 * For quit app mode: user confirms → send ACTION_QUIT_APP_FORCE (exit entire app).
 */
object StopConfirmDialog {

    /**
     * Entry point (called from MainActivity).
     * @param activity the MainActivity instance (must be ComponentActivity)
     * @param isQuitApp true for "关闭程序" mode (exit app after confirm),
     *                  false for "结束会话" mode (kill sessions only)
     */
    @JvmStatic
    @JvmOverloads
    fun start(activity: ComponentActivity, isQuitApp: Boolean = false) {
        activity.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                ProcessDetector.detectAllBlocking(activity)
            }

            if (result.qemuCount > 0 || result.containerRunning) {
                showOverlayDialog(activity, result.qemuCount, result.containerRunning, isQuitApp)
            } else {
                // No VM / container running => execute action immediately without dialog
                if (isQuitApp) {
                    triggerForceQuit(activity)
                } else {
                    triggerForceStop(activity)
                }
            }
        }
    }

    /**
     * Attach a ComposeView overlay to the activity and show Miuix OverlayDialog.
     */
    private fun showOverlayDialog(
        activity: ComponentActivity,
        qemuCount: Int,
        containerRunning: Boolean,
        isQuitApp: Boolean
    ) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val composeView = ComposeView(activity).apply {
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            // Keep this overlay transparent so the MainActivity content below is visible.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
        root.addView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        fun detachOverlay() {
            val parent = composeView.parent as? ViewGroup ?: return
            parent.removeView(composeView)
        }

        composeView.setContent {
            KiTerminalTheme {
                // Full-screen transparent holder
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                ) {
                    var showDialog by remember { mutableStateOf(true) }
                    if (showDialog) {
                        OverlayDialog(
                            show = showDialog,
                            onDismissRequest = {
                                showDialog = false
                                detachOverlay()
                            },
                            title = if (isQuitApp) "关闭程序" else "警告",
                            summary = buildDialogSummary(qemuCount, containerRunning),
                            content = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                                ) {
                                    TextButton(
                                        text = "否",
                                        onClick = {
                                            showDialog = false
                                            detachOverlay()
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        text = "是",
                                        onClick = {
                                            showDialog = false
                                            detachOverlay()
                                            if (isQuitApp) {
                                                triggerForceQuit(activity)
                                            } else {
                                                triggerForceStop(activity)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.textButtonColorsPrimary()
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun buildDialogSummary(qemuCount: Int, containerRunning: Boolean): String {
        return buildString {
            append("如果这么做，您可能丢失虚拟机/容器内的数据，继续吗？")
            if (qemuCount > 0 || containerRunning) {
                append("\n\n当前检测到：")
                if (qemuCount > 0) append("\n· 运行中的虚拟机：").append(qemuCount).append(" 台")
                if (containerRunning) append("\n· proot 容器正在运行")
            }
        }
    }

    /**
     * Send ACTION_STOP_SERVICE_FORCE intent to TermuxService, which kills sessions without
     * re-running the data-loss detection.
     */
    private fun triggerForceStop(context: Context) {
        try {
            val intent = Intent(context, TermuxService::class.java)
            intent.action = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_STOP_SERVICE_FORCE
            context.startService(intent)
        } catch (e: Exception) {
            android.util.Log.e("StopConfirmDialog", "Failed to start TermuxService for force-stop", e)
        }
    }

    /**
     * Send ACTION_QUIT_APP_FORCE intent to TermuxService, which kills sessions and
     * terminates the entire app process.
     */
    private fun triggerForceQuit(context: Context) {
        try {
            val intent = Intent(context, TermuxService::class.java)
            intent.action = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_QUIT_APP_FORCE
            context.startService(intent)
        } catch (e: Exception) {
            android.util.Log.e("StopConfirmDialog", "Failed to start TermuxService for force-quit", e)
        }
    }
}
