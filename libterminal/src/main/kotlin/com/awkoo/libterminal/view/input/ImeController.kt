package com.awkoo.libterminal.view.input

import android.app.Activity
import android.content.ContextWrapper
import android.view.View
import android.view.Window
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * IME（输入法）显示控制器。
 *
 * 负责定位宿主 Activity 窗口并切换软键盘可见性，
 * 从 View 的窗口控制职责中剥离出来单独管理。
 */
internal class ImeController(private val view: View) {

    /** 定位宿主 Activity 窗口，无窗口时返回 null。 */
    private fun activityWindow(): Window? = generateSequence(view.context) {
        (it as? ContextWrapper)?.baseContext
    }
        .filterIsInstance<Activity>()
        .firstOrNull()?.window

    /**
     * 显示或隐藏软键盘。
     *
     * [show] 为 null 时切换当前可见性；宿主无 Activity 窗口时静默忽略。
     */
    fun toggleIme(show: Boolean? = null) {
        view.requestFocus()

        val window = activityWindow() ?: return

        val controller = WindowCompat.getInsetsController(window, view)
        val imeType = WindowInsetsCompat.Type.ime()

        val shouldShow = show ?: (ViewCompat.getRootWindowInsets(view)?.isVisible(imeType) != true)
        if (shouldShow) {
            controller.show(imeType)
        } else {
            controller.hide(imeType)
        }
    }

    /**
     * 隐藏软键盘，不额外请求焦点。
     *
     * 供覆盖层（如设置页）打开时收起键盘，
     * 避免 [toggleIme] 的 [View.requestFocus] 触发输入法重新弹起。
     */
    fun hideIme() {
        val window = activityWindow() ?: return
        WindowCompat.getInsetsController(window, view).hide(WindowInsetsCompat.Type.ime())
    }
}
