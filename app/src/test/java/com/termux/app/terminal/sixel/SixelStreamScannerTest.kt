// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT
//
// SixelStreamScanner 的 JVM 单元测试：重点覆盖跨 chunk 边界的序列识别、
// 非 sixel DCS 的跳过与 CAN/SUB 中止——扫描器跑在 PTY 读线程上，状态机
// 出错会直接破坏终端数据通路。

package com.termux.app.terminal.sixel

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SixelStreamScannerTest {

    private val payloads = mutableListOf<ByteArray>()

    private fun newScanner(): SixelStreamScanner = SixelStreamScanner { payloads.add(it) }

    private fun SixelStreamScanner.feed(str: String) {
        val bytes = str.toByteArray(Charsets.ISO_8859_1)
        tap(bytes, 0, bytes.size)
    }

    /** 把字符串按 [sizes] 切成多个 chunk 分别喂入，模拟 PTY 读缓冲切割。 */
    private fun SixelStreamScanner.feedChunked(str: String, sizes: List<Int>) {
        val bytes = str.toByteArray(Charsets.ISO_8859_1)
        var offset = 0
        for (size in sizes) {
            val count = minOf(size, bytes.size - offset)
            if (count <= 0) break
            tap(bytes, offset, count)
            offset += count
        }
        if (offset < bytes.size) tap(bytes, offset, bytes.size - offset)
    }

    @Test
    fun `single chunk sixel payload is extracted`() {
        val scanner = newScanner()
        scanner.feed("\u001bP0;1;0q#0;2;0;0;0----\u001b\\")
        assertEquals(1, payloads.size)
        assertArrayEquals("0;1;0q#0;2;0;0;0----".toByteArray(Charsets.ISO_8859_1), payloads[0])
    }

    @Test
    fun `chunk boundary inside header and payload`() {
        val scanner = newScanner()
        val seq = "\u001bP2;1;100;60q!15~-\u001b\\"
        scanner.feedChunked(seq, listOf(1, 1, 3, 5, 2, 100))
        assertEquals(1, payloads.size)
        assertArrayEquals("2;1;100;60q!15~-".toByteArray(Charsets.ISO_8859_1), payloads[0])
    }

    @Test
    fun `esc split across chunks still detected`() {
        val scanner = newScanner()
        scanner.feedChunked("\u001bPq~\u001b\\", listOf(1, 1, 1, 1, 1, 1))
        assertEquals(1, payloads.size)
        assertEquals("q~", String(payloads[0], Charsets.ISO_8859_1))
    }

    @Test
    fun `non sixel dcs is skipped silently`() {
        val scanner = newScanner()
        // $q 是 VT100 属性查询（libterminal 自己会应答），必须原样放行给仿真器
        scanner.feed("\u001bP\$q\"p\u001b\\hello")
        assertEquals(0, payloads.size)
        // 跳过模式结束后恢复正常扫描
        scanner.feed("\u001bPq~\u001b\\")
        assertEquals(1, payloads.size)
        assertEquals("q~", String(payloads[0], Charsets.ISO_8859_1))
    }

    @Test
    fun `can aborts sixel payload`() {
        val scanner = newScanner()
        scanner.feed("\u001bPq~\u0018\u001bPq#\u001b\\")
        assertEquals(1, payloads.size)
        assertEquals("q#", String(payloads[0], Charsets.ISO_8859_1))
    }

    @Test
    fun `plain text and osc pass through untouched`() {
        val scanner = newScanner()
        scanner.feed("echo hi\u001b]0;title\u0007more\u001bPq\u001b\\")
        assertEquals(1, payloads.size)
        assertEquals("q", String(payloads[0], Charsets.ISO_8859_1))
    }

    @Test
    fun `two sequences back to back`() {
        val scanner = newScanner()
        scanner.feed("\u001bP0;1;0q~\u001b\\\u001bP0;1;0q-\u001b\\")
        assertEquals(2, payloads.size)
        assertEquals("0;1;0q~", String(payloads[0], Charsets.ISO_8859_1))
        assertEquals("0;1;0q-", String(payloads[1], Charsets.ISO_8859_1))
    }

    @Test
    fun `oversized payload is dropped and scanner recovers`() {
        val scanner = newScanner()
        val huge = "\u001bPq" + "~".repeat(20 * 1024 * 1024 / 1) // 超过 16MB 上限
        scanner.feedChunked(huge, listOf(8192, 8192, 8192))
        // 超限后序列被放弃；此后正常数据仍能识别
        scanner.feed("\u001bPq~\u001b\\")
        assertEquals(1, payloads.size)
        assertEquals("q~", String(payloads[0], Charsets.ISO_8859_1))
    }
}
