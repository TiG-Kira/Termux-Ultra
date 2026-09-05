package com.termux.app.compose.terminal.view.textselection

import android.view.MotionEvent
import android.view.ViewTreeObserver.OnTouchModeChangeListener
import com.termux.app.compose.terminal.view.TerminalView

/**
 * 文本选择光标控制器接口。
 *
 * 仅在 [TerminalView] 内部使用，管理选择手柄的显示、隐藏和位置更新。
 */
internal interface CursorController : OnTouchModeChangeListener {
    /**
     * 显示光标手柄。
     * @see hide
     */
    fun show(event: MotionEvent)

    /**
     * 隐藏光标手柄。
     * @see show
     */
    fun hide(): Boolean

    /** 渲染光标手柄。 */
    fun render()

    /** 更新光标手柄位置。 */
    fun updatePosition(handle: TextSelectionHandleView, x: Int, y: Int)

    /**
     * 由 [TerminalView.onTouchEvent] 调用，给予光标激活和显示的机会。
     *
     * @param event 触摸事件
     */
    fun onTouchEvent(event: MotionEvent): Boolean

    /** 光标是否处于激活状态。 */
    val isActive: Boolean
}
