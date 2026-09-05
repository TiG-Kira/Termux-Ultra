package com.termux.app.compose.terminal.engine

import androidx.annotation.Keep
import com.termux.app.compose.terminal.process.ITerminalProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.IOException

/**
 * 终端会话，包含一个子进程及其对应的终端模拟器。
 *
 * 构造时即执行子进程，通过 [updateSize] 通知模拟器尺寸后开始终端仿真。
 * 子进程 I/O 和模拟器回调均在协程中运行，屏幕更新通过 [uiEvent] 通知 UI 层。
 *
 * 注意：会话可能比 UI 组件存活更久，回调中需谨慎处理生命周期。
 */
class TerminalSession(
    val id: Int,
    val sessionName: MutableStateFlow<String>,
    private val stdin: ByteArray? = null,
    private val processFactory: (Int, Int, Int, Int) -> ITerminalProcess
) {

    /**
     * 高危命令输入拦截器。
     * 语义与 Java 版 com.termux.terminal.TerminalSession.InputInterceptor 一致，
     * 供增强防护（RiskConfirmManager）在 Compose 核心下检测用户输入的命令行。
     */
    interface InputInterceptor {
        /** 回车时回调。返回 true 表示命令已被拦截处理（如高危命令弹窗确认）。 */
        fun onCommandEntered(session: TerminalSession, command: String): Boolean

        /** 命令被拦截时回调（用于显示提示）。 */
        fun onCommandBlocked(session: TerminalSession, command: String)

        /** 是否为自动拦截模式（无需用户确认，直接拒绝）。 */
        fun onCommandAutoBlocked(session: TerminalSession, command: String): Boolean
    }

    companion object {
        /** 全局输入拦截器（与 Java 版一致为静态单点注册）。 */
        @JvmStatic
        @Volatile
        var inputInterceptor: InputInterceptor? = null
    }

    /**
     * 会话唯一句柄（确认结果返回时按此找回会话）。
     * id 全应用内唯一，加前缀避免与 Java 会话的 mHandle 混淆。
     */
    val handle: String
        get() = "compose-$id"

    /** Shell 路径元数据（供环境检测使用，创建会话后由 ComposeSessionManager 设置）。 */
    var shellPath: String? = null

    /** 启动参数元数据（供环境检测使用，创建会话后由 ComposeSessionManager 设置）。 */
    var args: Array<String>? = null

    /** 最近一次执行的（未拦截）命令，语义与 Java 版 mLastCommand 一致。 */
    @Volatile
    var lastCommand: String = ""
        private set

    // ---- 高危命令拦截状态（对齐 Java 版 mCommandBuffer/mPendingDangerousCommand） ----
    private val commandBuffer = StringBuilder()
    @Volatile
    private var pendingDangerousCommand: String? = null
    private var pendingEnterBytes: ByteArray? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class DataChunk(val buffer: ByteArray, var length: Int)
    private val terminalReadChannel: Channel<DataChunk> = Channel(Channel.UNLIMITED)
    private val terminalReadBufferPoolChannel = Channel<DataChunk>(64)
    private val terminalWriteChannel: Channel<ByteArray> = Channel(Channel.BUFFERED)

    init {
        for (i in 0..<64) {
            terminalReadBufferPoolChannel.trySend(DataChunk(ByteArray(4096), 0))
        }
    }

    /**
     * 进程 pid。
     * 语义与 Java 版 TerminalSession.mShellPid 保持一致：
     * 0=未初始化（尚未调用 [execute]），>0=运行中，-1=已结束。
     */
    @Volatile
    var pid: Int = 0

    val isRunning: Boolean
        get() = pid > 0

    /**
     * 进程退出事件流（[handleProcessExit] 时置 true）。
     * pid 是普通字段不会触发 Compose 重组，UI 层收集此流即可在会话结束的
     * 瞬间感知并立刻显示"会话已结束"（对齐 Java 版 onSessionFinished 回调行为）。
     */
    private val _sessionExited = MutableStateFlow(false)
    val sessionExited: StateFlow<Boolean> = _sessionExited.asStateFlow()

    /** 进程退出状态，仅在 [isRunning] 为 false 时有效。 */
    /** Shell 进程的退出码，仅在进程结束后有效。 */
    @Volatile
    var exitStatus: Int = 0
        private set

    internal val emulator = TerminalEmulator({ writeRaw(it.toByteArray()) }, ::writeRaw)

    private var process: ITerminalProcess? = null

    val titleState = emulator.titleState

    /** OSC 52 剪贴板写入事件流，仅供模块内 view 层消费。 */
    internal val copiedText = emulator.copiedText

    /** 通知伪终端新尺寸，并执行文本重排或重新初始化模拟器。 */
    internal fun updateSize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        if (isRunning) {
            process?.resize(columns, rows, cellWidthPixels, cellHeightPixels)
        }
        synchronized(emulator) {
            emulator.resize(columns, rows, cellWidthPixels, cellHeightPixels)
        }
    }

    fun execute() {
        val p = processFactory(
            emulator.mRows,
            emulator.mColumns,
            emulator.mCellWidthPixels,
            emulator.mCellHeightPixels
        )
        this.process = p
        this.pid = p.pid

        launchInputReader(p)
        launchOutputWriter(p)
        launchEmulatorProcessor()
        launchExitHandler(p)
    }

    private inline fun launchInputReader(p: ITerminalProcess) {
        scope.launch {
            try {
                p.inputStream.use { termIn ->
                    while (isActive) {
                        val chunk = terminalReadBufferPoolChannel.receive()
                        val read = termIn.read(chunk.buffer)
                        chunk.length = read
                        if (read != -1) {
                            terminalReadChannel.send(chunk)
                        } else {
                            terminalReadBufferPoolChannel.trySend(chunk)
                            break
                        }
                    }
                }
            } catch (e: IOException) {
                // 输入流关闭时静默忽略
            } finally {
                terminalReadChannel.close()
                terminalReadBufferPoolChannel.close()
            }
        }
    }

    private inline fun launchOutputWriter(p: ITerminalProcess) {
        scope.launch {
            try {
                p.outputStream.use { termOut ->
                    stdin?.let { termOut.write(it) }
                    for (buffer in terminalWriteChannel) {
                        termOut.write(buffer, 0, buffer.size)
                    }
                }
            } catch (e: IOException) {
                // 输出流关闭时静默忽略
            } finally {
                terminalWriteChannel.close()
            }
        }
    }

    private inline fun launchEmulatorProcessor() {
        scope.launch(Dispatchers.Default) {
            for (chunk in terminalReadChannel) {
                var bytesProcessed = chunk.length

                synchronized(emulator) {
                    emulator.append(chunk.buffer, chunk.length)

                    while (bytesProcessed < 32 * 1024) {
                        val moreChunk = terminalReadChannel.tryReceive().getOrNull() ?: break
                        emulator.append(moreChunk.buffer, moreChunk.length)
                        bytesProcessed += moreChunk.length
                        terminalReadBufferPoolChannel.trySend(moreChunk)
                    }
                }

                terminalReadBufferPoolChannel.trySend(chunk)
                notifyScreenUpdate()
                yield()
            }
        }
    }

    private inline fun launchExitHandler(p: ITerminalProcess) {
        scope.launch {
            val exitCode = p.waitFor()

            p.close()

            withContext(Dispatchers.Main.immediate) {
                handleProcessExit(exitCode)
            }

            scope.cancel()
        }
    }

    private inline fun handleProcessExit(exitCode: Int) {
        exitStatus = exitCode
        pid = -1
        // 通知 UI 层会话已结束（立刻显示"会话已结束"，无需等待其它状态触发重组）
        _sessionExited.value = true

        synchronized(emulator) {
            while (true) {
                val pendingChunk = terminalReadChannel.tryReceive().getOrNull() ?: break
                emulator.append(pendingChunk.buffer, pendingChunk.length)
                terminalReadBufferPoolChannel.trySend(pendingChunk)
            }

            var exitDescription = "\r\n[Process completed"
            if (exitCode > 0) {
                // 非零退出码
                exitDescription += " (code $exitCode)"
            } else if (exitCode < 0) {
                // 负数表示信号编号
                exitDescription += " (signal ${-exitCode})"
            }
            exitDescription += " - press Enter]"
            val buffer = exitDescription.toByteArray()
            emulator.append(buffer, buffer.size)
        }

        notifyScreenUpdate()
    }

    val isRemove = MutableStateFlow(false)

    /** 向 Shell 进程写入数据。 */
    fun write(data: ByteArray) {
        if (!this.isRunning) {
            // 死会话：Enter 键从会话列表移除（Termux 标准关会话逻辑）
            if (data.size == 1 &&
                (data[0] == '\n'.code.toByte() || data[0] == '\r'.code.toByte())
            ) {
                isRemove.update { true }
            }
            return
        }

        val interceptor = inputInterceptor
        if (interceptor == null || data.isEmpty()) {
            if (data.isNotEmpty()) terminalWriteChannel.trySend(data)
            return
        }

        // 有待确认的高危命令：缓冲新输入但不转发到 Shell（与 Java 版一致）
        if (pendingDangerousCommand != null) {
            for (b in data) bufferChar(b)
            return
        }

        // 扫描 Enter 键，提取完整命令行进行风险检测
        var segmentStart = 0
        for (i in data.indices) {
            val b = data[i]
            if (b == '\r'.code.toByte() || b == '\n'.code.toByte()) {
                for (j in segmentStart until i) bufferChar(data[j])
                segmentStart = i + 1

                val command = commandBuffer.toString().trim()
                commandBuffer.setLength(0)

                if (command.isNotEmpty()) {
                    val handled = interceptor.onCommandEntered(this, command)
                    if (handled) {
                        if (interceptor.onCommandAutoBlocked(this, command)) {
                            // 自动拦截：直接拒绝命令并清除输入行
                            denyPendingCommand()
                            return
                        }
                        // 高危命令：扣住 Enter，等待用户确认后放行
                        pendingEnterBytes = byteArrayOf(b)
                        pendingDangerousCommand = command
                        // 先转发命令字符（不含 Enter），保持行内回显完整
                        if (i > 0) {
                            terminalWriteChannel.trySend(data.copyOf(i))
                        }
                        return
                    } else {
                        // 非高危命令，记录为最近执行的命令
                        lastCommand = command
                    }
                }
            }
        }

        // 无 Enter：逐字节缓冲并原样转发
        for (i in segmentStart until data.size) bufferChar(data[i])
        terminalWriteChannel.trySend(data)
    }

    /** 逐字符缓冲命令行：处理退格 / Ctrl+C / Ctrl+D（与 Java 版 bufferChar 一致）。 */
    private fun bufferChar(b: Byte) {
        when {
            b == 8.toByte() || b == 127.toByte() -> {
                if (commandBuffer.isNotEmpty()) commandBuffer.deleteCharAt(commandBuffer.length - 1)
            }
            b == 3.toByte() || b == 4.toByte() -> commandBuffer.setLength(0) // Ctrl+C / Ctrl+D
            b >= 32 -> commandBuffer.append((b.toInt() and 0xFF).toChar())
        }
    }

    /** 用户确认执行危险命令，放行被扣住的 Enter 键（与 Java 版一致）。 */
    fun confirmPendingCommand() {
        val enter = pendingEnterBytes
        if (pendingDangerousCommand != null && enter != null) {
            terminalWriteChannel.trySend(enter)
            pendingDangerousCommand = null
            pendingEnterBytes = null
        }
    }

    /** 用户拒绝危险命令，向终端输出拒绝信息并清除当前输入行（与 Java 版一致）。 */
    fun denyPendingCommand() {
        if (pendingDangerousCommand == null) return
        synchronized(emulator) {
            val errorMsg = "\r\nTermux-Confirm: Permission Denied\r\n".toByteArray()
            emulator.append(errorMsg, errorMsg.size)
        }
        notifyScreenUpdate()
        // 发送 Ctrl+U 清除当前输入行
        terminalWriteChannel.trySend(byteArrayOf(0x15))
        pendingDangerousCommand = null
        pendingEnterBytes = null
        commandBuffer.setLength(0)
    }

    /** 是否有待处理的危险命令确认。 */
    fun hasPendingDangerousCommand(): Boolean = pendingDangerousCommand != null

    /**
     * 终端模拟器响应直通写入（不经过高危命令拦截）。
     * 模拟器对应用的应答（如设备查询响应）不是用户输入，
     * 拦截会污染命令缓冲，需绕过。
     */
    private fun writeRaw(data: ByteArray) {
        if (isRunning) terminalWriteChannel.trySend(data)
    }

    inline fun write(data: String) {
        write(data.toByteArray())
    }

    /** 将码点编码为 UTF-8 后写入进程输出的缓冲区。 */
    private val mUtf8InputBuffer = ByteArray(5)

    /** 将 Unicode 码点以 UTF-8 编码写入终端。 */
    internal fun writeCodePoint(prependEscape: Boolean, codePoint: Int) {
        require(!(codePoint > 1114111 || (codePoint in 0xD800..0xDFFF))) {
            "Invalid code point: $codePoint"
        }

        var bufferPosition = 0
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27

        if (codePoint <=  /* 7 位 */127) {
            mUtf8InputBuffer[bufferPosition++] = codePoint.toByte()
        } else if (codePoint <=  /* 11 位 */2047) {
            /* 110xxxxx 首字节，取高 5 位 */
            mUtf8InputBuffer[bufferPosition++] = (192 or (codePoint shr 6)).toByte()
            /* 10xxxxxx 后续字节，取低 6 位 */
            mUtf8InputBuffer[bufferPosition++] = (128 or (codePoint and 63)).toByte()
        } else if (codePoint <=  /* 16 位 */65535) {
            /* 1110xxxx 首字节，取高 4 位 */
            mUtf8InputBuffer[bufferPosition++] = (224 or (codePoint shr 12)).toByte()
            /* 10xxxxxx 后续字节，取次高 6 位 */
            mUtf8InputBuffer[bufferPosition++] = (128 or ((codePoint shr 6) and 63)).toByte()
            /* 10xxxxxx 后续字节，取低 6 位 */
            mUtf8InputBuffer[bufferPosition++] = (128 or (codePoint and 63)).toByte()
        } else { /* 上方已校验 codePoint <= 1114111，最多 21 位 = 0b111111111111111111111 */
            /* 11110xxx 首字节，取高 3 位 */
            mUtf8InputBuffer[bufferPosition++] = (240 or (codePoint shr 18)).toByte()
            /* 10xxxxxx 后续字节，取第 12~17 位 */
            mUtf8InputBuffer[bufferPosition++] = (128 or ((codePoint shr 12) and 63)).toByte()
            /* 10xxxxxx 后续字节，取第 6~11 位 */
            mUtf8InputBuffer[bufferPosition++] = (128 or ((codePoint shr 6) and 63)).toByte()
            /* 10xxxxxx 后续字节，取低 6 位 */
            mUtf8InputBuffer[bufferPosition++] = (128 or (codePoint and 63)).toByte()
        }
        write(mUtf8InputBuffer.copyOf(bufferPosition))
    }

    /** 屏幕变更通知事件流，供模块内 view 层订阅重绘。 */
    internal val uiEvent = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 通过 uiEvent 通知 UI 层屏幕已更新。 */
    private inline fun notifyScreenUpdate() {
        uiEvent.tryEmit(Unit)
    }

    /** 重置终端模拟器状态。 */
    fun reset() {
        synchronized(emulator) {
            emulator.reset()
        }
        notifyScreenUpdate()
    }

    /** 向 Shell 发送 SIGKILL 终止会话。 */
    fun finishIfRunning() {
        process?.kill()
    }
}