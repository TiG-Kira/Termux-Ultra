package com.termux.app.terminal.shell

import android.content.Context
import android.util.Log
import com.awkoo.libterminal.engine.TerminalSession
import com.awkoo.libterminal.process.ITerminalProcess
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.termux.shared.termux.TermuxConstants
import com.termux.shared.compat.ShellEnvironmentCompat
import com.termux.R
import com.termux.app.vortex.VorteXSandbox
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

    /** PREFIX 属主修复每进程只尝试一次，避免反复弹 su 授权。 */
    private val ownershipRepairAttempted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** exec 探测结果缓存（路径 → 可执行），属主修复成功后清空。 */
    private val execProbeCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 创建新会话、启动 shell、设为当前活跃会话。
     *
     * @param startImmediately 为 false 时创建"未初始化"会话（效仿 Java 版策略：
     * 只登记会话条目，不启动进程、不切换当前会话；待用户手动点击进入时再调用
     * [initializeSessionIfNeeded] 初始化）。
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
            val bridge = TermuxProcessBridge(
                shellPath, cwd, args, env, rows, cols, cw, ch,
                onInputCommand = { command -> TerminalSessionCompat.setLastCommand(sessionId, command) }
            )
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
            // 走统一初始化入口：execute + 注入自动执行命令（与延迟初始化路径同一套逻辑）
            initializeSessionIfNeeded(session)
            _currentSessionId.value = session.id
        }
        notifySessionsChanged()
        return session
    }

    /**
     * 按需初始化会话：未启动（pid == 0）时才拉起进程，并在首次启动后注入
     * 「新会话自动执行」命令（termux_preferences 的 auto_start_command）。
     *
     * 所有延迟初始化入口（TermuxActivityBridge / TerminalDetailScreenCompose 等）
     * 必须走此方法，不能直接调用 [TerminalSession.execute]——后者会漏掉自动执行
     * 注入，导致只有 startImmediately=true 的会话（沙箱会话 / 脚本执行入口）生效，
     * 而用户手动「+」新建的普通会话不触发。
     *
     * 已初始化（pid > 0）或已结束（pid == -1）的会话不会重复执行/重复注入，
     * 因此反复进入同一会话不会重复运行自动执行命令。
     */
    @Synchronized
    fun initializeSessionIfNeeded(session: TerminalSession) {
        if (session.pid != 0) return
        session.execute()
        injectAutoStartCommand(session)
    }

    /**
     * 把「新会话自动执行」命令写入会话 stdin。
     *
     * 调用方必须确保会话已 [TerminalSession.execute]（isRunning 已置 true）：
     * libterminal 的 write() 在 isRunning=false 时会静默丢弃非换行数据。
     */
    private fun injectAutoStartCommand(session: TerminalSession) {
        try {
            val prefs = context.getSharedPreferences("termux_preferences", Context.MODE_PRIVATE)
            val cmd = prefs.getString("auto_start_command", "") ?: ""
            if (cmd.isNotBlank()) {
                val withNewline = if (cmd.endsWith('\n')) cmd else cmd + '\n'
                session.write(withNewline.toByteArray())
            }
        } catch (_: Throwable) {}
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
        var probeSuspect = false
        if (!isFailsafe) {
            // 仅普通模式搜索 login/bash/zsh；安全模式效仿 Java 版直接回退到 /system/bin/sh。
            // canExecute 只查权限位，还需真实 exec 探测，拦住「检查放行、execve 才报
            // Permission denied」的属主/挂载层异常（见 maybeSchedulePrefixOwnershipRepair）
            for (binary in arrayOf("login", "bash", "zsh")) {
                val f = File(defaultBinPath, binary)
                if (!f.canExecute()) continue
                if (isActuallyExecutable(f)) {
                    shellPath = f.absolutePath
                    isLoginShell = true
                    break
                }
                probeSuspect = true
            }
            if (shellPath == null) probeSuspect = true
            maybeSchedulePrefixOwnershipRepair(File(defaultBinPath), probeSuspect)
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
     * 真实 exec 可行性探测。
     *
     * File.canExecute() 只查权限位，发现不了属主错乱、挂载 noexec 等
     * 「检查时放行、execve 时才报 Permission denied」的异常。这里用 ProcessBuilder
     * 实际 exec 一次（execve 语义与 JNI.createSubprocess 一致），失败即抛 IOException；
     * 成功则立即销毁进程。结果按进程缓存，避免每次新建会话都空跑候选 shell
     * （login 会连带跑一次用户 profile）；属主修复成功后清缓存以便重探。
     */
    private fun isActuallyExecutable(file: File): Boolean {
        val path = file.absolutePath
        execProbeCache[path]?.let { return it }
        val result = try {
            val process = ProcessBuilder(path).start()
            process.destroy()
            if (!process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)
            }
            true
        } catch (_: Throwable) {
            false
        }
        execProbeCache[path] = result
        return result
    }

    /**
     * 候选 shell exec 探测失败时的 $PREFIX 属主自修复。
     *
     * root 用户在终端里用 su 跑过 apt/dpkg、或用 root 文件管理器/备份工具碰过 $PREFIX，
     * 会让文件属主偏离本 app uid，表现为新会话报 exec(".../login"): Permission denied。
     * 仅在探测确实失败且检测到属主异常时才动手：后台用 su 把 $PREFIX 整体 chown 回本
     * uid；属主正常（纯 SELinux/挂载等原因）则不动，避免无谓弹出 su 授权。每进程只尝试一次。
     */
    private fun maybeSchedulePrefixOwnershipRepair(binDir: File, probeSuspect: Boolean) {
        if (!probeSuspect) return
        try {
            val myUid = android.os.Process.myUid()
            val anomalous = arrayOf("login", "bash", "zsh", "sh").any { name ->
                val f = File(binDir, name)
                f.exists() && android.system.Os.lstat(f.absolutePath).st_uid != myUid
            }
            if (!anomalous) return
            if (!ownershipRepairAttempted.compareAndSet(false, true)) return
            scope.launch {
                try {
                    val cmd = "chown -R $myUid:$myUid ${TermuxConstants.TERMUX_PREFIX_DIR_PATH}"
                    val process = ProcessBuilder("su", "-c", cmd)
                        .redirectErrorStream(true)
                        .start()
                    val done = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
                    if (!done) process.destroyForcibly()
                    process.inputStream.readBytes() // 排空管道，防 banner 填满缓冲卡住 su
                    if (done && process.exitValue() == 0) {
                        execProbeCache.clear()
                        Log.i(TAG, "PREFIX ownership repair done")
                    } else {
                        Log.w(TAG, "PREFIX ownership repair ${if (done) "exit=${process.exitValue()}" else "timed out"}")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "PREFIX ownership repair failed: ${t.message}")
                }
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * 创建并进入一个 VorteX 沙箱会话。
     *
     * 与默认 shell 不同，这里把 shell 指向 VorteX 沙箱引导脚本（交互模式），
     * 并把 $HOME 与 cwd 重定向到沙箱可写层、注入 VORTEX_ROOT / VORTEX_SANDBOX 环境变量，
     * 使该会话完全运行在隔离环境内，且自带虚拟 ROOT。
     *
     * 会话标题固定为 [VorteXSandbox.SANDBOX_SESSION_TITLE]（"沙箱会话"），
     * 由调用方配合「同时仅允许一个手动沙箱会话」的限制使用。
     */
    fun createSandboxSession(startImmediately: Boolean = true): TerminalSession {
        // 注意：不要在这里同步调ensureInitialized()——它可能触发全量 $HOME 拷贝，
        // 而本方法由终端列表页的按钮回调直接调用（运行在 UI 线程），会 ANR 闪退。
        // 引导脚本自身在会话启动时会做完整性校验与重播，这里只做幂等且廉价的准备。
        VorteXSandbox.ensureInitializedAsync(context)
        val vortexRoot = VorteXSandbox.getRootDir(context).absolutePath
        val bootstrap = VorteXSandbox.getBootstrapExecutable(context).absolutePath

        val envClient = ShellEnvironmentCompat(TermuxShellEnvironment())
        // 注意：HOME **不**在这里改写成 run/home。
        // proot 方案下，沙箱内看到的 $HOME 必须仍是真实绝对路径
        // （/data/data/com.termux/files/home），只是该路径被 proot 影子化遮蔽；
        // 若在此处提前改成 run/home，proot 的 -b 绑定会失效并造成路径混淆。
        // 轻量降级模式所需的 HOME 重定向由引导脚本自行完成。
        val realHome = File(TermuxConstants.TERMUX_HOME_DIR_PATH).absolutePath
        val baseEnv = envClient.buildEnvironment(context, false, realHome).toMutableList()
        val env = baseEnv
            .filter { !it.startsWith("VORTEX_") }
            .toMutableList()
        env.add("VORTEX_ROOT=$vortexRoot")
        env.add("VORTEX_REAL_HOME=$realHome")
        env.add("VORTEX_SANDBOX=1")

        return createSession(
            shellPath = bootstrap,
            cwd = realHome,
            args = arrayOf("--interactive"),
            env = env.toTypedArray(),
            sessionName = context.getString(R.string.vortex_sandbox_session_title),
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
        // 沙箱会话结束 → 彻底回收影子空间。
        // 注意顺序：**先 finish 再回收**。tmp/proc 是 proot guest 的 /proc 绑定，
        // 会话还活着就删会把 guest 的 /proc 抽空，bash 疯狂 ENOENT 直至卡死；
        // 回收本身已切后台线程，不会再阻塞 UI。
        // 名字必须在 finish 前取：finish 后会话对象可能已被重置。
        val sessionName = info.name
        info.session.finishIfRunning()
        VorteXSandbox.onSessionEnded(context, sessionName)
        TerminalSessionCompat.unregister(sessionId)

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
            _sessions.value.forEach {
                // 同 killSession：先 finish 再回收，别抽活会话的 /proc。
                val name = it.name
                it.session.finishIfRunning()
                VorteXSandbox.onSessionEnded(context, name)
                TerminalSessionCompat.unregister(it.session.id)
            }
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
        private const val TAG = "ComposeSessionManager"

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
