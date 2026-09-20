package com.termux.app.compose

import java.io.File

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.termux.app.utils.SnackbarHelper
import com.google.android.material.snackbar.Snackbar
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.auth.AuthPromptCallback
import androidx.biometric.auth.startClass2BiometricOrCredentialAuthentication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.termux.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 风险命令确认管理器。
 *
 * 负责：
 * 1. 通过 [RiskCommandDetector] 检测命令是否为高危命令
 * 2. 通过 WindowDialog 要求用户二次确认
 * 3. 根据用户选择决定放行或拦截
 * 4. 管理"高风险命令二次确认"开关状态
 */
object RiskConfirmManager {

    const val PREFS_NAME = "termux_risk_confirm"
    const val KEY_ENABLED = "risk_confirm_enabled"       // 迁移用：旧的布尔开关
    const val KEY_PROTECTION_LEVEL = "protection_level"   // 新的保护级别 (Int)
    const val KEY_PENDING_SESSION_HANDLE = "pending_session_handle"
    const val KEY_PENDING_COMMAND = "pending_command"
    const val KEY_PENDING_RESULT = "pending_result"
    const val RESULT_CONFIRMED = "confirmed"
    const val RESULT_DENIED = "denied"

    const val KEY_AGENT_PENDING_ACTION = "agent_pending_action"
    const val KEY_AGENT_PENDING_PARAMS = "agent_pending_params"
    const val KEY_AGENT_PENDING_MESSAGE_ID = "agent_pending_message_id"
    const val KEY_AGENT_PENDING_RESULT = "agent_pending_result"

    /** 跳过风险确认的标志：Agent 流程已确认的命令不需要二次确认 */
    @Volatile
    private var skipRiskCheck = false

    /** 设置跳过风险确认标志（Agent 流程已确认后调用） */
    fun setSkipRiskCheck(skip: Boolean) {
        skipRiskCheck = skip
    }

    /** 检查是否应跳过风险确认 */
    fun shouldSkipRiskCheck(): Boolean = skipRiskCheck

    /**
     * 检查无限制模式是否激活。
     * 无限制模式下：跳过所有风险确认，放开 Agent 全部限制。
     */
    fun isUnlimitedModeActive(context: Context): Boolean {
        return AiTermuxPrefs.isUnlimitedModeActive(context)
    }

    /** 标记上一次命令是否为自动拦截（AUTO_BLOCK 模式） */
    @Volatile
    private var lastCommandAutoBlocked = false

    /** 检查上一次命令是否为自动拦截 */
    fun isLastCommandAutoBlocked(): Boolean = lastCommandAutoBlocked

    /** 重置自动拦截标志 */
    fun resetAutoBlockedFlag() {
        lastCommandAutoBlocked = false
    }

    // ---- 性能优化：缓存防护等级，避免每次命令都读取 SharedPreferences ----
    @Volatile
    private var cachedProtectionLevel: ProtectionLevel? = null

    // 环境类型缓存（EnvironmentType）已随 InputInterceptor 拦截路径一并移除：
    // 唯一写入方是 detectEnvironment，而它只服务于被删除的
    // handleTerminalCommandInternal；现在所有活跃入口的 environmentType 都由调用方显式传入。

    /** 预加载缓存到内存（避免首次命令读取 SharedPreferences） */
    fun preloadCache(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val levelOrdinal = prefs.getInt(KEY_PROTECTION_LEVEL, ProtectionLevel.WARN_VERIFY.ordinal)
            cachedProtectionLevel = ProtectionLevel.entries.getOrElse(levelOrdinal) { ProtectionLevel.WARN_VERIFY }
        } catch (_: Exception) {
            // 忽略异常，保持缓存为 null
        }
    }

    // ---- Snackbar 事件流 ----
    data class SnackbarEvent(val message: String, val duration: Int = Snackbar.LENGTH_LONG)
    // SharedFlow(replay=0): 活跃 subscriber 实时收到，新 subscriber 不收历史
    // 仅终端详情页收集，主页不收集
    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(
        replay = 0,
        extraBufferCapacity = 32
    )
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents

    /** 发送 Snackbar 事件到所有活跃 collector。 */
    fun emitSnackbar(message: String, duration: Int = Snackbar.LENGTH_LONG) {
        _snackbarEvents.tryEmit(SnackbarEvent(message, duration))
    }

    // ---- 主页汇总 Snackbar（退出终端页时发送，显示统计信息） ----
    // 使用 replay=1 确保新订阅者（主页）激活后能收到最后一个事件
    data class SummarySnackbarEvent(
        val message: String,
        val duration: Int = Snackbar.LENGTH_LONG
    )
    private val _summarySnackbarEvents = MutableSharedFlow<SummarySnackbarEvent>(
        replay = 1,
        extraBufferCapacity = 8
    )
    val summarySnackbarEvents: SharedFlow<SummarySnackbarEvent> = _summarySnackbarEvents

    /** 发送汇总 Snackbar 到主页（退出终端页时调用） */
    fun emitSummarySnackbar(message: String, duration: Int = Snackbar.LENGTH_LONG) {
        _summarySnackbarEvents.tryEmit(SummarySnackbarEvent(message, duration))
    }

    // ---- 危险命令计数（统计用） ----
    private var dangerCommandCount: Int = 0
    private var lastProtectionLevel: ProtectionLevel = ProtectionLevel.OFF

    /** 增加危险命令计数，返回当前计数 */
    fun incrementDangerCount(): Int {
        dangerCommandCount++
        return dangerCommandCount
    }

    /** 获取当前危险命令计数 */
    fun getDangerCount(): Int = dangerCommandCount

    /** 重置危险命令计数 */
    fun resetDangerCount() {
        dangerCommandCount = 0
    }

    /** 设置最后使用的保护级别 */
    fun setLastProtectionLevel(level: ProtectionLevel) {
        lastProtectionLevel = level
    }

    /** 获取最后使用的保护级别 */
    fun getLastProtectionLevel(): ProtectionLevel = lastProtectionLevel

    /**
     * 检测设备是否拥有 ROOT 访问权限。
     * 通过尝试执行 "su -c echo 1" 并检查输出判断。
     *
     * @param context Context
     * @return true 表示设备已 root 且可用 su 命令
     */
    fun hasRootAccess(context: Context): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo", "1"))
            val result = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            result == "1"
        } catch (_: Exception) {
            false
        }
    }

    const val ACTION_RISK_RESULT = "com.termux.app.RISK_RESULT"
    const val EXTRA_RISK_RESULT = "extra_risk_result"
    const val EXTRA_SESSION_HANDLE = "extra_session_handle"

    /** 环境类型 */
    enum class EnvironmentType {
        NATIVE,      // 原生 Termux
        CONTAINER,   // proot 容器
        VM,          // 虚拟机
        SSH          // SSH 远程连接
    }

    /** 保护级别 */
    enum class ProtectionLevel(val displayName: String, val description: String) {
        OFF("关闭", "不检测危险命令"),
        WARN_ONLY("仅提示", "Snackbar 提示但不拦截"),
        WARN_VERIFY("警告并验证", "弹窗 + 倒计时 + 生物认证"),
        AUTO_BLOCK("自动拦截", "直接拒绝执行危险命令")
    }

    /** 弹窗状态 */
    data class DialogState(
        val command: String,
        val riskDescription: String,
        val riskType: String,
        val environmentType: EnvironmentType = EnvironmentType.NATIVE,
        val isSshPowerOperation: Boolean = false,
        /** 是否为 Windows 磁盘级命令（SSH 连接 Windows 时使用特殊警告文案） */
        val isWindowsDiskCommand: Boolean = false,
        /**
         * 该弹窗绑定的协程确认请求 id（阻塞式请求为空串）。
         * confirm/cancel 必须按它精确结算 —— 此前用 pendingRequests.keys.lastOrNull()
         * 取请求，而 HashMap 迭代顺序与插入顺序无关，多个请求同时在途时会把结果
         * 结算给错误的调用方（A 点了确认，B 拿到放行）。
         */
        val requestId: String = ""
    )

    internal val _dialogState = MutableStateFlow<DialogState?>(null)
    val dialogState: StateFlow<DialogState?> = _dialogState.asStateFlow()

    /** 倒计时（秒），60秒自动拒绝 */
    private val _countdown = MutableStateFlow(60)
    val countdown: StateFlow<Int> = _countdown.asStateFlow()

    /** 倒计时控制 */
    private var countdownJob: kotlinx.coroutines.Job? = null
    internal val countdownScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ===== Agent loading Dialog 状态 =====
    // 显示一个全局 loading 弹窗，提示"正在询问 Agent..."
    private val _agentLoadingVisible = kotlinx.coroutines.flow.MutableStateFlow(false)
    val agentLoadingVisible: kotlinx.coroutines.flow.StateFlow<Boolean> = _agentLoadingVisible
    private val _agentLoadingText = kotlinx.coroutines.flow.MutableStateFlow("")
    val agentLoadingText: kotlinx.coroutines.flow.StateFlow<String> = _agentLoadingText

    /** 显示"安全检测中"加载弹窗（SecuritySocketServer 脚本判定期间调用） */
    fun showAgentLoading(text: String? = null) {
        _agentLoadingText.value = text ?: "正在检测脚本安全性..."
        _agentLoadingVisible.value = true
    }

    /** 隐藏"安全检测中"加载弹窗 */
    fun hideAgentLoading() {
        _agentLoadingVisible.value = false
    }

    // ===== Agent 跳过判定二次确认 =====
    //
    // 触发场景：
    //   a) 用户在 Loading 弹窗点"跳过验证"按钮
    //   b) Agent 判定 TIMEOUT / ERROR / ABNORMAL 但本地检测安全
    // 两种场景共用一套 DialogState + pendingRequest 结算机制。
    // 弹窗显示后，用户确认才放行（PASS），否则拒绝（DENY）。

    /** Agent 跳过判定二次确认弹窗状态 */
    data class AgentSkipState(
        val command: String,
        /** SKIP=用户主动跳过；TIMEOUT=Agent超时；ABNORMAL=Agent异常 */
        val mode: Mode,
        /** 额外说明（来自本地检测结果或 Agent 返回的 reason） */
        val detail: String = "",
        val requestId: String = ""
    ) {
        enum class Mode { SKIP, TIMEOUT, ABNORMAL }
    }

    internal val _agentSkipState = MutableStateFlow<AgentSkipState?>(null)
    val agentSkipState: StateFlow<AgentSkipState?> = _agentSkipState.asStateFlow()

    /** 启动跳过二次确认（挂起协程等待结果） */
    suspend fun requestAgentSkipConfirm(
        context: Context,
        command: String,
        mode: AgentSkipState.Mode,
        detail: String = ""
    ): Boolean {
        if (isUnlimitedModeActive(context)) return true
        val level = getProtectionLevel(context)
        if (level == ProtectionLevel.OFF) return true

        val requestId = java.util.UUID.randomUUID().toString()
        val timeoutRunnable = Runnable { resolveAgentSkipRequest(requestId, false) }

        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                if (!continuation.isActive) return@suspendCancellableCoroutine

                preemptActiveRequest()
                pendingRequests[requestId] = { confirmed ->
                    cancelPendingTimeout(timeoutRunnable)
                    _agentSkipState.value = null
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(confirmed))
                    }
                }

                _agentSkipState.value = AgentSkipState(
                    command = command,
                    mode = mode,
                    detail = detail,
                    requestId = requestId
                )
                pendingTimeout = timeoutRunnable
                mainHandler.postDelayed(timeoutRunnable, CONFIRM_WAIT_SECONDS * 1000L)

                continuation.invokeOnCancellation {
                    cancelPendingTimeout(timeoutRunnable)
                    pendingRequests.remove(requestId)
                    if (_agentSkipState.value?.requestId == requestId) {
                        _agentSkipState.value = null
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelPendingTimeout(timeoutRunnable)
            pendingRequests.remove(requestId)
            throw e
        } catch (e: Exception) {
            cancelPendingTimeout(timeoutRunnable)
            pendingRequests.remove(requestId)
            _agentSkipState.value = null
            false
        }
    }

    /** 阻塞式跳过二次确认（供 SecuritySocketServer 在后台线程调用） */
    @JvmOverloads
    fun requestAgentSkipConfirmBlocking(
        context: Context,
        command: String,
        mode: AgentSkipState.Mode,
        detail: String = ""
    ): Boolean {
        if (isUnlimitedModeActive(context)) return true
        val level = getProtectionLevel(context)
        if (level == ProtectionLevel.OFF) return true

        if (Looper.myLooper() == Looper.getMainLooper()) {
            val result = arrayOf(false)
            val latch = CountDownLatch(1)
            CoroutineScope(Dispatchers.Default).launch {
                result[0] = doAgentSkipBlocking(context, command, mode, detail)
                latch.countDown()
            }
            try {
                latch.await(CONFIRM_WAIT_SECONDS.toLong(), TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                return false
            }
            return result[0]
        }
        return doAgentSkipBlocking(context, command, mode, detail)
    }

    private fun doAgentSkipBlocking(
        context: Context,
        command: String,
        mode: AgentSkipState.Mode,
        detail: String
    ): Boolean {
        val result = arrayOf(false)
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())
        val requestId = "skip-" + requestIdSeq.incrementAndGet()

        preemptActiveRequest()
        pendingRequests[requestId] = { confirmed ->
            result[0] = confirmed
            _agentSkipState.value = null
            latch.countDown()
        }

        handler.post {
            _agentSkipState.value = AgentSkipState(
                command = command,
                mode = mode,
                detail = detail,
                requestId = requestId
            )
        }
        handler.postDelayed({
            resolveAgentSkipRequest(requestId, false)
        }, CONFIRM_WAIT_SECONDS * 1000L)

        try {
            latch.await(CONFIRM_WAIT_SECONDS.toLong(), TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            resolveAgentSkipRequest(requestId, false)
            return false
        }
        return result[0]
    }

    /** 结算跳过确认请求 */
    private fun resolveAgentSkipRequest(requestId: String, confirmed: Boolean) {
        val callback = pendingRequests.remove(requestId)
        if (callback != null) {
            _agentSkipState.value = null
            callback(confirmed)
            return
        }
        // 阻塞式请求兜底：requestId 以 "skip-" 开头，也可能已经被 resolveRequest 吃掉
        if (_agentSkipState.value?.requestId == requestId) {
            _agentSkipState.value = null
        }
    }

    /** 用户点击"跳过验证"（Loading 弹窗上的按钮）。触发 SKIP 模式二次确认。 */
    fun requestSkipFromLoading(context: Context, command: String = "") {
        // 先 hide loading（否则两个 DialogWindow 叠加会冲突），然后 show skip confirm
        _agentLoadingVisible.value = false
        requestAgentSkipConfirmBlocking(
            context = context,
            command = command,
            mode = AgentSkipState.Mode.SKIP,
            detail = ""
        )
    }

    /** 用户在跳过二次确认弹窗上点"确认通过" */
    internal fun confirmAgentSkip() {
        val state = _agentSkipState.value ?: return
        resolveAgentSkipRequest(state.requestId, true)
    }

    /** 用户在跳过二次确认弹窗上点"取消" */
    internal fun cancelAgentSkip() {
        val state = _agentSkipState.value ?: return
        resolveAgentSkipRequest(state.requestId, false)
    }

    /** 自动确认等待时限(秒)：倒计时、auto-deny、latch await 三者一致。
     * 超过此时限（弹窗异常/未及时点击）自动按 DENY 返回并写响应，
     * 绝不让 shell 长时间卡死（此前 60s/90s 不一致导致“超90s才恢复/一直不恢复”）。 */
    private const val CONFIRM_WAIT_SECONDS = 25

    /** 开始倒计时 */
    internal fun startCountdown() {
        stopCountdown()
        _countdown.value = CONFIRM_WAIT_SECONDS
        countdownJob = countdownScope.launch {
            for (i in CONFIRM_WAIT_SECONDS downTo 1) {
                _countdown.value = i
                delay(1000)
            }
        }
    }

    /** 停止倒计时 */
    internal fun stopCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }

    /**
     * 挂起的确认请求（用于协程调用）。
     * 注册方在后台线程、结算方在主线程（用户点击），必须是并发容器。
     */
    private val pendingRequests =
        java.util.concurrent.ConcurrentHashMap<String, (Boolean) -> Unit>()

    /** requestId 自增序号。原实现用 System.currentTimeMillis() 作 id，
     * 同一毫秒内发起的两个请求会撞 id，后注册的覆盖先注册的回调。 */
    private val requestIdSeq = java.util.concurrent.atomic.AtomicLong(0)
    private fun nextRequestId(): String = "req-${requestIdSeq.incrementAndGet()}"

    /**
     * 按 requestId 精确结算一个挂起请求。remove 成功才执行回调，
     * 保证每个请求最多被结算一次（重复 resume 协程会抛 IllegalStateException）。
     */
    private fun resolveRequest(requestId: String, confirmed: Boolean) {
        val callback = pendingRequests.remove(requestId) ?: return
        callback(confirmed)
    }

    /**
     * 新弹窗顶掉旧弹窗前调用：把旧弹窗绑定的协程请求按「拒绝」结算。
     * 否则旧调用方会一直挂起，直到超时才恢复 —— 用户看到的是"点了没反应"。
     */
    private fun preemptActiveRequest() {
        val id = _dialogState.value?.requestId
        if (!id.isNullOrEmpty()) resolveRequest(id, false)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var pendingTimeout: Runnable? = null

    private fun cancelPendingTimeout(r: Runnable) {
        mainHandler.removeCallbacks(r)
        if (pendingTimeout === r) pendingTimeout = null
    }

    /** 阻塞式确认请求（用于 Service/Java 调用） */
    private var blockingRequest: ((Boolean) -> Unit)? = null
    private var blockingRequestActive = false

    /** "关闭二次确认" 警告弹窗状态 */
    data class DisableWarningState(
        val show: Boolean = false,
        val targetLevel: ProtectionLevel = ProtectionLevel.OFF
    )
    private val _disableWarningState = MutableStateFlow(DisableWarningState())
    val disableWarningState: StateFlow<DisableWarningState> = _disableWarningState.asStateFlow()

    /** 迁移旧的布尔开关到新的保护级别系统 */
    private fun migrateIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 如果 KEY_PROTECTION_LEVEL 不存在但 KEY_ENABLED 存在，执行迁移
        if (!prefs.contains(KEY_PROTECTION_LEVEL) && prefs.contains(KEY_ENABLED)) {
            val oldEnabled = prefs.getBoolean(KEY_ENABLED, true)
            val newLevel = if (oldEnabled) {
                ProtectionLevel.WARN_VERIFY.ordinal  // 2
            } else {
                ProtectionLevel.OFF.ordinal             // 0
            }
            prefs.edit()
                .putInt(KEY_PROTECTION_LEVEL, newLevel)
                .apply()
        }
    }

    /** 获取当前保护级别（优先从缓存读取） */
    fun getProtectionLevel(context: Context): ProtectionLevel {
        cachedProtectionLevel?.let { return it }
        migrateIfNeeded(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ordinal = prefs.getInt(KEY_PROTECTION_LEVEL, ProtectionLevel.WARN_VERIFY.ordinal)
        val level = ProtectionLevel.entries.getOrElse(ordinal) { ProtectionLevel.WARN_VERIFY }
        cachedProtectionLevel = level
        return level
    }

        /** 设置保护级别（同时更新缓存，并通知 SettingsScreen 刷新） */
    fun setProtectionLevel(context: Context, level: ProtectionLevel) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_PROTECTION_LEVEL, level.ordinal)
            .apply()
        cachedProtectionLevel = level
        _disableWarningState.value = DisableWarningState()
        applySecurityConfiguration(context, level == ProtectionLevel.OFF)
    }

    /**
     * 根据最新防护等级实时重载 shell 安全配置（hook 部署 + TCP 服务器启停）。
     * 仅在 TermuxService 已启动时生效；前台服务未运行时无需干预。
     */
    private fun applySecurityConfiguration(context: Context, remove: Boolean) {
        if (com.termux.app.TermuxService.serviceStartTimeMs <= 0L) return
        val appContext = context.applicationContext
        if (remove) {
            com.termux.app.TermuxService.deploySecurityHook(appContext, true)
            com.termux.app.compose.SecuritySocketServer.stop()
        } else {
            com.termux.app.compose.SecuritySocketServer.start(appContext)
            com.termux.app.TermuxService.deploySecurityHook(appContext, false)
        }
    }

    /** @Deprecated 请使用 getProtectionLevel() 代替 */
    @Deprecated("Use getProtectionLevel() instead", ReplaceWith("getProtectionLevel(context)"))
    fun isEnabled(context: Context): Boolean {
        return getProtectionLevel(context) != ProtectionLevel.OFF
    }

    /** @Deprecated 请使用 setProtectionLevel() 代替 */
    @Deprecated("Use setProtectionLevel() instead", ReplaceWith("setProtectionLevel(context, level)"))
    fun setEnabled(context: Context, enabled: Boolean) {
        val level = if (enabled) ProtectionLevel.WARN_VERIFY else ProtectionLevel.OFF
        setProtectionLevel(context, level)
    }

    /** 清除待处理的命令状态（公开方法，供 TermuxActivity 在处理完 Intent 结果后调用） */
    fun clearPendingState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_PENDING_SESSION_HANDLE)
            .remove(KEY_PENDING_COMMAND)
            .remove(KEY_PENDING_RESULT)
            .apply()
    }

    /** 保存 Agent 待确认的操作状态到 SharedPreferences */
    fun saveAgentPendingState(
        context: Context,
        skillType: String,
        params: String,
        messageId: String
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_AGENT_PENDING_ACTION, skillType)
            .putString(KEY_AGENT_PENDING_PARAMS, params)
            .putString(KEY_AGENT_PENDING_MESSAGE_ID, messageId)
            .remove(KEY_AGENT_PENDING_RESULT)
            .apply()
    }

    /** 检查是否有 Agent 待处理的确认结果 */
    fun hasAgentPendingResult(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AGENT_PENDING_RESULT, null) != null
    }

    /** Agent 待处理结果数据类 */
    data class AgentPendingResult(
        val action: String,
        val params: String,
        val result: String,
        val messageId: String
    )

    /** 获取并消费 Agent 待处理的确认结果 */
    fun consumeAgentPendingResult(context: Context): AgentPendingResult? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val action = prefs.getString(KEY_AGENT_PENDING_ACTION, null)
        val params = prefs.getString(KEY_AGENT_PENDING_PARAMS, null)
        val result = prefs.getString(KEY_AGENT_PENDING_RESULT, null)
        val messageId = prefs.getString(KEY_AGENT_PENDING_MESSAGE_ID, null)
        if (action != null && result != null && messageId != null) {
            prefs.edit()
                .remove(KEY_AGENT_PENDING_ACTION)
                .remove(KEY_AGENT_PENDING_PARAMS)
                .remove(KEY_AGENT_PENDING_MESSAGE_ID)
                .remove(KEY_AGENT_PENDING_RESULT)
                .apply()
            return AgentPendingResult(action, params ?: "", result, messageId)
        }
        return null
    }

    /** 清除 Agent 待处理状态 */
    fun clearAgentPendingState(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_AGENT_PENDING_ACTION)
            .remove(KEY_AGENT_PENDING_PARAMS)
            .remove(KEY_AGENT_PENDING_MESSAGE_ID)
            .remove(KEY_AGENT_PENDING_RESULT)
            .apply()
    }

    /**
     * 由 TermuxActivity 调用，检查并消费待处理的风险确认结果。
     *
     * @param context Context
     * @return android.util.Pair(sessionHandle, result) 或 null 表示无待处理结果
     */
    fun consumePendingResult(context: Context): android.util.Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val handle = prefs.getString(KEY_PENDING_SESSION_HANDLE, null)
        val result = prefs.getString(KEY_PENDING_RESULT, null)
        if (handle != null && result != null) {
            clearPendingState(context)
            return android.util.Pair(handle, result)
        }
        return null
    }

    /**
     * 请求危险命令确认（挂起函数，等待用户在弹窗中操作后返回）。
     * 用于协程场景（如 SkillExecutor）。
     *
     * @param context 上下文
     * @param command 待执行的命令
     * @param inNativeTermux 是否运行在原生 Termux 环境
     * @param environmentType 环境类型，默认为原生环境
     * @return true = 用户确认允许执行，false = 用户拒绝或未开启确认
     */
    @JvmOverloads
    suspend fun requestConfirmation(
        context: Context,
        command: String,
        inNativeTermux: Boolean = true,
        environmentType: EnvironmentType = EnvironmentType.NATIVE
    ): Boolean {
        // 无限制模式：直接放行所有命令
        if (isUnlimitedModeActive(context)) return true

        val level = getProtectionLevel(context)

        // OFF: 直接放行
        if (level == ProtectionLevel.OFF) return true

        val detection = RiskCommandDetector.detect(command, inNativeTermux)
        if (!detection.isDangerous) return true

        // --- SSH 会话优化：大部分命令仅提示不弹窗 ---
        if (environmentType == EnvironmentType.SSH && level == ProtectionLevel.WARN_VERIFY) {
            when (detection.riskType) {
                RiskCommandDetector.RiskType.SHUTDOWN_REBOOT,
                RiskCommandDetector.RiskType.FORMAT,
                RiskCommandDetector.RiskType.RM_RF_ROOT -> {
                    // 这些命令在远程服务器上也很危险，继续弹窗流程
                }
                else -> {
                    // 其他命令仅 Snackbar 提示，放行
                    Handler(Looper.getMainLooper()).post {
                        SnackbarHelper.show(
                            context,
                            "SSH远程: ${detection.description}",
                            Snackbar.LENGTH_SHORT
                        )
                    }
                    return true
                }
            }
        }

        // WARN_ONLY: Snackbar 提示但放行
        if (level == ProtectionLevel.WARN_ONLY) {
            Handler(Looper.getMainLooper()).post {
                SnackbarHelper.show(
                    context,
                    detection.description,
                    Snackbar.LENGTH_LONG
                )
            }
            return true
        }

        // AUTO_BLOCK: 直接拦截
        if (level == ProtectionLevel.AUTO_BLOCK) {
            Handler(Looper.getMainLooper()).post {
                SnackbarHelper.show(
                    context,
                    "Access Denied(权限拒绝)",
                    Snackbar.LENGTH_LONG
                )
            }
            return false
        }

        // WARN_VERIFY: 完整弹窗验证流程
        val activity = context as? ComponentActivity
        // 使用 UUID 作为请求键，避免同毫秒并发确认时 map key 碰撞导致
        // 前一个 suspendCancellableCoroutine 永不 resume（协程泄漏/永久挂起）。
        val requestId = java.util.UUID.randomUUID().toString()

        // 注意：弹窗只能在 suspendCancellableCoroutine 内部、且**回调登记之后**再放出来（见下方）。
        // 此处曾经也赋过一次 _dialogState（且漏了 requestId = requestId），后果是：
        //   1) 弹窗先于回调登记出现，用户在这一瞬间点击会被 resolveRequest 吃掉 —— F16 回归；
        //   2) 这个中途状态里 requestId 为空，此时点击走的是
        //      resolveActiveRequest 的 else 分支：只把弹窗收起，谁也不结算，
        //      调用方要挂到 CONFIRM_WAIT_SECONDS 超时才有反应。
        // 第二轮修过的两个缺陷由此回到线上，RiskConfirmRequestMatchTest 也随之变红。

        // 超时与倒计时/自动拒绝保持同一时限（此前写死 60000ms，与 CONFIRM_WAIT_SECONDS=25
        // 不一致，且 Runnable 从不移除，用户确认后仍会留一个空转回调在主线程队列里）。
        val timeoutRunnable = Runnable { resolveRequest(requestId, false) }

        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                if (!continuation.isActive) {
                    // 协程已被取消：不放弹窗，避免留下无人结算的对话框
                    return@suspendCancellableCoroutine
                }

                // 顺序至关重要：
                //   ① 结算被顶掉的旧请求 → ② 登记本请求回调 → ③ 才放出弹窗
                // 若在回调登记之前就把弹窗放出去，用户在这个窗口内点「确认」时
                // resolveRequest 找不到回调，本次点击被吃掉，请求要等超时才恢复
                // （现象：点了确认没反应）。
                preemptActiveRequest()
                pendingRequests[requestId] = { confirmed ->
                    cancelPendingTimeout(timeoutRunnable)
                    _dialogState.value = null
                    stopCountdown()
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(confirmed))
                    }
                }

                _dialogState.value = DialogState(
                    command = command,
                    riskDescription = detection.description,
                    riskType = detection.riskType?.displayName ?: "高危操作",
                    environmentType = environmentType,
                    requestId = requestId
                )
                startCountdown()
                pendingTimeout = timeoutRunnable
                mainHandler.postDelayed(timeoutRunnable, CONFIRM_WAIT_SECONDS * 1000L)

                continuation.invokeOnCancellation {
                    // 调用方协程被取消：移除登记并撤掉超时回调，避免泄漏与后续误结算
                    cancelPendingTimeout(timeoutRunnable)
                    pendingRequests.remove(requestId)
                    if (_dialogState.value?.requestId == requestId) {
                        _dialogState.value = null
                        stopCountdown()
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消必须向上传播，不能被当成"用户拒绝"吞掉
            cancelPendingTimeout(timeoutRunnable)
            pendingRequests.remove(requestId)
            throw e
        } catch (e: Exception) {
            cancelPendingTimeout(timeoutRunnable)
            pendingRequests.remove(requestId)
            _dialogState.value = null
            stopCountdown()
            false
        }
    }

    /**
     * 非协程式阻塞请求确认。
     * 用于 TermuxService 等非协程环境。
     *
     * 调用规则：必须在后台线程调用，内部会将对话框显示 post 到主线程。
     * 如果在主线程调用，会自动切换到后台线程执行，避免 ANR。
     *
     * @param context Context
     * @param command 待检测的命令
     * @param environmentType 环境类型，默认为原生环境
     * @return true = 允许执行，false = 拒绝执行或非高危命令
     */
    @JvmOverloads
    fun requestConfirmationBlocking(
        context: Context,
        command: String,
        environmentType: EnvironmentType = EnvironmentType.NATIVE
    ): Boolean {
        // 无限制模式：直接放行所有命令
        if (isUnlimitedModeActive(context)) return true

        val level = getProtectionLevel(context)

        // OFF: 直接放行
        if (level == ProtectionLevel.OFF) return true

        val detection = RiskCommandDetector.detect(command)
        if (!detection.isDangerous) return true

        // --- SSH 会话优化：大部分命令仅提示不弹窗 ---
        if (environmentType == EnvironmentType.SSH && level == ProtectionLevel.WARN_VERIFY) {
            when (detection.riskType) {
                RiskCommandDetector.RiskType.SHUTDOWN_REBOOT,
                RiskCommandDetector.RiskType.FORMAT,
                RiskCommandDetector.RiskType.RM_RF_ROOT -> {
                    // 这些命令在远程服务器上也很危险，继续弹窗流程
                }
                else -> {
                    // 其他命令仅 Snackbar 提示，放行
                    Handler(Looper.getMainLooper()).post {
                        SnackbarHelper.show(
                            context,
                            "SSH远程: ${detection.description}",
                            Snackbar.LENGTH_SHORT
                        )
                    }
                    return true
                }
            }
        }

        // WARN_ONLY: Snackbar 提示但放行
        if (level == ProtectionLevel.WARN_ONLY) {
            Handler(Looper.getMainLooper()).post {
                SnackbarHelper.show(
                    context,
                    detection.description,
                    Snackbar.LENGTH_LONG
                )
            }
            return true
        }

        // AUTO_BLOCK: 直接拦截
        if (level == ProtectionLevel.AUTO_BLOCK) {
            Handler(Looper.getMainLooper()).post {
                SnackbarHelper.show(
                    context,
                    "Access Denied(权限拒绝)",
                    Snackbar.LENGTH_LONG
                )
            }
            return false
        }

        // WARN_VERIFY: 完整弹窗验证流程
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val result = arrayOf(false)
            val latch = CountDownLatch(1)
            CoroutineScope(Dispatchers.Default).launch {
                result[0] = doRequestConfirmationBlocking(context, command, detection, environmentType)
                latch.countDown()
            }
            try {
                latch.await(CONFIRM_WAIT_SECONDS.toLong(), TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                return false
            }
            return result[0]
        }

        return doRequestConfirmationBlocking(context, command, detection, environmentType)
    }

    private fun doRequestConfirmationBlocking(
        context: Context,
        command: String,
        detection: RiskCommandDetector.DetectionResult,
        environmentType: EnvironmentType = EnvironmentType.NATIVE
    ): Boolean {
        return doDialogConfirmationBlocking(
            context, command,
            detection.description,
            detection.riskType?.displayName ?: "高危操作",
            environmentType
        )
    }

    /**
     * 检测结果已知时的阻塞式确认（供 SecuritySocketServer 的 CHECK_CMD / CHECK_SCRIPT 使用）。
     *
     * 不重新检测命令，直接按增强模式处理：
     *   OFF         → 直接放行（PASS）
     *   WARN_ONLY   → Snackbar 提示危险原因，放行（PASS）
     *   AUTO_BLOCK  → Snackbar「已自动拒绝: 原因」，拒绝（DENY）
     *   WARN_VERIFY → 弹二次确认框（附原因），用户选择执行则放行（PASS），否则拒绝（DENY）
     *
     * @return true = 放行（PASS），false = 拒绝（DENY）
     */
    @JvmOverloads
    fun requestDetectedConfirmationBlocking(
        context: Context,
        command: String,
        reason: String,
        riskType: String? = null,
        environmentType: EnvironmentType = EnvironmentType.NATIVE
    ): Boolean {
        // 注意：此处【不再】受 isUnlimitedModeActive（AI 无限制模式）影响。
        // 该函数只被 SecuritySocketServer 的 CHECK_CMD / CHECK_SCRIPT 调用，属于 shell
        // 命令/脚本安全拦截。若被无限制模式绕过，则 su、危险脚本等都会被无条件放行（pass），
        // 不弹二次确认 → VorteX Guard Engine失效。因此这里严格以防护等级为准。

        val level = getProtectionLevel(context)
        setLastProtectionLevel(level)

        // OFF: 直接放行
        if (level == ProtectionLevel.OFF) return true

        return when (level) {
            // WARN_ONLY: Snackbar 提示危险原因后放行
            ProtectionLevel.WARN_ONLY -> {
                Handler(Looper.getMainLooper()).post {
                    emitSnackbar(reason, Snackbar.LENGTH_LONG)
                }
                true
            }
            // AUTO_BLOCK: Snackbar 提示已自动拒绝后拦截
            ProtectionLevel.AUTO_BLOCK -> {
                lastCommandAutoBlocked = true
                Handler(Looper.getMainLooper()).post {
                    emitSnackbar("已自动拒绝: $reason", Snackbar.LENGTH_LONG)
                }
                false
            }
            // WARN_VERIFY: 弹二次确认框，等待用户选择
            ProtectionLevel.WARN_VERIFY -> {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    val result = arrayOf(false)
                    val latch = CountDownLatch(1)
                    CoroutineScope(Dispatchers.Default).launch {
                        result[0] = doDialogConfirmationBlocking(
                            context, command, reason, riskType ?: "高危操作", environmentType
                        )
                        latch.countDown()
                    }
                    try {
                        latch.await(CONFIRM_WAIT_SECONDS.toLong(), TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        return false
                    }
                    result[0]
                } else {
                    doDialogConfirmationBlocking(
                        context, command, reason, riskType ?: "高危操作", environmentType
                    )
                }
            }
            ProtectionLevel.OFF -> true
        }
    }

    /**
     * 弹窗确认流程（阻塞等待用户选择，WARN_VERIFY 使用）。
     */
    private fun doDialogConfirmationBlocking(
        context: Context,
        command: String,
        reason: String,
        riskType: String,
        environmentType: EnvironmentType = EnvironmentType.NATIVE
    ): Boolean {
        if (blockingRequestActive) {
            Handler(Looper.getMainLooper()).post {
                SnackbarHelper.show(context, "Access Denied(权限拒绝)", Snackbar.LENGTH_LONG)
            }
            return false
        }

        val result = arrayOf(false)
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())

        blockingRequestActive = true
        handler.post {
            // 阻塞式请求顶掉协程请求时，同样要先结算旧请求
            preemptActiveRequest()
            // 阻塞式请求不设 requestId：它由 blockingRequest 单独结算，
            // 不参与 pendingRequests 的 requestId 匹配
            _dialogState.value = DialogState(
                command = command,
                riskDescription = reason,
                riskType = riskType,
                environmentType = environmentType
            )
            startCountdown()
            blockingRequest = { confirmed ->
                result[0] = confirmed
                _dialogState.value = null
                stopCountdown()
                blockingRequest = null
                blockingRequestActive = false
                latch.countDown()
            }

            handler.postDelayed({
                if (blockingRequestActive && blockingRequest != null) {
                    blockingRequest?.invoke(false)
                    blockingRequest = null
                    blockingRequestActive = false
                    _dialogState.value = null
                    stopCountdown()
                    latch.countDown()
                }
            }, CONFIRM_WAIT_SECONDS * 1000L)
        }

        try {
            latch.await(CONFIRM_WAIT_SECONDS.toLong(), TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            return false
        }

        return result[0]
    }

    /** 用户点击"确认执行" */
    internal fun confirm(context: Context) {
        stopCountdown()
        // 优先处理阻塞式请求(SecuritySocketServer 的 shell 拦截)。必须即时释放 latch，
        // 否则 shell 会一直等待 server 响应而卡死。此前该分支排在 Agent/session 之后，
        // 会被残留的 pendingAction/handle 截住——弹窗被清掉却不释放 latch，
        // 导致"点了没反应→只能等超时→超过90秒才恢复/一直不恢复"。
        if (blockingRequest != null) {
            blockingRequest?.invoke(true)
            blockingRequest = null
            blockingRequestActive = false
            _dialogState.value = null
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 先处理 Agent 流程（无 session handle 但有 agent pending action）
        val agentAction = prefs.getString(KEY_AGENT_PENDING_ACTION, null)
        if (agentAction != null) {
            prefs.edit().putString(KEY_AGENT_PENDING_RESULT, RESULT_CONFIRMED).apply()
            _dialogState.value = null
            navigateBackToAgent(context)
            return
        }
        // 再处理终端会话的跳转模式
        val sessionHandle = prefs.getString(KEY_PENDING_SESSION_HANDLE, null)
        if (sessionHandle != null) {
            prefs.edit().putString(KEY_PENDING_RESULT, RESULT_CONFIRMED).apply()
            _dialogState.value = null
            navigateBackToTermux(context, sessionHandle, RESULT_CONFIRMED)
            return
        }
        // 最后处理协程请求：按当前弹窗绑定的 requestId 精确结算
        resolveActiveRequest(true)
    }

    /** 结算当前弹窗绑定的协程确认请求；无绑定请求时只收起弹窗。 */
    private fun resolveActiveRequest(confirmed: Boolean) {
        val requestId = _dialogState.value?.requestId
        if (!requestId.isNullOrEmpty()) {
            resolveRequest(requestId, confirmed)
        } else {
            _dialogState.value = null
        }
    }

    /** 用户点击"取消" */
    internal fun cancel(context: Context) {
        stopCountdown()
        if (blockingRequest != null) {
            blockingRequest?.invoke(false)
            blockingRequest = null
            blockingRequestActive = false
            _dialogState.value = null
            return
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 先处理 Agent 流程
        val agentAction = prefs.getString(KEY_AGENT_PENDING_ACTION, null)
        if (agentAction != null) {
            prefs.edit().putString(KEY_AGENT_PENDING_RESULT, RESULT_DENIED).apply()
            _dialogState.value = null
            navigateBackToAgent(context)
            return
        }
        // 处理终端会话的跳转模式（与 confirm() 对称：取消时写 DENIED 并导航回 Termux，
        // 否则 pending 状态永不清除，终端会一直等待确认而卡死）
        val sessionHandle = prefs.getString(KEY_PENDING_SESSION_HANDLE, null)
        if (sessionHandle != null) {
            prefs.edit().putString(KEY_PENDING_RESULT, RESULT_DENIED).apply()
            _dialogState.value = null
            navigateBackToTermux(context, sessionHandle, RESULT_DENIED)
            return
        }
        // 最后处理协程请求。
        // 旧实现用 pendingRequests.keys.lastOrNull() 结算，与 confirm() 的精确匹配不对称：
        // 现在只有一个请求在途时能碰巧正确，一旦同时在途多个就会把「拒绝」
        // 结算给别的挂起调用方。
        resolveActiveRequest(false)
    }

    /** 导航回 AiTermuxActivity */
    private fun navigateBackToAgent(context: Context) {
        val intent = Intent(context, com.termux.app.activities.AiTermuxActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** 导航回 TermuxActivity 并传递结果 */
    private fun navigateBackToTermux(context: Context, sessionHandle: String, result: String) {
        val intent = Intent(context, com.termux.app.TermuxActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra(EXTRA_RISK_RESULT, result)
        intent.putExtra(EXTRA_SESSION_HANDLE, sessionHandle)
        context.startActivity(intent)
    }

    /** 显示"关闭二次确认"的警告弹窗（使用主页授权遮罩覆盖方式） */
    fun showDisableWarning(context: Context, targetLevel: ProtectionLevel = ProtectionLevel.OFF) {
        _disableWarningState.value = DisableWarningState(show = true, targetLevel = targetLevel)
        // 跳转到主页 Activity，主页的 DisableWarningMask 会显示遮罩弹窗
        val intent = Intent(context, com.termux.app.MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
    }

    /** 关闭"关闭二次确认"的警告弹窗（保留用于兼容） */
    fun hideDisableWarning() {
        _disableWarningState.value = DisableWarningState()
    }

    /** 用户确认降级防护级别 */
    fun confirmDisable(context: Context) {
        val targetLevel = _disableWarningState.value.targetLevel
        setProtectionLevel(context, targetLevel)
        hideDisableWarning()
    }
/**
     * 从 shell 命令里提取脚本文件路径。
     * 识别: bash xxx.sh, sh -c xxx, source xxx, . xxx, 直接执行 .sh 文件 等
     */
    fun extractScriptPath(command: String): String? {
        val trimmed = command.trim()
        // 1. shell 前缀（bash xxx, sh -c xxx, zsh xxx, fish xxx, dash xxx）
        val shellRegex = Regex("""(?:bash|sh|zsh|fish|dash)\s+(?:-c\s+)?['"]?(\S+?)['"]?(?:\s|$)""")
        shellRegex.find(trimmed)?.let { m ->
            val path = m.groupValues[1].trimEnd(';', '&', '|')
            if (path.isNotBlank()) return path
        }
        // 2. source / . 前缀（source xxx, . xxx）
        val sourceRegex = Regex("""(?:source|(?<!\w)\.(?!\w))\s+['"]?(\S+?)['"]?(?:\s|$)""")
        sourceRegex.find(trimmed)?.let { m ->
            val path = m.groupValues[1].trimEnd(';', '&', '|')
            if (path.isNotBlank()) return path
        }
        // 3. 直接执行带扩展名的 shell 脚本（./install.sh, /path/to/deploy.sh, ./setup.bash）
        //    只识别 sh/bash/zsh/ksh/dash/fish 后缀；py/pl/rb/js 等非 sh 文件不走 Agent，走本地检测
        val directRegex = Regex("""^['"]?(\.?/\S+\.(?:sh|bash|zsh|ksh|dash|fish))['"]?(?:\s|$)""")
        directRegex.find(trimmed)?.let { m ->
            return m.groupValues[1].trimEnd(';', '&', '|')
        }
        // 4. 直接执行相对路径（./setup, ./deploy）
        //    绝对路径（/data/.../binary）可能是普通程序，不识别为脚本 → 走本地检测
        val directExecRegex = Regex("""^['"]?(\./\S+?)['"]?(?:\s|$)""")
        directExecRegex.find(trimmed)?.let { m ->
            val path = m.groupValues[1].trimEnd(';', '&', '|')
            if (path.startsWith("./")) return path
        }
        return null
    }
}

/**
 * 风险确认 WindowDialog 宿主。
 *
 * 放置在 Activity 的 Compose 树顶层，通过观察 RiskConfirmManager.dialogState
 * 来渲染弹窗。必须保持 Activity 存活。
 *
 * @param snackbarHostState Snackbar 宿主状态，用于显示 Snackbar
 * @param collectSnackbar 是否收集并显示 RiskConfirmManager 的 Snackbar 事件。
 *        主页设为 false（由终端页独占显示），终端页设为 true。
 * @param collectSnackbarEvents 主开关：是否收集 Snackbar 事件（详情/汇总）。
 *        设为 false 时完全跳过 Snackbar 事件收集，仅处理弹窗状态。
 *        用于 MainActivity 级别宿主（避免在主页重复显示终端页的 Snackbar）。
 */
@Composable
fun RiskConfirmDialogHost(
    snackbarHostState: top.yukonga.miuix.kmp.basic.SnackbarHostState? = null,
    collectSnackbar: Boolean = true,
    collectSnackbarEvents: Boolean = true
) {
    val dialogState by RiskConfirmManager.dialogState.collectAsState()
    val countdown by RiskConfirmManager.countdown.collectAsState()
    val agentLoading by RiskConfirmManager.agentLoadingVisible.collectAsState()
    val agentLoadingText by RiskConfirmManager.agentLoadingText.collectAsState()
    val agentSkipState by RiskConfirmManager.agentSkipState.collectAsState()
    var checkboxChecked by remember { mutableStateOf(false) }

    LaunchedEffect(dialogState) {
        if (dialogState == null) {
            checkboxChecked = false
        }
    }

    val context = LocalContext.current
    val snackbarScope = rememberCoroutineScope()
    val showBlockedMessage: () -> Unit = {
        val msg = "请手动点击按钮完成操作，第三方无障碍服务无法执行此操作"
        if (snackbarHostState != null) {
            snackbarScope.launch {
                snackbarHostState.showSnackbar(
                    message = msg,
                    duration = top.yukonga.miuix.kmp.basic.SnackbarDuration.Long
                )
            }
        } else {
            SnackbarHelper.show(context, msg, Snackbar.LENGTH_LONG)
        }
    }

    // 仅在 collectSnackbarEvents=true 时收集 Snackbar 事件
    // collectSnackbar=true → 收集详情 Snackbar（终端页）
    // collectSnackbar=false → 收集汇总 Snackbar（主页）
    if (collectSnackbarEvents) {
        if (collectSnackbar) {
            LaunchedEffect(Unit) {
                RiskConfirmManager.snackbarEvents.collect { event ->
                    val duration = if (event.duration >= Snackbar.LENGTH_LONG) {
                        top.yukonga.miuix.kmp.basic.SnackbarDuration.Long
                    } else {
                        top.yukonga.miuix.kmp.basic.SnackbarDuration.Short
                    }
                    if (snackbarHostState != null) {
                        snackbarScope.launch {
                            snackbarHostState.showSnackbar(
                                message = event.message,
                                duration = duration
                            )
                        }
                    } else {
                        SnackbarHelper.show(context, event.message, event.duration)
                    }
                }
            }
        } else {
            // 主页：收集汇总 Snackbar（退出终端页时显示统计信息）
            LaunchedEffect(Unit) {
                RiskConfirmManager.summarySnackbarEvents.collect { event ->
                    val duration = if (event.duration >= Snackbar.LENGTH_LONG) {
                        top.yukonga.miuix.kmp.basic.SnackbarDuration.Long
                    } else {
                        top.yukonga.miuix.kmp.basic.SnackbarDuration.Short
                    }
                    if (snackbarHostState != null) {
                        snackbarScope.launch {
                            snackbarHostState.showSnackbar(
                                message = event.message,
                                duration = duration
                            )
                        }
                    } else {
                        SnackbarHelper.show(context, event.message, event.duration)
                    }
                }
            }
        }
    }

    val thirdPartyBlocked = rememberThirdPartyBlocked(context)

    val activity = context as? ComponentActivity
    val window = activity?.window

    LaunchedEffect(dialogState != null) {
        if (dialogState != null) {
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // ===== 统一弹窗宿主：Agent Loading 与风险确认合并为【单个】WindowDialog =====
    // 关键：同一时间只允许存在一个 DialogWindow。此前 Loading 与确认各开一个
    // WindowDialog，Loading 关闭动画与新确认窗叠加时 z-order 冲突，导致确认弹窗
    // 渲染不出来（用户看到"检测弹窗消失但没有二次确认"，随后 latch 等待 → shell 卡死）。
    val state = dialogState
    val isSshPower = state?.isSshPowerOperation == true
    val skipState = agentSkipState
    val showDialog = agentLoading || state != null || skipState != null

    val dialogTitle: String = when {
        isSshPower -> "远程电源操作确认"
        skipState != null -> when (skipState.mode) {
            RiskConfirmManager.AgentSkipState.Mode.SKIP -> "跳过 Agent 验证 - 二次确认"
            RiskConfirmManager.AgentSkipState.Mode.TIMEOUT -> "Agent 判定超时 - 二次确认"
            RiskConfirmManager.AgentSkipState.Mode.ABNORMAL -> "Agent 判定异常 - 二次确认"
        }
        state != null -> when (state.environmentType) {
            RiskConfirmManager.EnvironmentType.NATIVE -> "即将执行风险命令"
            RiskConfirmManager.EnvironmentType.CONTAINER -> "高危命令 - 容器环境"
            RiskConfirmManager.EnvironmentType.VM -> "高危命令 - 虚拟机环境"
            RiskConfirmManager.EnvironmentType.SSH -> "高危命令 - 远程系统 (SSH)"
        }
        else -> "安全检测中"
    }
    val envWarning: String? = when (state?.environmentType) {
        null -> null
        RiskConfirmManager.EnvironmentType.NATIVE -> null
        RiskConfirmManager.EnvironmentType.CONTAINER -> "此命令正在容器环境中执行，可能会对容器系统造成不可逆的损害，包括但不限于：容器数据丢失、容器系统损坏、容器无法重新启动等。请在执行前仔细评估此命令的必要性和安全性。"
        RiskConfirmManager.EnvironmentType.VM -> "此命令正在虚拟机环境中执行，可能会对虚拟机系统造成不可逆的损害，包括但不限于：虚拟机数据丢失、虚拟机系统损坏、虚拟机无法启动等。请在执行前仔细评估此命令的必要性和安全性。"
        RiskConfirmManager.EnvironmentType.SSH -> {
            val isDiskCommand = state?.riskType in listOf("dd 磁盘写入", "格式化/分区")
            if (isDiskCommand) {
                if (state?.isWindowsDiskCommand == true) {
                    "此磁盘级命令将在通过 SSH 连接的远程 Windows 系统上执行。format、diskpart、bcdedit 等操作可格式化分区、擦除磁盘分区表、修改或删除系统启动配置。diskpart 的 clean / clean all 指令会清除磁盘全部分区信息，clean-all 将覆写磁盘全部扇区，数据几乎无法恢复。错误指定磁盘号、盘符会造成整块磁盘数据丢失；即使系统正在运行，管理员权限仍可摧毁非系统卷数据。如果远程系统为生产环境，执行此命令将造成大规模数据丢失、业务中断甚至系统无法启动，并可能带来法律风险。请在执行前仔细核对磁盘编号、盘符，评估执行必要性。"
                } else {
                    "此磁盘级命令将在通过 SSH 连接的远程系统上执行。dd、mkfs、fdisk 和 parted 等操作可能会覆盖原始磁盘、破坏分区表并永久擦除所有数据。错误指定设备路径可能导致远程主机完全无法启动，且损坏的数据几乎无法恢复。如果远程系统为生产环境，执行此命令可能导致服务中断、大规模数据丢失，甚至带来法律风险。请在执行前仔细检查目标设备路径并评估执行的必要性。"
                }
            } else {
                "此命令正在通过 SSH 连接的远程系统上执行，可能会对远程系统造成不可逆的损害，包括但不限于：远程数据丢失、远程系统损坏、服务中断等。如果远程系统为生产环境，执行此命令可能导致服务中断、数据丢失，甚至带来法律风险。请在执行前仔细评估此命令的必要性和安全性。"
            }
        }
        else -> null
    }
    val agentSkipSummary: String = skipState?.let { skip ->
    buildString {
        when (skip.mode) {
            RiskConfirmManager.AgentSkipState.Mode.SKIP -> {
                append("您手动选择跳过 Agent 安全检测。")
                if (skip.detail.isNotBlank()) { append("\n\nAgent 返回原因：${skip.detail}") }
                append("\n\n继续执行脚本意味着您将绕过 VorteX Guard Engine 的 AI 安全审查。脚本可能包含危险操作（磁盘破坏、远程连接、数据泄露等），这些风险将完全由您自己承担。")
            }
            RiskConfirmManager.AgentSkipState.Mode.TIMEOUT -> {
                append("Agent 判定在时限内未返回（可能是网络延迟或长脚本推理耗时过长）。")
                if (skip.detail.isNotBlank()) { append("\n\n本地静态检测结果：${skip.detail}") }
                append("\n\n继续执行脚本意味着您承认 Agent 判定未完成，同意自行确认脚本用途无害。")
            }
            RiskConfirmManager.AgentSkipState.Mode.ABNORMAL -> {
                append("Agent 判定发生异常（如 API 调用失败、本地模型未就绪等）。")
                if (skip.detail.isNotBlank()) { append("\n\nAgent 错误信息：${skip.detail}") }
                append("\n\n继续执行脚本意味着您确认脚本用途无害，同意自行承担风险。")
            }
        }
    }
} ?: ""

    val dialogSummary: String = when {
        isSshPower -> "您即将对通过 SSH 连接的远程系统执行关机或重新启动。\n\n您确认后，远程主机将终止全部正在运行的程序与服务并断开 SSH 会话。如您选择关机，如果没有相关人员物理接触此远程设备或此设备不具备网络开机能力，系统将要持续离线，您无法通过远程方式恢复运行。\n\n若此环境为生产环境，此操作会造成服务中断与可能的业务损失。\n\n请确认您确实需要执行电源操作再继续！"
        state != null -> buildString {
            append(state.riskDescription)
            if (envWarning != null) {
                append("\n\n")
                append(envWarning)
            }
            append("\n\n")
            append("该命令可能造成不可恢复的数据丢失、系统损坏或安全问题。您执行高危命令所造成的任何后果，本应用不承担任何责任，且不受理因高危操作产生的 Issue。")
        }
        agentSkipState != null -> agentSkipSummary
        else -> agentLoadingText.ifBlank { "正在检测脚本安全性..." }
    }

    if (state != null) {
        LaunchedEffect(state.command) {
            checkboxChecked = false
        }
    }

    // 用 key 强制 content 分支变化（Loading ↔ 确认）时重建整个 Dialog，
    // 避免同一个 WindowDialog 内 content 切换时残留"安全检测中"内容、确认帧渲染不出来。
    key(agentLoading, state?.command, isSshPower, skipState?.requestId) {
    top.yukonga.miuix.kmp.window.WindowDialog(
        show = showDialog,
        onDismissRequest = {},
        title = dialogTitle,
        summary = dialogSummary,
        content = {
            when {
                agentSkipState != null -> run {
                    val skip = agentSkipState ?: return@run
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .physicalTouchDetector()
                            .accessibilityGuard(thirdPartyBlocked)
                            .padding(top = 4.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "命令" + ":",
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                        Spacer(Modifier.height(4.dp))
                        top.yukonga.miuix.kmp.basic.Card(
                            modifier = Modifier
                                .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = skip.command.ifBlank { "（未提供命令）" },
                                modifier = Modifier.padding(8.dp),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 13.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MiuixTheme.colorScheme.onSurface
                                ),
                                maxLines = 3
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = "警告：此操作将绕过或确认跳过 VorteX Guard Engine 的 AI 安全审查。请确认脚本用途确实无害后再继续。",
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                        )

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Button(
                                onClick = guardedOnClick(context, thirdPartyBlocked, showBlockedMessage) {
                                    RiskConfirmManager.cancelAgentSkip()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    color = Color.Transparent
                                )
                            ) {
                                Text(
                                    text = "取消",
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Button(
                                onClick = guardedOnClick(context, thirdPartyBlocked, showBlockedMessage) {
                                    RiskConfirmManager.confirmAgentSkip()
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    color = Color(0xFFD32F2F)
                                )
                            ) {
                                Text(
                                    text = "确认通过",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                isSshPower && state != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .physicalTouchDetector()
                            .accessibilityGuard(thirdPartyBlocked)
                            .padding(top = 4.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "命令" + ":",
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                        Spacer(Modifier.height(4.dp))
                        top.yukonga.miuix.kmp.basic.Card(
                            modifier = Modifier
                                .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = state.command,
                                modifier = Modifier.padding(8.dp),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 13.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MiuixTheme.colorScheme.onSurface
                                ),
                                maxLines = 3
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Button(
                                onClick = guardedOnClick(context, thirdPartyBlocked, showBlockedMessage) {
                                    RiskConfirmManager.cancel(context)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    color = Color.Transparent
                                )
                            ) {
                                Text(
                                    text = "${"否"}(${countdown}s)",
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Button(
                                onClick = guardedOnClick(context, thirdPartyBlocked, showBlockedMessage) {
                                    RiskConfirmManager.confirm(context)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    color = Color(0xFFD32F2F)
                                )
                            ) {
                                Text(
                                    text = "是，关机/重启",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                state != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .physicalTouchDetector()
                            .accessibilityGuard(thirdPartyBlocked)
                            .padding(top = 4.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = "命令" + ":",
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                        Spacer(Modifier.height(4.dp))
                        top.yukonga.miuix.kmp.basic.Card(
                            modifier = Modifier
                                .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = state.command,
                                modifier = Modifier.padding(8.dp),
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 13.sp,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MiuixTheme.colorScheme.onSurface
                                ),
                                maxLines = 3
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        Text(
                            text = "警告：这是一项高危操作，可能导致不可逆的后果。",
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                        )

                        Spacer(Modifier.height(12.dp))

                        CheckboxPreference(
                            title = "我自愿承担执行此命令的全部风险，继续执行",
                            checked = checkboxChecked,
                            onCheckedChange = { checkboxChecked = it },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            Button(
                                onClick = guardedOnClick(context, thirdPartyBlocked, showBlockedMessage) {
                                    RiskConfirmManager.cancel(context)
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    color = Color.Transparent
                                )
                            ) {
                                Text(
                                    text = "${"取消"}(${countdown}s)",
                                    color = MiuixTheme.colorScheme.onSurface,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Button(
                                onClick = guardedOnClick(context, thirdPartyBlocked, showBlockedMessage) {
                                    RiskConfirmManager.confirm(context)
                                },
                                enabled = checkboxChecked,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    color = if (checkboxChecked) Color(0xFFD32F2F) else Color(0xFFBDBDBD)
                                )
                            ) {
                                Text(
                                    text = "继续执行",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                else -> {
                    androidx.compose.foundation.layout.Column(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .then(androidx.compose.ui.Modifier.padding(top = 8.dp)),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = androidx.compose.ui.Modifier.size(36.dp),
                            strokeWidth = 3.dp
                        )
                        Spacer(Modifier.height(16.dp))
                        top.yukonga.miuix.kmp.basic.Button(
                            onClick = guardedOnClick(context, thirdPartyBlocked, showBlockedMessage) {
                                RiskConfirmManager.requestSkipFromLoading(context)
                            }
                        ) {
                            Text(
                                text = "跳过验证",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    )
    } // key(...) 闭合：强制 content 分支变化时重建 Dialog
}

fun hasBiometricAuthentication(activity: ComponentActivity): Boolean {
    val biometricManager = BiometricManager.from(activity)
    val canAuthenticate = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )
    return canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS

}
/**
 * 启动生物识别验证。
 * 如果设备未设置任何生物验证或屏幕锁，则跳过验证并提示用户。
 * 使用 startClass2BiometricOrCredentialAuthentication 兼容 FragmentActivity。
 */
fun launchBiometricAuth(
    activity: FragmentActivity,
    onResult: (Boolean) -> Unit
) {
    if (!hasBiometricAuthentication(activity)) {
        SnackbarHelper.show(
            activity,
            "设备未设置生物验证或屏幕锁。已跳过验证。建议在系统设置中设置屏幕锁（PIN/图案）或生物验证以获得更好的安全性。",
            Snackbar.LENGTH_LONG
        )
        onResult(true)
        return
    }

    val title = "请验证您的身份以继续"
    val subtitle = "确认调整"

    RiskConfirmManager.countdownScope.launch {
        try {
            activity.startClass2BiometricOrCredentialAuthentication(
                title = title,
                subtitle = subtitle,
                confirmationRequired = false,
                callback = object : AuthPromptCallback() {
                    override fun onAuthenticationSucceeded(
                        activity: FragmentActivity?,
                        result: BiometricPrompt.AuthenticationResult
                    ) {
                        onResult(true)
                    }

                    override fun onAuthenticationError(
                        activity: FragmentActivity?,
                        errorCode: Int,
                        errString: CharSequence
                    ) {
                        onResult(false)
                    }

                    override fun onAuthenticationFailed(activity: FragmentActivity?) {
                    }
                }
            )
        } catch (e: Exception) {
            onResult(false)
        }
    }
}

/**
 * 在 TermuxActivity 等传统 View 系统中初始化 RiskConfirmDialogHost
 */
fun setupRiskConfirmDialogHost(composeView: androidx.compose.ui.platform.ComposeView) {
    composeView.setContent {
        com.termux.app.compose.KiTerminalTheme {
            RiskConfirmDialogHost()
        }
    }
}
