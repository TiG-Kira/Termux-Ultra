package com.termux.app.compose

import android.content.Context
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
    private const val KEY_STREAM = "stream"
    private const val KEY_VISION_MODE = "vision_mode"
    private const val KEY_SYSTEM_PROMPT = "system_prompt"

    /** 与 AgentPaw 上游 LlmConfig 默认值保持一致 */
    const val DEFAULT_TEMPERATURE = 0.7f
    const val DEFAULT_TOP_P = 1.0f
    const val DEFAULT_MAX_TOKENS = 2048
    const val DEFAULT_MAX_TOOL_ROUNDS = 15
    const val DEFAULT_VISION_MODE = "AUTO"

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

    fun isStreamEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_STREAM, true)

    /** 视觉分辨率模式：AUTO / FAST / HIGH（AgentPaw 截屏工具的取图策略） */
    fun getVisionMode(context: Context): String =
        prefs(context).getString(KEY_VISION_MODE, DEFAULT_VISION_MODE) ?: DEFAULT_VISION_MODE

    /** 自定义系统提示词；为空时使用 AgentPaw 内置默认提示词 */
    fun getSystemPrompt(context: Context): String =
        prefs(context).getString(KEY_SYSTEM_PROMPT, "").orEmpty()

    fun save(
        context: Context,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
        maxToolRounds: Int,
        stream: Boolean,
        visionMode: String,
        systemPrompt: String,
    ) {
        prefs(context).edit().apply {
            putFloat(KEY_TEMPERATURE, temperature)
            putFloat(KEY_TOP_P, topP)
            putInt(KEY_MAX_TOKENS, maxTokens)
            putInt(KEY_MAX_TOOL_ROUNDS, maxToolRounds)
            putBoolean(KEY_STREAM, stream)
            putString(KEY_VISION_MODE, visionMode)
            putString(KEY_SYSTEM_PROMPT, systemPrompt)
            apply()
        }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
