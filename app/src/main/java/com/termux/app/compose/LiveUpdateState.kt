package com.termux.app.compose

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LiveUpdate 通知状态总线。
 *
 * 三档优先级（越靠前越优先）：
 *   1. 包管理器操作中（install/uninstall/update/upgrade）
 *   2. Termux Agent 正在执行（思考+输出阶段）
 *   3. 终端会话运行中（现有逻辑）
 *
 * TermuxService.buildNotification() 读取此状态决定通知样式。
 * 包管理器 / Agent 通过对应 API 发布状态。
 *
 * 包操作统一使用 [pkgScope] 执行 — 独立于 UI 生命周期，切后台不会被取消。
 */
object LiveUpdateState {

    // ====== 1. 包管理器状态 ======

    enum class PkgOperation { INSTALL, UNINSTALL, UPDATE, UPGRADE }

    data class PkgState(
        val operation: PkgOperation,
        val packageName: String,
        /** 进度 0-100；-1 表示 indeterminate */
        val progress: Int = -1,
        val finished: Boolean = false,
        val success: Boolean = false
    )

    private val _pkgState = MutableStateFlow<PkgState?>(null)
    val pkgState: StateFlow<PkgState?> = _pkgState.asStateFlow()

    /** 实时 log（流式追加，max ~ 20000 字符） */
    private val _pkgLog = MutableStateFlow("")
    val pkgLog: StateFlow<String> = _pkgLog.asStateFlow()

    /**
     * 包操作交互式输入通道：页面检测到需要用户输入（如 keep/replace 之类的 apt/dpkg 提示）时，
     * 把用户的答案写到这里；AppShell.execStreaming 会把它转交给底层进程的标准输入，命令得以继续。
     */
    val pkgStdin = Channel<String>(Channel.UNLIMITED)

    /** 全局包操作协程 — 独立于 Activity/Composable 生命周期 */
    val pkgScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @JvmStatic fun getPkgStateSnapshot(): PkgState? = _pkgState.value

    /** 开始包操作（初始进度 -1 indeterminate） */
    @JvmStatic
    fun startPkg(operation: PkgOperation, packageName: String) {
        _pkgState.value = PkgState(operation, packageName, progress = -1)
        _pkgLog.value = ""
        notifyChanged()
    }

    /** 更新包操作进度（0-100） */
    @JvmStatic
    fun updatePkgProgress(progress: Int) {
        val current = _pkgState.value ?: return
        _pkgState.value = current.copy(progress = progress.coerceIn(-1, 100))
        notifyChanged()
    }

    /** 追加实时 log（截断到 20000 字符上限） */
    @JvmStatic
    fun appendPkgLog(delta: String) {
        if (delta.isEmpty()) return
        val cur = _pkgLog.value
        val next = cur + delta
        _pkgLog.value = if (next.length > 20000) next.substring(next.length - 20000) else next
    }

    /** 包操作完成。完成态保留到 clearPkg()（任务页对勾退出时消费），供任务页与调用方恢复状态 */
    @JvmStatic
    fun finishPkg(success: Boolean) {
        val current = _pkgState.value ?: return
        _pkgState.value = current.copy(finished = true, success = success)
        notifyChanged()
    }

    /** 取消/清除包状态 */
    @JvmStatic
    fun clearPkg() {
        _pkgState.value = null
        _pkgLog.value = ""
        notifyChanged()
    }

    // ====== 2. Agent 状态 ======

    data class AgentState(
        val active: Boolean = false,
        /** 思考中(true)或输出中(false)；通知文案"Agent 正在执行任务"，药丸"思考中" */
        val thinking: Boolean = false,
        /** 后台模式：对话页面不活跃时仍由 LiveUpdate 承载 */
        val backgrounded: Boolean = false
    )

    private val _agentState = MutableStateFlow(AgentState())
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    @JvmStatic fun getAgentStateSnapshot(): AgentState = _agentState.value

    /** Agent 开始一次新的执行（思考阶段） */
    @JvmStatic
    fun agentStart(backgrounded: Boolean = false) {
        _agentState.value = AgentState(active = true, thinking = true, backgrounded = backgrounded)
        // 对话执行真正开始 —— 通知会话宿主，页面离开时才知道要不要留悬浮窗入口
        AgentChatSession.onExecutionStart()
        notifyChanged()
    }

    /** Agent 进入输出阶段（</think> 已出现） */
    @JvmStatic
    fun agentSwitchToOutput() {
        val cur = _agentState.value
        if (!cur.active) return
        _agentState.value = cur.copy(thinking = false)
        notifyChanged()
    }

    /** Agent 执行结束（正常完成 / 被中断） */
    @JvmStatic
    fun agentStop() {
        if (!_agentState.value.active) return
        _agentState.value = AgentState()
        // 结束点驱动悬浮气泡的"回答完成"提示；用户主动停止时由 userStopped 抑制
        AgentChatSession.onExecutionEnd()
        notifyChanged()
    }

    // ====== 通知变化回调（TermuxService 注册） ======

    fun interface OnChangeListener { fun onChanged() }

    private val listeners = mutableListOf<OnChangeListener>()

    @JvmStatic
    fun addListener(listener: OnChangeListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    @JvmStatic
    fun removeListener(listener: OnChangeListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyChanged() {
        val snap = synchronized(listeners) { listeners.toList() }
        for (l in snap) {
            try { l.onChanged() } catch (_: Throwable) {}
        }
    }

    // ====== 优先级判断 ======

    @JvmStatic
    fun hasPkg(): Boolean = _pkgState.value != null && !_pkgState.value!!.finished

    @JvmStatic
    fun hasAgent(): Boolean = _agentState.value.active
}
