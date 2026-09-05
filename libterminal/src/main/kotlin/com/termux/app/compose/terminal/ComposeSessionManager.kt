package com.termux.app.compose.terminal

import android.content.Context
import com.termux.app.compose.terminal.engine.TerminalSession
import com.termux.app.compose.terminal.process.ITerminalProcess
import com.termux.app.compose.terminal.process.TermuxProcessBridge
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.shell.TermuxShellUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Compose 模式下的终端会话管理器。
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

    private var nextId = 1

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
        val processFactory: (Int, Int, Int, Int) -> ITerminalProcess = { rows, cols, cw, ch ->
            TermuxProcessBridge(shellPath, cwd, args, env, rows, cols, cw, ch)
        }

        val session = TerminalSession(
            id = nextId++,
            sessionName = kotlinx.coroutines.flow.MutableStateFlow(sessionName),
            processFactory = processFactory
        )

        _sessions.value = _sessions.value + SessionInfo(session, sessionName)
        if (startImmediately) {
            session.execute()
            _currentSessionId.value = session.id
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
        val envClient = TermuxShellEnvironmentClient()
        val prefs = context.getSharedPreferences("termux_preferences", Context.MODE_PRIVATE)
        val workingDir = prefs.getString("current_session_dir", null)
            ?: envClient.getDefaultWorkingDirectoryPath()
        val env = TermuxShellUtils.buildEnvironment(context, isFailsafe, workingDir)
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
     * 结束指定会话。如果关闭的是当前会话，自动切换到列表中下一个可用的（或上一个）。
     */
    fun killSession(sessionId: Int) {
        val info = _sessions.value.firstOrNull { it.session.id == sessionId } ?: return
        info.session.finishIfRunning()

        val remaining = _sessions.value.filter { it.session.id != sessionId }
        _sessions.value = remaining

        if (_currentSessionId.value == sessionId) {
            _currentSessionId.value = remaining.firstOrNull()?.session?.id ?: -1
        }
        notifySessionsChanged()
    }

    /** 结束所有会话。 */
    fun killAllSessions() {
        _sessions.value.forEach { it.session.finishIfRunning() }
        _sessions.value = emptyList()
        _currentSessionId.value = -1
        nextId = 1
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
