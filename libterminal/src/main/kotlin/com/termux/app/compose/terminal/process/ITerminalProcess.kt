package com.termux.app.compose.terminal.process

import java.io.InputStream
import java.io.OutputStream

/**
 * 终端进程的抽象接口
 * 无论是本地 Linux 进程、还是远程 SSH 连接、亦或是用于测试的 Mock 进程，均实现此接口。
 */
interface ITerminalProcess {
    val pid: Int
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
}