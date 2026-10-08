package com.termux.app.compose

import android.content.Context
import com.paw.agent.core.agent.Agent
import com.paw.agent.core.agent.ToolRegistry
import com.paw.agent.core.llm.LlmClient
import com.paw.agent.core.llm.LlmConfig
import com.paw.agent.core.llm.LlmProvider
import com.paw.agent.core.search.DuckDuckGoSearchBackend
import com.paw.agent.core.skill.OpenAndSearchSkill
import com.paw.agent.core.skill.ReturnHomeAndResetSkill
import com.paw.agent.core.skill.ScrollAndFindSkill
import com.paw.agent.core.skill.SkillRegistry
import com.paw.agent.core.tool.ShellTool
import com.paw.agent.core.tool.WebSearchTool
import com.paw.agent.core.tool.android.DeepLinkTool
import com.paw.agent.core.tool.android.DoubleTapTool
import com.paw.agent.core.tool.android.GetScreenStateTool
import com.paw.agent.core.tool.android.InputTextTool
import com.paw.agent.core.tool.android.KeyActionTool
import com.paw.agent.core.tool.android.LaunchAppTool
import com.paw.agent.core.tool.android.LongPressTool
import com.paw.agent.core.tool.android.TakeScreenshotTool
import com.paw.agent.core.tool.android.TapTool
import com.paw.agent.core.tool.android.WaitTool
import com.paw.agent.core.tool.android.SwipeTool
import com.paw.agent.device.HybridPhoneController
import java.io.File

/**
 * AgentPaw 集成胶水层：把 Termux Agent 已配置的 LLM 连接信息桥接给 AgentPaw
 * 核心引擎（com.paw.agent:agentpaw-core），并装配其工具集。
 */
object AgentPawEngine {

    /**
     * 是否启用 AI 智能步间节奏（v0.2.0 新增）。封装 Agent.run 的 enableAdaptivePacing
     * 参数读取逻辑，调用方直接透传即可，不必各自 import AgentPawPrefs。
     */
    fun isAdaptivePacingEnabled(context: Context): Boolean =
        AgentPawPrefs.isAdaptivePacingEnabled(context)

    /**
     * 由 Termux Agent 的 LLM 配置 + AgentPaw 行为参数构造 LlmConfig。
     *
     * 连接信息（baseUrl / apiKey / model）完全沿用 Termux Agent 当前生效的配置，
     * AgentPaw 不单独配置 LLM；Termux Agent 处于本地模型模式时无法桥接（AgentPaw
     * 只支持 OpenAI 兼容端点），返回 null。
     */
    fun buildLlmConfig(context: Context): LlmConfig? {
        val providerConfig = AiTermuxPrefs.getConfig(context).providerConfig
        if (providerConfig.provider == "local") return null
        if (providerConfig.apiBaseUrl.isBlank() || providerConfig.model.isBlank()) return null

        val provider = if (providerConfig.provider == "openai") LlmProvider.OPENAI else LlmProvider.CUSTOM
        // 步数无上限开关开启时，给库传 0 触发 0.1.3 的步数无上限机制（run 循环对 maxToolRounds <= 0 视为不限轮数）；
        // 关闭时沿用用户在设置里配置的上限。
        val maxToolRounds = if (AgentPawPrefs.isUnlimitedToolRounds(context)) 0
        else AgentPawPrefs.getMaxToolRounds(context)
        return LlmConfig(
            provider = provider,
            baseUrl = providerConfig.apiBaseUrl,
            apiKey = providerConfig.apiKey,
            model = providerConfig.model,
            temperature = AgentPawPrefs.getTemperature(context),
            topP = AgentPawPrefs.getTopP(context),
            maxTokens = AgentPawPrefs.getMaxTokens(context),
            maxToolRounds = maxToolRounds,
            visionResolutionMode = AgentPawPrefs.getVisionMode(context),
            stream = AgentPawPrefs.isStreamEnabled(context),
            systemPrompt = AgentPawPrefs.getSystemPrompt(context)
                .ifBlank { LlmConfig.DEFAULT_SYSTEM_PROMPT },
        )
    }

    /** 配置是否满足让 AgentPaw 接管一轮对话（在线模型 + 端点/模型已填）。 */
    fun canUseAgentPaw(context: Context): Boolean = buildLlmConfig(context)?.isUsable == true

    /**
     * 装配 AgentPaw 工具集，构成与 AgentPaw 上游 App 一致的能力面：
     * 沙盒脚本、Web 搜索、手机控制（截屏/点击/滑动/输入/启动应用）与组合技能。
     */
    fun buildToolRegistry(context: Context): ToolRegistry {
        val appContext = context.applicationContext
        val phoneController = HybridPhoneController(appContext).apply {
            // v0.2.0 执行策略：让用户选择 AUTO / ROOT / SHIZUKU / ACCESSIBILITY
            // 之间的优先级；默认 AUTO 会在 ROOT 不可用时自动回退到 Shizuku，再回退到无障碍。
            controlMode = AgentPawPrefs.getControlMode(context)
        }
        val sandboxRoot = File(appContext.filesDir, "agentpaw_sandbox").apply { mkdirs() }
        val skills = SkillRegistry(
            listOf(
                ReturnHomeAndResetSkill(),
                OpenAndSearchSkill(),
                ScrollAndFindSkill(),
            ),
        )
        return ToolRegistry(
            listOf(
                ShellTool(sandboxRoot),
                WebSearchTool(DuckDuckGoSearchBackend()),
                TakeScreenshotTool(phoneController),
                TapTool(phoneController),
                DoubleTapTool(phoneController),
                LongPressTool(phoneController),
                SwipeTool(phoneController),
                InputTextTool(phoneController),
                KeyActionTool(phoneController),
                LaunchAppTool(phoneController),
                DeepLinkTool(phoneController),
                GetScreenStateTool(phoneController),
                WaitTool(),
            ) + skills.toTools(phoneController),
        )
    }

    fun buildAgent(llmClient: LlmClient, toolRegistry: ToolRegistry): Agent =
        Agent(llmClient = llmClient, toolRegistry = toolRegistry)

    /** 引擎装配结果（较重，调用方应缓存复用） */
    data class Parts(
        val llmClient: LlmClient,
        val toolRegistry: ToolRegistry,
    )

    /**
     * 自动切换判定：让当前生效的 Termux Agent 模型判断这条消息是否属于
     * 「需要操控手机界面（截屏、点击、滑动、跨应用操作）」的任务。
     * 判定失败一律视为不需要（宁可漏判也不误接管）。
     */
    suspend fun shouldAutoDelegate(context: Context, userText: String): Boolean {
        val llmConfig = buildLlmConfig(context) ?: return false
        if (!llmConfig.isUsable) return false

        val providerConfig = AiTermuxPrefs.getConfig(context).providerConfig
        val classifyMessages = listOf(
            OpenAiMessage(
                "system",
                "你是一个任务路由器。判断用户消息是否需要直接操控 Android 手机界面才能完成" +
                    "（例如截屏看屏幕、点击/滑动界面、跨应用操作、自动化手机操作）。" +
                    "只需操控手机界面时回答 YES，其余（终端命令、文件处理、提问闲聊等）回答 NO。" +
                    "只回答 YES 或 NO，不要输出任何其他内容。"
            ),
            OpenAiMessage("user", userText.take(500)),
        )
        return try {
            val resp = AiApiClient.chat(context, providerConfig, classifyMessages)
            resp.choices.firstOrNull()?.message?.content?.trim()?.uppercase()?.startsWith("YES") == true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户停止生成时必须让取消继续传播，吞掉会导致后续回合成脏状态
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
