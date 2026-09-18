package com.termux.app.compose.terminal.view.input

import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.app.compose.terminal.engine.TerminalEmulator
import com.termux.app.compose.terminal.view.TerminalView

/**
 * 终端触摸手势处理器。
 *
 * 处理单击（显示 IME）、双击（保留用于缩放）、长按（文本选择）、
 * 滚动（历史导航或鼠标事件转发）、双指缩放（字号）和惯性滚动。
 */
internal class TerminalGestureListener(
    private val view: TerminalView
) : GestureAndScaleRecognizer.Listener {

    var scrolledWithFinger: Boolean = false

    override fun onUp(e: MotionEvent): Boolean {
        // 重置滚动状态
        view.touchHandler.resetScrollRemainder()

        val emulator = view.mEmulator
        if (emulator != null &&
            emulator.isMouseTrackingActive &&
            !e.isFromSource(InputDevice.SOURCE_MOUSE) &&
            !view.isSelectingText &&
            !scrolledWithFinger
        ) {
            // 鼠标追踪激活时快速处理事件，不等待双击检测
            view.touchHandler.sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON, true)
            view.touchHandler.sendMouseEventCode(e, TerminalEmulator.MOUSE_LEFT_BUTTON, false)
            return true
        }
        scrolledWithFinger = false
        return false
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val emulator = view.mEmulator ?: return true

        if (view.isSelectingText) {
            view.stopTextSelectionMode()
            return true
        }

        view.requestFocus()

        if (!emulator.isMouseTrackingActive && !e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val imm = view.context.getSystemService(InputMethodManager::class.java)
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }

        return true
    }

    override fun onScroll(e: MotionEvent, dx: Float, dy: Float): Boolean {
        if (view.mEmulator == null) return true
        // 鼠标移动事件已由 handleMouseEvent 统一上报追踪序列，手势层不再重复处理
        if (!e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            scrolledWithFinger = true
            view.touchHandler.handleScrollEvent(e, dy)
        }
        return true
    }

    override fun onScale(focusX: Float, focusY: Float, scale: Float): Boolean {
        if (view.mEmulator == null || view.isSelectingText) return true
        view.touchHandler.scaleFactor *= scale
        if (view.touchHandler.scaleFactor !in 0.9f..1.1f) {
            val increase = view.touchHandler.scaleFactor > 1f
            view.textSize += if (increase) 1 else -1
            view.touchHandler.scaleFactor = 1.0f
        }
        return true
    }

    override fun onFling(e: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        val emulator = view.mEmulator ?: return true
        val scroller = view.touchHandler.scroller
        // 上一次惯性滚动未结束时不启动新的滚动
        if (!scroller.isFinished) return true

        val mouseTrackingAtStartOfFling = emulator.isMouseTrackingActive
        val flingScale = 0.25f
        if (mouseTrackingAtStartOfFling) {
            scroller.fling(0, 0, 0, -(velocityY * flingScale).toInt(), 0, 0, -emulator.mRows / 2, emulator.mRows / 2)
        } else {
            scroller.fling(0, view.topRow, 0, -(velocityY * flingScale).toInt(), 0, 0, -emulator.screen.activeTranscriptRows, 0)
        }

        val eventDownTime = e.downTime
        view.post(object : Runnable {
            private var mLastY = 0

            override fun run() {
                if (mouseTrackingAtStartOfFling != emulator.isMouseTrackingActive) {
                    scroller.abortAnimation()
                    return
                }
                if (scroller.isFinished) return
                val more = scroller.computeScrollOffset()
                val newY = scroller.currY
                val diff = if (mouseTrackingAtStartOfFling) (newY - mLastY) else (newY - view.topRow)
                val syntheticEvent = MotionEvent.obtain(
                    eventDownTime, eventDownTime,
                    MotionEvent.ACTION_DOWN, 0f, 0f, 0
                )
                view.touchHandler.doScroll(syntheticEvent, diff)
                syntheticEvent.recycle()
                mLastY = newY
                if (more) view.post(this)
            }
        })

        return true
    }

    override fun onDown(x: Float, y: Float): Boolean {
        return false
    }

    override fun onDoubleTap(e: MotionEvent): Boolean {
        val emulator = view.mEmulator ?: return false

        // 如果处于鼠标追踪模式（如 Vim），双击屏幕强制唤起软键盘
        if (emulator.isMouseTrackingActive) {
            view.requestFocus()
            val imm = view.context.getSystemService(InputMethodManager::class.java)
            imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            return true
        }
        return false
    }

    override fun onLongPress(e: MotionEvent) {
        if (view.touchHandler.gestureRecognizer.isInProgress) return
        if (!view.isSelectingText) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            view.startTextSelectionMode(e)
        }
    }
}