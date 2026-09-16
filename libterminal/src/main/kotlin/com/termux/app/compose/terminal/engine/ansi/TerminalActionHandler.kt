package com.termux.app.compose.terminal.engine.ansi

/**
 * 终端动作回调接口。
 *
 * 由 [com.awkoo.libterminal.engine.TerminalEmulator] 实现，供 [AnsiEscapeParser] 在解析到各种终端动作时回调。
 */
internal interface TerminalActionHandler {
    /** 收到普通可打印字符。 */
    fun onCodePoint(codePoint: Int)

    /** 响铃（BEL，\x07）。 */
    fun onBell()

    /** 退格（BS，\x08）。 */
    fun onBackspace()

    /** 水平制表（HT，\x09）。 */
    fun onHorizontalTab()

    /** 换行（LF，\x0A）。 */
    fun onLinefeed()

    /** 回车（CR，\x0D）。 */
    fun onCarriageReturn()

    /** 移入（SI，\x0F），切换到 G0 字符集。 */
    fun onShiftIn()

    /** 移出（SO，\x0E），切换到 G1 字符集。 */
    fun onShiftOut()

    /** ESC 序列命令完成。 */
    fun onEscCommand(state: Int, command: Int)

    /** CSI 序列命令完成。 */
    fun onCsiCommand(state: Int, command: Int, args: IntArray, argCount: Int, subParams: Int)

    /** OSC 序列命令完成。 */
    fun onOscCommand(value: Int, textParameter: String, bellOrStringTerminator: String)

    /** DCS 设备控制字符串完成。 */
    fun onDeviceControl(dcs: String)

    /** 软重置（DECSTR）。 */
    fun onSoftReset()
}