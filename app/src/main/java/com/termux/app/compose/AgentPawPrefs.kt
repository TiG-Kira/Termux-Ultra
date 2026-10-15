package com.termux.app.compose

import android.content.Context
import com.paw.agent.device.PhoneControlMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AgentPaw 集成偏好。
 *
 * LLM 连接信息（baseUrl / apiKey / model 等）不在这里存——按集成约定直接沿用
 * Termux Agent 已配置的 LLM（见 [AgentPawEngine.buildLlmConfig]）；此处只保存
 * AgentPaw 自有的行为参数，字段语义与 AgentPaw 上游 LlmSettingsScreen 保持一致。
 */
object AgentPawPrefs {

    private const val PREFS_NAME = "agentpaw_prefs"
    private const val KEY_MANUAL_MODE = "manual_mode"
    private const val KEY_AUTO_SWITCH = "auto_switch"
    private const val KEY_TEMPERATURE = "temperature"
    private const val KEY_TOP_P = "top_p"
    private const val KEY_MAX_TOKENS = "max_tokens"
    private const val KEY_MAX_TOOL_ROUNDS = "max_tool_rounds"
    private const val KEY_UNLIMITED_TOOL_ROUNDS = "unlimited_tool_rounds"
    private const val KEY_STREAM = "stream"
    private const val KEY_VISION_MODE = "vision_mode"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"
    private const val KEY_CONTROL_MODE = "control_mode"
    private const val KEY_ADAPTIVE_PACING = "adaptive_pacing"

    /** 与 AgentPaw 上游 LlmConfig 默认值保持一致 */
    const val DEFAULT_TEMPERATURE = 0.7f
    const val DEFAULT_TOP_P = 1.0f
    const val DEFAULT_MAX_TOKENS = 2048
    const val DEFAULT_MAX_TOOL_ROUNDS = 15
    const val DEFAULT_VISION_MODE = "AUTO"

    /** 截屏分辨率模式的可选值，与 AgentPaw 截屏工具的取图策略一一对应。 */
    val VISION_MODES = listOf(DEFAULT_VISION_MODE, "FAST", "HIGH")

    /**
     * 手机执行策略的可选值，与 AgentPaw 上游 [PhoneControlMode] 枚举保持一致。
     * AUTO：智能判断优先级（ROOT → Shizuku → 无障碍）；
     * ROOT / SHIZUKU / ACCESSIBILITY：强制锁定对应通道。
     */
    val CONTROL_MODES = PhoneControlMode.entries

    /** 手动模式：进入后持续由 AgentPaw 处理对话，直到用户手动退出。 */
    private val _manualMode = MutableStateFlow(false)
    val manualMode: StateFlow<Boolean> = _manualMode.asStateFlow()

    @Volatile
    private var loaded = false

    /** 从磁盘加载；幂等。必须在首次读取 [manualMode] 前调用，否则首帧会用默认值。 */
    @Synchronized
    fun init(context: Context) {
        if (loaded) return
        _manualMode.value = prefs(context).getBoolean(KEY_MANUAL_MODE, false)
        loaded = true
    }

    @Synchronized
    fun setManualMode(context: Context, enabled: Boolean) {
        _manualMode.value = enabled
        prefs(context).edit().putBoolean(KEY_MANUAL_MODE, enabled).apply()
    }

    /** 自动切换：Termux Agent 识别到需由 AgentPaw 执行的任务时临时接管（任务完成后自动回到原模式）。 */
    fun isAutoSwitchEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_SWITCH, true)

    fun setAutoSwitch(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_SWITCH, enabled).apply()
    }

    fun getTemperature(context: Context): Float =
        prefs(context).getFloat(KEY_TEMPERATURE, DEFAULT_TEMPERATURE)

    fun getTopP(context: Context): Float =
        prefs(context).getFloat(KEY_TOP_P, DEFAULT_TOP_P)

    fun getMaxTokens(context: Context): Int =
        prefs(context).getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)

    fun getMaxToolRounds(context: Context): Int =
        prefs(context).getInt(KEY_MAX_TOOL_ROUNDS, DEFAULT_MAX_TOOL_ROUNDS)

    /**
     * 无限执行步数：开启后步数不再受限（交给 0.1.3 的步数无上限机制，见 [AgentPawEngine.buildLlmConfig]），
     * 关闭时沿用 [getMaxToolRounds] 的原有上限。默认关闭。
     */
    fun isUnlimitedToolRounds(context: Context): Boolean =
        prefs(context).getBoolean(KEY_UNLIMITED_TOOL_ROUNDS, false)

    fun setUnlimitedToolRounds(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_UNLIMITED_TOOL_ROUNDS, enabled).apply()
    }

    fun isStreamEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STREAM, true)

    /** 视觉分辨率模式：AUTO / FAST / HIGH（AgentPaw 截屏工具的取图策略） */
    fun getVisionMode(context: Context): String {
        val stored = prefs(context).getString(KEY_VISION_MODE, DEFAULT_VISION_MODE) ?: DEFAULT_VISION_MODE
        // 磁盘上可能是旧版本或被外部写坏的取值，落到默认模式而不是让选中态无匹配项
        return if (VISION_MODES.contains(stored)) stored else DEFAULT_VISION_MODE
    }

    fun setVisionMode(context: Context, mode: String) {
        prefs(context).edit().putString(KEY_VISION_MODE, mode).apply()
    }

    fun setStreamEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_STREAM, enabled).apply()
    }

    /**
     * 手机执行策略（v0.2.0 新增）。决定 HybridPhoneController 在 ROOT / Shizuku / 无障碍
     * 三者之间的优先级。默认 AUTO：按 ROOT → Shizuku → 无障碍 的可用情况自动回退。
     * 磁盘上的值做一次白名单校验，避免旧版本写入的非法字符串把 RadioButtonPreference
     * 的选中态搞成"没有任何一项匹配"。
     */
    fun getControlMode(context: Context): PhoneControlMode {
        val stored = prefs(context).getString(KEY_CONTROL_MODE, PhoneControlMode.AUTO.name)
            ?: PhoneControlMode.AUTO.name
        return PhoneControlMode.entries.firstOrNull { it.name == stored } ?: PhoneControlMode.AUTO
    }

    fun setControlMode(context: Context, mode: PhoneControlMode) {
        prefs(context).edit().putString(KEY_CONTROL_MODE, mode.name).apply()
    }

    /**
     * AI 智能步间节奏（v0.2.0 新增）。开启后 Agent.run 每轮工具调用之间会用
     * AdaptivePacingEngine 根据工具类型、上下文意图和节奏 profile 自适应延时——
     * 让 LaunchApp / Swipe / Tap 之后等待最恰当的稳定时间，避免操作过密漏击或
     * 过慢卡顿。默认开启。
     */
    fun isAdaptivePacingEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ADAPTIVE_PACING, true)

    fun setAdaptivePacingEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ADAPTIVE_PACING, enabled).apply()
    }

    /** 自定义系统提示词；为空时使用 AgentPaw 内置默认提示词 */
    fun getSystemPrompt(context: Context): String =
        prefs(context).getString(KEY_SYSTEM_PROMPT, "").orEmpty()

    /**
     * 写入数值/文本字段（自动保存用）。null 表示该项当前输入非法，跳过写入以保留上次的值，
     * 这样一处输错不会连带丢掉其它已改好的项。
     *
     * [sync] = true 时用 commit() 同步等待写盘完成，仅在离开页面前使用——apply() 的异步
     * 写入有可能来不及在页面被回收前落盘。开关与单选项由各自的 setter 即时落盘，不在这里写。
     */
    fun saveFields(
        context: Context,
        temperature: Float?,
        topP: Float?,
        maxTokens: Int?,
        maxToolRounds: Int?,
        systemPrompt: String,
        sync: Boolean = false,
    ) {
        val editor = prefs(context).edit()
        temperature?.let { editor.putFloat(KEY_TEMPERATURE, it) }
        topP?.let { editor.putFloat(KEY_TOP_P, it) }
        maxTokens?.let { editor.putInt(KEY_MAX_TOKENS, it) }
        maxToolRounds?.let { editor.putInt(KEY_MAX_TOOL_ROUNDS, it) }
        editor.putString(KEY_SYSTEM_PROMPT, systemPrompt)
        if (sync) editor.commit() else editor.apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
