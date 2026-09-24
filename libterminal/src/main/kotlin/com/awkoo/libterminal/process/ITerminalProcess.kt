package com.awkoo.libterminal.process

import java.io.InputStream
import java.io.OutputStream

/**
 * 终端进程的抽象接口
 * 无论是本地 Linux 进程、还是远程 SSH 连接、亦或是用于测试的 Mock 进程，均实现此接口。
 */
interface ITerminalProcess {
    val inputStream: InputStream
    val outputStream: OutputStream

    /** 调整伪终端大小 */
    fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int)

    /** 阻塞并等待进程退出，返回退出码 */
    fun waitFor(): Int

    /** 杀死进程 */
    fun kill()

    /** 释放资源（关闭文件描述符等） */
    fun close()

    /**
     * 非正常退出时的人类可读原因（如 SSH 认证失败、连接中断）。
     * 仅 [waitFor] 返回非零后在进程被 [close] 前可读；本地进程恒为 null。
     */
    val failureReason: String?
        get() = null
}