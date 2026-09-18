package com.termux.app.compose.terminal.process

import android.system.Os
import android.system.OsConstants
import com.termux.terminal.JNI
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Field

/**
 * Termux 原生 PTY 的 ITerminalProcess 桥接实现。
 *
 * 复用 Termux 已有的 JNI.createSubprocess 启动 shell，
 * 将 PTY master fd 包装成 InputStream/OutputStream 供 libterminal TerminalSession 使用。
 */
class TermuxProcessBridge(
    private val shellPath: String,
    private val cwd: String,
    private val args: Array<String>,
    private val env: Array<String>,
    rows: Int,
    cols: Int,
    cellWidth: Int,
    cellHeight: Int
) : ITerminalProcess {

    private var terminalFd: Int = 0
    private var processId: Int = -1
    private var fileDescriptor: FileDescriptor? = null

    private val _inputStream: FileInputStream by lazy {
        FileInputStream(fileDescriptor)
    }

    private val _outputStream: FileOutputStream by lazy {
        FileOutputStream(fileDescriptor)
    }

    init {
        val processIdHolder = intArrayOf(0)
        terminalFd = JNI.createSubprocess(
            shellPath, cwd, args, env,
            processIdHolder, rows, cols, cellWidth, cellHeight
        )
        processId = processIdHolder[0]
        fileDescriptor = wrapFd(terminalFd)
    }

    override val pid: Int get() = processId

    override val inputStream: java.io.InputStream get() = _inputStream

    override val outputStream: java.io.OutputStream get() = _outputStream

    override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (terminalFd != 0) {
            JNI.setPtyWindowSize(terminalFd, rows, columns, cellWidthPixels, cellHeightPixels)
        }
    }

    override fun waitFor(): Int {
        return JNI.waitFor(processId)
    }

    override fun kill() {
        if (processId > 0) {
            try {
                Os.kill(processId, OsConstants.SIGKILL)
            } catch (_: Exception) {}
        }
    }

    override fun close() {
        if (terminalFd != 0) {
            JNI.close(terminalFd)
            terminalFd = 0
        }
        try {
            _inputStream.close()
        } catch (_: Exception) {}
        try {
            _outputStream.close()
        } catch (_: Exception) {}
    }

    private fun wrapFd(fd: Int): FileDescriptor {
        val result = FileDescriptor()
        try {
            val descriptorField = try {
                FileDescriptor::class.java.getDeclaredField("descriptor")
            } catch (_: NoSuchFieldException) {
                FileDescriptor::class.java.getDeclaredField("fd")
            }
            descriptorField.isAccessible = true
            descriptorField.set(result, fd)
        } catch (e: Exception) {
            throw RuntimeException("Failed to wrap FileDescriptor", e)
        }
        return result
    }
}
