package com.termux.app.utils

import android.content.Context
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 统一 Snackbar 工具类，用于替代项目中的 Toast 调用。
 * 支持 Compose (SnackbarHostState) 和传统 View 系统 (Material Snackbar)。
 */
object SnackbarHelper {

    /**
     * 获取导航栏/系统栏底部 insets。
     */
    private fun getBottomInsets(activity: android.app.Activity): Int {
        return try {
            val decorView = activity.window.decorView
            val rootInsets = ViewCompat.getRootWindowInsets(decorView)
            if (rootInsets != null) {
                val navBars = rootInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
                val systemBars = rootInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                val ime = rootInsets.getInsets(WindowInsetsCompat.Type.ime())
                maxOf(navBars.bottom, systemBars.bottom, ime.bottom)
            } else {
                // Fallback: 手动获取导航栏高度
                val resourceId = activity.resources.getIdentifier("navigation_bar_height", "dimen", "android")
                if (resourceId > 0) activity.resources.getDimensionPixelSize(resourceId) else 0
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * 在传统 View 系统中显示 Snackbar（非 Compose 环境 fallback）。
     * Snackbar 显示在导航栏之上 5dp 位置。
     * @param context Context
     * @param text 要显示的文本
     * @param duration 持续时间（Snackbar.LENGTH_SHORT / LENGTH_LONG / LENGTH_INDEFINITE）
     * @param anchor 可选的 anchor View，用于定位 Snackbar
     */
    fun show(
        context: Context,
        text: CharSequence,
        duration: Int = Snackbar.LENGTH_SHORT,
        anchor: View? = null
    ) {
        try {
            val activity = context as? android.app.Activity
            if (activity == null) return

            val rootView = activity.findViewById<View>(android.R.id.content)
            val snackbar = Snackbar.make(rootView, text, duration)

            if (anchor != null) {
                snackbar.anchorView = anchor
            }

            // 计算目标底部间距：导航栏高度 + 5dp
            val density = context.resources.displayMetrics.density
            val extraBottomPx = (5f * density + 0.5f).toInt()

            // 在 Snackbar 显示前设置布局参数
            snackbar.view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    // 视图附加到窗口后获取正确的 insets
                    val insetsBottom = getBottomInsets(activity)
                    val targetMargin = insetsBottom + extraBottomPx
                    
                    val params = v.layoutParams
                    if (params is ViewGroup.MarginLayoutParams) {
                        params.bottomMargin = targetMargin
                        v.layoutParams = params
                    }
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })

            // 同时使用 OnLayoutChangeListener 确保后续布局变化也调整
            snackbar.view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val insetsBottom = getBottomInsets(activity)
                    val targetMargin = insetsBottom + extraBottomPx
                    
                    val params = snackbar.view.layoutParams
                    if (params is ViewGroup.MarginLayoutParams) {
                        if (params.bottomMargin != targetMargin) {
                            params.bottomMargin = targetMargin
                            snackbar.view.layoutParams = params
                        }
                    }
                    snackbar.view.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            })

            snackbar.show()
        } catch (_: Exception) {
            // Fallback: 如果 Snackbar 失败，静默忽略
        }
    }

    /**
     * 在 Compose 环境中通过 SnackbarHostState 显示 Snackbar。
     * @param scope CoroutineScope
     * @param snackbarHostState SnackbarHostState
     * @param text 要显示的文本
     * @param durationMillis 持续时间（毫秒，转换为 SnackbarDuration）
     */
    fun show(
        scope: CoroutineScope,
        snackbarHostState: androidx.compose.material3.SnackbarHostState,
        text: String,
        durationMillis: Long = 2000L
    ) {
        scope.launch(Dispatchers.Main) {
            try {
                val duration = if (durationMillis >= 5000L) {
                    androidx.compose.material3.SnackbarDuration.Long
                } else {
                    androidx.compose.material3.SnackbarDuration.Short
                }
                snackbarHostState.showSnackbar(
                    message = text,
                    duration = duration
                )
            } catch (_: Exception) {
                // Snackbar 显示失败时静默忽略
            }
        }
    }

    /**
     * 将 duration 布尔值（原 showToast 的参数）转换为 Snackbar duration。
     * @param isLongDuration true = LENGTH_LONG, false = LENGTH_SHORT
     */
    fun getDuration(isLongDuration: Boolean): Int {
        return if (isLongDuration) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
    }
}
