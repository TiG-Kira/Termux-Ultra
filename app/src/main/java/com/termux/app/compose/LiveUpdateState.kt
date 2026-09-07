package com.termux.app.compose

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        val success: Boolean = false,
        /** 后台运行模式：UI 隐藏操作弹窗，由 LiveUpdate 通知承载 */
        val backgrounded: Boolean = false
    )

    private val _pkgState = MutableStateFlow<PkgState?>(null)
    val pkgState: StateFlow<PkgState?> = _pkgState.asStateFlow()

    /** 实时 log（流式追加，max ~ 20000 字符） */
    private val _pkgLog = MutableStateFlow("")
    val pkgLog: StateFlow<String> = _pkgLog.asStateFlow()

    /** 后台操作结束后恢复弹窗标记 — 置位后 UI 观察到此值变为 true 时弹回 */
    private val _pkgResumeRequest = MutableStateFlow(false)
    val pkgResumeRequest: StateFlow<Boolean> = _pkgResumeRequest.asStateFlow()

    /** 全局包操作协程 — 独立于 Activity/Composable 生命周期 */
    val pkgScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @JvmStatic fun getPkgStateSnapshot(): PkgState? = _pkgState.value

    /** 开始包操作（初始进度 -1 indeterminate） */
    @JvmStatic
    fun startPkg(operation: PkgOperation, packageName: String, backgrounded: Boolean = false) {
        _pkgState.value = PkgState(operation, packageName, progress = -1, backgrounded = backgrounded)
        _pkgLog.value = ""
        _pkgResumeRequest.value = false
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

    /** 包操作完成 */
    @JvmStatic
    fun finishPkg(success: Boolean) {
        val current = _pkgState.value ?: return
        _pkgState.value = current.copy(finished = true, success = success)
        notifyChanged()
        // 5 秒后清除，让完成态有机会被看到
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            _pkgState.value = null
            _pkgLog.value = ""
            notifyChanged()
        }, 5000)
    }

    /** 取消/清除包状态 */
    @JvmStatic
    fun clearPkg() {
        _pkgState.value = null
        _pkgLog.value = ""
        notifyChanged()
    }

    /** 后台操作 → 请求前台 UI 恢复弹窗（PackageManagerScreen 右上角按钮调用） */
    @JvmStatic
    fun requestResumePkg() {
        _pkgResumeRequest.value = true
    }

    /** UI 消费完恢复请求后调用 */
    @JvmStatic
    fun consumeResumeRequest() {
        _pkgResumeRequest.value = false
    }

    /** 前台操作转后台 — 更新 PkgState.backgrounded 标志（通知和右上角按钮靠此判断） */
    @JvmStatic
    fun markPkgBackgrounded() {
        val cur = _pkgState.value ?: return
        _pkgState.value = cur.copy(backgrounded = true)
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
