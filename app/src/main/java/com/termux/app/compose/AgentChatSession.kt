package com.termux.app.compose

/**
 * Agent 对话生命周期的进程级宿主。
 *
 * ## 为什么需要它
 *
 * [com.termux.app.activities.AiTermuxActivity] 此前用 `by viewModels()` 持有
 * `AiTermuxViewModel`，而 ViewModel 的 `viewModelScope` 绑定在 Activity 的
 * ViewModelStore 上。用户离开对话页（`finish()`）时 ViewModel 被 `onCleared()`，
 * `viewModelScope` 随之取消 —— 正在执行的对话被强行中断，已流式输出的半截内容留在
 * 内存里随进程一起丢掉。
 *
 * 本单例把「执行态」从 ViewModel 中剥离出来，交给一个与进程同生命周期的 scope 持有：
 * 页面销毁不再取消对话执行；新的 Activity 重新挂载后能从同一份状态继续观察流式输出。
 *
 * 与 [LiveUpdateState] 是同一套思路的延伸 —— 后者负责把执行状态广播给前台服务通知，
 * 本者负责真正持有执行协程本身，两者配合构成「后台持续执行 + 通知/悬浮窗可见」。
 *
 * ## 状态如何在页面间传递
 *
 * 状态用 Snapshot 状态（`mutableStateListOf` / `mutableStateOf`）而非 StateFlow：
 * UI 侧原有的 `vm.messages` 读法完全不变，只是这些集合现在由本单例持有，
 * 同一进程内重建的 Activity 读到的是同一份对象，无需重新从磁盘加载。
 *
 * ## 销毁
 *
 * 仅在进程真正结束时随进程回收，**不**在 Activity `onDestroy` 中清理 ——
 * 那正是要修掉的 bug。真正的用户主动终止入口是 [cancelGeneration]。
 */
object AgentChatSession {

    /**
     * 对话执行 scope —— 独立于 Activity / ViewModel 生命周期。
     *
     * 用 [SupervisorJob] 而非默认 Job：一个回合失败不应连带杀掉整个 scope。
     * `Dispatchers.IO` 与原 `viewModelScope`（`Dispatchers.Main.immediate`）的差别由
     * 各执行点自身的 `synchronized` + 快照状态保证一致，切换不改变可见语义。
     */
    val scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
    )

    /** 当前进行中的生成任务；用户点「停止」时真正取消协程，而不只是置标志位 */
    @Volatile
    var generationJob: kotlinx.coroutines.Job? = null

    /** 与 ViewModel 中的 cancelled 标志同义，抽到宿主以便跨页面共享 */
    @Volatile
    var cancelled: Boolean = false

    /**
     * 是否有对话正在执行（思考 + 输出 + 技能执行全过程）。
     *
     * 供悬浮窗与通知侧判断「是否需要在后台继续展示入口」。
     */
    @Volatile
    var isExecuting: Boolean = false

    /** 用户主动停止过当前这一轮 —— 用于抑制不该出现的「回答完成」提示 */
    @Volatile
    var userStopped: Boolean = false

    /**
     * 完整对话在后台跑完、但用户还没回来看过结果。
     *
     * 用于完成瞬间的「回答完成」气泡提示 —— 这是唯一一个需要跨越页面销毁去保留的时刻，
     * 因为提示必须出现在用户离开之后。
     */
    @Volatile
    var completedWhileAway: Boolean = false

    /** 标记一次新的执行开始。 */
    @JvmStatic
    fun onExecutionStart() {
        cancelled = false
        userStopped = false
        isExecuting = true
        completedWhileAway = false
    }

    /**
     * 标记执行结束（由 [LiveUpdateState.agentStop] 统一调用）。
     *
     * 用户主动停止时不弹「回答完成」——那是用户自己终止的，不是完成。
     */
    @JvmStatic
    fun onExecutionEnd() {
        isExecuting = false
        if (userStopped) return
        completedWhileAway = true
        AgentChatBubblePolicy.onExecutionFinished()
    }

    /** 用户主动停止：取消协程本体，让正在执行的技能循环立即退出。 */
    @JvmStatic
    fun cancel() {
        cancelled = true
        userStopped = true
        isExecuting = false
        completedWhileAway = false
        generationJob?.cancel()
        generationJob = null
    }

    /** 用户回到对话页并已看到结果 → 清掉待提示状态，避免下次弹出陈旧的「回答完成」。 */
    @JvmStatic
    fun consumeCompletionNotice() {
        completedWhileAway = false
    }

    /**
     * 彻底复位。会话被删除等场景使用。
     */
    @JvmStatic
    fun reset() {
        isExecuting = false
        completedWhileAway = false
        cancelled = false
        userStopped = false
        generationJob = null
    }
}