package com.termux.app.x11

import java.util.UUID

/**
 * XServer 连接条目（远程页「XServer」标签）。
 *
 * @param displayNumber X display 编号（:0 对应 TCP 6000 端口）
 * @param isFromTermux  本机 Termux 内置条目：不落盘、不可编辑删除
 */
data class XServerConnection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val displayNumber: Int = 0,
    val isFromTermux: Boolean = false
) {
    /** 会话要导出的 DISPLAY 值：本机 `:0`，远程 `host:display`。 */
    val displayValue: String
        get() = if (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) ":$displayNumber"
                else "$host:$displayNumber"

    companion object {
        /** 本机 Termux 的固定条目（X display :0）。 */
        fun localTermux(): XServerConnection = XServerConnection(
            id = "local-termux",
            name = "本机 Termux",
            host = "127.0.0.1",
            displayNumber = 0,
            isFromTermux = true
        )
    }
}
