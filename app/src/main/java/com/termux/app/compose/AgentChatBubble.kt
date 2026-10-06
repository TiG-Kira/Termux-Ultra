package com.termux.app.compose

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Agent 对话悬浮气泡。
 *
 * 用户离开对话页后，对话仍在后台执行（见 [AgentChatSession]），这个气泡是他在应用内
 * 唯一能感知到「任务还在跑 / 跑完了」的入口。
 *
 * 三个状态：
 *  - [STATE_RUNNING]：执行中，点击回到对话页
 *  - [STATE_DONE]：完成后短暂提示「回答完成」，[DONE_LINGER_MS] 后自动隐藏
 *
 * ## 显示条件（由调用方 [AgentChatBubblePolicy] 判定）
 *
 * 用户要求「应用内部场景显示悬浮窗，应用外部已有通知/LiveUpdate 提示则不显示」——
 * 避免同一个状态既在通知栏又在屏幕上出现两次。
 *
 * 权限：无 `SYSTEM_ALERT_WINDOW` 时静默降级为不显示，逻辑与库内
 * `AgentStopFloatingButton` 保持一致（那边也是库内静默返回 false）。
 */
object AgentChatBubble {

    private const val TAG = "AgentChatBubble"

    /** 执行中 */
    const val STATE_RUNNING = 0

    /** 已完成，提示「回答完成」 */
    const val STATE_DONE = 1

    /** 「回答完成」提示出现后到自动隐藏的时长：2 分钟 */
    const val DONE_LINGER_MS = 2 * 60 * 1000L

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var params: WindowManager.LayoutParams? = null

    private var state = STATE_RUNNING

    /** 完成提示的自动隐藏定时器 */
    private var hideRunnable: Runnable? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    @Volatile
    private var visible = false

    fun isVisible(): Boolean = visible

    /**
     * 显示悬浮气泡。
     *
     * @param onClick 点击气泡时的回调，一般是拉起对话页。传 null 则点击无反应。
     * @return 是否真的显示出来了（无权限 / WindowManager 拿不到时为 false）。
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show(context: Context, onClick: (() -> Unit)?): Boolean {
        // addView 必须落在主线程，而调用点（onBack 回调）也在主线程，
        // 因此这里直接同步返回可见性，不再包一层 post。
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            mainHandler.post { show(context, onClick) }
            return visible
        }
        if (!Settings.canDrawOverlays(context)) return false
        if (visible) return true
        val appContext = context.applicationContext
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return false
        val view = buildView(appContext, onClick) ?: return false

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.END
        // 避开状态栏 + 悬浮按钮所在的顶部区域
        val topInset = appContext.resources.getIdentifier("status_bar_height", "dimen", "android")
        p.y = if (topInset > 0) appContext.resources.getDimensionPixelSize(topInset) + dp(appContext, 56) else dp(appContext, 72)
        p.x = dp(appContext, 8)

        runCatching { wm.addView(view, p) }.onFailure {
            Log.w(TAG, "addView 失败，跳过悬浮气泡", it)
            return false
        }
        windowManager = wm
        bubbleView = view
        params = p
        visible = true
        return true
    }

    /** 隐藏并清理悬浮气泡（同时撤掉待执行的自动隐藏定时器）。 */
    fun hide() {
        runOnMain {
            hideRunnable?.let { mainHandler.removeCallbacks(it) }
            hideRunnable = null
            val wm = windowManager
            val v = bubbleView
            if (wm != null && v != null) {
                runCatching { wm.removeView(v) }.onFailure { Log.w(TAG, "removeView 失败", it) }
            }
            windowManager = null
            bubbleView = null
            params = null
            visible = false
        }
    }

    /**
     * 执行完成 → 切成「回答完成」提示，并在 [DONE_LINGER_MS] 后自动隐藏。
     *
     * 若当前气泡不可见（用户在应用外，已由通知承载），这里不做任何事 ——
     * 不主动弹出气泡，避免违背「应用外有通知就不显示悬浮窗」的约定。
     */
    fun notifyDone() {
        runOnMain {
            if (!visible) return@runOnMain
            state = STATE_DONE
            applyState()
            hideRunnable?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable {
                hideRunnable = null
                hide()
            }
            hideRunnable = r
            mainHandler.postDelayed(r, DONE_LINGER_MS)
        }
    }

    private fun applyState() {
        val done = state == STATE_DONE
        val view = bubbleView ?: return
        view.findViewWithTag<TextView>(TAG_TEXT)?.let { tv ->
            tv.text = labelFor(done)
            tv.setTextColor(if (done) 0xFF1B5E20.toInt() else Color.WHITE)
        }
        (view.background as? GradientDrawable)?.let { bg ->
            bg.setColor(if (done) 0xFFE8F5E9.toInt() else 0xEE2B2B2E.toInt())
        }
    }

    private fun labelFor(done: Boolean): String =
        if (done) "✓ 回答完成" else "⋯ Agent 思考中"

    @SuppressLint("ClickableViewAccessibility")
    private fun buildView(context: Context, onClick: (() -> Unit)?): View? {
        val density = context.resources.displayMetrics.density
        val padH = (16 * density).toInt()
        val padV = (10 * density).toInt()

        val tv = TextView(context).apply {
            text = labelFor(state == STATE_DONE)
            setTextColor(if (state == STATE_DONE) 0xFF1B5E20.toInt() else Color.WHITE)
            textSize = 13f
            setPadding(padH, padV, padH, padV)
            tag = TAG_TEXT
        }

        val container = android.widget.FrameLayout(context).apply {
            addView(tv)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * density
                setColor(0xEE2B2B2E.toInt())
                setStroke((1 * density).toInt().coerceAtLeast(1), 0x33FFFFFF)
            }
        }

        onClick?.let { cb ->
            container.setOnClickListener { cb() }
            container.isClickable = true
        }
        return container
    }

    private const val TAG_TEXT = "bubble_text"

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun runOnMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}