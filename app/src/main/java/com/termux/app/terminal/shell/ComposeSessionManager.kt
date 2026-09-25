package com.termux.app.terminal.shell

import android.content.Context
import com.awkoo.libterminal.engine.TerminalSession
import com.awkoo.libterminal.process.ITerminalProcess
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.termux.shared.compat.ShellEnvironmentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * 终端会话管理器。
 *
 * 单例，全局持久持有所有终端会话。会话创建后，即使 Activity 退出也不会被 kill
 *（除非显式调用 killSession 或 killAllSessions）。
 *
 * 与 Java 版 TermuxService 多会话体系并行存在。
 */
class ComposeSessionManager private constructor(private val context: Context) {

    data class SessionInfo(
        val session: TerminalSession,
        val name: String
    )

    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions.asStateFlow()

    /** 当前活跃会话的 id（-1 表示无） */
    private val _currentSessionId = MutableStateFlow<Int>(-1)
    val currentSessionId: StateFlow<Int> = _currentSessionId.asStateFlow()

    /** 当前活跃会话（可能为 null） */
    val currentSession: TerminalSession?
        get() = _sessions.value.firstOrNull { it.session.id == _currentSessionId.value }?.session

    /** 会话 id 分配器（原子，避免 binder 线程与主线并发创建时产生重复 id）。 */
    private val nextId = AtomicInteger(1)

    /** 保护 _sessions 列表读-改-写的锁（create/kill 可能跨线程调用）。 */
    private val sessionsLock = Any()

    /** 会话状态观察协程作用域。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 创建新会话、启动 shell、设为当前活跃会话。
     *
     * @param startImmediately 为 false 时创建"未初始化"会话（效仿 Java 版策略：
     * 只登记会话条目，不启动进程、不切换当前会话；待用户手动点击进入时再调用
     * [TerminalSession.execute] 初始化）。
     */
    fun createSession(
        shellPath: String,
        cwd: String,
        args: Array<String>,
        env: Array<String>,
        sessionName: String = "",
        startImmediately: Boolean = true
    ): TerminalSession {
        val sessionId = nextId.getAndIncrement()

        // 初始化兼容层注册表：pidState 默认 0（未初始化），sessionExited 默认 false
        TerminalSessionCompat.registerSession(sessionId)

        val processFactory: (Int, Int, Int, Int) -> ITerminalProcess = { rows, cols, cw, ch ->
            val bridge = TermuxProcessBridge(shellPath, cwd, args, env, rows, cols, cw, ch)
            TerminalSessionCompat.setPid(sessionId, bridge.pid)
            bridge
        }

        val session = TerminalSession(
            id = sessionId,
            sessionName = kotlinx.coroutines.flow.MutableStateFlow(sessionName),
            processFactory = processFactory
        )

        // 观察会话运行态，维护 pidState / sessionExited 兼容属性的实时更新
        scope.launch {
            session.isRunning.collect { isRunning ->
                TerminalSessionCompat.updateFromUi(sessionId, isRunning)
            }
        }

        synchronized(sessionsLock) {
            _sessions.value = _sessions.value + SessionInfo(session, sessionName)
        }

        if (startImmediately) {
            session.execute()
            _currentSessionId.value = session.id
            // 进程启动后注入自动执行命令
            try {
                val prefs = context.getSharedPreferences("termux_preferences", android.content.Context.MODE_PRIVATE)
                val cmd = prefs.getString("auto_start_command", "") ?: ""
                if (cmd.isNotBlank()) {
                    val withNewline = if (cmd.endsWith('\n')) cmd else cmd + '\n'
                    session.write(withNewline.toByteArray())
                }
            } catch (_: Throwable) {}
        }
        notifySessionsChanged()
        return session
    }

    /**
     * 创建一个"默认 shell" 新会话。
     *
     * @param startImmediately 为 false 时只创建未初始化的会话条目（不启动进程、不切换当前会话），
     * 与 Java 版主页"新建终端"策略一致。
     * @param isFailsafe 为 true 时效仿 Java 版安全模式（failsafe）会话：
     * 使用最小化环境（buildEnvironment 保留系统 PATH，可用系统二进制）、
     * 跳过 login/bash/zsh 搜索直接用 /system/bin/sh，且不以 login shell 启动
     * （不加载 ~/.profile，避免配置文件损坏导致会话无法启动）。
     */
    fun createDefaultSession(startImmediately: Boolean = true, isFailsafe: Boolean = false): TerminalSession {
        val envClient = ShellEnvironmentCompat(TermuxShellEnvironment())
        val prefs = context.getSharedPreferences("termux_preferences", Context.MODE_PRIVATE)
        val workingDir = prefs.getString("current_session_dir", null)
            ?: envClient.getDefaultWorkingDirectoryPath()
        val env = envClient.buildEnvironment(context, isFailsafe, workingDir)
        val defaultBinPath = envClient.getDefaultBinPath().ifEmpty { "/system/bin" }

        var shellPath: String? = null
        var isLoginShell = false
        if (!isFailsafe) {
            // 仅普通模式搜索 login/bash/zsh；安全模式效仿 Java 版直接回退到 /system/bin/sh
            for (binary in arrayOf("login", "bash", "zsh")) {
                val f = File(defaultBinPath, binary)
                if (f.canExecute()) {
                    shellPath = f.absolutePath
                    isLoginShell = true
                    break
                }
            }
        }
        if (shellPath == null) shellPath = "/system/bin/sh"

        val processArgs = envClient.setupProcessArgs(shellPath, emptyArray())
        val executable = processArgs[0]
        val shellBasename = executable.substringAfterLast('/')
        val argv0 = if (isLoginShell) "-$shellBasename" else shellBasename
        val args = arrayOf(argv0) + processArgs.drop(1)

        return createSession(
            shellPath = executable,
            cwd = workingDir,
            args = args,
            env = env,
            sessionName = "",
            startImmediately = startImmediately
        )
    }

    /**
     * 切换当前活跃会话。id 必须存在于 sessions 列表中。
     */
    fun switchTo(sessionId: Int) {
        if (_sessions.value.any { it.session.id == sessionId }) {
            _currentSessionId.value = sessionId
        }
    }

    /**
     * 结束指定会话。如果关闭的是当前会话，自动接管原索引处的会话（越界取末尾）。
     */
    fun killSession(sessionId: Int) {
        var removedIndex = -1
        val info = synchronized(sessionsLock) {
            removedIndex = _sessions.value.indexOfFirst { it.session.id == sessionId }
            _sessions.value.getOrNull(removedIndex)
        } ?: return
        info.session.finishIfRunning()

        synchronized(sessionsLock) {
            val remaining = _sessions.value.filter { it.session.id != sessionId }
            _sessions.value = remaining

            if (_currentSessionId.value == sessionId) {
                // 优先接管原索引处会话，越界取末尾
                val index = if (removedIndex >= remaining.size) remaining.size - 1 else removedIndex
                _currentSessionId.value = remaining.getOrNull(index)?.session?.id ?: -1
            }
        }
        notifySessionsChanged()
    }

    /** 结束所有会话。 */
    fun killAllSessions() {
        synchronized(sessionsLock) {
            _sessions.value.forEach { it.session.finishIfRunning() }
            _sessions.value = emptyList()
            _currentSessionId.value = -1
        }
        // 注意：不重置 nextId，保持会话 handle 全局单调递增，
        // 避免切换核心后旧的 sessionHandle intent extra 解析到已不存在的会话。
        notifySessionsChanged()
    }

    /**
     * 会话列表变化通知（创建/关闭时触发，用于刷新 LiveUpdate 通知的会话数量）。
     * 由宿主（TermuxService）注册，libterminal 模块不反向依赖 app 层。
     */
    private fun notifySessionsChanged() {
        try {
            onSessionsChanged?.invoke()
        } catch (_: Throwable) {
        }
    }

    companion object {
        @Volatile
        private var instance: ComposeSessionManager? = null

        /** 会话列表变化回调（app 层在 TermuxService.onCreate 中注册）。 */
        @JvmStatic
        @Volatile
        var onSessionsChanged: (() -> Unit)? = null

        @JvmStatic
        fun getInstance(context: Context): ComposeSessionManager {
            return instance ?: synchronized(this) {
                instance ?: ComposeSessionManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
