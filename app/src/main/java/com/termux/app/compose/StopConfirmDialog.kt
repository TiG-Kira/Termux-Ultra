package com.termux.app.compose

import android.content.Context
import android.content.Intent
import com.termux.app.TermuxService
import com.termux.app.activities.AlertDialogActivity
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 停止/退出确认弹窗控制器。
 *
 * 迁移到 WindowDialog：直接启动透明 AlertDialogActivity，
 * 不再需要通过 MainScreen 的 OverlayDialog 宿主。
 */
object StopConfirmDialog {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * 外部请求显示弹窗。
     *
     * 执行步骤：
     *  1. 检测 QEMU / 容器进程
     *  2. 若有危险进程 → 启动 AlertDialogActivity 显示 WindowDialog
     *  3. 若无危险进程 → 直接执行对应操作
     */
    @JvmStatic
    @JvmOverloads
    fun start(context: Context, isQuitApp: Boolean = false) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ProcessDetector.detectAllBlocking(context)
            }

            if (result.qemuCount > 0 || result.containerRunning) {
                AlertDialogActivity.startStopConfirm(
                    context = context,
                    isQuitApp = isQuitApp,
                    qemuCount = result.qemuCount,
                    containerRunning = result.containerRunning
                )
            } else {
                if (isQuitApp) {
                    triggerForceQuit(context)
                } else {
                    triggerForceStop(context)
                }
            }
        }
    }

    /**
     * 直接启动弹窗（已检测到危险进程时调用，例如从 TermuxService）。
     */
    @JvmStatic
    fun startWithDetection(
        context: Context,
        isQuitApp: Boolean,
        qemuCount: Int,
        containerRunning: Boolean
    ) {
        if (qemuCount > 0 || containerRunning) {
            AlertDialogActivity.startStopConfirm(
                context = context,
                isQuitApp = isQuitApp,
                qemuCount = qemuCount,
                containerRunning = containerRunning
            )
        } else {
            if (isQuitApp) {
                triggerForceQuit(context)
            } else {
                triggerForceStop(context)
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
        } catch (_: Exception) {}
    }

    /**
     * Send ACTION_QUIT_APP_FORCE intent to TermuxService
     */
    private fun triggerForceQuit(context: Context) {
        try {
            val intent = Intent(context, TermuxService::class.java)
            intent.action = TermuxConstants.TERMUX_APP.TERMUX_SERVICE.ACTION_QUIT_APP_FORCE
            context.startService(intent)
        } catch (_: Exception) {}
    }
}