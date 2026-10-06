package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 悬浮气泡的显示策略 —— 「什么时候该让用户看见这个气泡」。
 *
 * 规则来自需求：
 *  - 离开对话页时，**应用内部场景**显示悬浮窗（用户还在应用里，只是换了页面，
 *    这时他能看见屏幕，悬浮窗是唯一线索）
 *  - **应用外部已有通知或 LiveUpdate 提示**时不显示（避免通知 + 悬浮窗双重提示）
 *
 * ## 怎么判断「应用在前台」
 *
 * 用 ActivityLifecycleCallbacks 统计前台 Activity 数，而不是判断 AiTermuxActivity
 * 自身状态：用户从对话页跳到别的应用页时，对话页已 stop 但应用整体仍在前台 ——
 * 这正是「应用内部场景」，需要悬浮窗。而退到桌面/切到别的应用时前台数为 0，
 * 若此时通知已承载就交给通知。
 */
object AgentChatBubblePolicy {

    private const val TAG = "AgentChatBubblePolicy"

    /** 前台 Activity 计数（不含对话页自身，见 [isInsideApp]） */
    private var resumedCount = 0

    /** 对话页当前是否在前台。用于把「自己」从计数里排除。 */
    private var chatPageResumed = false

    private var registered = false

    private val callbacks = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: android.app.Activity) {
            if (activity is com.termux.app.activities.AiTermuxActivity) {
                chatPageResumed = true
            } else {
                resumedCount++
            }
        }

        override fun onActivityPaused(activity: android.app.Activity) {
            if (activity is com.termux.app.activities.AiTermuxActivity) {
                chatPageResumed = false
            } else {
                resumedCount--
                if (resumedCount < 0) resumedCount = 0
            }
        }

        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
        override fun onActivityStarted(activity: android.app.Activity) {}
        override fun onActivityStopped(activity: android.app.Activity) {}
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: android.app.Activity) {}
    }

    /** 注册一次即可，进程内有效。 */
    @JvmStatic
    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext as? android.app.Application ?: return
        app.registerActivityLifecycleCallbacks(callbacks)
        registered = true
    }

    /**
     * 用户是否仍停留在应用内部。
     *
     * 对话页自己仍在屏上时（resumed）也算应用内 —— 此时不该弹气泡，因为用户正看着对话。
     */
    @JvmStatic
    fun isInsideApp(): Boolean = chatPageResumed || resumedCount > 0

    /**
     * Agent 执行状态是否已经由通知栏 / LiveUpdate 承载。
     *
     * 与 [com.termux.app.TermuxService.buildNotification] 的档 2 判定保持一致：
     * Agent 通知开关打开、且当前没有更高优先级的包操作占用通知时，才算「通知已承载」。
     * 开关关掉时前台服务只剩最小化通知，不足以承载 Agent 语义 → 需要悬浮窗兜底。
     */
    @JvmStatic
    fun hasExternalNotification(context: Context): Boolean {
        if (!NotificationPrefs.isAgentEnabled(context)) return false
        // 包操作优先级高于 Agent，此时通知展示的是包操作，Agent 没有自己的提示
        if (LiveUpdateState.hasPkg()) return false
        return LiveUpdateState.hasAgent()
    }

    /**
     * 离开对话页时调用。返回是否真的显示了气泡。
     */
    @JvmStatic
    fun onLeftChatPage(context: Context, onClick: (() -> Unit)?): Boolean {
        register(context)
        if (!AgentChatSession.isExecuting) return false
        // 决定性条件：应用外且通知已承载 → 不显示，避免双重提示
        if (!isInsideApp() && hasExternalNotification(context)) return false
        val shown = AgentChatBubble.show(context, onClick)
        Log.d(TAG, "离开对话页：insideApp=${isInsideApp()} notify=${hasExternalNotification(context)} bubble=$shown")
        return shown
    }

    /** 对话执行完成 → 让气泡切到「回答完成」并计时自动隐藏。 */
    @JvmStatic
    fun onExecutionFinished() {
        AgentChatBubble.notifyDone()
    }

    /** 用户回到对话页 → 气泡让位给真实页面，完成提示也就没有意义了。 */
    @JvmStatic
    fun onReturnedToChatPage() {
        AgentChatBubble.hide()
        AgentChatSession.consumeCompletionNotice()
    }

    /**
     * 拉起对话页 —— 气泡点击时用。
     *
     * 对话 Activity 未 exported，无法用 `adb shell am start` 从外部拉起；
     * 这里从应用自身上下文启动是合法的。`FLAG_ACTIVITY_SINGLE_TOP + CLEAR_TOP`
     * 保证复用已有实例而不是叠一个新页面。
     */
    @JvmStatic
    fun launchChatPage(context: Context) {
        val intent = Intent(context, com.termux.app.activities.AiTermuxActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "拉起对话页失败", it) }
    }
}