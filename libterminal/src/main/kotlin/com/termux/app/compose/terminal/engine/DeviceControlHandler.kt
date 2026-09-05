package com.termux.app.compose.terminal.engine

/**
 * DCS（Device Control String）设备控制串处理器。
 *
 * 支持两种格式：
 * - $q：VT100 设备属性查询（DA1）
 * - +q：XTGETTCAP 终端能力查询（xterm 扩展）
 *
 * 无状态纯处理器：应用键盘模式由调用方查询后以布尔参数传入。
 */
internal class DeviceControlHandler(
    private val writeString: (data: String) -> Unit
) {

    fun handleDeviceControl(dcs: String, appCursorKeys: Boolean, appKeypad: Boolean) {
        if (dcs.startsWith("\$q")) {
            if (dcs == "\$q\"p") {
                writeString("\u001bP1\$r64;1\"p\u001b\\")
            }
        } else if (dcs.startsWith("+q")) {
            for (part in dcs.substring(2).split(";").filter { it.isNotEmpty() }) {
                if (part.length % 2 == 0) {
                    val trans = part.chunked(2)
                        .mapNotNull { it.toIntOrNull(16)?.toChar() }
                        .joinToString("")

                    val responseValue = when (trans) {
                        "Co", "colors" -> "256"
                        "TN", "name" -> "xterm"
                        else -> KeyHandler.getCodeFromTermcap(trans, appCursorKeys, appKeypad)
                    }
                    if (responseValue == null) {
                        writeString("\u001bP0+r$part\u001b\\")
                    } else {
                        val hexEncoded = buildString {
                            responseValue.forEach { append("%02X".format(it.code)) }
                        }
                        writeString("\u001bP1+r$part=$hexEncoded\u001b\\")
                    }
                }
            }
        }
    }
}