// Copyright 2026, Kira contributors
// SPDX-License-Identifier: MIT
//
// sixel 会话挂载点（Issue #401）：包装 ITerminalProcess，在 PTY 输入流上
// 旁路扫描 sixel，解码结果按会话投递到 [SixelImageBus]。
//
// 为什么挂在进程层而不是仿真器层：libterminal 是 Maven 依赖，仿真器内部
// 不可注入；而 TerminalSession 的进程工厂（ITerminalProcess）由应用注入，
// 输入流包装后字节原样透传，扫描只在读线程上做一次 O(n) 状态检查。

package com.termux.app.terminal.sixel

import com.awkoo.libterminal.process.ITerminalProcess
import java.io.InputStream

/**
 * 透明包装的 [ITerminalProcess]：仅 inputStream 多套一层旁路扫描，
 * 其余行为（stdout / resize / waitFor / kill / close）原样委托。
 */
class SixelTapProcess(
    private val delegate: ITerminalProcess,
    private val sessionId: Int,
) : ITerminalProcess by delegate {

    private val tappingStream: InputStream by lazy {
        SixelTappingInputStream(delegate.inputStream, sessionId)
    }

    override val inputStream: InputStream get() = tappingStream
}

/**
 * 逐字节透传的 [InputStream] 装饰器：读出的数据先喂给 [SixelStreamScanner]
 * 再返回给调用方，字节内容与顺序完全不变。
 */
private class SixelTappingInputStream(
    private val upstream: InputStream,
    sessionId: Int,
) : InputStream() {

    private val scanner = SixelStreamScanner { payload ->
        SixelImageBus.post(sessionId, payload)
    }

    override fun read(): Int {
        val b = upstream.read()
        if (b >= 0) {
            SINGLE_BYTE[0] = b.toByte()
            scanner.tap(SINGLE_BYTE, 0, 1)
        }
        return b
    }

    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
        val n = upstream.read(buffer, offset, count)
        if (n > 0) scanner.tap(buffer, offset, n)
        return n
    }

    override fun available(): Int = upstream.available()

    override fun close() {
        upstream.close()
    }

    companion object {
        /** 单字节读路径的复用缓冲（scanner.tap 同步消费，无并发风险）。 */
        private val SINGLE_BYTE = ByteArray(1)
    }
}
