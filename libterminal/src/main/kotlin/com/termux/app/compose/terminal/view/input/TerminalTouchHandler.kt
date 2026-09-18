package com.termux.app.compose.terminal.view.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.Scroller
import com.termux.app.compose.terminal.engine.TerminalEmulator
import com.termux.app.compose.terminal.view.TerminalView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 终端触摸、鼠标和滚动输入处理器。
 *
 * 持有 [GestureAndScaleRecognizer]、[Scroller] 以及所有鼠标/触控板状态。
 * 触摸事件转发给手势识别器；鼠标事件可被翻译为终端鼠标追踪序列或滚动操作。
 */
internal class TerminalTouchHandler(
    private val view: TerminalView
) {
    val gestureRecognizer: GestureAndScaleRecognizer
    val scroller: Scroller = Scroller(view.context)

    private var scrollRemainder: Float = 0f
    var scaleFactor: Float = 1f

    /** 鼠标滚动状态 — 报告给终端鼠标追踪的坐标。 */
    private var mouseScrollStartX = -1
    private var mouseScrollStartY = -1

    /** 启动当前鼠标滚动序列的触摸事件时间戳。 */
    private var mouseStartDownTime: Long = -1

    init {
        gestureRecognizer = GestureAndScaleRecognizer(
            view.context,
            TerminalGestureListener(view)
        )
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        val emulator = view.mEmulator ?: return true

        if (view.isSelectingText) {
            view.updateFloatingToolbarVisibility(event)
            gestureRecognizer.onTouchEvent(event)
            return true
        }

        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (handleMouseEvent(emulator, event)) return true
        }

        gestureRecognizer.onTouchEvent(event)
        return true
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val emulator = view.mEmulator ?: return false
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        return when (event.action) {
            MotionEvent.ACTION_SCROLL -> {
                val up = event.getAxisValue(MotionEvent.AXIS_VSCROLL) > 0.0f
                doScroll(event, if (up) -3 else 3)
                true
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                // 1003 任意事件模式：无按钮的悬停移动也上报（悬停事件仅经此通道投递）
                if (emulator.isMouseAnyEventTrackingActive) {
                    sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
                    true
                } else {
                    false
                }
            }
            else -> false
        }
    }

    // 供外部重置滚动余数
    fun resetScrollRemainder() {
        scrollRemainder = 0f
    }

    // 接管外部传递进来的平滑滚动事件并处理余数
    fun handleScrollEvent(event: MotionEvent, dy: Float) {
        val distanceY = dy + scrollRemainder
        val deltaRows = (distanceY / view.mRenderer.fontLineSpacing).toInt()
        scrollRemainder = distanceY - deltaRows * view.mRenderer.fontLineSpacing
        doScroll(event, deltaRows)
    }

    /** 执行滚动：移动终端历史、发送按键事件或转发鼠标滚轮事件。 */
    fun doScroll(event: MotionEvent, rowsDown: Int) {
        val emulator = view.mEmulator ?: return
        val up = rowsDown < 0
        val amount = abs(rowsDown)

        // 在锁外计算鼠标坐标和去重状态（均为本类私有字段，无并发竞争）
        val coord = view.getColumnAndRow(event, false)
        var scrollX = coord.col + 1
        var scrollY = coord.row + 1

        synchronized(emulator) {
            for (i in 0..<amount) {
                if (emulator.isMouseTrackingActive) {
                    val button = if (up) TerminalEmulator.MOUSE_WHEELUP_BUTTON else TerminalEmulator.MOUSE_WHEELDOWN_BUTTON
                    if (mouseStartDownTime == event.downTime) {
                        scrollX = mouseScrollStartX
                        scrollY = mouseScrollStartY
                    } else {
                        mouseStartDownTime = event.downTime
                        mouseScrollStartX = scrollX
                        mouseScrollStartY = scrollY
                    }
                    emulator.sendMouseEvent(button, scrollX, scrollY, true)
                } else if (emulator.isAlternateBufferActive) {
                    view.handleKeyCode(if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN, 0)
                } else {
                    view.topRow = min(
                        0,
                        max(
                            -emulator.screen.activeTranscriptRows,
                            view.topRow + (if (up) -1 else 1)
                        )
                    )
                    if (!view.awakenScrollbars()) view.invalidate()
                }
            }
        }
    }

    /** 向终端发送单个鼠标事件代码。 */
    fun sendMouseEventCode(e: MotionEvent, button: Int, pressed: Boolean) {
        val emulator = view.mEmulator ?: return
        val coord = view.getColumnAndRow(e, false)
        var x = coord.col + 1
        var y = coord.row + 1

        synchronized(emulator) {
            if (pressed && (button == TerminalEmulator.MOUSE_WHEELDOWN_BUTTON || button == TerminalEmulator.MOUSE_WHEELUP_BUTTON)) {
                if (mouseStartDownTime == e.downTime) {
                    x = mouseScrollStartX
                    y = mouseScrollStartY
                } else {
                    mouseStartDownTime = e.downTime
                    mouseScrollStartX = x
                    mouseScrollStartY = y
                }
            }
            emulator.sendMouseEvent(button, x, y, pressed)
        }
    }

    private fun handleMouseEvent(emulator: TerminalEmulator, event: MotionEvent): Boolean {
        if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
            if (event.action == MotionEvent.ACTION_DOWN) view.showContextMenu()
            return true
        }

        if (event.isButtonPressed(MotionEvent.BUTTON_TERTIARY)) {
            view.pasteTextFromClipboard()
            return true
        }

        if (emulator.isMouseTrackingActive) {
            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_UP -> sendMouseEventCode(
                    event,
                    TerminalEmulator.MOUSE_LEFT_BUTTON,
                    event.action == MotionEvent.ACTION_DOWN
                )
                MotionEvent.ACTION_MOVE -> {
                    // 1003 任意事件模式无条件上报移动；1002 仅在上报按住左键的拖拽移动
                    if (emulator.isMouseAnyEventTrackingActive ||
                        (emulator.isMouseButtonEventTrackingActive &&
                            event.isButtonPressed(MotionEvent.BUTTON_PRIMARY))
                    ) {
                        sendMouseEventCode(event, TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, true)
                    }
                }
            }
            return false
        }
        return false
    }
}