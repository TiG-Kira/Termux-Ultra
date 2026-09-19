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

    companion object {
        // 高危命令拦截已由 shell hook（trap DEBUG + PROMPT_COMMAND）+ SecuritySocketServer
        // 在 shell 层处理，Kotlin 层不再维护 InputInterceptor。
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

    // 命令输入缓冲区，用于检测回车时的完整命令（主页终端卡片的「最近执行」）
    private val commandBuffer = StringBuilder()

    // 增量 UTF-8 解码状态：还需要读取的后续字节数（0 = 不在多字节序列中）
    private var utf8BytesNeeded = 0
    private var utf8CodePoint = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class DataChunk(val buffer: ByteArray, var length: Int)
    private val terminalReadChannel: Channel<DataChunk> = Channel(Channel.UNLIMITED)
    private val terminalReadBufferPoolChannel = Channel<DataChunk>(64)
    // UNLIMITED：用户输入绝不能丢。原实现用 BUFFERED（容量 64）+ trySend，
    // 消费者（向 pty 写）被慢速 shell 阻塞时队列会被填满，trySend 返回失败且结果被忽略，
    // 后续按键/粘贴内容被静默丢弃。UNLIMITED 的 trySend 永远不会因容量失败，
    // 保证「写进去的字节一定会送到 pty」。
    private val terminalWriteChannel: Channel<ByteArray> = Channel(Channel.UNLIMITED)

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
    // pid 变化同步到 pidState，供 Compose UI 订阅，实现状态（未初始化/运行中/已结束）实时刷新
    private val _pidState = MutableStateFlow(0)
    val pidState: StateFlow<Int> = _pidState.asStateFlow()

    @Volatile
    var pid: Int = 0
        set(value) {
            field = value
            _pidState.value = value
        }

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

    /** 会话启动完成后的回调，可注入欢迎文本和自动执行命令。 */
    var onSessionStarted: (() -> Unit)? = null

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

        // 启动后延迟注入自定义内容（shell 初始化需要一小段时间）
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(500)
            onSessionStarted?.invoke()
        }
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
            } catch (t: Throwable) {
                // 兜底：捕获所有非 IOException，防止 PTY 读循环静默死亡导致输出管线阻塞
                android.util.Log.e("TerminalSession", "launchInputReader crash", t)
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

    private fun launchEmulatorProcessor() {
        scope.launch(Dispatchers.Default) {
            var consecutiveCrashes = 0
            val maxCrashes = 5
            while (consecutiveCrashes < maxCrashes) {
                try {
                    for (chunk in terminalReadChannel) {
                        consecutiveCrashes = 0 // 正常处理一个 chunk 后重置计数
                        var bytesProcessed = chunk.length

                        // 无论 append 是否抛异常，都要把「真实」的 chunk 归还池中。
                        // 原实现在 catch 里改为回池一个 4096 占位对象：既静默丢弃了
                        // 这段 PTY 输出，又让池里混入尺寸不一致的对象。
                        try {
                            synchronized(emulator) {
                                emulator.append(chunk.buffer, chunk.length)

                                while (bytesProcessed < 32 * 1024) {
                                    val moreChunk = terminalReadChannel.tryReceive().getOrNull() ?: break
                                    emulator.append(moreChunk.buffer, moreChunk.length)
                                    bytesProcessed += moreChunk.length
                                    terminalReadBufferPoolChannel.trySend(moreChunk)
                                }
                            }
                        } finally {
                            terminalReadBufferPoolChannel.trySend(chunk)
                        }

                        notifyScreenUpdate()
                        yield()
                    }
                    return@launch // channel closed → 正常退出
                } catch (t: Throwable) {
                    consecutiveCrashes++
                    android.util.Log.e("TerminalSession",
                        "launchEmulatorProcessor crash ($consecutiveCrashes/$maxCrashes), restarting...", t)
                    // 当前 chunk 已在上面的 finally 中归还，这里无需再补占位对象
                    kotlinx.coroutines.delay(100)
                }
            }
            android.util.Log.e("TerminalSession",
                "launchEmulatorProcessor crashed $maxCrashes times, giving up. PTY output may stop.")
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

        if (data.isEmpty()) return

        // 扫描回车提取完整命令，用于更新 lastCommand（主页终端卡片的「最近执行」）。
        // 命令拦截已完全由 shell hook（trap DEBUG + PROMPT_COMMAND）+ SecuritySocketServer
        // 在 shell 层处理，不再在 Kotlin/Java 层拦截。
        var segmentStart = 0
        for (i in data.indices) {
            val b = data[i]
            if (b == '\r'.code.toByte() || b == '\n'.code.toByte()) {
                for (j in segmentStart until i) bufferChar(data[j])
                segmentStart = i + 1

                val command = commandBuffer.toString().trim()
                commandBuffer.setLength(0)
                resetUtf8State()

                if (command.isNotEmpty()) {
                    lastCommand = command
                }
            }
        }

        for (i in segmentStart until data.size) bufferChar(data[i])
        enqueueWrite(data)
    }

    /**
     * 投递待写数据。
     * 通道为 UNLIMITED，trySend 只可能在通道已关闭（会话结束）时失败，
     * 那时丢弃是正确行为，但必须留痕，避免"输入莫名消失"无法定位。
     */
    private fun enqueueWrite(data: ByteArray) {
        val result = terminalWriteChannel.trySend(data)
        if (result.isFailure) {
            android.util.Log.w(
                "TerminalSession",
                "write: 通道已关闭，丢弃 ${data.size} 字节（会话已结束）"
            )
        }
    }

    /** 逐字符缓冲命令行：处理退格 / Ctrl+C / Ctrl+D（与 Java 版 bufferChar 一致）。 */
    private fun bufferChar(b: Byte) {
        val v = b.toInt() and 0xFF
        when {
            b == 8.toByte() || b == 127.toByte() -> {
                // 退格：删除最后一个「字符」，代理对整体删除，避免留下半个 emoji
                if (commandBuffer.isNotEmpty()) {
                    val last = commandBuffer.length - 1
                    if (Character.isLowSurrogate(commandBuffer[last]) && last > 0) {
                        commandBuffer.delete(last - 1, last + 1)
                    } else {
                        commandBuffer.deleteCharAt(last)
                    }
                }
                resetUtf8State()
            }
            b == 3.toByte() || b == 4.toByte() -> { // Ctrl+C / Ctrl+D
                commandBuffer.setLength(0)
                resetUtf8State()
            }
            v >= 32 -> {
                // 原实现把每个字节当成 Latin-1 字符；而 Kotlin 的 Byte 有符号，
                // `b >= 32` 对所有 >=0x80 的 UTF-8 字节都为假，整段被丢弃 ——
                // 中文/emoji 命令在「最近执行」里被截断。这里改为 UTF-8 增量解码。
                bufferUtf8(v)
            }
        }
    }

    /** 重置增量 UTF-8 解码状态。 */
    private fun resetUtf8State() {
        utf8BytesNeeded = 0
        utf8CodePoint = 0
    }

    /** 增量解码一个 UTF-8 字节，凑满一个码点后追加到命令缓冲区。 */
    private fun bufferUtf8(v: Int) {
        if (utf8BytesNeeded == 0) {
            when {
                v < 0x80 -> {
                    commandBuffer.append(v.toChar())
                    return
                }
                (v and 0xE0) == 0xC0 -> {
                    utf8BytesNeeded = 1
                    utf8CodePoint = v and 0x1F
                }
                (v and 0xF0) == 0xE0 -> {
                    utf8BytesNeeded = 2
                    utf8CodePoint = v and 0x0F
                }
                (v and 0xF8) == 0xF0 -> {
                    utf8BytesNeeded = 3
                    utf8CodePoint = v and 0x07
                }
                else -> resetUtf8State() // 非法首字节 / 孤立续字节：丢弃
            }
        } else {
            if ((v and 0xC0) != 0x80) {
                // 期望续字节却来了别的字节：整段序列作废
                resetUtf8State()
                return
            }
            utf8CodePoint = (utf8CodePoint shl 6) or (v and 0x3F)
            if (--utf8BytesNeeded == 0) {
                commandBuffer.appendCodePoint(utf8CodePoint)
                utf8CodePoint = 0
            }
        }
    }

    /**
     * 终端模拟器响应直通写入（不经过高危命令拦截）。
     * 模拟器对应用的应答（如设备查询响应）不是用户输入，
     * 拦截会污染命令缓冲，需绕过。
     */
    private fun writeRaw(data: ByteArray) {
        if (isRunning) enqueueWrite(data)
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