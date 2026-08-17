package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.termux.R
import com.termux.app.TermuxService
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 全局单例的停止/退出确认弹窗控制器。
 *
 * 不再使用 addView overlay 方式（会导致白屏），而是通过 [MainScreen] 内部的
 * 状态驱动弹窗显示。外部调用 [requestShow]，UI 观察 [dialogState] 并在 Compose 树内渲染。
 */
object StopConfirmDialog {

    /** 弹窗状态：null = 不显示，非空 = 显示弹窗 */
    data class DialogState(
        val qemuCount: Int,
        val containerRunning: Boolean,
        val isQuitApp: Boolean
    )

    private val _dialogState = MutableStateFlow<DialogState?>(null)
    val dialogState: StateFlow<DialogState?> = _dialogState.asStateFlow()

    /**
     * 外部请求显示弹窗（由 MainActivity.onStart 调用）。
     *
     * 执行步骤：
     *  1. 等待 Compose 首帧渲染完成
     *  2. 检测 QEMU / 容器进程
     *  3. 若有危险进程 → 设置 dialogState → MainScreen 内显示弹窗
     *  4. 若无危险进程 → 直接执行对应操作
     */
    @JvmStatic
    @JvmOverloads
    fun start(context: Context, isQuitApp: Boolean = false) {
        val activity = context as? androidx.activity.ComponentActivity
            ?: return

        activity.lifecycleScope.launch {
            // 等待 Compose 首帧渲染完成
            val frameClock = android.os.Looper.getMainLooper()
            val handler = android.os.Handler(frameClock)
            withContext(Dispatchers.Main) {
                // 等两帧确保主页面已完全绘制
                handler.post { /* no-op */ }
                handler.post { /* no-op */ }
            }

            val result = withContext(Dispatchers.IO) {
                ProcessDetector.detectAllBlocking(activity)
            }

            if (result.qemuCount > 0 || result.containerRunning) {
                // 通过 StateFlow 通知 MainScreen 显示弹窗
                _dialogState.value = DialogState(
                    qemuCount = result.qemuCount,
                    containerRunning = result.containerRunning,
                    isQuitApp = isQuitApp
                )
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

    /** 用户点击"是"——确认执行危险操作 */
    fun confirm(context: Context, state: DialogState) {
        _dialogState.value = null // 先关闭弹窗
        if (state.isQuitApp) {
            triggerForceQuit(context)
        } else {
            triggerForceStop(context)
        }
    }

    /** 用户点击"否"或弹窗被关闭 */
    fun dismiss() {
        _dialogState.value = null
    }

    /** 构建弹窗摘要文本 */
    fun buildDialogSummary(qemuCount: Int, containerRunning: Boolean): String {
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
     * Send ACTION_STOP_SERVICE_FORCE intent to TermuxService
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
     * Send ACTION_QUIT_APP_FORCE intent to TermuxService
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

/**
 * 在 MainScreen 内部观察 [StopConfirmDialog.dialogState] 并渲染弹窗。
 *
 * 此 Composable 必须放在 MainScreen 的顶层（Scaffold 的 content 内部），
 * 作为主 Compose 树的一部分，而不是 overlay view，因此不会阻塞或遮挡主页面。
 */
@Composable
fun StopConfirmDialogHost(snackbarHostState: top.yukonga.miuix.kmp.basic.SnackbarHostState? = null) {
    val context = LocalContext.current
    val dialogState by StopConfirmDialog.dialogState.collectAsState()

    val thirdPartyBlocked = rememberThirdPartyBlocked(context)
    val snackbarScope = rememberCoroutineScope()
    val showBlockedMessage: () -> Unit = {
        val msg = context.getString(R.string.accessibility_guard_blocked_toast)
        if (snackbarHostState != null) {
            snackbarScope.launch {
                snackbarHostState.showSnackbar(
                    message = msg,
                    duration = top.yukonga.miuix.kmp.basic.SnackbarDuration.Long
                )
            }
        } else {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    val activity = context as? ComponentActivity
    val window = activity?.window

    LaunchedEffect(dialogState != null) {
        if (dialogState != null) {
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    dialogState?.let { state ->
        OverlayDialog(
            show = true,
            onDismissRequest = { StopConfirmDialog.dismiss() },
            title = if (state.isQuitApp) "关闭程序" else "警告",
            summary = StopConfirmDialog.buildDialogSummary(state.qemuCount, state.containerRunning),
            content = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .physicalTouchDetector()
                        .accessibilityGuard(thirdPartyBlocked),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    TextButton(
                        text = "否",
                        onClick = guardedOnClick(context, thirdPartyBlocked, showBlockedMessage) {
                            StopConfirmDialog.dismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = "是",
                        onClick = guardedOnClick(context, thirdPartyBlocked, showBlockedMessage) {
                            StopConfirmDialog.confirm(context, state)
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary()
                    )
                }
            }
        )
    }
}
