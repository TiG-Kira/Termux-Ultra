package com.termux.app.compose.terminal.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections
import java.util.WeakHashMap

/**
 * 新版 TerminalSession 兼容层：为上游 v3.1.1 移除的 pid / pidState / sessionExited / shellPid
 * 提供扩展属性桥接，维持 app 层 Java 版 pid 语义（0=未初始化, >0=运行中, -1=已结束）。
 *
 * 注册表由 ComposeSessionManager 在 createSession 时初始化并在会话生命周期内维护。
 * 使用 WeakHashMap 避免会话被移除后遗留内存。
 */
object TerminalSessionCompat {

    /** sessionId -> 真实 pid 映射。未启动进程时无 key，进程结束后若会话从列表移除则自动回收。 */
    private val pidRegistry = Collections.synchronizedMap(WeakHashMap<Int, Int>())

    /** sessionId -> pidState (MutableStateFlow<Int>)。Java 版 pid 语义：0=未初始化, >0=运行中, -1=已结束。 */
    private val pidStateRegistry = Collections.synchronizedMap(WeakHashMap<Int, MutableStateFlow<Int>>())

    /** sessionId -> sessionExited (MutableStateFlow<Boolean>)。进程结束瞬间置 true。 */
    private val exitedRegistry = Collections.synchronizedMap(WeakHashMap<Int, MutableStateFlow<Boolean>>())

    fun registerSession(sessionId: Int) {
        pidStateRegistry[sessionId] = MutableStateFlow(0)
        exitedRegistry[sessionId] = MutableStateFlow(false)
    }

    fun setPid(sessionId: Int, pid: Int) {
        pidRegistry[sessionId] = pid
        pidStateRegistry[sessionId]?.value = pid
    }

    /**
     * 每次会话 uiEvent（屏幕刷新）触发时调用：
     * - isRunning=true  → pidState 保持真实 pid（由 setPid 已设置）
     * - isRunning=false → pidState 置 -1，sessionExited 置 true
     */
    fun updateFromUi(sessionId: Int, isRunning: Boolean) {
        if (!isRunning) {
            pidStateRegistry[sessionId]?.value = -1
            exitedRegistry[sessionId]?.value = true
        }
    }

    /** 清理注册表（供 killSession / killAllSessions 在 ComposeSessionManager 中可选调用）。 */
    fun unregister(sessionId: Int) {
        pidRegistry.remove(sessionId)
        pidStateRegistry.remove(sessionId)
        exitedRegistry.remove(sessionId)
    }

    // --- 读取 API（供 TerminalSession 扩展属性使用） ---

    internal fun getPid(sessionId: Int): Int {
        val pid = pidRegistry[sessionId] ?: return 0
        // 若已结束但 pidRegistry 尚未清理，返回 -1 匹配 Java 版语义
        val state = pidStateRegistry[sessionId]?.value
        if (state == -1) return -1
        return pid
    }

    internal fun getPidState(sessionId: Int): StateFlow<Int> {
        return pidStateRegistry[sessionId] ?: MutableStateFlow(-1)
    }

    internal fun getExited(sessionId: Int): StateFlow<Boolean> {
        return exitedRegistry[sessionId] ?: MutableStateFlow(true)
    }
}

// --- TerminalSession 扩展属性（与 TerminalSession 同包，app 层无需额外 import） ---

/**
 * 进程 pid。
 * Java 版语义：0=未初始化（尚未 execute），>0=运行中，-1=已结束。
 */
val TerminalSession.pid: Int
    get() = TerminalSessionCompat.getPid(id)

/** `pid` 的 Compose 可观察 StateFlow 版本，收集它可实时感知会话状态变化。 */
val TerminalSession.pidState: StateFlow<Int>
    get() = TerminalSessionCompat.getPidState(id)

/** shellPid 别名，与 pid 语义一致。 */
val TerminalSession.shellPid: Int
    get() = pid

/** 会话是否已结束（进程退出瞬间变 true，配合 collectAsState 可让 UI 立即感知）。 */
val TerminalSession.sessionExited: StateFlow<Boolean>
    get() = TerminalSessionCompat.getExited(id)
