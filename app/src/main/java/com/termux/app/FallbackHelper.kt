package com.termux.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.app.compose.ApiCompat
import com.termux.app.utils.LogManager
import com.termux.app.utils.SnackbarHelper

/**
 * 分级降级助手。
 *
 * 触发路径:
 *   OOBE 崩溃 → 跳过 OOBE → 启动 MainActivity → 如果又崩溃 →
 *     MainActivity 按崩溃位置屏蔽对应页面/功能 → 重建 MainActivity
 *     → 如果仍崩溃或定位失败 → 终端锁定 Fallback → 失败则 -1 退出。
 *
 * 每次启动都会重新尝试正常加载；运行时标记仅进程内有效，App 重启即重置。
 */
object FallbackHelper {

    private const val CHANNEL_ID = "low_android_fallback"
    private const val NOTIFICATION_ID = 9991

    /** SharedPreferences 文件（与 AlertDialogActivity / CrashUtils 保持一致） */
    private const val PREFS_NAME = "app_settings"
    /** 一次性降级 flag：崩溃对话框选择降级模式时写入 → 下次启动消费并进入降级 → 立即清除 */
    private const val KEY_ONE_SHOT_FALLBACK = "one_shot_fallback"

    // 用于避免 OOBE 失败 → 跳 MainActivity 又失败 → 再跳 OOBE（死循环）
    @Volatile
    private var sSkipOobeAttempted: Boolean = false

    // ─── 入口 1：OOBE 渲染失败 ────────────────────────────────────

    /**
     * OobeActivity setContent 抛出异常时调用。
     *
     * 第一优先：直接把 OOBE 状态标记为已通过，然后启动 MainActivity，
     * 让 MainActivity 走主页流程（MainActivity 若再遇到崩溃会自己分级处理）。
     * 只有跳过 OOBE 后启动 MainActivity 依然失败（或当前 App 进程内已尝试过跳过 OOBE），
     * 才进入终端锁定模式。
     */
    fun onOobeRenderFailure(oobeActivity: Activity, throwable: Throwable) {
        if (!sSkipOobeAttempted) {
            sSkipOobeAttempted = true
            try {
                // 1. 标记 OOBE 已通过
                val prefs = oobeActivity.getSharedPreferences(
                    SplashActivity.PREF_OOBE_STATE,
                    Context.MODE_PRIVATE
                )
                prefs.edit()
                    .putBoolean(SplashActivity.KEY_IS_PROVISIONED, true)
                    .apply()

                // 2. 先执行 bootstrap（对应 OOBE 正常流程里的 "下一步"），
                //    完成后再进 MainActivity，避免二次进主页但终端环境未初始化。
                TermuxInstaller.setupBootstrapIfNeeded(oobeActivity) {
                    oobeActivity.runOnUiThread {
                        try {
                            val intent = Intent(oobeActivity, MainActivity::class.java)
                            intent.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            )
                            oobeActivity.startActivity(intent)
                            oobeActivity.finish()
                        } catch (ignored: Throwable) {
                            // 启动 MainActivity 仍然失败 → 终端锁定 Fallback
                            ApiCompat.markMiuixUiFailed()
                            enterTerminalOnlyMode(oobeActivity)
                        }
                    }
                }
                return
            } catch (ignored: Throwable) {
                // 跳过 OOBE 失败，继续走终端锁定 Fallback
            }
        }

        // 跳过 OOBE 已尝试过（或上述步骤失败），终端锁定 Fallback
        ApiCompat.markMiuixUiFailed()
        enterTerminalOnlyMode(oobeActivity)
    }

    // ─── 入口 2：MainActivity 渲染失败（按崩溃位置粒度屏蔽） ────────

    /**
     * MainActivity setContent 抛出异常时调用。
     *
     * 分级策略：
     *  1. 分析 Throwable 的堆栈与消息，按文件名/类名映射到 [ApiCompat.Page] 或 [ApiCompat.Feature]
     *  2. 若能精准定位 → 标记该页面/功能为运行时禁用 → 重启 MainActivity
     *     （下一帧渲染入口会被过滤/变灰，不再触发同样的崩溃）
     *  3. 若无法定位 → 标记 miuix 失败 → 终端锁定 Fallback
     *  4. 如果同一页面已经被屏蔽过仍然崩溃 → 直接终端锁定 Fallback
     */
    fun onMainRenderFailure(mainActivity: Activity, throwable: Throwable) {
        val mappedPage = identifyPageFromThrowable(throwable)
        if (mappedPage != null) {
            // 检测是否死循环：该页已经在被屏蔽状态，说明屏蔽粒度不够/判断错误
            if (ApiCompat.isPageDisabledAtRuntime(mappedPage)) {
                // 再屏蔽也没用，直接终端锁定
                ApiCompat.markMiuixUiFailed()
                enterTerminalOnlyMode(mainActivity)
                return
            }
            // 标记页面运行时禁用（底部导航与滑动会被移除）
            ApiCompat.markPageDisabledAtRuntime(mappedPage)
            // 触发终端页警告卡片显示：hasAnyRuntimeDisabled()
            showLowVersionDisabledHint(mainActivity, mappedPage)
            // 重建 MainActivity：新实例启动时，MainScreen 的 availableTabs 会
            // 基于 ApiCompat.isPageAvailable() 过滤掉已被禁用的页，避免渲染时
            // 再次进入该页的 Screen 而崩溃
            restartMainActivityCleanly(mainActivity)
            return
        }

        // 无法映射到具体页面：弹出崩溃对话框，让用户选择操作
        showCrashDialog(mainActivity, throwable, canRecover = true)
    }

    /**
     * 保留的老入口（与历史调用兼容），等价于 [onMainRenderFailure]。
     */
    fun handleMiuixRenderFailure(activity: Activity, throwable: Throwable) {
        onMainRenderFailure(activity, throwable)
    }

    // ─── 入口 3：终端锁定 Fallback（最后一道防线） ────────────────

    /**
     * 终端锁定：先检查/初始化 bootstrap → 启动 TermuxActivity（作为主页）。
     * 若 bootstrap 初始化或跳转过程中也抛异常 → fatalExit(-1)。
     *
     * @param showNotify 是否发送降级通知，降级模式手动触发时传 false。
     */
    @JvmOverloads
    fun enterTerminalOnlyMode(activity: Activity, showNotify: Boolean = true) {
        if (showNotify) {
            showNotification(
                activity,
                title = activity.getString(R.string.low_android_terminal_mode_title),
                message = activity.getString(R.string.low_android_terminal_mode_message)
            )
        }
        try {
            TermuxInstaller.setupBootstrapIfNeeded(activity) {
                activity.runOnUiThread {
                    launchTerminalActivity(activity)
                }
            }
        } catch (t: Throwable) {
            fatalExit(activity)
        }
    }

    /**
     * 启用降级模式（不发送通知），用于崩溃对话框中选择降级模式。
     * 直接标记 miuix 失败并进入终端锁定模式（跳过通知）。
     */
    fun enableFallbackMode(context: Context) {
        ApiCompat.markMiuixUiFailed()
        if (context is Activity) {
            enterTerminalOnlyMode(context, showNotify = false)
        }
    }

    // ─── 一次性降级 flag（崩溃对话框降级模式 → 下次启动进入降级） ──

    /**
     * 写入一次性降级 flag。崩溃对话框用户选择"降级模式"后调用，
     * 杀进程后下次启动 TermuxApplication / MainActivity 会消费此 flag
     * 自动进入终端锁定降级模式，然后立即清除，不再触发。
     */
    fun setOneShotFallbackFlag(context: Context) {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ONE_SHOT_FALLBACK, true).apply()
        } catch (ignored: Throwable) {}
    }

    /**
     * 检测并清除一次性降级 flag。TermuxApplication.onCreate 中调用。
     * 返回 true 表示用户上次崩溃对话框选了降级模式，应立即进入终端锁定模式。
     */
    fun consumeOneShotFallbackFlag(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val v = prefs.getBoolean(KEY_ONE_SHOT_FALLBACK, false)
            if (v) prefs.edit().remove(KEY_ONE_SHOT_FALLBACK).apply()
            v
        } catch (_: Throwable) { false }
    }

    /**
     * 致命退出：Toast + 通知提示用户后以错误码 -1 退出应用。
     */
    fun fatalExit(activity: Activity) {
        try {
            showNotification(
                activity,
                title = activity.getString(R.string.low_android_warning_title),
                message = activity.getString(R.string.low_android_exit_message)
            )
        } catch (ignored: Throwable) {
        }
        try {
            SnackbarHelper.show(activity, activity.getString(R.string.low_android_exit_message), Snackbar.LENGTH_LONG)
        } catch (ignored: Throwable) {
        }
        try {
            activity.finishAndRemoveTask()
        } catch (ignored: Throwable) {
        }
        System.exit(-1)
    }

    // ─── 内部辅助：崩溃对话框 ──────────────────────────────────────

    /**
     * 显示崩溃对话框，让用户选择查看崩溃报告、查看日志、确认（可恢复时）
     * 或使用降级模式、关闭应用（不可恢复时）
     */
    private fun showCrashDialog(activity: Activity, throwable: Throwable, canRecover: Boolean) {
        try {
            val errorMessage = buildString {
                append(throwable.javaClass.simpleName)
                append(": ")
                append(throwable.message ?: "Unknown error")
            }

            com.termux.app.activities.AlertDialogActivity.startCrashError(
                activity,
                errorMessage,
                canRecover
            )
        } catch (dialogError: Throwable) {
            // 如果连对话框都弹不出来，直接尝试进入终端锁定模式
            try {
                ApiCompat.markMiuixUiFailed()
                enterTerminalOnlyMode(activity)
            } catch (terminalError: Throwable) {
                // 最后的尝试失败，只能退出应用
                fatalExit(activity)
            }
        }
    }

    // ─── 内部辅助：Throwable → Page 映射 ──────────────────────────

    /** 基于堆栈的类名 / 文件名匹配，推断异常来自哪个 Screen。 */
    private fun identifyPageFromThrowable(t: Throwable): ApiCompat.Page? {
        val stackTraceString = buildStackTraceString(t)
        return when {
            // 文件管理页
            matches(stackTraceString, "FileManagerScreenKt", "FileManagerScreen") ->
                ApiCompat.Page.FILES
            // 远程页（VNC / SSH）
            matches(stackTraceString, "RemoteScreenKt", "RemoteScreen") ->
                ApiCompat.Page.REMOTE
            // 资源页
            matches(stackTraceString, "ResourcesScreenKt", "ResourcesScreen") ->
                ApiCompat.Page.RESOURCES
            // 设置页
            matches(stackTraceString, "SettingsScreenKt", "SettingsScreen") ->
                ApiCompat.Page.SETTINGS
            // 终端页会话列表
            matches(stackTraceString, "TerminalListScreenKt", "TerminalListScreen") ->
                ApiCompat.Page.TERMINAL
            // 更上层的 MainScreen 动画/过渡崩溃：不屏蔽页面，避免不必要的降级
            else -> null
        }
    }

    private fun matches(haystack: String, vararg needles: String): Boolean {
        for (n in needles) {
            if (haystack.contains(n)) return true
        }
        return false
    }

    private fun buildStackTraceString(t: Throwable): String {
        val sb = StringBuilder()
        sb.append(t.javaClass.name).append(' ').append(t.message ?: "")
        var cur: Throwable? = t
        while (cur != null) {
            for (e in cur.stackTrace) {
                sb.append('\n').append(e.className).append('.').append(e.methodName)
                    .append('(').append(e.fileName ?: "unknown").append(':').append(e.lineNumber).append(')')
            }
            cur = cur.cause
        }
        return sb.toString()
    }

    // ─── 内部辅助：UI 重建 ────────────────────────────────────────

    private fun restartMainActivityCleanly(activity: Activity) {
        try {
            val intent = Intent(activity, MainActivity::class.java)
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                    or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
            activity.startActivity(intent)
            activity.finish()
            // 确保过渡动画不造成额外的重组
            activity.overridePendingTransition(0, 0)
        } catch (t: Throwable) {
            // 重建 Activity 也失败，直接终端锁定 Fallback
            ApiCompat.markMiuixUiFailed()
            enterTerminalOnlyMode(activity)
        }
    }

    private fun showLowVersionDisabledHint(context: Context, page: ApiCompat.Page) {
        val pageName = when (page) {
            ApiCompat.Page.OVERVIEW -> "总览"
            ApiCompat.Page.TERMINAL -> "终端"
            ApiCompat.Page.FILES -> "文件"
            ApiCompat.Page.REMOTE -> "远程"
            ApiCompat.Page.RESOURCES -> "资源"
            ApiCompat.Page.SETTINGS -> "设置"
        }
        try {
            val msg = context.getString(
                R.string.low_android_runtime_disabled_page,
                pageName,
                ApiCompat.androidReleaseName,
                ApiCompat.sdkInt
            )
            SnackbarHelper.show(context, msg, Snackbar.LENGTH_LONG)
        } catch (ignored: Throwable) {
        }
    }

    // ─── 内部辅助：启动 / 通知 ────────────────────────────────────

    private fun launchTerminalActivity(activity: Activity) {
        try {
            val intent = Intent(activity, TermuxActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            intent.putExtra(TermuxActivity.EXTRA_FALLBACK_MODE, true)
            activity.startActivity(intent)
            activity.finish()
        } catch (t: Throwable) {
            fatalExit(activity)
        }
    }

    private fun showNotification(context: Context, title: String, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.low_android_warning_title),
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationCompat.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            NotificationCompat.Builder(context)
        }

        builder
            .setSmallIcon(R.drawable.ic_warning)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(false)
            .setAutoCancel(true)

        try {
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (ignored: Throwable) {
        }
    }
}
