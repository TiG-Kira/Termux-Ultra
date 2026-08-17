package com.termux.app.activities

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.OpenableColumns
import com.google.gson.JsonObject
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termux.R
import com.termux.app.TermuxService
import com.termux.app.compose.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

class AiTermuxActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val vm: AiTermuxViewModel by viewModels()
        handlePendingAgentResult(vm)
        setContent {
            com.termux.app.compose.KiTerminalTheme {
                AiTermuxRoot(vm) { finish() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val vm: AiTermuxViewModel by viewModels()
        handlePendingAgentResult(vm)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val vm: AiTermuxViewModel by viewModels()
        handlePendingAgentResult(vm)
    }

    override fun onPause() {
        super.onPause()
        val vm: AiTermuxViewModel by viewModels()
        AiTermuxPrefs.saveChatHistory(this, vm.messages.toList())
    }

    /** 检查并处理从主页返回的 Agent 二次确认结果 */
    private fun handlePendingAgentResult(vm: AiTermuxViewModel) {
        val result = RiskConfirmManager.consumeAgentPendingResult(this)
        if (result != null) {
            val params = runCatching {
                com.google.gson.JsonParser.parseString(result.params).asJsonObject
            }.getOrNull()
            if (params == null) {
                // 参数解析失败，取消操作
                vm.cancelRejectedSkill(result.messageId)
                return
            }
            when (result.result) {
                RiskConfirmManager.RESULT_CONFIRMED -> {
                    vm.executeConfirmedSkill(result.messageId, result.action, params)
                }
                RiskConfirmManager.RESULT_DENIED -> {
                    vm.cancelRejectedSkill(result.messageId)
                }
            }
        }
    }
}

class AiTermuxViewModel(app: android.app.Application) : AndroidViewModel(app) {

    var config by mutableStateOf(AiTermuxPrefs.getConfig(app))
        private set

    var messages = mutableStateListOf<ChatMessage>()
        private set

    var isLoading by mutableStateOf(false)
        private set

    var isStreaming by mutableStateOf(false)
        private set

    private var cancelled = false

    private val autoExecSkills: Set<SkillType> by lazy {
        AiTermuxPrefs.getAutoExecSkills(getApplication())
    }

    fun cancelGeneration() {
        cancelled = true
        isStreaming = false
    }

    var termuxService by mutableStateOf<TermuxService?>(null)
        private set

    private var bound = false
    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TermuxService.LocalBinder
            termuxService = binder.service
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            termuxService = null
            bound = false
        }
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("AiTermux", "协程异常", throwable)
        isLoading = false
        val ctx = getApplication<android.app.Application>()
        synchronized(messages) {
            val lastMsg = messages.lastOrNull()
            if (lastMsg?.role == "assistant") {
                val idx = messages.indexOf(lastMsg)
                if (idx >= 0) {
                    messages[idx] = lastMsg.copy(
                        content = lastMsg.content.ifBlank { "⚠️ 执行出错" },
                        errorMessage = "内部错误: ${throwable.message ?: "未知"}"
                    )
                }
            } else {
                messages.add(ChatMessage(
                    role = "assistant",
                    content = "⚠️ 执行出错",
                    errorMessage = "内部错误: ${throwable.message ?: "未知"}"
                ))
            }
        }
        AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
    }

    init {
        val history = AiTermuxPrefs.getChatHistory(app)
        messages.addAll(history)
        val ctx = getApplication<android.app.Application>()
        val intent = Intent(ctx, TermuxService::class.java)
        ctx.bindService(intent, serviceConn, Context.BIND_AUTO_CREATE)

        viewModelScope.launch(Dispatchers.IO) {
            loadMemoryMd(ctx)
        }
    }

    private suspend fun loadMemoryMd(context: Context) {
        try {
            val memoryFile = File("/data/data/com.termux/files/home/.ai_memory/MEMORY.md")
            if (memoryFile.exists()) {
                val content = memoryFile.readText()
                AiTermuxPrefs.setMemory(context, content)
            }
        } catch (_: Exception) {
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (bound) {
            runCatching { getApplication<android.app.Application>().unbindService(serviceConn) }
        }
    }

    fun updateConfig(newConfig: AiTermuxConfig) {
        config = newConfig.copy(isConfigured = newConfig.providerConfig.apiKey.isNotBlank())
        AiTermuxPrefs.saveConfig(getApplication(), config)
    }

    private fun runInScope(block: suspend () -> Unit) {
        viewModelScope.launch(exceptionHandler) {
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.e("AiTermux", "未捕获异常", e)
                isLoading = false
                val ctx = getApplication<android.app.Application>()
                synchronized(messages) {
                    messages.add(ChatMessage(
                        role = "assistant",
                        content = "⚠️ 执行出错",
                        errorMessage = "操作失败: ${e.message ?: "未知错误"}"
                    ))
                }
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
            }
        }
    }

    fun sendUserMessage(text: String) {
        val ctx = getApplication<android.app.Application>()
        if (text.isBlank()) return
        val userMsg = ChatMessage(role = "user", content = text)
        synchronized(messages) { messages.add(userMsg) }
        AiTermuxPrefs.saveChatHistory(ctx, messages.toList())

        runInScope {
            isLoading = true
            try {
                processUserMessage(ctx, text)
            } finally {
                isLoading = false
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
            }
        }
    }

    /** 提交对 ASK_USER 卡片的回答 */
    fun submitAnswer(messageId: String, answer: String) {
        val ctx = getApplication<android.app.Application>()
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val old = messages[idx]
        val card = old.skillCard ?: return
        if (card.skillType != SkillType.ASK_USER) return
        synchronized(messages) {
            messages[idx] = old.copy(
                skillCard = card.copy(status = SkillStatus.COMPLETED, askAnswer = answer)
            )
        }
        val userMsg = ChatMessage(role = "user", content = "[用户回答] ${card.askQuestion}\n回答：$answer")
        synchronized(messages) { messages.add(userMsg) }
        AiTermuxPrefs.saveChatHistory(ctx, messages.toList())

        runInScope {
            isLoading = true
            try {
                processAiTurn(ctx, "[用户回答] ${card.askQuestion}\n回答：$answer")
            } finally {
                isLoading = false
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
            }
        }
    }

    /** 确认危险操作，进入二次确认流程 */
    fun confirmDangerous(messageId: String) {
        val ctx = getApplication<android.app.Application>()
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val old = messages[idx]
        val card = old.skillCard ?: return
        if (card.skillType != SkillType.CONFIRM_DANGEROUS) return
        val pending = pendingDanger[messageId] ?: return

        // 保存 Agent 待确认状态到 SharedPreferences
        RiskConfirmManager.saveAgentPendingState(ctx, pending.first, pending.second.toString(), messageId)

        synchronized(messages) {
            messages[idx] = old.copy(
                skillCard = card.copy(status = SkillStatus.RUNNING, title = "等待二次确认…")
            )
        }
        AiTermuxPrefs.saveChatHistory(ctx, messages.toList())

        // 跳转到主页进行二次确认
        val intent = Intent(ctx, com.termux.app.MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /** 执行已确认的危险操作（从主页返回后调用） */
    fun executeConfirmedSkill(
        messageId: String,
        skillType: String,
        params: com.google.gson.JsonObject
    ) {
        val ctx = getApplication<android.app.Application>()
        // 清理 pendingDanger
        pendingDanger.remove(messageId)
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val old = messages[idx]
        val card = old.skillCard ?: return

        synchronized(messages) {
            messages[idx] = old.copy(
                skillCard = card.copy(status = SkillStatus.RUNNING, title = "正在执行…")
            )
        }
        AiTermuxPrefs.saveChatHistory(ctx, messages.toList())

        runInScope {
            isLoading = true
            try {
                val svc = termuxService
                val result = SkillExecutor.executeSkill(
                    ctx, svc, skillType, params
                )
                val resultCard = result.skillCard ?: SkillCardData(
                    skillType = try { SkillType.valueOf(skillType) } catch (_: Exception) { SkillType.RUN_COMMAND },
                    title = if (result.success) "执行成功" else "执行失败",
                    description = result.message,
                    status = if (result.success) SkillStatus.COMPLETED else SkillStatus.FAILED
                )
                synchronized(messages) {
                    val idx2 = messages.indexOfFirst { it.id == messageId }
                    if (idx2 >= 0) {
                        messages[idx2] = messages[idx2].copy(
                            skillCard = resultCard,
                            content = if (result.success) "" else "⚠️ 执行出错"
                        )
                    }
                }
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
                continueAfterSkill(ctx, resultCard, result.message)
            } finally {
                isLoading = false
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
            }
        }
    }

    /** 取消已拒绝的危险操作（从主页返回后调用） */
    fun cancelRejectedSkill(messageId: String) {
        val ctx = getApplication<android.app.Application>()
        // 清理 pendingDanger
        pendingDanger.remove(messageId)
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val old = messages[idx]
        val card = old.skillCard ?: return

        synchronized(messages) {
            messages[idx] = old.copy(
                skillCard = card.copy(
                    status = SkillStatus.FAILED,
                    title = "已取消",
                    description = "二次确认未通过，用户取消了该危险操作"
                )
            )
        }
        AiTermuxPrefs.saveChatHistory(ctx, messages.toList())

        runInScope {
            isLoading = true
            try {
                processAiTurn(ctx, "[用户在二次确认中拒绝了危险操作] ${card.dangerousAction ?: card.title}，用户选择不执行。")
            } finally {
                isLoading = false
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
            }
        }
    }

    /** 取消危险操作 */
    fun cancelDangerous(messageId: String) {
        val ctx = getApplication<android.app.Application>()
        val idx = messages.indexOfFirst { it.id == messageId }
        if (idx < 0) return
        val old = messages[idx]
        val card = old.skillCard ?: return
        if (card.skillType != SkillType.CONFIRM_DANGEROUS) return
        pendingDanger.remove(messageId)
        synchronized(messages) {
            messages[idx] = old.copy(
                skillCard = card.copy(
                    status = SkillStatus.FAILED,
                    title = "已取消",
                    description = "用户取消了该危险操作"
                )
            )
        }
        AiTermuxPrefs.saveChatHistory(ctx, messages.toList())

        runInScope {
            isLoading = true
            try {
                processAiTurn(ctx, "[用户取消了危险操作] ${card.dangerousAction ?: card.title}，用户选择不执行。")
            } finally {
                isLoading = false
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
            }
        }
    }

    // 待执行的危险操作：messageId -> (skillType, params)
    private val pendingDanger = mutableMapOf<String, Pair<String, JsonObject>>()

    /** 获取待执行的危险操作（用于从主页返回后恢复执行） */
    fun getPendingDanger(messageId: String): Pair<String, JsonObject>? = pendingDanger[messageId]

    private suspend fun processUserMessage(ctx: Context, userText: String) {
        processAiTurn(ctx, userText)
    }

    /**
     * 一次 AI 回合：发消息给 AI → 流式显示回复 → 执行技能 → 结果回传 AI → 循环
     */
    private suspend fun processAiTurn(ctx: Context, userText: String) {
        var currentUserText = userText
        cancelled = false
        // 被幻觉拦截的技能卡片（skillType -> params key），用于重输出时去重
        var hallucinatedSkillKeys: Set<String> = emptySet()
        var hallucinatedTotalCount = 0
        var hallucinationRetryCount = 0
        val maxHallucinationRetries = 1
        // 技能执行历史：记录已执行的 skillType:params key，用于检测重复
        val executedHistory = mutableListOf<String>()
        val maxHistorySize = 10
        // 连续相同技能计数，用于检测 AI 陷入技能循环
        var lastSkillKey: String? = null
        var consecutiveSameSkill = 0
        val maxConsecutiveSameSkill = 3
        // AI 文本回复历史：检测纯文本重复（AI 反复回答同样的问题）
        val recentAiReplies = mutableListOf<String>()
        val maxReplyHistory = 5
        var consecutiveSimilarReplies = 0
        val maxConsecutiveSimilarReplies = 3

        // 构建一次 System Prompt，后续迭代复用
        val baseSystemPrompt = AiTermuxPrefs.buildFullSystemPrompt(ctx)
        // 重试时使用的精简 System Prompt
        val retrySystemPrompt = """
你刚才的回复违反了 Termux Agent 的输出规范。请重新输出，并遵守以下核心规则：
1. 仅输出技能卡片（```skill 代码块）+ 一句自然语言说明
2. 不要编造执行结果、不要声称操作已完成
3. 不要添加技能结果、执行结果等伪造段落
4. 如果之前被拦截过相同的技能卡片，不要重复输出
5. 类别A技能（NEW_SESSION/RUN_COMMAND/CAPTURE_OUTPUT等）仅生成卡片，告知用户点击即可
6. 每轮回复结尾必须输出 [END_TURN] 表示完成
""".trimIndent()

        for (round in 1..20) {
            if (cancelled) break

            // 决定使用的 System Prompt：重试时用精简版
            val systemPrompt = if (hallucinationRetryCount > 0) retrySystemPrompt else baseSystemPrompt
            val apiMsgs = mutableListOf<OpenAiMessage>()
            apiMsgs.add(OpenAiMessage("system", systemPrompt))
            synchronized(messages) {
                messages.dropLast(1).takeLast(20).forEach {
                    if (it.role == "user" || it.role == "assistant") {
                        apiMsgs.add(OpenAiMessage(it.role, it.content))
                    }
                }
            }
            apiMsgs.add(OpenAiMessage("user", currentUserText))

            // 先放一个空的 assistant 消息用于流式填充
            val streamMsgId = "stream_${System.currentTimeMillis()}_${Math.random()}"
            synchronized(messages) {
                messages.add(
                    ChatMessage(
                        id = streamMsgId,
                        role = "assistant",
                        content = ""
                    )
                )
            }

            isStreaming = true
            var replyText = ""
            var reasoningText = ""
            var rawResponseText = ""
            var streamError: String? = null
            var wasCancelled = false

            AiApiClient.chatStream(config.providerConfig, apiMsgs, { cancelled }).collect { chunk ->
                when (chunk) {
                    is StreamChunk.Reasoning -> {
                        reasoningText += chunk.delta
                        synchronized(messages) {
                            val idx = messages.indexOfFirst { it.id == streamMsgId }
                            if (idx >= 0) {
                                messages[idx] = messages[idx].copy(
                                    reasoningContent = reasoningText,
                                    reasoningDone = false
                                )
                            }
                        }
                    }
                    StreamChunk.ReasoningDone -> {
                        android.util.Log.d("AiTermux", "ReasoningDone received, reasoningLen=${reasoningText.length}")
                        synchronized(messages) {
                            val idx = messages.indexOfFirst { it.id == streamMsgId }
                            if (idx >= 0) {
                                messages[idx] = messages[idx].copy(
                                    reasoningContent = reasoningText,
                                    reasoningDone = true
                                )
                            }
                        }
                    }
                    is StreamChunk.Content -> {
                        replyText += chunk.delta
                        synchronized(messages) {
                            val idx = messages.indexOfFirst { it.id == streamMsgId }
                            if (idx >= 0) {
                                // 流式显示时隐藏 [END_TURN] 标记
                                val displayText = replyText.replace("[END_TURN]", "").trimEnd()
                                messages[idx] = messages[idx].copy(
                                    content = SkillExecutor.stripSkillBlocks(displayText).ifBlank { displayText }
                                )
                            }
                        }
                    }
                    is StreamChunk.Done -> {
                        replyText = chunk.fullText
                        rawResponseText = chunk.rawResponse
                        android.util.Log.d("AiTermux", "Done received, contentLen=${chunk.fullText.length}, reasoningLen=${chunk.fullReasoning.length}, rawLen=${chunk.rawResponse.length}")
                    }
                    is StreamChunk.Error -> {
                        streamError = chunk.message
                    }
                    is StreamChunk.Cancelled -> {
                        wasCancelled = true
                    }
                }
            }
            isStreaming = false

// 流结束后，处理消息
            if (wasCancelled) {
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
                return
            }

            if (streamError != null) {
                synchronized(messages) {
                    val idx = messages.indexOfFirst { it.id == streamMsgId }
                    if (idx >= 0) {
                        messages[idx] = messages[idx].copy(
                            content = "调用 AI 时出错了",
                            errorMessage = "API 错误：$streamError"
                        )
                    }
                }
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
                return
            }

            // 如果只有思考内容没有回复文本，中断并保存原始响应用于调试
            if (replyText.isBlank() && reasoningText.isNotBlank()) {
                android.util.Log.e("AiTermux", "AI 思考完成但未输出文本，保存原始响应用于调试")
                synchronized(messages) {
                    val idx = messages.indexOfFirst { it.id == streamMsgId }
                    if (idx >= 0) {
                        messages[idx] = messages[idx].copy(
                            content = "⚠️ AI 只进行了深度思考，未输出实际回复。点击查看原始 API 响应以供排查。",
                            reasoningContent = reasoningText,
                            reasoningDone = true,
                            rawResponse = rawResponseText
                        )
                    }
                }
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
                return
            }

            // 如果流式返回的是空内容（无思考也无回复），删掉占位消息
            if (replyText.isBlank()) {
                synchronized(messages) {
                    val idx = messages.indexOfFirst { it.id == streamMsgId }
                    if (idx >= 0) messages.removeAt(idx)
                }
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
                return
            }

            // === 预解析技能块（用于交叉验证，防止正则遗漏导致的误判）===
            val preParsedSkills = SkillExecutor.parseSkillBlocks(replyText)
            val preParsedCount = preParsedSkills.size

            // === 假输出检测（传入预解析数量进行交叉验证）===
            val fakeCheck = SkillExecutor.detectFakeOutput(replyText, preParsedCount)

            // 二次校验：如果预解析找到了技能块，放宽检测标准
            val finalViolations = if (preParsedCount > 0 && fakeCheck.isFake) {
                fakeCheck.violations.filter { v ->
                    // 移除"无技能块"类禁令（4、6、7、8）
                    v.contains("但未输出任何技能卡片").not() &&
                    v.contains("自信式幻觉").not() &&
                    v.contains("捏造不存在的技能").not() &&
                    v.contains("逃避执行").not() &&
                    // 移除"通用描述"类禁令1条目（当有技能卡时，"已执行"/"已完成"等是合法描述）
                    v.contains("但未识别到技能类型").not()
                }
            } else {
                fakeCheck.violations
            }

            if (finalViolations.isNotEmpty()) {
                android.util.Log.e("AiTermux", "检测到 AI 假输出（第${hallucinationRetryCount + 1}次）: $finalViolations")

                // 超过最大重试次数，接受输出
                if (hallucinationRetryCount >= maxHallucinationRetries) {
                    android.util.Log.w("AiTermux", "幻觉重试次数已达上限，接受当前输出")
                    synchronized(messages) {
                        val idx = messages.indexOfFirst { it.id == streamMsgId }
                        if (idx >= 0) {
                            messages[idx] = messages[idx].copy(
                                content = "⚠️ 检测到 AI 输出不规范，但已达重试上限，已接受输出。",
                                isWarning = true
                            )
                        }
                    }
                    AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
                    // 继续执行下面的技能解析和执行逻辑，跳过幻觉检测
                    // 不设置 continue，让代码走到下面的正常流程
                } else {
                    // 保存被拦截的技能卡片 key，用于重输出时去重
                    hallucinatedSkillKeys = preParsedSkills.map { (st, params) ->
                        "$st:$params"
                    }.toSet()
                    hallucinatedTotalCount = preParsedSkills.size
                    hallucinationRetryCount++

                    // 替换流式消息为警告
                    synchronized(messages) {
                        val idx = messages.indexOfFirst { it.id == streamMsgId }
                        if (idx >= 0) {
                            messages[idx] = messages[idx].copy(
                                content = "⚠️ 检测到 AI 幻觉输出（${finalViolations.firstOrNull() ?: "违反禁令"}），正在要求重新生成…",
                                isWarning = true
                            )
                        }
                    }
                    AiTermuxPrefs.saveChatHistory(ctx, messages.toList())

                    val shortReason = finalViolations.firstOrNull() ?: "违反输出规范"
                    currentUserText = buildString {
                        appendLine("[拦截] $shortReason")
                        appendLine("重新输出：仅需输出 skill 代码块 + 简短说明，停止。")
                        if (hallucinatedSkillKeys.isNotEmpty()) {
                            appendLine("跳过之前已拦截的 ${hallucinatedTotalCount} 个相同卡片。")
                        }
                        appendLine("原请求：$userText")
                    }
                    continue
                }
            }

            // 更新最终消息（可能包含技能卡片）
            // 注意：以下所有处理仅基于 replyText（实际回复内容），
            // reasoningContent（深度思考）不参与任何检测/解析/执行逻辑
            // 检测并移除 [END_TURN] 结束标记
            val hasEndTurn = replyText.contains("[END_TURN]")
            val cleanedReply = replyText.replace("[END_TURN]", "").trimEnd()
            val plainText = SkillExecutor.stripSkillBlocks(cleanedReply)
            val skills = SkillExecutor.parseSkillBlocks(cleanedReply)

            // === 去重检查 1：与之前被幻觉拦截的技能卡片比较 ===
            val newSkills = mutableListOf<Pair<String, JsonObject>>()
            val skippedSkills = mutableListOf<Pair<String, JsonObject>>()

            for (skill in skills) {
                val key = "${skill.first}:${skill.second}"
                if (key in hallucinatedSkillKeys) {
                    skippedSkills.add(skill)
                } else {
                    newSkills.add(skill)
                }
            }

            // === 去重检查 2：与已执行的历史比较，检测 AI 重复执行同一操作 ===
            val trulyNewSkills = mutableListOf<Pair<String, JsonObject>>()
            for (skill in newSkills) {
                val key = "${skill.first}:${skill.second}"
                if (key in executedHistory) {
                    // AI 试图重复执行已执行过的相同技能
                    skippedSkills.add(skill)
                    android.util.Log.w("AiTermux", "AI 试图重复执行已执行的技能: $key")
                } else {
                    trulyNewSkills.add(skill)
                }
            }

            // 检查连续相同技能，检测 AI 陷入循环
            if (trulyNewSkills.size == 1 && lastSkillKey != null) {
                if (trulyNewSkills[0].first + ":" + trulyNewSkills[0].second == lastSkillKey) {
                    consecutiveSameSkill++
                } else {
                    consecutiveSameSkill = 1
                }
            } else if (trulyNewSkills.isNotEmpty()) {
                consecutiveSameSkill = 1
            }

            if (consecutiveSameSkill >= maxConsecutiveSameSkill) {
                android.util.Log.e("AiTermux", "AI 陷入循环：连续 $consecutiveSameSkill 次输出相同技能 $lastSkillKey")
                synchronized(messages) {
                    val idx = messages.indexOfFirst { it.id == streamMsgId }
                    if (idx >= 0) {
                        messages[idx] = messages[idx].copy(
                            content = "⚠️ 检测到 AI 陷入循环（连续 $consecutiveSameSkill 次执行相同操作），已自动停止。",
                            isWarning = true
                        )
                    }
                }
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
                // 用简短消息告诉 AI 不要再重复
                currentUserText = "[系统] 你连续多次输出了相同的技能，请直接回答用户的问题，不要重复执行已完成的操作。"
                // 重置连续计数，防止立即再次触发
                consecutiveSameSkill = 0
                lastSkillKey = null
                continue
            }

            // 用 trulyNewSkills 替换 newSkills 用于后续执行
            val skillsToExecute = trulyNewSkills

            // 如果有被跳过的卡片，显示提示
            if (skippedSkills.isNotEmpty()) {
                val skipMsg = buildString {
                    append("⚠️ 以下 ${skippedSkills.size} 个操作已被跳过（重复执行）")
                    if (skillsToExecute.isNotEmpty()) append("，将执行其余 ${skillsToExecute.size} 个操作")
                    append("。")
                }
                synchronized(messages) {
                    val idx = messages.indexOfFirst { it.id == streamMsgId }
                    if (idx >= 0) {
                        messages[idx] = messages[idx].copy(
                            content = skipMsg
                        )
                    }
                }
                // 为每个被跳过的卡片显示"已跳过"卡片
                for ((skStr, _) in skippedSkills) {
                    val sk = runCatching { SkillType.valueOf(skStr) }.getOrNull()
                    val skipCard = SkillCardData(
                        skillType = sk ?: SkillType.RUN_COMMAND,
                        title = "已跳过（重复执行）",
                        description = "此操作已在之前的回复中生成，无需重复执行",
                        status = SkillStatus.COMPLETED
                    )
                    val skipId = "skip_${System.currentTimeMillis()}_${Math.random()}"
                    synchronized(messages) {
                        messages.add(
                            ChatMessage(
                                id = skipId,
                                role = "assistant",
                                content = "",
                                skillCard = skipCard
                            )
                        )
                    }
                }
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
            }

            // 更新消息文本
            if (skippedSkills.isEmpty()) {
                synchronized(messages) {
                    val idx = messages.indexOfFirst { it.id == streamMsgId }
                    if (idx >= 0) {
                        messages[idx] = messages[idx].copy(
                            content = plainText.ifBlank { replyText }
                        )
                    }
                }
            }
            AiTermuxPrefs.saveChatHistory(ctx, messages.toList())

            // 清理被拦截的技能集合
            hallucinatedSkillKeys = emptySet()
            hallucinatedTotalCount = 0

            // === 文本重复检测：检测 AI 是否在反复输出相同/相似的回答 ===
            val normalizedReply = plainText.trim().lowercase().replace(Regex("\\s+"), " ")
            if (normalizedReply.length > 20) {
                // 与历史回复比较相似度
                val isSimilarToRecent = recentAiReplies.any { prev ->
                    calcTextSimilarity(normalizedReply, prev) > 0.85
                }
                if (isSimilarToRecent) {
                    consecutiveSimilarReplies++
                    android.util.Log.w("AiTermux", "AI 文本重复: 连续相似回复 $consecutiveSimilarReplies 次")
                } else {
                    consecutiveSimilarReplies = 1
                }
                recentAiReplies.add(normalizedReply)
                if (recentAiReplies.size > maxReplyHistory) {
                    recentAiReplies.removeAt(0)
                }

                if (consecutiveSimilarReplies >= maxConsecutiveSimilarReplies) {
                    android.util.Log.e("AiTermux", "AI 陷入文本循环：连续 $consecutiveSimilarReplies 次相似回复")
                    synchronized(messages) {
                        val idx = messages.indexOfFirst { it.id == streamMsgId }
                        if (idx >= 0) {
                            messages[idx] = messages[idx].copy(
                                content = plainText.ifBlank { "（AI 重复回答已停止）" } +
                                    "\n\n⚠️ 检测到 AI 陷入重复回答循环（$consecutiveSimilarReplies 次相似回复），已自动停止。",
                                isWarning = true
                            )
                        }
                    }
                    AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
                    return
                }
            }

            // 检测是否有重复执行的技能（触犯禁令第四条）
            val hasDuplicateViolation = skippedSkills.isNotEmpty() && hallucinatedSkillKeys.isEmpty()
            // 构建警告消息，用于告知 AI 触犯了禁令
            val duplicateWarning = if (hasDuplicateViolation) {
                "[系统警告] 你违反了输出规范禁令第四条：禁止重复执行已执行过的技能。以下 ${skippedSkills.size} 个技能已跳过执行。请直接回答用户的问题，不要再重复执行已完成的操作。"
            } else null

            // === 终止逻辑：基于 [END_TURN] 标记 ===
            if (skillsToExecute.isEmpty()) {
                // 无新技能要执行
                if (hasEndTurn) {
                    // AI 标记了结束 → 终止（即使是违规警告后 AI 回复了文本）
                    android.util.Log.d("AiTermux", "AI 输出 [END_TURN]（无技能），终止循环")
                    return
                }
                if (hasDuplicateViolation) {
                    // 违规且无 END_TURN → 发送警告让 AI 回复文本
                    currentUserText = duplicateWarning!!
                    continue
                }
                // 无 END_TURN 但也没有违规 → AI 忘记标记，安全终止并警告
                android.util.Log.w("AiTermux", "AI 未输出 [END_TURN]，安全终止")
                synchronized(messages) {
                    val idx = messages.indexOfFirst { it.id == streamMsgId }
                    if (idx >= 0) {
                        messages[idx] = messages[idx].copy(
                            content = plainText.ifBlank { replyText } +
                                    "\n\n⚠️ AI 未输出结束标记，已自动停止（下次将提醒 AI 输出 [END_TURN]）",
                            isWarning = true
                        )
                    }
                }
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
                return
            }

            var needsUserInput = false
            var lastResultText: String? = null
            var executedSkillTypes = mutableListOf<SkillType>()

            for ((skillTypeStr, params) in skillsToExecute) {
                val st = runCatching { SkillType.valueOf(skillTypeStr) }.getOrNull()
                val dangerReason = if (st != null) SkillExecutor.checkDangerous(st, params) else null
                if (dangerReason != null && st != null) {
                    val dangerCard = SkillCardData(
                        skillType = SkillType.CONFIRM_DANGEROUS,
                        title = "危险操作，需要确认",
                        description = dangerReason,
                        status = SkillStatus.RUNNING,
                        dangerousReason = dangerReason,
                        dangerousAction = buildDangerousActionDesc(st, params)
                    )
                    val tempId = "danger_${System.currentTimeMillis()}_${Math.random()}"
                    pendingDanger[tempId] = skillTypeStr to params
                    synchronized(messages) {
                        messages.add(
                            ChatMessage(
                                id = tempId,
                                role = "assistant",
                                content = "",
                                skillCard = dangerCard
                            )
                        )
                    }
                    AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
                    needsUserInput = true
                    break
                }

                val runningCard = SkillCardData(
                    skillType = st ?: SkillType.RUN_COMMAND,
                    title = "执行技能中…",
                    description = skillTypeStr,
                    status = SkillStatus.RUNNING
                )
                val tempId = "skill_${System.currentTimeMillis()}_${Math.random()}"
                synchronized(messages) {
                    messages.add(
                        ChatMessage(
                            id = tempId,
                            role = "assistant",
                            content = "",
                            skillCard = runningCard
                        )
                    )
                }

                val svc = termuxService
                val result = runCatching {
                    SkillExecutor.executeSkill(ctx, svc, skillTypeStr, params)
                }.getOrElse { e ->
                    SkillExecutionResult(false, "执行异常: ${e.message}")
                }

                val actualSkillType = result.skillCard?.skillType ?: st ?: SkillType.RUN_COMMAND
                executedSkillTypes.add(actualSkillType)

                // 记录执行的技能 key 到历史，用于检测重复
                val execKey = "$skillTypeStr:$params"
                executedHistory.add(execKey)
                if (executedHistory.size > maxHistorySize) {
                    executedHistory.removeAt(0)
                }
                lastSkillKey = execKey

                synchronized(messages) {
                    val idx = messages.indexOfLast { it.id == tempId }
                    if (idx >= 0) {
                        val resultCard = result.skillCard ?: SkillCardData(
                            skillType = actualSkillType,
                            title = if (result.success) "执行成功" else "执行失败",
                            description = result.message,
                            status = if (result.success) SkillStatus.COMPLETED else SkillStatus.FAILED
                        )
                        messages[idx] = messages[idx].copy(
                            content = if (result.success) "" else "⚠️ 执行出错",
                            skillCard = resultCard
                        )

                        if (resultCard.skillType == SkillType.ASK_USER) {
                            needsUserInput = true
                            AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
                            break
                        }

                        lastResultText = buildSkillResultText(resultCard, result.message)
                    }
                }
                AiTermuxPrefs.saveChatHistory(ctx, messages.toList())
                withContext(Dispatchers.IO) { kotlinx.coroutines.delay(150) }
            }

            if (needsUserInput) {
                return
            }

            // 如果所有执行的技能都是"需点击执行"类型，终止循环
            // AI 已经生成了卡片并告知用户点击，不应再继续生成更多卡片
            if (executedSkillTypes.isNotEmpty() &&
                executedSkillTypes.all { it.requiresClick(autoExecSkills) }) {
                return
            }

            // AI 输出了 [END_TURN] → 执行完技能后终止，不再回传结果给 AI
            if (hasEndTurn) {
                android.util.Log.d("AiTermux", "AI 输出 [END_TURN]（有技能已执行），终止循环")
                return
            }

            // 构建回传给 AI 的结果消息（仅当 AI 未标记结束时才回传）
            val baseResult = lastResultText ?: "(技能执行完成，无输出)"
            val missingEndTurnWarning = if (!hasEndTurn) {
                "\n\n[系统警告] 你上一轮回复遗漏了 [END_TURN] 结束标记，导致系统再次调用你。请在本轮回复结尾务必输出 [END_TURN]。"
            } else ""
            currentUserText = if (hasDuplicateViolation) {
                "$baseResult\n\n$duplicateWarning$missingEndTurnWarning"
            } else {
                "$baseResult$missingEndTurnWarning"
            }
        }
    }

    /**
     * 计算两个字符串的相似度（基于最长公共子序列比率）
     * 用于检测 AI 是否在反复输出相同/相似的回答
     */
    private fun calcTextSimilarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        // 使用简单的基于词的 Jaccard 相似度 + 子序列比率
        val wordsA = a.split("\\s+".toRegex()).toSet()
        val wordsB = b.split("\\s+".toRegex()).toSet()
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0.0
        val intersection = wordsA intersect wordsB
        val union = wordsA union wordsB
        val jaccard = intersection.size.toDouble() / union.size.toDouble()
        // 额外检查：较短文本是否是较长文本的子串
        val shorter = if (a.length < b.length) a else b
        val longer = if (a.length < b.length) b else a
        val substringBonus = if (longer.contains(shorter)) 0.3 else 0.0
        return (jaccard + substringBonus).coerceAtMost(1.0)
    }

    private fun buildSkillResultText(card: SkillCardData, defaultMsg: String): String {
        val status = if (card.status == SkillStatus.COMPLETED) "成功" else "失败"
        val output = if (!card.output.isNullOrBlank()) "\n${card.output}" else ""
        val desc = card.description.ifBlank { defaultMsg }
        return "[技能结果] ${card.skillType.name} $status：$desc$output"
    }

    private fun buildDangerousActionDesc(type: SkillType, params: JsonObject): String {
        return when (type) {
            SkillType.RUN_COMMAND -> "执行命令：${if (params.has("command")) params.get("command").asString else ""}"
            SkillType.FILE_DELETE -> "删除：${if (params.has("path")) params.get("path").asString else ""}"
            SkillType.CLOSE_ALL_SESSIONS -> "关闭全部会话"
            SkillType.EXIT_TERMUX -> "退出 Termux"
            else -> type.name
        }
    }

    private suspend fun continueAfterSkill(ctx: Context, card: SkillCardData, defaultMsg: String) {
        val resultText = buildSkillResultText(card, defaultMsg)
        processAiTurn(ctx, resultText)
    }

    fun clearHistory() {
        val ctx = getApplication<android.app.Application>()
        synchronized(messages) { messages.clear() }
        AiTermuxPrefs.clearChatHistory(ctx)
    }
}

/** ================================================================ */

@Composable
private fun AiTermuxRoot(vm: AiTermuxViewModel, onBack: () -> Unit) {
    if (!vm.config.isConfigured) {
        AiSetupScreen(vm = vm, onBack = onBack)
    } else {
        AiChatScreen(vm = vm, onBack = onBack)
    }
}

/** -------------------- 配置引导页 -------------------- */

@Composable
private fun AiSetupScreen(vm: AiTermuxViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val isDark = isSystemInDarkTheme()

    var provider by remember { mutableStateOf(vm.config.providerConfig.provider) }
    var apiKey by remember { mutableStateOf(vm.config.providerConfig.apiKey) }
    var baseUrl by remember { mutableStateOf(vm.config.providerConfig.apiBaseUrl) }
    var model by remember { mutableStateOf(vm.config.providerConfig.model) }
    var temperature by remember { mutableStateOf(vm.config.providerConfig.temperature) }
    var customPrompt by remember { mutableStateOf(vm.config.customSystemPrompt) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = "Termux Agent 设置",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            item {
                // Hero 卡
                val gradient = Brush.linearGradient(
                    colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFEC4899))
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(gradient)
                        .padding(20.dp)
                ) {
                    Column {
                        Text(
                            text = "🤖 Termux Agent",
                            color = Color.White,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "你的专属 Termux 智能助手",
                            color = Color.White,
                            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "支持管理终端会话、运行虚拟机、VNC/SSH 连接、文件操作等。只需用自然语言描述你的需求。",
                            color = Color.White.copy(alpha = 0.9f),
                            style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)
                        )
                    }
                }
            }

            item { SectionTitle("AI 能力说明") }
            item {
                InfoBullet("会话管理", "新建 / 关闭终端会话、退出 Termux")
                InfoBullet("虚拟机", "运行 QEMU with VNC，支持新建配置")
                InfoBullet("远程连接", "VNC 连接、SSH 连接远程机器")
                InfoBullet("文件操作", "列出目录、读取 / 写入 / 删除文件（限 Termux 容器内）")
                InfoBullet("命令执行", "在会话中执行任意命令、通过 pkg 安装软件包")
            }

            item { SectionTitle("1. 选择提供商") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderChip("OpenAI 兼容", "custom", provider, isDark) { provider = it }
                    ProviderChip("直接 OpenAI", "openai", provider, isDark) { provider = it }
                }
            }

            item { SectionTitle("2. API Key（必填）") }
            item {
                TextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim() },
                    label = "API Key",
                    modifier = Modifier.fillMaxWidth(),
                    useLabelAsPlaceholder = true,
                    singleLine = true
                )
            }

            item { SectionTitle("3. API 地址") }
            item {
                TextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it.trim() },
                    label = "Base URL（如 https://api.openai.com/v1）",
                    modifier = Modifier.fillMaxWidth(),
                    useLabelAsPlaceholder = true,
                    singleLine = true
                )
            }

            item { SectionTitle("4. 模型名称") }
            item {
                TextField(
                    value = model,
                    onValueChange = { model = it.trim() },
                    label = "Model（如 gpt-4o-mini / deepseek-chat 等）",
                    modifier = Modifier.fillMaxWidth(),
                    useLabelAsPlaceholder = true,
                    singleLine = true
                )
            }

            item { SectionTitle("5. 温度 (%.1f)".format(temperature)) }
            item {
                Slider(
                    value = temperature,
                    onValueChange = { temperature = (it * 10).toInt() / 10f },
                    valueRange = 0f..1.6f,
                    steps = 15,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item { SectionTitle("6. 自定义 System Prompt（可选）") }
            item {
                TextField(
                    value = customPrompt,
                    onValueChange = { customPrompt = it },
                    label = "你可以添加自己的要求，例如：'用繁体字回答'、'尽量用一行命令解决' 等",
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    useLabelAsPlaceholder = true,
                    singleLine = false,
                    maxLines = Int.MAX_VALUE
                )
            }

            testResult?.let { msg ->
                item {
                    Text(
                        text = msg,
                        color = if (msg.startsWith("✅")) Color(0xFF16A34A) else Color(0xFFDC2626),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            testing = true
                            testResult = null
                            val cfg = AiProviderConfig(provider, apiKey, baseUrl, model, temperature)
                            vm.viewModelScope.launch {
                                val resp = AiApiClient.chat(
                                    cfg,
                                    listOf(
                                        OpenAiMessage("system", "你是测试机器人，只回复 'ok' 一个字，不要加其他任何文字。"),
                                        OpenAiMessage("user", "ping")
                                    )
                                )
                                testResult = if (resp.error != null) {
                                    "❌ 连接失败：${resp.error.message}"
                                } else {
                                    val reply = resp.choices.firstOrNull()?.message?.content ?: ""
                                    if (reply.isNotBlank()) "✅ 连接成功！模型回复：$reply"
                                    else "❌ 返回为空，请检查配置"
                                }
                                testing = false
                            }
                        },
                        enabled = apiKey.isNotBlank() && !testing,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        colors = ButtonDefaults.buttonColors(
                            color = if (isDark) Color(0xFF424242) else Color(0xFFE0E0E0)
                        )
                    ) {
                        if (testing) androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("测试连接", color = MiuixTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            if (apiKey.isBlank()) {
                                testResult = "❌ 请先填写 API Key"
                                return@Button
                            }
                            val newCfg = AiTermuxConfig(
                                providerConfig = AiProviderConfig(provider, apiKey, baseUrl, model, temperature),
                                customSystemPrompt = customPrompt,
                                isConfigured = true
                            )
                            vm.updateConfig(newCfg)
                            android.widget.Toast.makeText(ctx, "配置已保存，进入对话界面", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                    ) {
                        Text("保存并开始使用", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface),
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun InfoBullet(title: String, desc: String) {
    val isDark = isSystemInDarkTheme()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
            Text(desc, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun ProviderChip(label: String, value: String, selected: String, isDark: Boolean, onClick: (String) -> Unit) {
    val sel = selected == value
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (sel) MiuixTheme.colorScheme.primary else if (isDark) Color(0xFF2A2A2A) else Color(0xFFF0F0F0))
            .clickable { onClick(value) }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 14.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (sel) Color.White else MiuixTheme.colorScheme.onSurface)
        }
    }
}

/** -------------------- 聊天界面 -------------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiChatScreen(vm: AiTermuxViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val isDark = isSystemInDarkTheme()
    val focusRequester = remember { FocusRequester() }
    var inputText by remember { mutableStateOf("") }
    var pendingAttachment by remember { mutableStateOf<Triple<String, String, Long>?>(null) }


    // 文件/图片选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingAttachment = resolveAttachment(ctx, uri)
        }
    }

    LaunchedEffect(vm.messages.size, vm.isLoading) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }

    Box {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = "Termux Agent",
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable { onBack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    },
                    actions = {
                        if (vm.isStreaming) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .clickable { vm.cancelGeneration() }
                                    .background(Color(0xFFDC2626)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "停止生成",
                                    modifier = Modifier.size(18.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                Column {
                    HorizontalDivider(color = if (isDark) Color(0xFF2A2A2A) else Color(0xFFE8E8E8))
                    // 选中的附件标签
                    pendingAttachment?.let { (fileName, filePath, sizeB) ->
                        val sizeStr = when {
                            sizeB >= 1024 * 1024 -> "%.1f MB".format(sizeB.toFloat() / (1024 * 1024))
                            sizeB >= 1024 -> "%.1f KB".format(sizeB.toFloat() / 1024)
                            else -> "$sizeB B"
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 10.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_upload),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MiuixTheme.colorScheme.primary
                            )
                            Text(
                                text = fileName,
                                style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = sizeStr,
                                style = TextStyle(fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            )
                            IconButton(onClick = { pendingAttachment = null }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "移除附件",
                                    modifier = Modifier.size(16.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 上传按钮
                        IconButton(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0xFF2A2A2A) else Color(0xFFF0F0F0))
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_upload),
                                contentDescription = "上传文件/图片",
                                modifier = Modifier.size(22.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            label = "需要 Termux Agent 做什么…",
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            useLabelAsPlaceholder = true,
                            singleLine = false,
                            maxLines = 4
                        )
                        IconButton(
                            onClick = {
                                val text = inputText.trim()
                                if ((text.isNotBlank() || pendingAttachment != null) && !vm.isLoading) {
                                    val finalMsg = buildString {
                                        pendingAttachment?.let { (fname, fpath, sz) ->
                                            val sizeStr = when {
                                                sz >= 1024 * 1024 -> "%.1fMB".format(sz.toFloat() / (1024 * 1024))
                                                sz >= 1024 -> "%.1fKB".format(sz.toFloat() / 1024)
                                                else -> "${sz}B"
                                            }
                                            append("📎 附件：$fname（$sizeStr，路径：$fpath）\n")
                                        }
                                        if (text.isNotBlank()) append(text)
                                    }
                                    vm.sendUserMessage(finalMsg)
                                    inputText = ""
                                    pendingAttachment = null
                                }
                            },
                            enabled = (inputText.isNotBlank() || pendingAttachment != null) && !vm.isLoading,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(
                                    if ((inputText.isNotBlank() || pendingAttachment != null) && !vm.isLoading) MiuixTheme.colorScheme.primary
                                    else if (isDark) Color(0xFF333) else Color(0xFFE0E0E0)
                                )
                        ) {
                            if (vm.isLoading) {
                                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "发送",
                                    tint = if ((inputText.isNotBlank() || pendingAttachment != null)) Color.White else Color.Gray,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        ) { padding ->
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {

                item {
                    AiDisclaimerCard(isDark)
                }

                if (vm.messages.isEmpty()) {
                    item { WelcomeChatCard(isDark) }
                    item { QuickChips(vm, inputText = "") { inputText = it } }
                }

                items(vm.messages, key = { it.id }) { msg ->
                    ChatBubble(msg = msg, vm = vm)
                }

                if (vm.isLoading && vm.messages.lastOrNull()?.role != "assistant") {
                    item { TypingIndicator(isDark) }
                }

                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }

    // 风险命令确认弹窗
    com.termux.app.compose.RiskConfirmDialogHost()
}

@Composable
private fun AiDisclaimerCard(isDark: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDark) Color(0xFF1E1E1E) else Color(0xFFF5F5F5))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_info),
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = "内容由 AI 生成",
            style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        )
    }
}

@Composable
private fun WelcomeChatCard(isDark: Boolean) {
    val gradient = Brush.linearGradient(
        colors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFEC4899))
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(gradient)
            .padding(18.dp)
    ) {
        Column {
            Text("👋 欢迎使用 Termux Agent", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "我可以帮你管理 Termux。试试说：\n" +
                        "• 新建一个终端会话\n" +
                        "• 帮我安装 git 和 vim\n" +
                        "• 列出家目录文件\n" +
                        "• 运行我的 QEMU 虚拟机\n" +
                        "• 用 VNC 连接 192.168.1.10:5901",
                color = Color.White.copy(alpha = 0.92f),
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickChips(vm: AiTermuxViewModel, inputText: String, setInput: (String) -> Unit) {
    val suggestions = listOf(
        "新建一个终端会话",
        "帮我列出当前会话",
        "安装 git 和 vim",
        "列出家目录文件",
        "读取 ~/.bashrc",
        "用 VNC 连接 127.0.0.1:5900"
    )
    val isDark = isSystemInDarkTheme()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        suggestions.forEach { s ->
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isDark) Color(0xFF242424) else Color(0xFFF3F3F3))
                    .clickable {
                        if (!vm.isLoading) vm.sendUserMessage(s)
                    }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(s, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator(isDark: Boolean) {
    Row(
        modifier = Modifier
            .padding(end = 60.dp)
            .clip(RoundedCornerShape(14.dp, 14.dp, 14.dp, 2.dp))
            .background(if (isDark) Color(0xFF242424) else Color(0xFFF3F3F3))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        val dotColor = if (isDark) Color(0xFFAAA) else Color(0xFF888)
        repeat(3) { i ->
            var alpha by remember { mutableStateOf(0.3f) }
            LaunchedEffect(i) {
                while (true) {
                    kotlinx.coroutines.delay((i * 200).toLong())
                    alpha = 1f
                    kotlinx.coroutines.delay(400)
                    alpha = 0.3f
                }
            }
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = alpha))
            )
        }
    }
}

/** -------------------- 消息气泡 -------------------- */

private fun parseMarkdown(text: String, isDark: Boolean = true): AnnotatedString = buildAnnotatedString {
    val codeBg = if (isDark) Color(0xFF3A3A3A) else Color(0xFFE8E8E8)
    val codeFg = if (isDark) Color(0xFFFFD54F) else Color(0xFFB71C1C)

    val lines = text.split("\n")
    var firstLine = true

    for (line in lines) {
        if (!firstLine) append("\n")
        firstLine = false

        when {
            line.startsWith("### ") -> {
                val content = line.removePrefix("### ").trim()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)) {
                    append(content)
                }
            }
            line.startsWith("## ") -> {
                val content = line.removePrefix("## ").trim()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)) {
                    append(content)
                }
            }
            line.startsWith("# ") -> {
                val content = line.removePrefix("# ").trim()
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)) {
                    append(content)
                }
            }
            line.matches(Regex("\\d+\\.\\s.*")) -> {
                val content = line.replaceFirst(Regex("^(\\d+\\.\\s)"), "")
                val prefix = line.substringBefore(" ")
                append("$prefix ")
                appendInlineFormatted(content, codeBg, codeFg)
            }
            line.startsWith("- ") || line.startsWith("* ") -> {
                val content = line.substring(2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("• ") }
                appendInlineFormatted(content, codeBg, codeFg)
            }
            line.trimStart().startsWith("|") -> {
                val cells = line.trim().removeSurrounding("|").split("|").map { it.trim() }
                val isSeparator = cells.all { it.matches(Regex("^:?-{3,}:?$")) }
                if (isSeparator) {
                    append("│ ")
                    append(cells.joinToString(" │ ") { "─".repeat(it.length.coerceAtLeast(3)) })
                    append(" │")
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("│ ")
                        append(cells.joinToString(" │ "))
                        append(" │")
                    }
                }
            }
            else -> appendInlineFormatted(line, codeBg, codeFg)
        }
    }
}

private fun AnnotatedString.Builder.appendInlineFormatted(
    text: String, codeBg: Color, codeFg: Color
) {
    val segments = mutableListOf<Triple<String, Boolean, Boolean>>()
    var current = ""
    var inBold = false
    var inCode = false
    var i = 0
    while (i < text.length) {
        when {
            i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' && !inCode -> {
                if (current.isNotBlank()) segments.add(Triple(current, inBold, inCode))
                current = ""
                inBold = !inBold
                i += 2
            }
            text[i] == '`' && !inBold -> {
                if (current.isNotBlank()) segments.add(Triple(current, inBold, inCode))
                current = ""
                inCode = !inCode
                i++
            }
            else -> {
                current += text[i]
                i++
            }
        }
    }
    if (current.isNotBlank()) segments.add(Triple(current, inBold, inCode))

    for ((seg, bold, code) in segments) {
        when {
            code -> {
                withStyle(SpanStyle(background = codeBg, color = codeFg)) {
                    append(seg)
                }
            }
            bold -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(seg)
                }
            }
            else -> append(seg)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(msg: ChatMessage, vm: AiTermuxViewModel) {
    val isDark = isSystemInDarkTheme()
    val isUser = msg.role == "user"
    val isWarning = msg.isWarning
    var showRawResponse by remember { mutableStateOf(false) }

    // 空内容且只有卡片，不画文本气泡
    if (msg.content.isBlank() && msg.skillCard != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            SkillCard(msgId = msg.id, card = msg.skillCard, errorMsg = msg.errorMessage, isDark = isDark, vm = vm)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // 深度思考内容（可折叠）
        if (!isUser && !msg.reasoningContent.isNullOrBlank()) {
            ReasoningBlock(reasoning = msg.reasoningContent, isDone = msg.reasoningDone, isDark = isDark)
            Spacer(Modifier.height(6.dp))
        }

        if (msg.content.isNotBlank()) {
            val bg = when {
                isWarning -> if (isDark) Color(0xFF4A2C00) else Color(0xFFFFF3CD)
                isUser -> MiuixTheme.colorScheme.primary
                isDark -> Color(0xFF242424)
                else -> Color(0xFFF3F3F3)
            }
            val textColor = when {
                isWarning -> if (isDark) Color(0xFFFFD666) else Color(0xFF856404)
                isUser -> Color.White
                else -> MiuixTheme.colorScheme.onSurface
            }
            val corners = if (isUser) {
                RoundedCornerShape(14.dp, 14.dp, 2.dp, 14.dp)
            } else {
                RoundedCornerShape(14.dp, 14.dp, 14.dp, 2.dp)
            }
            val ctx = LocalContext.current
            Box(
                modifier = Modifier
                    .then(if (isUser) Modifier.padding(start = 40.dp) else Modifier.padding(end = 40.dp))
                    .clip(corners)
                    .background(bg)
                    .then(
                        if (!isUser && msg.content.isNotBlank()) {
                            Modifier.combinedClickable(
                                onLongClick = {
                                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("AI 回复", msg.content)
                                    clipboard.setPrimaryClip(clip)
                                    android.widget.Toast.makeText(ctx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                },
                                onClick = {}
                            )
                        } else Modifier
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = parseMarkdown(msg.content, isDark),
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = textColor)
                )
            }
        }

        msg.skillCard?.let { card ->
            Spacer(Modifier.height(6.dp))
            SkillCard(msgId = msg.id, card = card, errorMsg = msg.errorMessage, isDark = isDark, vm = vm)
        }

        msg.errorMessage?.takeIf { msg.skillCard == null }?.let { err ->
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFFEE2E2).copy(alpha = if (isDark) 0.15f else 1f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_error),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFFDC2626)
                )
                Text(
                    text = err,
                    style = TextStyle(fontSize = 12.sp, color = Color(0xFFDC2626))
                )
            }
        }

        // 原始 API 响应查看入口
        msg.rawResponse?.takeIf { it.isNotBlank() }?.let { rawResp ->
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFDBEAFE).copy(alpha = if (isDark) 0.15f else 1f))
                    .clickable { showRawResponse = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "🔍 点击查看原始 API 响应",
                    style = TextStyle(fontSize = 12.sp, color = Color(0xFF2563EB))
                )
            }
        }
    }

    // 原始 API 响应对话框
    if (showRawResponse && msg.rawResponse?.isNotBlank() == true) {
        val ctx = LocalContext.current
        OverlayDialog(
            show = showRawResponse,
            onDismissRequest = { showRawResponse = false },
            title = "原始 API 响应",
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "这是从 API 收到的原始 SSE 数据，用于排查问题。",
                        style = TextStyle(fontSize = 12.sp, color = Color.Gray)
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .height(300.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = msg.rawResponse!!,
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = if (isDark) Color(0xFFB0B0B0) else Color(0xFF333333)
                            )
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            text = "关闭",
                            onClick = { showRawResponse = false }
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            text = "复制",
                            onClick = {
                                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("原始 API 响应", msg.rawResponse!!)
                                clipboard.setPrimaryClip(clip)
                                android.widget.Toast.makeText(ctx, "已复制到剪贴板", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        )
    }
}

/** -------------------- 深度思考内容（可折叠）-------------------- */

@Composable
private fun ReasoningBlock(reasoning: String, isDone: Boolean, isDark: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val bg = if (isDark) Color(0xFF1A1A2E) else Color(0xFFF0F0F8)
    val textColor = if (isDark) Color(0xFFB0B0C8) else Color(0xFF555570)
    val headerColor = if (isDark) Color(0xFF8888AA) else Color(0xFF7777A0)
    val statusColor = if (isDone) Color(0xFF16A34A) else Color(0xFF6366F1)

    Column(
        modifier = Modifier
            .then(Modifier.padding(end = 40.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = if (expanded) "▼" else "▶",
                style = TextStyle(fontSize = 10.sp, color = headerColor)
            )
            Text(
                text = "深度思考",
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = headerColor
                )
            )
            // 思考状态指示
            Text(
                text = if (isDone) "✓ 已完成" else "⋯ 进行中",
                style = TextStyle(
                    fontSize = 11.sp,
                    color = statusColor
                )
            )
            if (!expanded) {
                Text(
                    text = "（点击展开）",
                    style = TextStyle(fontSize = 11.sp, color = headerColor.copy(alpha = 0.6f))
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = reasoning.trim(),
                style = TextStyle(
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = textColor
                )
            )
        }
    }
}

/** -------------------- 技能卡片 -------------------- */

@Composable
private fun SkillCard(msgId: String, card: SkillCardData, errorMsg: String?, isDark: Boolean, vm: AiTermuxViewModel) {
    val ctx = LocalContext.current
    // ASK_USER / CONFIRM_DANGEROUS 需要可交互，状态为 RUNNING 时显示交互组件
    val isInteractive = (card.skillType == SkillType.ASK_USER || card.skillType == SkillType.CONFIRM_DANGEROUS)
            && card.status == SkillStatus.RUNNING

    val (statusColor, statusBg, statusText) = when {
        card.skillType == SkillType.CONFIRM_DANGEROUS && card.status == SkillStatus.RUNNING ->
            Triple(Color(0xFFDC2626), Color(0xFFDC2626).copy(alpha = 0.12f), "待确认")
        card.skillType == SkillType.ASK_USER && card.status == SkillStatus.RUNNING ->
            Triple(Color(0xFF6366F1), Color(0xFF6366F1).copy(alpha = 0.12f), "待回答")
        card.status == SkillStatus.RUNNING -> Triple(Color(0xFF2563EB), Color(0xFF2563EB).copy(alpha = 0.12f), "执行中")
        card.status == SkillStatus.COMPLETED -> Triple(Color(0xFF16A34A), Color(0xFF16A34A).copy(alpha = 0.12f), "已完成")
        card.status == SkillStatus.FAILED -> Triple(Color(0xFFDC2626), Color(0xFFDC2626).copy(alpha = 0.12f), "失败")
        else -> Triple(Color(0xFF64748B), Color(0xFF64748B).copy(alpha = 0.12f), "未知")
    }
    val iconRes = when (card.skillType) {
        SkillType.NEW_SESSION, SkillType.CLOSE_SESSION,
        SkillType.CLOSE_ALL_SESSIONS, SkillType.EXIT_TERMUX,
        SkillType.GET_SESSION_INFO, SkillType.GET_CURRENT_SESSION,
        SkillType.RUN_COMMAND, SkillType.CAPTURE_OUTPUT,
        SkillType.CUSTOM_COMMAND -> R.drawable.ic_terminal
        SkillType.RUN_VM_QEMU, SkillType.CREATE_VM_QEMU,
        SkillType.VM_LIST -> R.drawable.ic_computer
        SkillType.CONNECT_VNC -> R.drawable.ic_vnc
        SkillType.CONNECT_SSH -> R.drawable.ic_ssh
        SkillType.FILE_LIST, SkillType.FILE_READ,
        SkillType.FILE_WRITE, SkillType.FILE_DELETE -> R.drawable.ic_files
        SkillType.PACKAGE_INSTALL -> R.drawable.ic_download
        SkillType.ASK_USER -> R.drawable.ic_help
        SkillType.CONFIRM_DANGEROUS -> R.drawable.ic_warning
        SkillType.SCHEDULE_TASK -> R.drawable.ic_service_notification
        SkillType.GET_DEVICE_STATUS -> R.drawable.ic_info
        SkillType.CLIPBOARD_READ, SkillType.CLIPBOARD_WRITE -> R.drawable.ic_copy
    }

    val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFFAFAFA)
    val borderColor = if (isDark) Color(0xFF2C2C2C) else Color(0xFFE8E8E8)

    val clickable = card.skillType in setOf(
        SkillType.NEW_SESSION, SkillType.CLOSE_SESSION, SkillType.RUN_COMMAND,
        SkillType.CAPTURE_OUTPUT, SkillType.CONNECT_SSH, SkillType.CONNECT_VNC,
        SkillType.RUN_VM_QEMU, SkillType.CREATE_VM_QEMU,
        SkillType.VM_LIST, SkillType.CUSTOM_COMMAND, SkillType.EXIT_TERMUX
    )

    Box(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (clickable) Modifier.clickable { SkillExecutor.onSkillCardClick(ctx, card) }
                else Modifier
            )
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cardBg)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .padding(12.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 左侧图标（右下半露）
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .fillMaxHeight()
                            .height(64.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(statusBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = statusColor
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = card.title,
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                            )
                            Spacer(Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(statusBg)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(statusText, fontSize = 10.sp, color = statusColor, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = card.description,
                            style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        )
                    }
                }

                // 会话 / 连接信息行
                val sessionText = if (!card.sessionName.isNullOrBlank()) {
                    "会话：${card.sessionName}"
                } else null
                val connText = card.connectionAddress?.let { "地址：$it" }
                val vmText = card.vmName?.let { "虚拟机：$it" }
                val fileText = card.filePath?.let { "路径：$it" }
                val cmdText = card.command?.let { "命令：$it" }
                val meta = listOfNotNull(sessionText, connText, vmText, fileText, cmdText)
                if (meta.isNotEmpty()) {
                    HorizontalDivider(color = borderColor)
                    Column(modifier = Modifier.padding(12.dp)) {
                        meta.forEach { line ->
                            Text(
                                text = line,
                                style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }

                // 输出（如文件内容、目录列表）
                card.output?.let { output ->
                    if (output.isNotBlank()) {
                        HorizontalDivider(color = borderColor)
                        val isLong = output.length > 400
                        val display = if (isLong) output.take(400) + "\n…… (输出过长，已截断，请在终端中查看完整结果)" else output
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isDark) Color(0xFF111) else Color(0xFF1E1E1E))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = display,
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = Color(0xFFD4D4D4),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    lineHeight = 18.sp
                                )
                            )
                        }
                    }
                }

                // 错误信息
                if (!errorMsg.isNullOrBlank() || card.status == SkillStatus.FAILED) {
                    val errText = errorMsg ?: card.description
                    if (errText.isNotBlank()) {
                        HorizontalDivider(color = borderColor)
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFEE2E2).copy(alpha = if (isDark) 0.15f else 1f))
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_error),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFFDC2626)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("执行出错", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                                Spacer(Modifier.height(2.dp))
                                Text(errText, fontSize = 12.sp, color = Color(0xFFB91C1C))
                            }
                        }
                    }
                }

                // 点击提示
                if (clickable) {
                    HorizontalDivider(color = borderColor)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "点击卡片打开对应页面 / 会话",
                            style = TextStyle(fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        )
                    }
                }

                // 交互组件（ASK_USER / CONFIRM_DANGEROUS）
                if (isInteractive) {
                    HorizontalDivider(color = borderColor)
                    when (card.skillType) {
                        SkillType.ASK_USER -> {
                            var textInput by remember { mutableStateOf("") }
                            var singleSelection by remember { mutableStateOf<String?>(null) }
                            val multiSelection = remember { mutableStateListOf<String>() }

                            Column(modifier = Modifier.padding(12.dp)) {
                                card.askQuestion?.let { q ->
                                    Text(
                                        text = q,
                                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface),
                                        modifier = Modifier.padding(bottom = 10.dp)
                                    )
                                }

                                val type = card.askType ?: "text"
                                when (type) {
                                    "text" -> {
                                        TextField(
                                            value = textInput,
                                            onValueChange = { textInput = it },
                                            label = card.askPlaceholder ?: "请输入...",
                                            modifier = Modifier.fillMaxWidth(),
                                            useLabelAsPlaceholder = true,
                                            singleLine = true
                                        )
                                        Spacer(Modifier.height(10.dp))
                                        Button(
                                            onClick = { vm.submitAnswer(msgId, textInput) },
                                            enabled = textInput.isNotBlank(),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(10.dp)),
                                            colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                                        ) {
                                            Text("提交回答", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    "single" -> {
                                        card.askOptions?.forEach { option ->
                                            val selected = singleSelection == option
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (selected) statusBg else Color.Transparent)
                                                    .clickable { singleSelection = option }
                                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (selected) MiuixTheme.colorScheme.primary
                                                            else if (isDark) Color(0xFF333333) else Color(0xFFDDDDDD)
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (selected) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(8.dp)
                                                                .clip(CircleShape)
                                                                .background(Color.White)
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = option,
                                                    style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Button(
                                            onClick = { singleSelection?.let { vm.submitAnswer(msgId, it) } },
                                            enabled = singleSelection != null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(10.dp)),
                                            colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                                        ) {
                                            Text("提交回答", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    "multi" -> {
                                        card.askOptions?.forEach { option ->
                                            val checked = multiSelection.contains(option)
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(if (checked) statusBg else Color.Transparent)
                                                    .clickable {
                                                        if (checked) multiSelection.remove(option)
                                                        else multiSelection.add(option)
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(22.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                        .background(
                                                            if (checked) MiuixTheme.colorScheme.primary
                                                            else if (isDark) Color(0xFF333333) else Color(0xFFDDDDDD)
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (checked) {
                                                        Text(
                                                            text = "✓",
                                                            color = Color.White,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                }
                                                Text(
                                                    text = option,
                                                    style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        Button(
                                            onClick = {
                                                vm.submitAnswer(msgId, multiSelection.joinToString(", "))
                                            },
                                            enabled = multiSelection.isNotEmpty(),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                                .clip(RoundedCornerShape(10.dp)),
                                            colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                                        ) {
                                            Text("提交回答", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }

                        SkillType.CONFIRM_DANGEROUS -> {
                            Column(modifier = Modifier.padding(12.dp)) {
                                card.dangerousAction?.let { action ->
                                    Text(
                                        text = "即将执行：$action",
                                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface),
                                        modifier = Modifier.padding(bottom = 10.dp)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Button(
                                        onClick = { vm.cancelDangerous(msgId) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                        colors = ButtonDefaults.buttonColors(
                                            color = if (isDark) Color(0xFF333333) else Color(0xFFEEEEEE)
                                        )
                                    ) {
                                        Text("取消", color = MiuixTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                    }
                                    Button(
                                        onClick = { vm.confirmDangerous(msgId) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(44.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                        colors = ButtonDefaults.buttonColors(color = Color(0xFFDC2626))
                                    ) {
                                        Text("确认执行", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        else -> {}
                    }
                }
            }
        }
    }
}

/**
 * 将用户选择的 content Uri 解析成 <文件名, 绝对路径, 大小字节>。
 * 如果是相册/外部文件且拿不到真实路径，就复制到 Termux filesDir 下。
 */
private fun resolveAttachment(ctx: Context, uri: Uri): Triple<String, String, Long> {
    // 1. 查询显示名和大小
    var fileName: String? = null
    var sizeBytes: Long = 0
    try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            cursor.moveToFirst()
            if (nameIdx >= 0) fileName = cursor.getString(nameIdx)
            if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx).coerceAtLeast(0L)
        }
    } catch (_: Throwable) {
    }

    // 兜底文件名
    val finalName = fileName
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "attachment_${System.currentTimeMillis()}"

    // 2. 尝试拿本地真实文件路径（file:// 或 MediaStore _data）
    var realPath: String? = try {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            uri.path
        } else {
            null
        }
    } catch (_: Throwable) { null }

    // 尝试从 MediaStore 查询 _data 列
    if (realPath == null && "content".equals(uri.scheme, ignoreCase = true)) {
        try {
            ctx.contentResolver.query(
                uri, arrayOf(android.provider.MediaStore.MediaColumns.DATA),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (idx >= 0) realPath = cursor.getString(idx)?.takeIf { it.isNotBlank() }
                }
            }
        } catch (_: Throwable) {
        }
    }

    if (realPath != null) {
        val f = java.io.File(realPath!!)
        if (f.exists() && f.canRead()) {
            return Triple(finalName, f.absolutePath, if (sizeBytes > 0) sizeBytes else f.length())
        }
    }

    // 3. 取不到真实路径（例如相册 content:// 或第三方应用）→ 复制到 Termux filesDir/ai_uploads/
    val uploadDir = java.io.File(ctx.filesDir, "ai_uploads").apply { mkdirs() }
    val outFile = java.io.File(uploadDir, finalName).let { base ->
        var candidate = base
        var i = 1
        while (candidate.exists()) {
            val ext = base.extension.let { if (it.isBlank()) "" else ".$it" }
            candidate = java.io.File(uploadDir, "${base.nameWithoutExtension}_$i$ext")
            i++
        }
        candidate
    }
    ctx.contentResolver.openInputStream(uri)?.use { input ->
        java.io.FileOutputStream(outFile).use { output ->
            input.copyTo(output)
        }
    }
    return Triple(finalName, outFile.absolutePath, if (sizeBytes > 0) sizeBytes else outFile.length())
}
