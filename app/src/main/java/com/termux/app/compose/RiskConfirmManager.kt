package com.termux.app.compose

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
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 风险命令确认管理器。
 *
 * 负责：
 * 1. 通过 [RiskCommandDetector] 检测命令是否为高危命令
 * 2. 通过 OverlayDialog 要求用户二次确认
 * 3. 根据用户选择决定放行或拦截
 * 4. 管理"高风险命令二次确认"开关状态
 */
object RiskConfirmManager {

    const val PREFS_NAME = "termux_risk_confirm"
    const val KEY_ENABLED = "risk_confirm_enabled"       // 迁移用：旧的布尔开关
    const val KEY_PROTECTION_LEVEL = "protection_level"   // 新的保护级别 (Int)
    const val KEY_DETECTION_MODE = "detection_mode"       // 新的侦测模式 (Int)
    const val KEY_PREVIOUS_DETECTION_MODE = "previous_detection_mode"  // 上次选择的侦测模式
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

    // ---- 性能优化：缓存防护等级和侦测模式，避免每次命令都读取 SharedPreferences ----
    @Volatile
    private var cachedProtectionLevel: ProtectionLevel? = null

    @Volatile
    private var cachedDetectionMode: DetectionMode? = null

    /** 会话环境类型缓存（使用 WeakHashMap 避免内存泄漏） */
    private val environmentCache = java.util.concurrent.ConcurrentHashMap<String, EnvironmentType>()

    /** 清除指定会话的环境缓存 */
    fun invalidateEnvironmentCache(sessionHandle: String) {
        environmentCache.remove(sessionHandle)
    }

    /** 清除所有环境缓存 */
    fun clearAllEnvironmentCache() {
        environmentCache.clear()
    }

    /** 预加载缓存到内存（避免首次命令读取 SharedPreferences） */
    fun preloadCache(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val levelOrdinal = prefs.getInt(KEY_PROTECTION_LEVEL, ProtectionLevel.WARN_VERIFY.ordinal)
            cachedProtectionLevel = ProtectionLevel.entries.getOrElse(levelOrdinal) { ProtectionLevel.WARN_VERIFY }
            
            if (cachedProtectionLevel != ProtectionLevel.OFF) {
                val modeOrdinal = prefs.getInt(KEY_DETECTION_MODE, DetectionMode.STATIC.ordinal)
                cachedDetectionMode = DetectionMode.entries.getOrElse(modeOrdinal) { DetectionMode.STATIC }
            } else {
                cachedDetectionMode = DetectionMode.NONE
            }
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

    /** 侦测模式 */
    enum class DetectionMode(val displayName: String) {
        NONE("无"),
        STATIC("静态侦测"),
        RUNTIME("运行时解析")
    }

    /** 弹窗状态 */
    data class DialogState(
        val command: String,
        val riskDescription: String,
        val riskType: String,
        val environmentType: EnvironmentType = EnvironmentType.NATIVE,
        val isSshPowerOperation: Boolean = false,
        /** 是否为 Windows 磁盘级命令（SSH 连接 Windows 时使用特殊警告文案） */
        val isWindowsDiskCommand: Boolean = false
    )

    internal val _dialogState = MutableStateFlow<DialogState?>(null)
    val dialogState: StateFlow<DialogState?> = _dialogState.asStateFlow()

    /** 倒计时（秒），60秒自动拒绝 */
    private val _countdown = MutableStateFlow(60)
    val countdown: StateFlow<Int> = _countdown.asStateFlow()

    /** 倒计时控制 */
    private var countdownJob: kotlinx.coroutines.Job? = null
    internal val countdownScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 开始倒计时 */
    internal fun startCountdown() {
        stopCountdown()
        _countdown.value = 60
        countdownJob = countdownScope.launch {
            for (i in 60 downTo 1) {
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

    /** 挂起的确认请求（用于协程调用） */
    private val pendingRequests = mutableMapOf<String, (Boolean) -> Unit>()

    /** 阻塞式确认请求（用于 Service/Java 调用） */
    private var blockingRequest: ((Boolean) -> Unit)? = null
    private var blockingRequestActive = false

    /** 待处理的终端会话（用于拦截用户输入的高危命令，直接回调模式） */
    private var pendingTerminalSession: com.termux.terminal.TerminalSession? = null

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
        val currentLevel = getProtectionLevel(context)
        
        // 如果切换到 OFF，保存当前检测模式到 previous，然后设置为 NONE
        if (level == ProtectionLevel.OFF && currentLevel != ProtectionLevel.OFF) {
            val currentDetection = getDetectionMode(context)
            if (currentDetection != DetectionMode.NONE) {
                prefs.edit()
                    .putInt(KEY_PREVIOUS_DETECTION_MODE, currentDetection.ordinal)
                    .putInt(KEY_DETECTION_MODE, DetectionMode.NONE.ordinal)
                    .putInt(KEY_PROTECTION_LEVEL, level.ordinal)
                    .apply()
                cachedProtectionLevel = level
                cachedDetectionMode = DetectionMode.NONE
                _disableWarningState.value = DisableWarningState()
                return
            }
        }
        
        // 从 OFF 切换到其他等级时，恢复之前保存的检测模式
        if (level != ProtectionLevel.OFF && currentLevel == ProtectionLevel.OFF) {
            val previousDetection = prefs.getInt(KEY_PREVIOUS_DETECTION_MODE, DetectionMode.STATIC.ordinal)
            prefs.edit()
                .putInt(KEY_PROTECTION_LEVEL, level.ordinal)
                .putInt(KEY_DETECTION_MODE, previousDetection)
                .apply()
            cachedProtectionLevel = level
            cachedDetectionMode = DetectionMode.entries.getOrElse(previousDetection) { DetectionMode.STATIC }
            _disableWarningState.value = DisableWarningState()
            return
        }
        
        prefs.edit()
            .putInt(KEY_PROTECTION_LEVEL, level.ordinal)
            .apply()
        cachedProtectionLevel = level
        _disableWarningState.value = DisableWarningState()
    }

    /** 获取侦测模式（优先从缓存读取） */
    fun getDetectionMode(context: Context): DetectionMode {
        // 如果防护等级为 OFF，返回 NONE
        val protectionLevel = getProtectionLevel(context)
        if (protectionLevel == ProtectionLevel.OFF) {
            cachedDetectionMode = DetectionMode.NONE
            return DetectionMode.NONE
        }
        cachedDetectionMode?.let { mode ->
            if (mode != DetectionMode.NONE) return mode
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ordinal = prefs.getInt(KEY_DETECTION_MODE, DetectionMode.STATIC.ordinal)
        val mode = DetectionMode.entries.getOrElse(ordinal) { DetectionMode.STATIC }
        val result = if (mode == DetectionMode.NONE) DetectionMode.STATIC else mode
        cachedDetectionMode = result
        return result
    }

    /** 设置侦测模式（同时更新缓存） */
    fun setDetectionMode(context: Context, mode: DetectionMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_DETECTION_MODE, mode.ordinal)
            .apply()
        cachedDetectionMode = mode
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

    /** 保存待确认的命令状态到 SharedPreferences */
    private fun savePendingState(context: Context, sessionHandle: String, command: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PENDING_SESSION_HANDLE, sessionHandle)
            .putString(KEY_PENDING_COMMAND, command)
            .remove(KEY_PENDING_RESULT)
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
                    context.getString(R.string.access_denied),
                    Snackbar.LENGTH_LONG
                )
            }
            return false
        }

        // WARN_VERIFY: 完整弹窗验证流程
        val activity = context as? ComponentActivity
        val requestId = System.currentTimeMillis().toString()

        _dialogState.value = DialogState(
            command = command,
            riskDescription = detection.description,
            riskType = detection.riskType?.displayName ?: "高危操作",
            environmentType = environmentType
        )

        startCountdown()

        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                pendingRequests[requestId] = { confirmed ->
                    _dialogState.value = null
                    stopCountdown()
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.success(confirmed))
                    }
                }

                val timeoutRunnable = Runnable {
                    if (pendingRequests.containsKey(requestId)) {
                        pendingRequests[requestId]?.invoke(false)
                        pendingRequests.remove(requestId)
                    }
                }
                activity?.window?.decorView?.postDelayed(timeoutRunnable, 60000L)
            }
        } catch (e: Exception) {
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
                    context.getString(R.string.access_denied),
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
                latch.await(90, TimeUnit.SECONDS)
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
        if (blockingRequestActive) {
            Handler(Looper.getMainLooper()).post {
                SnackbarHelper.show(context, context.getString(R.string.access_denied), Snackbar.LENGTH_LONG)
            }
            return false
        }

        val result = arrayOf(false)
        val latch = CountDownLatch(1)
        val handler = Handler(Looper.getMainLooper())

        blockingRequestActive = true
        handler.post {
            _dialogState.value = DialogState(
                command = command,
                riskDescription = detection.description,
                riskType = detection.riskType?.displayName ?: "高危操作",
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
            }, 60000L)
        }

        try {
            latch.await(90, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            return false
        }

        return result[0]
    }

    /** 用户点击"确认执行" */
    internal fun confirm(context: Context) {
        stopCountdown()
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
        // 再处理直接回调模式
        if (pendingTerminalSession != null) {
            pendingTerminalSession?.confirmPendingCommand()
            pendingTerminalSession = null
            _dialogState.value = null
            return
        }
        // 再处理阻塞式请求
        if (blockingRequest != null) {
            blockingRequest?.invoke(true)
            blockingRequest = null
            blockingRequestActive = false
            _dialogState.value = null
            return
        }
        // 最后处理协程请求
        val requestId = pendingRequests.keys.lastOrNull()
        if (requestId != null) {
            pendingRequests[requestId]?.invoke(true)
            pendingRequests.remove(requestId)
            _dialogState.value = null
        }
    }

    /** 用户点击"取消" */
    internal fun cancel(context: Context) {
        stopCountdown()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 先处理 Agent 流程
        val agentAction = prefs.getString(KEY_AGENT_PENDING_ACTION, null)
        if (agentAction != null) {
            prefs.edit().putString(KEY_AGENT_PENDING_RESULT, RESULT_DENIED).apply()
            _dialogState.value = null
            navigateBackToAgent(context)
            return
        }
        // 再处理终端会话的跳转模式
        val sessionHandle = prefs.getString(KEY_PENDING_SESSION_HANDLE, null)
        if (sessionHandle != null) {
            prefs.edit().putString(KEY_PENDING_RESULT, RESULT_DENIED).apply()
            _dialogState.value = null
            navigateBackToTermux(context, sessionHandle, RESULT_DENIED)
            return
        }
        // 再处理直接回调模式
        if (pendingTerminalSession != null) {
            pendingTerminalSession?.denyPendingCommand()
            pendingTerminalSession = null
            _dialogState.value = null
            return
        }
        if (blockingRequest != null) {
            blockingRequest?.invoke(false)
            blockingRequest = null
            blockingRequestActive = false
            _dialogState.value = null
            return
        }
        val requestId = pendingRequests.keys.lastOrNull()
        if (requestId != null) {
            pendingRequests[requestId]?.invoke(false)
            pendingRequests.remove(requestId)
            _dialogState.value = null
        }
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

    /**
     * 处理终端会话中用户输入的高危命令。
     * 由 TerminalSession.InputInterceptor 调用。
     *
     * 流程：检测高危 → 保存状态 → 跳转主页 → 主页弹窗 → 用户确认/取消 → 返回执行
     *
     * 特殊逻辑：
     * - su/sudo 在非原生 Termux 环境（容器/VM/SSH）中：只 Snackbar 提醒，放行不拦截
     * - su/sudo 在原生 Termux 环境中：完整拦截 + 弹窗
     * - 其他高危命令：无论环境如何均拦截
     *
     * @param context Context
     * @param session TerminalSession
     * @param command 用户输入的命令
     * @return true 表示命令已被拦截处理，false 表示非高危命令
     */
    fun handleTerminalCommand(context: Context, session: com.termux.terminal.TerminalSession, command: String): Boolean {
        // 无限制模式：仅对 Agent 命令放行（shouldSkipRiskCheck 为 true），用户手敲命令仍按保护级别检查
        if (isUnlimitedModeActive(context) && shouldSkipRiskCheck()) return false

        val level = getProtectionLevel(context)
        setLastProtectionLevel(level)

        // OFF: 直接放行，不检测
        if (level == ProtectionLevel.OFF) return false

        val trimmed = command.trim()

        // 先用原生环境模式检测（su/sudo 会被标记为危险）
        val nativeDetection = RiskCommandDetector.detect(command, inNativeTermux = true)
        if (!nativeDetection.isDangerous) return false

        // 记录危险命令计数
        incrementDangerCount()

        // 检测当前环境（带缓存）
        val envType = detectEnvironment(context, session)

        // --- SSH 会话快速路径优化 ---
        // SSH 会话中，危险操作实际发生在远程设备，本地防护意义有限
        // 对于非破坏性命令（su/sudo 等），直接放行
        if (envType == EnvironmentType.SSH) {
            // su/sudo 在 SSH 中：仅 Snackbar 提醒，放行
            if (nativeDetection.riskType == RiskCommandDetector.RiskType.SU_SUDO) {
                Handler(Looper.getMainLooper()).post {
                    SnackbarHelper.show(
                        context,
                        context.getString(R.string.risk_command_container_sudo_warning),
                        Snackbar.LENGTH_SHORT
                    )
                }
                return false
            }

            // WARN_ONLY 级别：所有危险命令仅提示，放行
            if (level == ProtectionLevel.WARN_ONLY) {
                emitSnackbar(nativeDetection.description, Snackbar.LENGTH_SHORT)
                return false
            }

            // WARN_VERIFY 级别：SSH 会话中大部分危险命令仅 Snackbar 提示
            // SHUTDOWN_REBOOT 和 FORMAT 仍需弹窗（可能影响远程服务器可用性）
            if (level == ProtectionLevel.WARN_VERIFY) {
                when (nativeDetection.riskType) {
                    RiskCommandDetector.RiskType.SHUTDOWN_REBOOT,
                    RiskCommandDetector.RiskType.FORMAT,
                    RiskCommandDetector.RiskType.RM_RF_ROOT -> {
                        // 这些命令在远程服务器上也很危险，继续拦截流程
                        return handleDangerousCommand(context, session, command, nativeDetection, envType)
                    }
                    else -> {
                        // 其他命令仅 Snackbar 提示，放行
                        val msg = "SSH远程: ${nativeDetection.description}"
                        emitSnackbar(msg, Snackbar.LENGTH_SHORT)
                        return false
                    }
                }
            }

            // AUTO_BLOCK 级别：SSH 会话中也直接拦截
            if (level == ProtectionLevel.AUTO_BLOCK) {
                lastCommandAutoBlocked = true
                val msg = "危险操作被拒绝: ${nativeDetection.description}"
                emitSnackbar(msg, Snackbar.LENGTH_LONG)
                return true
            }
        }

        // --- 非 SSH 环境（原生/容器/虚拟机）按原逻辑处理 ---

        // WARN_ONLY: Snackbar 提示但不拦截，显示危险命令的具体描述
        if (level == ProtectionLevel.WARN_ONLY) {
            val msg = nativeDetection.description
            emitSnackbar(msg, Snackbar.LENGTH_LONG)
            return false
        }

        // AUTO_BLOCK: 直接拦截，显示拒绝原因
        if (level == ProtectionLevel.AUTO_BLOCK) {
            lastCommandAutoBlocked = true
            val msg = "危险操作被拒绝: ${nativeDetection.description}"
            emitSnackbar(msg, Snackbar.LENGTH_LONG)
            return true
        }

        // WARN_VERIFY: 完整拦截 + 弹窗验证流程
        // 如果是 su/sudo，检查是否在原生 Termux 环境
        if (nativeDetection.riskType == RiskCommandDetector.RiskType.SU_SUDO) {
            // 检查是否包装了其他危险命令（如 sudo shutdown、sudo poweroff 等）
            val wrappedCommand = extractWrappedCommand(trimmed)
            if (wrappedCommand != null) {
                val wrappedDetection = RiskCommandDetector.detect(wrappedCommand)
                if (wrappedDetection.isDangerous && wrappedDetection.riskType != RiskCommandDetector.RiskType.SU_SUDO) {
                    // 包装的命令更危险，按包装命令的类型处理
                    return handleDangerousCommand(context, session, command, wrappedDetection, envType)
                }
            }

            if (envType != EnvironmentType.NATIVE) {
                // 非原生环境：Snackbar 提醒后放行
                Handler(Looper.getMainLooper()).post {
                    SnackbarHelper.show(
                        context,
                        context.getString(R.string.risk_command_container_sudo_warning),
                        Snackbar.LENGTH_LONG
                    )
                }
                return false
            }
        }

        // 其他高危命令或原生环境下的 su/sudo：正常拦截流程
        return handleDangerousCommand(context, session, command, nativeDetection, envType)
    }

    /**
     * 从 su/sudo 命令中提取被包装的子命令。
     * 例如："sudo shutdown -h now" → "shutdown -h now"
     *       "su -c 'poweroff'" → "poweroff"
     *       "su -c reboot" → "reboot"
     */
    private fun extractWrappedCommand(command: String): String? {
        val trimmed = command.trim()

        // 匹配 sudo <command> 或 su -c <command> 或 su -c '<command>'
        val sudoPattern = Regex("""^\s*sudo\s+(.*)""", RegexOption.DOT_MATCHES_ALL)
        val sudoMatch = sudoPattern.find(trimmed)
        if (sudoMatch != null) {
            return sudoMatch.groupValues[1].trim()
        }

        val suPattern = Regex("""^\s*su\s+-c\s+['"]?(.+?)['"]?\s*$""", RegexOption.DOT_MATCHES_ALL)
        val suMatch = suPattern.find(trimmed)
        if (suMatch != null) {
            return suMatch.groupValues[1].trim()
        }

        return null
    }

    /**
     * 处理高危命令拦截的通用流程。
     */
    private fun handleDangerousCommand(
        context: Context,
        session: com.termux.terminal.TerminalSession,
        command: String,
        detection: RiskCommandDetector.DetectionResult,
        envType: EnvironmentType
    ): Boolean {
        // SHUTDOWN_REBOOT 类型：原生环境和 SSH 环境都拦截
        if (detection.riskType == RiskCommandDetector.RiskType.SHUTDOWN_REBOOT) {
            // SSH 环境下，对 init 命令额外检查只拦截 init 0 和 init 6
            if (envType == EnvironmentType.SSH) {
                val trimmed = command.trim()
                if (trimmed.matches(Regex("""\s*init\s+.*""", RegexOption.IGNORE_CASE))) {
                    if (!trimmed.matches(Regex("""\s*init\s+[06]\s*""", RegexOption.IGNORE_CASE))) {
                        // 不是 init 0 或 init 6，放行
                        return false
                    }
                }
            }
            // SSH 电源操作，设置特殊弹窗状态
            savePendingState(context, session.mHandle, command)
            startCountdown()
            _dialogState.value = DialogState(
                command = command,
                riskDescription = detection.description,
                riskType = detection.riskType?.displayName ?: "高危操作",
                environmentType = envType,
                isSshPowerOperation = true
            )
            // 60 秒超时自动拒绝并恢复会话
            Handler(Looper.getMainLooper()).postDelayed({
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val handle = prefs.getString(KEY_PENDING_SESSION_HANDLE, null)
                val result = prefs.getString(KEY_PENDING_RESULT, null)
                if (handle != null && result == null) {
                    prefs.edit().putString(KEY_PENDING_RESULT, RESULT_DENIED).apply()
                    _dialogState.value = null
                    stopCountdown()
                    // 超时走取消逻辑，恢复会话
                    navigateBackToTermux(context, handle, RESULT_DENIED)
                }
            }, 60000L)
            // 跳转到主页 Activity
            val intent = Intent(context, com.termux.app.MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            context.startActivity(intent)
            return true
        }

        // 其他高危命令：拦截流程
        // 保存待处理状态到 SharedPreferences
        savePendingState(context, session.mHandle, command)

        // 启动倒计时
        startCountdown()

        // 设置弹窗状态（MainActivity 中的 RiskConfirmDialogHost 会观察到并显示）
        val dialogState = DialogState(
            command = command,
            riskDescription = detection.description,
            riskType = detection.riskType?.displayName ?: "高危操作",
            environmentType = envType,
            isWindowsDiskCommand = detection.isWindowsDiskCommand
        )
        _dialogState.value = dialogState
        
        // 记录日志帮助调试
        android.util.Log.i("RiskConfirmManager", "Dialog state set: command=$command, envType=$envType, riskType=${detection.riskType}")
        android.util.Log.i("RiskConfirmManager", "Starting MainActivity to show dialog...")

        // 60 秒超时自动拒绝并恢复会话
        Handler(Looper.getMainLooper()).postDelayed({
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val handle = prefs.getString(KEY_PENDING_SESSION_HANDLE, null)
            val result = prefs.getString(KEY_PENDING_RESULT, null)
            if (handle != null && result == null) {
                // 超时未处理，自动拒绝
                prefs.edit().putString(KEY_PENDING_RESULT, RESULT_DENIED).apply()
                _dialogState.value = null
                stopCountdown()
                // 超时走取消逻辑，恢复会话
                navigateBackToTermux(context, handle, RESULT_DENIED)
            }
        }, 60000L)

        // 跳转到主页 Activity，主页的 RiskConfirmDialogHost 会显示弹窗
        val intent = Intent(context, com.termux.app.MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)

        return true
    }

    /**
     * 检测当前终端会话的运行环境（带缓存优化）。
     *
     * @param context Context
     * @param session TerminalSession
     * @return 环境类型
     */
    private fun detectEnvironment(
        context: Context,
        session: com.termux.terminal.TerminalSession
    ): EnvironmentType {
        val sessionHandle = session.mHandle
        
        // 先从缓存读取
        environmentCache[sessionHandle]?.let { return it }

        val shellPath = session.shellPath ?: ""
        val sessionName = session.mSessionName ?: ""

        // 检查 shell 路径是否指向容器
        val containerIndicators = listOf("proot", "/rootfs/", "/container/")
        for (indicator in containerIndicators) {
            if (shellPath.contains(indicator, ignoreCase = true)) {
                environmentCache[sessionHandle] = EnvironmentType.CONTAINER
                return EnvironmentType.CONTAINER
            }
        }

        // 检查 shell 路径是否指向虚拟机
        val vmIndicators = listOf("qemu", "/vm/", "/guest/")
        for (indicator in vmIndicators) {
            if (shellPath.contains(indicator, ignoreCase = true)) {
                environmentCache[sessionHandle] = EnvironmentType.VM
                return EnvironmentType.VM
            }
        }

        // 检查会话名称是否包含 SSH 标识
        val sshIndicators = listOf("ssh", "scp", "sftp", "remote", "SSH-")
        for (indicator in sshIndicators) {
            if (sessionName.contains(indicator, ignoreCase = true)) {
                environmentCache[sessionHandle] = EnvironmentType.SSH
                return EnvironmentType.SSH
            }
        }

        // 检查会话参数是否包含 SSH 命令（通过远程页面创建的 SSH 会话）
        val args = session.args
        if (args != null) {
            for (arg in args) {
                if (arg != null && arg.contains("ssh", ignoreCase = true)) {
                    environmentCache[sessionHandle] = EnvironmentType.SSH
                    return EnvironmentType.SSH
                }
            }
        }

        // 默认视为原生 Termux 环境
        environmentCache[sessionHandle] = EnvironmentType.NATIVE
        return EnvironmentType.NATIVE
    }

    /** 判断是否为原生 Termux 环境 */
    private fun isNativeTermuxEnvironment(
        context: Context,
        session: com.termux.terminal.TerminalSession
    ): Boolean = detectEnvironment(context, session) == EnvironmentType.NATIVE

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
}

/**
 * 风险确认 OverlayDialog 宿主。
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
    var checkboxChecked by remember { mutableStateOf(false) }

    LaunchedEffect(dialogState) {
        if (dialogState == null) {
            checkboxChecked = false
        }
    }

    val context = LocalContext.current
    val snackbarScope = rememberCoroutineScope()
    val showBlockedMessage: () -> Unit = {
        val msg = context.getString(R.string.accessibility_guard_blocked_toast)
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

    // 主风险确认弹窗
    dialogState?.let { state ->
        LaunchedEffect(state.command) {
            checkboxChecked = false
        }

        if (state.isSshPowerOperation) {
            // SSH 电源操作专用弹窗
            OverlayDialog(
                show = true,
                onDismissRequest = {},
                title = stringResource(R.string.risk_command_ssh_power_title),
                summary = stringResource(R.string.risk_command_ssh_power_warning),
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .physicalTouchDetector()
                            .accessibilityGuard(thirdPartyBlocked)
                            .padding(top = 4.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = stringResource(R.string.risk_command_label) + ":",
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
                                    text = "${stringResource(R.string.risk_command_ssh_power_confirm_no)}(${countdown}s)",
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
                                    text = stringResource(R.string.risk_command_ssh_power_confirm_yes),
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            )
        } else {
            // 普通高危命令弹窗
            // 根据环境类型选择不同的标题和描述
            val dialogTitle = when (state.environmentType) {
                RiskConfirmManager.EnvironmentType.NATIVE -> stringResource(R.string.risk_command_dialog_title)
                RiskConfirmManager.EnvironmentType.CONTAINER -> stringResource(R.string.risk_command_env_container_title)
                RiskConfirmManager.EnvironmentType.VM -> stringResource(R.string.risk_command_env_vm_title)
                RiskConfirmManager.EnvironmentType.SSH -> stringResource(R.string.risk_command_env_ssh_title)
            }
            val envWarning = when (state.environmentType) {
                 RiskConfirmManager.EnvironmentType.NATIVE -> null
                 RiskConfirmManager.EnvironmentType.CONTAINER -> stringResource(R.string.risk_command_env_container_warning)
                 RiskConfirmManager.EnvironmentType.VM -> stringResource(R.string.risk_command_env_vm_warning)
                 RiskConfirmManager.EnvironmentType.SSH -> {
                     val isDiskCommand = state.riskType in listOf("dd 磁盘写入", "格式化/分区")
                     if (isDiskCommand) {
                         if (state.isWindowsDiskCommand) {
                             stringResource(R.string.risk_command_env_ssh_disk_windows_warning)
                         } else {
                             stringResource(R.string.risk_command_env_ssh_disk_warning)
                         }
                     } else {
                         stringResource(R.string.risk_command_env_ssh_warning)
                     }
                 }
             }

            OverlayDialog(
                show = true,
                onDismissRequest = {},
                title = dialogTitle,
                summary = buildString {
                    append(state.riskDescription)
                    if (envWarning != null) {
                        append("\n\n")
                        append(envWarning)
                    }
                    append("\n\n")
                    append(stringResource(R.string.risk_command_dialog_disclaimer))
                },
                content = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .physicalTouchDetector()
                            .accessibilityGuard(thirdPartyBlocked)
                            .padding(top = 4.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            text = stringResource(R.string.risk_command_label) + ":",
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
                            text = stringResource(R.string.risk_command_warning),
                            style = androidx.compose.ui.text.TextStyle(
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium
                            )
                        )

                        Spacer(Modifier.height(12.dp))

                        CheckboxPreference(
                            title = stringResource(R.string.risk_command_confirm_checkbox),
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
                                    text = "${stringResource(R.string.cancel)}(${countdown}s)",
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
                                    text = stringResource(R.string.risk_command_continue),
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}

/**
 * 检测设备是否设置了生物识别或屏幕锁验证。
 * 如果没有设置任何验证方式，返回 false，应跳过生物验证。
 */
private fun hasBiometricAuthentication(activity: ComponentActivity): Boolean {
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
            activity.getString(R.string.risk_command_biometric_not_set),
            Snackbar.LENGTH_LONG
        )
        onResult(true)
        return
    }

    val title = activity.getString(R.string.risk_command_biometric_prompt)
    val subtitle = activity.getString(R.string.risk_command_disable_confirm)

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
