package com.termux.app.compose.terminal.engine

import kotlin.math.max
import kotlin.math.min

/**
 * 输入序列编码器。
 *
 * 将 UI 输入事件（鼠标、焦点、粘贴）编码为发往 shell 的转义序列。
 * 无状态纯编码器：终端模式开关由调用方查询后以布尔参数传入，
 * 本类不持有任何终端状态，便于独立测试。
 */
internal class InputSequenceEncoder(
    private val writeString: (data: String) -> Unit,
    private val writeByteArray: (data: ByteArray) -> Unit
) {

    /**
     * 编码鼠标事件。
     *
     * SGR 协议启用时输出 `\e[<b;c;rM/m`，否则输出传统 X10 协议的 6 字节序列；
     * 仅移动事件（[button] 为 MOUSE_LEFT_BUTTON_MOVED）在按钮事件（1002）或
     * 任意事件（1003）追踪模式下才上报。
     */
    fun encodeMouseEvent(
        button: Int,
        column: Int,
        row: Int,
        pressed: Boolean,
        buttonEventTracking: Boolean,
        anyEventTracking: Boolean,
        sgrProtocol: Boolean,
        columns: Int,
        rows: Int
    ) {
        var button = button
        val c = min(max(column, 1), columns)
        val r = min(max(row, 1), rows)
        if (button == TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED && !buttonEventTracking && !anyEventTracking) {
            return
        } else if (sgrProtocol) {
            if (pressed) writeString("\u001b[<${button};${c};${r}M")
            else writeString("\u001b[<${button};${c};${r}m")
        } else {
            button = if (pressed) button else 3
            if (!(c > 255 - 32 || r > 255 - 32)) {
                val data = byteArrayOf('\u001b'.code.toByte(), '['.code.toByte(), 'M'.code.toByte(), (32 + button).toByte(), (32 + c).toByte(), (32 + r).toByte())
                writeByteArray(data)
            }
        }
    }

    /** 编码焦点变化事件：获得焦点发 `\e[I`，丢失焦点发 `\e[O`。 */
    fun encodeFocusEvent(hasFocus: Boolean, reportFocusEvents: Boolean) {
        if (reportFocusEvents) writeString(if (hasFocus) "\u001b[I" else "\u001b[O")
    }

    /**
     * 编码粘贴文本。
     *
     * 过滤 ESC 与 C1 控制字符防止转义序列注入，换行统一为 CR；
     * 括号粘贴模式启用时用 `\e[200~`/`\e[201~` 包裹。
     */
    fun encodePastedText(text: String, bracketedPaste: Boolean) {
        val processedText = buildString(text.length) {
            var i = 0
            while (i < text.length) {
                val c = text[i]; val code = c.code
                if (code == 0x1B || code in 0x80..0x9F) { i++; continue }
                if (c == '\n' || c == '\r') {
                    append('\r'); if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                } else append(c)
                i++
            }
        }
        if (bracketedPaste) writeString("\u001b[200~")
        writeString(processedText)
        if (bracketedPaste) writeString("\u001b[201~")
    }
}