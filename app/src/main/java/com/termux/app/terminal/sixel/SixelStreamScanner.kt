// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT
//
// PTY 输出流上的 sixel 旁路扫描器（Issue #401）。
//
// libterminal 的仿真器对未知 DCS（Device Control String）静默吞掉不打印
// （见 libterminal DeviceControlHandler），但也不产生任何图像。这里在应用侧
// 把同一份字节流再做一遍轻量状态扫描：认出 `ESC P ... q ... ESC \`（sixel DCS）
// 后把负载交给回调。字节一律原样透传，扫描器抛任何异常都被吞掉，绝不影响
// 终端数据通路。

package com.termux.app.terminal.sixel

/** 单段 sixel 负载硬上限：超过直接放弃本段（防御内存失控）。 */
private const val MAX_SIXEL_PAYLOAD = 16 * 1024 * 1024

class SixelStreamScanner(private val onSixelPayload: (ByteArray) -> Unit) {

    private enum class State {
        /** 普通数据：只盯 ESC P。 */
        GROUND,

        /** 已见 ESC P：收集参数头，等 final 'q' 或其他终结。 */
        DCS_HEADER,

        /** 已确认 sixel：收集负载直到 ST / CAN / SUB。 */
        SIXEL_PAYLOAD,

        /** 非 sixel 的 DCS：跳到 ST 为止，避免把 DCS 体误当普通数据。 */
        DCS_SKIP,
    }

    private var state = State.GROUND

    /** ESC 后暂存：true 表示上一个字节是 ESC。 */
    private var afterEsc = false

    /**
     * DCS 头部是否出现过中间字节（0x20-0x2F）。
     * sixel 的 final 'q' 不允许中间字节——`DCS $q`（VT100 属性查询）这类
     * 序列的终结符同样是 'q'，必须靠这个标志区分开。
     */
    private var sawIntermediate = false

    private var buffer = ByteArray(4096)
    private var length = 0

    /** 逐字节喂入；在 PTY 读线程上调用。 */
    fun tap(bytes: ByteArray, offset: Int, count: Int) {
        try {
            for (i in offset until offset + count) {
                feed(bytes[i].toInt() and 0xFF)
            }
        } catch (_: Throwable) {
            // 扫描器永不破坏数据通路：任何异常都直接复位状态
            reset()
        }
    }

    private fun reset() {
        state = State.GROUND
        afterEsc = false
        sawIntermediate = false
        length = 0
    }

    private fun append(b: Int): Boolean {
        if (length >= MAX_SIXEL_PAYLOAD) return false
        if (length == buffer.size) {
            val newSize = minOf(buffer.size * 2, MAX_SIXEL_PAYLOAD)
            if (newSize <= buffer.size) return false
            buffer = buffer.copyOf(newSize)
        }
        buffer[length++] = b.toByte()
        return true
    }

    private fun feed(b: Int) {
        // ESC 是所有终结符/起始符的入口，单独处理一次
        if (afterEsc) {
            if (b == 0x1B) {
                // 连续 ESC：前一个 ESC 被悬空丢弃，以最后一个为准
                return
            }
            afterEsc = false
            when (state) {
                State.GROUND -> {
                    if (b == 'P'.code) {
                        state = State.DCS_HEADER
                        sawIntermediate = false
                        length = 0
                    }
                    return
                }
                else -> {
                    if (b == '\\'.code) {
                        // ST：DCS 结束
                        finishSequence()
                        return
                    }
                    // ESC 后跟非 '\\'：按 xterm 口径视为异常终结
                    reset()
                    return
                }
            }
        }

        when (b) {
            0x1B -> afterEsc = true
            0x18, 0x1A -> reset() // CAN / SUB：终止当前序列
            else -> when (state) {
                State.GROUND -> {} // 普通字节只透传
                State.DCS_HEADER -> {
                    if (!append(b)) {
                        reset()
                        return
                    }
                    when {
                        // final 'q' 且无中间字节才是 sixel；`DCS $q` 等带中间字节的
                        // 序列终结符同样是 'q'，转跳过模式
                        b == 'q'.code && !sawIntermediate -> state = State.SIXEL_PAYLOAD
                        // DCS final byte（含带中间字节的 'q'）：转为跳过模式直到 ST
                        b in 0x40..0x7E -> state = State.DCS_SKIP
                        b in 0x20..0x2F -> sawIntermediate = true
                        b < 0x20 -> reset()
                    }
                }
                State.SIXEL_PAYLOAD -> {
                    if (!append(b)) reset()
                }
                State.DCS_SKIP -> {} // 只等 ST
            }
        }
    }

    private fun finishSequence() {
        if (state == State.SIXEL_PAYLOAD && length > 0) {
            val payload = buffer.copyOf(length)
            runCatching { onSixelPayload(payload) }
        }
        reset()
    }
}
