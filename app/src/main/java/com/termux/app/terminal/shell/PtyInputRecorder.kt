package com.termux.app.terminal.shell

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * PTY 写入侧的交互式输入记录器：按回车切出整行，回调给上层做「最近执行」展示。
 *
 * libterminal 的 TerminalSession 是 final 类且不提供输入回调，
 * 而所有输入（含 IME 文本、extra keys、快捷命令）最终都会写入进程桥的输出流，
 * 因此只能在这一层截获。
 */
internal class PtyInputRecorder(
    private val sink: OutputStream,
    private val onCommand: (String) -> Unit
) : OutputStream() {

    private val lineBuffer = ByteArrayOutputStream()
    private val guard = Any()

    private var escapeState = EscapeState.NONE

    private enum class EscapeState { NONE, EXPECT_INTRODUCER, CSI }

    override fun write(b: Int) {
        sink.write(b)
        appendByte(b and 0xFF)
    }

    override fun write(b: ByteArray) {
        write(b, 0, b.size)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        sink.write(b, off, len)
        for (i in off until off + len) {
            appendByte(b[i].toInt() and 0xFF)
        }
    }

    override fun flush() {
        sink.flush()
    }

    override fun close() {
        sink.close()
    }

    private fun appendByte(value: Int) {
        when (escapeState) {
            // CSI（方向键/Home/End 等）：ESC [ <参数> <终止字节 0x40..0x7E>，整段丢弃
            EscapeState.CSI -> {
                if (value in 0x40..0x7E) escapeState = EscapeState.NONE
                return
            }
            // ESC 后的引入字节：`[` 进入 CSI，`O`/`?` 等单字节引入后仍以终止字节收尾
            EscapeState.EXPECT_INTRODUCER -> {
                escapeState = if (value == '['.code) EscapeState.CSI else EscapeState.NONE
                return
            }
            EscapeState.NONE -> Unit
        }

        when {
            value == 0x1B -> escapeState = EscapeState.EXPECT_INTRODUCER
            value == '\r'.code || value == '\n'.code -> resolveLine()
            value == 8 || value == 127 -> synchronized(guard) { trimLastUtf8Char() }
            // Tab 补全也是命令的一部分，只丢弃其余控制字符
            value == '\t'.code -> synchronized(guard) { lineBuffer.write(value) }
            value < 0x20 -> Unit
            else -> synchronized(guard) { lineBuffer.write(value) }
        }
    }

    private fun resolveLine() {
        val raw = synchronized(guard) {
            val bytes = lineBuffer.toByteArray()
            lineBuffer.reset()
            bytes
        }
        val command = String(raw, Charsets.UTF_8).trim()
        if (command.isNotEmpty()) onCommand(command)
    }

    /** 按 UTF-8 字节边界回退一个字符：续字节（10xxxxxx）连同首字节一起丢弃。 */
    private fun trimLastUtf8Char() {
        val bytes = lineBuffer.toByteArray()
        if (bytes.isEmpty()) return
        var drop = 1
        while (drop < bytes.size && (bytes[bytes.size - drop].toInt() and 0xC0) == 0x80) {
            drop++
        }
        lineBuffer.reset()
        lineBuffer.write(bytes, 0, bytes.size - drop)
    }
}
