package com.termux.app.compose.terminal.view

import com.termux.app.compose.terminal.engine.TerminalEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 通用的终端闪烁动画管理器（可用于光标闪烁、文本闪烁等）。
 *
 * 结合 [poke] 方法，可以在用户输入文字时打断闪烁周期，保持常亮并重新计时。
 */
internal class TerminalBlinker(
    private val blinkerName: String,
    private val scope: CoroutineScope,
    private val onInvalidate: () -> Unit,
    private val shouldStart: (TerminalEmulator) -> Boolean = { true },
    private val setBlinkingEnabled: (TerminalEmulator, Boolean) -> Unit,
    private val setBlinkState: (TerminalEmulator, Boolean) -> Unit,
    blinkRate: Long = 500L
) {
    private var job: Job? = null
    
    // 挂起通道，用于接收输入中断信号。CONFLATED 保证如果瞬间触发多次，只保留最后一次信号。
    private val resetChannel = Channel<Unit>(Channel.CONFLATED)

    var blinkRate: Long = blinkRate
        set(value) {
            field = when(value) {
                0L -> 0L
                else -> value.coerceIn(100L, 2000L)
            }
        }

    fun start(emulator: TerminalEmulator) {
        stop()

        if (blinkRate == 0L || !shouldStart(emulator)) {
            return
        }

        // 启动前清空旧的信号积压
        while (resetChannel.tryReceive().isSuccess) {}

        job = scope.launch {
            try {
                setBlinkingEnabled(emulator, true)
                var isVisible = true
                while (isActive) {
                    setBlinkState(emulator, isVisible)
                    onInvalidate()

                    // 挂起等待：如果在 rate 时间内没有收到信号，将返回 null
                    val interrupted = withTimeoutOrNull(blinkRate) {
                        resetChannel.receive()
                    }

                    if (interrupted != null) {
                        // 被打断（即用户正在输入）：强制光标进入可见状态，并立刻进入下一轮 while 循环重新计时
                        isVisible = true
                    } else {
                        // 正常超时：闪烁状态翻转
                        isVisible = !isVisible
                    }
                }
            } finally {
                // 确保协程被取消时关闭闪烁状态
                setBlinkingEnabled(emulator, false)
            }
        }
    }

    fun stop() {
        job?.let {
            it.cancel()
            job = null
        }
    }

    /**
     * 唤醒 / 重置闪烁器。
     * 在用户输入文字或执行操作时调用，可使光标保持常亮并重新开始计时。
     */
    fun poke() {
        resetChannel.trySend(Unit)
    }
}