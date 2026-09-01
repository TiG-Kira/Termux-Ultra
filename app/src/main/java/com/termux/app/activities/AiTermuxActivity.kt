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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
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
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.R
import com.termux.app.TermuxService
import com.termux.app.compose.*
import com.termux.app.utils.SnackbarHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

class AiTermuxActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val vm: AiTermuxViewModel by viewModels()
        handlePendingAgentResult(vm)
        // 如果是从设置页面"重新配置 AI"启动的，强制进入配置页面
        if (intent?.getBooleanExtra("force_setup", false) == true) {
            vm.forceShowSetup()
            intent.removeExtra("force_setup")
        }
        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                com.termux.app.compose.KiTerminalTheme {
                    AiTermuxRoot(vm) { finish() }
                }
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
        AiTermuxPrefs.saveChatHistory(this, vm.messages.toOpenAiMessages())
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

    /** 强制显示配置页面（用户从设置页"重新配置 AI"进入时调用） */
    fun forceShowSetup() {
        config = config.copy(isConfigured = false)
    }

    var messages = mutableStateListOf<ChatMessage>()
        private set

    var isLoading by mutableStateOf(false)
        private set

    var isStreaming by mutableStateOf(false)
        private set

    /** 当provider为local时，允许用户切换使用本地还是在线模型 */
    var useLocalModel by mutableStateOf(true)
        private set

    fun toggleLocalModel() {
        useLocalModel = !useLocalModel
    }

    fun updateLocalModelSelection(useLocal: Boolean) {
        useLocalModel = useLocal
    }

    /** 是否显示模型切换开关（仅当provider为local且配置了备用在线模型时） */
    fun shouldShowModelSwitch(): Boolean {
        val ctx = getApplication<android.app.Application>()
        return config.providerConfig.provider == "local" 
            && AiTermuxPrefs.isFallbackOnlineEnabled(ctx)
            && AiTermuxPrefs.isFallbackOnlineConfigReady(ctx)
    }

    private var cancelled = false

    private val autoExecSkills: Set<String> by lazy {
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
        AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
    }

    init {
        val history = AiTermuxPrefs.getChatHistory(app)
        messages.addAll(history.toChatMessages())
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
        val configured = if (newConfig.providerConfig.provider == "local") {
            AiLocalModel.isLocalModelReady()
        } else {
            newConfig.providerConfig.apiKey.isNotBlank()
        }
        config = newConfig.copy(isConfigured = configured)
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
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
            }
        }
    }

    /** 会话压缩阈值：超过此数量自动触发压缩 */
    private val COMPRESS_THRESHOLD = 40
    /** 压缩后保留的最近消息数量 */
    private val COMPRESS_KEEP_RECENT = 12

    fun sendUserMessage(text: String) {
        val ctx = getApplication<android.app.Application>()
        if (text.isBlank()) return

        // ===== 自动会话压缩 =====
        runInScope {
            checkAndCompressMessages(ctx)
        }

        val userMsg = ChatMessage(role = "user", content = text)
        synchronized(messages) { messages.add(userMsg) }
        AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())

        runInScope {
            isLoading = true
            try {
                processUserMessage(ctx, text)
            } finally {
                isLoading = false
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
            }
        }
    }

    /** 检查并压缩会话历史（如果消息太多） */
    private suspend fun checkAndCompressMessages(ctx: Context) {
        synchronized(messages) {
            if (messages.size <= COMPRESS_THRESHOLD) return
        }
        try {
            val result = compressMessages(ctx)
            if (result != null) {
                android.util.Log.i("AiTermux", "会话压缩完成: ${result.keptRecent} 条保留，摘要长度 ${result.summary.length}")
                // 压缩后重新保存
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
            }
        } catch (e: Exception) {
            android.util.Log.e("AiTermux", "会话压缩失败（忽略，继续正常流程）", e)
        }
    }

    /** 压缩会话历史：调用在线模型生成摘要，用摘要替换旧消息 */
    private suspend fun compressMessages(ctx: Context): CompressResult? {
        val msgSnapshot = synchronized(messages) { messages.toList() }
        if (msgSnapshot.size <= COMPRESS_THRESHOLD) return null

        // 将旧消息（排除最近的 COMPRESS_KEEP_RECENT 条）转为对话格式
        val keepFrom = msgSnapshot.size - COMPRESS_KEEP_RECENT
        if (keepFrom <= 0) return null
        val oldMsgs = msgSnapshot.subList(0, keepFrom)

        // 构建在线模型请求
        val cfg = AiTermuxPrefs.getFallbackOnlineConfig(ctx)
        if (cfg.apiKey.isBlank() || cfg.baseUrl.isBlank()) {
            // 没有在线配置，直接截断（保留最近的消息）
            synchronized(messages) {
                val toRemove = messages.size - COMPRESS_KEEP_RECENT
                if (toRemove > 0) {
                    repeat(toRemove) { messages.removeAt(0) }
                    messages.add(0, ChatMessage(
                        role = "assistant",
                        content = "📎 历史会话已自动压缩（无在线模型可用，直接截断到最近 $COMPRESS_KEEP_RECENT 条）"
                    ))
                }
            }
            return CompressResult(summary = "（无在线模型，直接截断）", keptRecent = COMPRESS_KEEP_RECENT)
        }

        val providerCfg = AiProviderConfig(
            provider = "custom",
            apiKey = cfg.apiKey,
            apiBaseUrl = cfg.baseUrl,
            model = cfg.model,
            temperature = cfg.temperature
        )

        val systemPrompt = """你是一个 AI 对话历史压缩助手。请将以下 AI 与用户的对话历史压缩成一段简洁的摘要。

要求：
1. 保留所有关键任务、决定、重要信息和技能执行结果
2. 去除对话过程中的寒暄、重复、无关内容
3. 保持时间顺序
4. 用中文输出
5. 摘要控制在 500-1000 字以内"""

        val historyText = oldMsgs.mapIndexed { i, m ->
            val roleLabel = when (m.role) { "user" -> "用户"; "assistant" -> "AI"; else -> m.role }
            "[$i] **$roleLabel**: ${m.content.take(500)}"
        }.joinToString("\n\n")

        val compressMsgs = listOf(
            OpenAiMessage("system", systemPrompt),
            OpenAiMessage("user", "请压缩以下对话历史：\n\n$historyText")
        )

        val resp = runCatching { AiApiClient.chat(ctx, providerCfg, compressMsgs) }.getOrNull()
        val summary = resp?.choices?.firstOrNull()?.message?.content?.trim().orEmpty()

        if (summary.isBlank()) return null

        // 执行压缩：移除旧消息，在开头插入摘要
        synchronized(messages) {
            val toRemove = messages.size - COMPRESS_KEEP_RECENT
            if (toRemove > 0) {
                repeat(toRemove) { messages.removeAt(0) }
                messages.add(0, ChatMessage(
                    role = "assistant",
                    content = "📎 历史会话自动压缩摘要：\\n\\n$summary\\n\\n（以上是之前的对话摘要，以下是最近的对话）"
                ))
            }
        }

        return CompressResult(summary = summary, keptRecent = COMPRESS_KEEP_RECENT)
    }

    /** 压缩结果数据类 */
    data class CompressResult(val summary: String, val keptRecent: Int)

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
        AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())

        runInScope {
            isLoading = true
            try {
                processAiTurn(ctx, "[用户回答] ${card.askQuestion}\n回答：$answer")
            } finally {
                isLoading = false
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
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

        // 解析命令并设置弹窗状态，确保在主页能显示二次确认对话框
        val params = runCatching {
            com.google.gson.JsonParser.parseString(pending.second.toString()).asJsonObject
        }.getOrNull()
        val command = params?.let {
            when (pending.first) {
                SkillType.RUN_COMMAND.name -> if (it.has("command")) it.get("command").asString else ""
                else -> card.dangerousAction ?: ""
            }
        } ?: (card.dangerousAction ?: "")

        val detection = RiskCommandDetector.detect(command)
        if (detection.isDangerous) {
            RiskConfirmManager._dialogState.value = RiskConfirmManager.DialogState(
                command = command,
                riskDescription = detection.description,
                riskType = detection.riskType?.displayName ?: "高危操作",
                environmentType = RiskConfirmManager.EnvironmentType.NATIVE,
                isWindowsDiskCommand = detection.isWindowsDiskCommand
            )
            RiskConfirmManager.startCountdown()
        }

        // 60 秒超时自动拒绝（走取消逻辑，恢复 Agent 会话）
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val prefs = ctx.getSharedPreferences(RiskConfirmManager.PREFS_NAME, Context.MODE_PRIVATE)
            val action = prefs.getString(RiskConfirmManager.KEY_AGENT_PENDING_ACTION, null)
            val result = prefs.getString(RiskConfirmManager.KEY_AGENT_PENDING_RESULT, null)
            if (action != null && result == null) {
                // 超时未处理，自动拒绝
                prefs.edit().putString(RiskConfirmManager.KEY_AGENT_PENDING_RESULT, RiskConfirmManager.RESULT_DENIED).apply()
                RiskConfirmManager._dialogState.value = null
                RiskConfirmManager.stopCountdown()
                // 导航回 AiTermuxActivity 处理拒绝结果
                val intent = Intent(ctx, AiTermuxActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            }
        }, 60000L)

        synchronized(messages) {
            messages[idx] = old.copy(
                skillCard = card.copy(status = SkillStatus.RUNNING, title = "等待二次确认…")
            )
        }
        AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())

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
        AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())

        runInScope {
            isLoading = true
            try {
                // 跳过风险确认：用户已在对话框中确认过
                RiskConfirmManager.setSkipRiskCheck(true)
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
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
                continueAfterSkill(ctx, resultCard, result.message)
            } finally {
                // 恢复风险确认标志
                RiskConfirmManager.setSkipRiskCheck(false)
                isLoading = false
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
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
        AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())

        runInScope {
            isLoading = true
            try {
                processAiTurn(ctx, "[用户在二次确认中拒绝了危险操作] ${card.dangerousAction ?: card.title}，用户选择不执行。")
            } finally {
                isLoading = false
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
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
        AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())

        runInScope {
            isLoading = true
            try {
                processAiTurn(ctx, "[用户取消了危险操作] ${card.dangerousAction ?: card.title}，用户选择不执行。")
            } finally {
                isLoading = false
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
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
        // [END_TURN] 容错计数：AI 连续多少轮未输出 [END_TURN] 才截断
        var missingEndTurnRounds = 0
        val maxMissingEndTurnRounds = 5
        // AI 文本回复历史：检测纯文本重复（AI 反复回答同样的问题）
        val recentAiReplies = mutableListOf<String>()
        val maxReplyHistory = 5
        var consecutiveSimilarReplies = 0
        val maxConsecutiveSimilarReplies = 3

        // 构建一次 System Prompt，后续迭代复用
        val baseSystemPrompt = AiTermuxPrefs.buildFullSystemPrompt(ctx)
        // 备用在线模式专用：不含训练教训记忆块
        val baseSystemPromptNoLearned = AiTermuxPrefs.buildFullSystemPrompt(ctx, includeLearnedMemory = false, maxChars = 0)
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

            // 根据用户选择决定使用本地还是在线模型（提前判断，用于选择是否包含教训）
            val forceLocal = config.providerConfig.provider == "local" && useLocalModel
            val forceOnline = config.providerConfig.provider == "local" && !useLocalModel

            // 决定使用的 System Prompt：重试时用精简版；在线模式排除训练教训
            val systemPrompt = when {
                hallucinationRetryCount > 0 -> retrySystemPrompt
                forceOnline -> baseSystemPromptNoLearned
                else -> baseSystemPrompt
            }
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

            val providerConfig: AiProviderConfig = if (config.providerConfig.provider == "local" && !useLocalModel) {
                val fb = AiTermuxPrefs.getFallbackOnlineConfig(ctx)
                AiProviderConfig(
                    provider = "custom",
                    apiKey = fb.apiKey,
                    apiBaseUrl = fb.baseUrl,
                    model = fb.model,
                    temperature = fb.temperature
                )
            } else {
                config.providerConfig
            }

            AiApiClient.chatStream(ctx, providerConfig, apiMsgs, { cancelled }, 
                forceLocal = forceLocal, forceOnline = forceOnline).collect { chunk ->
                when (chunk) {
                    is StreamChunk.Reasoning -> {
                        reasoningText += chunk.delta
                        synchronized(messages) {
                            val idx = messages.indexOfFirst { it.id == streamMsgId }
                            if (idx >= 0) {
                                messages[idx] = messages[idx].copy(
                                    reasoningContent = reasoningText,
                                    reasoningDone = false,
                                    preparingStatus = null
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
                                    reasoningDone = true,
                                    preparingStatus = null
                                )
                            }
                        }
                    }
                    is StreamChunk.Prepare -> {
                        synchronized(messages) {
                            val idx = messages.indexOfFirst { it.id == streamMsgId }
                            if (idx >= 0) {
                                val cur = messages[idx]
                                val newDetails = if (!chunk.detailLine.isNullOrBlank()) {
                                    cur.preparingDetails + chunk.detailLine!!.split("\\n").filter { it.isNotBlank() }
                                } else cur.preparingDetails
                                messages[idx] = cur.copy(
                                    preparingStatus = chunk.status,
                                    preparingDetails = newDetails
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
                                    content = SkillExecutor.stripSkillBlocks(displayText).ifBlank { displayText },
                                    preparingStatus = null
                                )
                            }
                        }
                    }
                    is StreamChunk.Done -> {
                        replyText = chunk.fullText
                        rawResponseText = chunk.rawResponse
                        android.util.Log.d("AiTermux", "Done received, contentLen=${chunk.fullText.length}, reasoningLen=${chunk.fullReasoning.length}, rawLen=${chunk.rawResponse.length}")
                        synchronized(messages) {
                            val idx = messages.indexOfFirst { it.id == streamMsgId }
                            if (idx >= 0) {
                                messages[idx] = messages[idx].copy(preparingStatus = null, rawResponse = chunk.rawResponse)
                            }
                        }
                    }
                    is StreamChunk.Error -> {
                        streamError = chunk.message
                        synchronized(messages) {
                            val idx = messages.indexOfFirst { it.id == streamMsgId }
                            if (idx >= 0) {
                                messages[idx] = messages[idx].copy(preparingStatus = null)
                            }
                        }
                    }
                    is StreamChunk.Cancelled -> {
                        wasCancelled = true
                    }
                }
            }
            isStreaming = false

// 流结束后，处理消息
            if (wasCancelled) {
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
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
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
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
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
                return
            }

            // 如果流式返回的是空内容（无思考也无回复），显示诊断信息而非静默删除
            if (replyText.isBlank()) {
                android.util.Log.e("AiTermux", "AI returned empty output with no content and no reasoning")
                synchronized(messages) {
                    val idx = messages.indexOfFirst { it.id == streamMsgId }
                    if (idx >= 0) {
                        messages[idx] = messages[idx].copy(
                            content = "⚠️ AI 未输出任何内容（可能是本地模型推理异常）",
                            errorMessage = "本地模型返回为空，可能原因：模型输出格式不匹配、进程启动失败、或模型文件异常"
                        )
                    }
                }
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
                return
            }

            // === 预解析技能块（用于交叉验证，防止正则遗漏导致的误判）===
            val preParsedSkills = SkillExecutor.parseSkillBlocks(replyText)
            val preParsedCount = preParsedSkills.size

            // === 假输出检测（传入预解析数量进行交叉验证）===
            val fakeCheck = SkillExecutor.detectFakeOutput(replyText, preParsedCount, ctx)

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
                    AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
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
                    AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())

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
            // 智能完成检测：不依赖 [END_TURN]，无技能调用即视为回复完毕
            val cleanedReply = replyText.replace("[END_TURN]", "").trimEnd()
            val plainText = SkillExecutor.stripSkillBlocks(cleanedReply)
            val skills = SkillExecutor.parseSkillBlocks(cleanedReply)
            val hasSkills = skills.isNotEmpty()


            // === 检测 AI 创造的新工具（new_tool 标签）===
            val newToolBlocks = SkillExecutor.parseNewToolBlocks(replyText)
            for (toolData in newToolBlocks) {
                try {
                    val savedSkill = AiTermuxPrefs.saveNewTool(ctx, toolData)
                    if (savedSkill != null) {
                        android.util.Log.i("AiTermux", "新技能已保存: ")
                        synchronized(messages) {
                            val idx2 = messages.indexOfFirst { it.id == streamMsgId }
                            if (idx2 >= 0) {
                                val currentContent = messages[idx2].content
                                messages[idx2] = messages[idx2].copy(
                                    content = currentContent + "\n\n🛠️ AI 创造了新技能: **** - ",
                                    isWarning = false
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AiTermux", "Failed to save new tool", e)
                }
            }

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
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
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
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
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
            AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())

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
                    AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
                    return
                }
            }

            // 检测是否有重复执行的技能（触犯禁令第四条）
            val hasDuplicateViolation = skippedSkills.isNotEmpty() && hallucinatedSkillKeys.isEmpty()
            // 构建警告消息，用于告知 AI 触犯了禁令
            val duplicateWarning = if (hasDuplicateViolation) {
                "[系统警告] 你违反了输出规范禁令第四条：禁止重复执行已执行过的技能。以下 ${skippedSkills.size} 个技能已跳过执行。请直接回答用户的问题，不要再重复执行已完成的操作。"
            } else null

            // === 智能终止逻辑 ===
            if (skillsToExecute.isEmpty()) {
                // 无新技能要执行 → 视为回复完成
                if (hasDuplicateViolation) {
                    currentUserText = duplicateWarning!!
                    continue
                }
                android.util.Log.d("AiTermux", "AI 回复完成（无技能），终止循环")
                return
            }
            // 有技能要执行，重置容错计数
            missingEndTurnRounds = 0

            missingEndTurnRounds = 0

            var needsUserInput = false
            var lastResultText: String? = null
            var executedSkillTypes = mutableListOf<SkillType>()

            for ((skillTypeStr, params) in skillsToExecute) {
                val st = runCatching { SkillType.valueOf(skillTypeStr) }.getOrNull()
                val dangerReason = if (st != null) SkillExecutor.checkDangerous(ctx, st, params) else null
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
                    AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
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
                            AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
                            break
                        }

                        lastResultText = buildSkillResultText(resultCard, result.message)
                    }
                }
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
                withContext(Dispatchers.IO) { kotlinx.coroutines.delay(150) }
            }

            if (needsUserInput) {
                return
            }

            // 如果所有执行的技能都是"需点击执行"类型，终止循环
            // AI 已经生成了卡片并告知用户点击，不应再继续生成更多卡片
            val unlimitedModeActive = AiTermuxPrefs.isUnlimitedModeActive(ctx)
            if (executedSkillTypes.isNotEmpty() &&
                executedSkillTypes.all { it.requiresClick(autoExecSkills, unlimitedModeActive) }) {
                return
            }

            // 执行完技能后，如果没有需要回传的结果则直接终止
            if (executedSkillTypes.isNotEmpty() &&
                executedSkillTypes.all { it.requiresClick(autoExecSkills, unlimitedModeActive) }) {
                return
            }

            val missingEndTurnWarning = ""  // 已移除 END_TURN 依赖，保留空字符串供兼容
            currentUserText = if (hasDuplicateViolation) {
                "\n\n"
            } else {
                ""
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

    // ---- 本地大模型状态 ----
    var downloadingModelId by remember { mutableStateOf<String?>(null) }
    var localProgress by remember { mutableStateOf(0f) }
    var localProgressMsg by remember { mutableStateOf("") }
    var localRefresh by remember { mutableStateOf(0) }
    var localLlamaReady by remember { mutableStateOf(AiLocalModel.isLlamaCppInstalled()) }
    
    // ---- 本地引擎状态 ----
    val settingsScope = rememberCoroutineScope()
    val localEngineType: MutableState<String> = remember { mutableStateOf(AiTermuxPrefs.getLocalEngineType(ctx)) }
    
    // ---- Ollama 状态 ----
    val ollamaInstalled = remember { mutableStateOf(AiOllamaManager.isOllamaInstalled()) }
    val ollamaRunning = remember { mutableStateOf(AiOllamaManager.isOllamaRunning()) }
    val ollamaInstalling = remember { mutableStateOf(false) }
    val ollamaProgress = remember { mutableStateOf(0f) }
    val ollamaProgressMsg = remember { mutableStateOf("") }
    val ollamaModelsList: MutableState<List<String>> = remember { mutableStateOf<List<String>>(AiTermuxPrefs.getInstalledOllamaModels(ctx)) }
    val selectedOllamaModel: MutableState<String> = remember { mutableStateOf(AiTermuxPrefs.getSelectedOllamaModel(ctx)) }
    val ollamaPulling = remember { mutableStateOf(false) }
    val ollamaPullProgress = remember { mutableStateOf(0f) }
    val ollamaPullMsg = remember { mutableStateOf("") }

    LaunchedEffect(Unit) { AiLocalModel.init(ctx) }
    LaunchedEffect(localRefresh) { localLlamaReady = AiLocalModel.isLlamaCppInstalled() }

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
                    ProviderChip("本地大模型", "local", provider, isDark) { provider = it }
                }
            }


            if (provider == "local") {
                item { SectionTitle("本地大模型（离线 · 设备端运行）") }

                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_warning),
                                    contentDescription = null,
                                    tint = if (isDark) Color(0xFFFFB300) else Color(0xFFF57C00),
                                    modifier = Modifier.size(20.dp).align(Alignment.CenterVertically)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "资源占用较高",
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color(0xFFFFB300) else Color(0xFFF57C00)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "本地大模型在设备端运行，会占用较多内存与电量，推理速度有限。建议在具备充足存储、内存（≥ 2GB 空闲）与散热的设备上使用，下载需数百 MB 至数十 GB 不等。",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }

                                // Llama.cpp 模型列表（仅当选择 llama 引擎时显示）
                if (localEngineType.value == "llama") {
                LOCAL_MODELS.forEach { entry ->
                    item {
                        val scope = rememberCoroutineScope()
                        val installed = remember(localRefresh) { AiLocalModel.isModelInstalled(entry) }
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(entry.displayName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                Text(entry.description, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                entry.warning?.let { warn ->
                                    Spacer(Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier.fillMaxWidth()
                                            .background(MiuixTheme.colorScheme.error.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = "⚠ " + warn,
                                            fontSize = 11.sp,
                                            color = MiuixTheme.colorScheme.error,
                                            lineHeight = 14.sp
                                        )
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                if (downloadingModelId == entry.id) {
                                    if (localProgress in 0f..1f) {
                                        LinearProgressIndicator(progress = localProgress, modifier = Modifier.fillMaxWidth())
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(localProgressMsg, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                } else if (installed) {
                                    Text(
                                        text = "已安装 · 已配置",
                                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                        color = MiuixTheme.colorScheme.primary
                                    )
                                } else {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                downloadingModelId = entry.id
                                                localProgress = 0f
                                                localProgressMsg = "正在准备下载…"
                                                val ok = AiLocalModel.downloadModel(entry) { p, msg ->
                                                    localProgress = p
                                                    localProgressMsg = msg
                                                }
                                                if (ok) {
                                                    localProgress = 1f
                                                    localProgressMsg = "模型下载完成，已自动配置"
                                                    // 关键：持久化选中的模型 ID，否则 getSelectedModel() 返回 null → isLocalModelReady()=false → 入口跳回设置页
                                                    AiLocalModel.setSelectedModelId(entry.id)
                                                    val cfg = AiTermuxConfig(
                                                        providerConfig = AiProviderConfig(
                                                            provider = "local", apiKey = "", apiBaseUrl = "",
                                                            model = entry.displayName, temperature = temperature,
                                                            localModelId = entry.id
                                                        ),
                                                        customSystemPrompt = customPrompt
                                                    )
                                                    vm.updateConfig(cfg)
                                                } else {
                                                    SnackbarHelper.show(ctx, "下载失败：" + localProgressMsg, Snackbar.LENGTH_LONG, null)
                                                }
                                                downloadingModelId = null
                                                localRefresh++
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                                    ) {
                                        Text(
                                            text = run {
                                                val sizeB = entry.sizeBytes
                                                val sizeStr = when {
                                                    sizeB >= 1024L * 1024L * 1024L -> "%.1f GB".format(sizeB.toFloat() / (1024L * 1024L * 1024L))
                                                    sizeB >= 1024L * 1024L -> "%.1f MB".format(sizeB.toFloat() / (1024L * 1024L))
                                                    else -> "${sizeB / 1024} KB"
                                                }
                                                "下载并配置（约 $sizeStr）"
                                            },
                                            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }

                // 本地推理引擎选择
                item { SectionTitle("本地推理引擎") }
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("选择本地推理引擎", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    text = "Llama.cpp",
                                    selected = localEngineType.value == "llama",
                                    onClick = {
                                        localEngineType.value = "llama"
                                        AiTermuxPrefs.setLocalEngineType(ctx, "llama")
                                    }
                                )
                                FilterChip(
                                    text = "Ollama",
                                    selected = localEngineType.value == "ollama",
                                    onClick = {
                                        localEngineType.value = "ollama"
                                        AiTermuxPrefs.setLocalEngineType(ctx, "ollama")
                                        // 切换到 Ollama 时，自动选择已安装的第一个模型
                                        // 如果没有已选择的模型，尝试从已安装列表中选择
                                        if (selectedOllamaModel.value.isBlank()) {
                                            val installedModels = AiTermuxPrefs.getInstalledOllamaModels(ctx)
                                            if (installedModels.isNotEmpty()) {
                                                selectedOllamaModel.value = installedModels.first()
                                                AiTermuxPrefs.setSelectedOllamaModel(ctx, installedModels.first())
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // Ollama 配置区域
                if (localEngineType.value == "ollama") {
                    item { SectionTitle("Ollama 模型") }
                    
                    // Ollama 安装状态
                    item {
                        // 使用顶层定义的 ollama 状态变量
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Ollama 状态", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                    Spacer(Modifier.width(8.dp))
                                    if (ollamaInstalled.value) {
                                        Text(
                                            text = if (ollamaRunning.value) "● 运行中" else "○ 已安装（未启动）",
                                            fontSize = 12.sp,
                                            color = if (ollamaRunning.value) Color(0xFF16A34A) else MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        )
                                    } else {
                                        Text(
                                            text = "○ 未安装",
                                            fontSize = 12.sp,
                                            color = Color(0xFFDC2626)
                                        )
                                    }
                                }
                                
                                if (ollamaInstalling.value) {
                                    Spacer(Modifier.height(10.dp))
                                    LinearProgressIndicator(progress = ollamaProgress.value, modifier = Modifier.fillMaxWidth())
                                    Spacer(Modifier.height(6.dp))
                                    Text(ollamaProgressMsg.value, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                } else if (!ollamaInstalled.value) {
                                    Spacer(Modifier.height(10.dp))
                                    Text(
                                        text = "Ollama 是一个轻量级本地大模型运行器，支持多种开源模型。点击下方按钮自动安装。",
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            settingsScope.launch {
                                                ollamaInstalling.value = true
                                                ollamaProgress.value = 0f
                                                ollamaProgressMsg.value = "正在准备 Ollama 安装…"
                                                val ok = AiOllamaManager.installOllama { p, msg ->
                                                    ollamaProgress.value = p
                                                    ollamaProgressMsg.value = msg
                                                }
                                                ollamaInstalling.value = false
                                                ollamaInstalled.value = AiOllamaManager.isOllamaInstalled()
                                                if (ok) {
                                                    SnackbarHelper.show(ctx, "Ollama 安装成功", Snackbar.LENGTH_SHORT, null)
                                                } else {
                                                    SnackbarHelper.show(ctx, "Ollama 安装失败", Snackbar.LENGTH_LONG, null)
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)
                                    ) {
                                        Text("安装 Ollama", color = Color.White, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Spacer(Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                settingsScope.launch {
                                                    if (ollamaRunning.value) {
                                                        AiOllamaManager.stopOllamaService()
                                                        ollamaRunning.value = false
                                                    } else {
                                                        val ok = AiOllamaManager.startOllamaService()
                                                        ollamaRunning.value = ok
                                                        if (ok) {
                                                            SnackbarHelper.show(ctx, "Ollama 服务已启动", Snackbar.LENGTH_SHORT, null)
                                                        }
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(if (ollamaRunning.value) "停止服务" else "启动服务")
                                        }
                                        Button(
                                            onClick = {
                                                settingsScope.launch {
                                                        val installedModels = AiOllamaManager.getInstalledModels()
                                                        AiTermuxPrefs.saveInstalledOllamaModels(ctx, installedModels)
    SnackbarHelper.show(ctx, "已刷新模型列表", Snackbar.LENGTH_SHORT, null)
                                                    }
                                            },
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("刷新列表")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Ollama 模型列表
                    if (!ollamaInstalled.value) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Ollama 尚未安装", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "Ollama 是一个轻量级本地大模型运行器。请先安装 Ollama，然后下载所需模型。",
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    Button(
                                        onClick = {
                                            settingsScope.launch {
                                                ollamaInstalling.value = true
                                                ollamaProgress.value = 0f
                                                ollamaProgressMsg.value = "正在准备 Ollama 安装…"
                                                val ok = AiOllamaManager.installOllama { p, msg ->
                                                    ollamaProgress.value = p
                                                    ollamaProgressMsg.value = msg
                                                }
                                                ollamaInstalling.value = false
                                                ollamaInstalled.value = AiOllamaManager.isOllamaInstalled()
                                                if (ok) {
                                                    SnackbarHelper.show(ctx, "Ollama 安装成功", Snackbar.LENGTH_SHORT, null)
                                                } else {
                                                    SnackbarHelper.show(ctx, "Ollama 安装失败", Snackbar.LENGTH_LONG, null)
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("安装 Ollama")
                                    }
                                }
                            }
                        }
                    }
                    
                    if (ollamaInstalled.value || true) {  // Always show models list
                        // 使用顶层定义的 ollama 状态变量
                        
                        OLLAMA_MODELS.forEach { ollamaEntry ->
                            item {
                                Card(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(ollamaEntry.displayName, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                                Text(ollamaEntry.description, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    text = ollamaEntry.sizeDescription,
                                                    fontSize = 11.sp,
                                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                                )
                                            }
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(
                                                        if (selectedOllamaModel.value == ollamaEntry.ollamaModelName) 
                                                            MiuixTheme.colorScheme.primary 
                                                        else MiuixTheme.colorScheme.surfaceVariant
                                                    )
                                                    .clickable {
                                                        selectedOllamaModel.value = ollamaEntry.ollamaModelName
                                                        AiTermuxPrefs.setSelectedOllamaModel(ctx, ollamaEntry.ollamaModelName)
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (selectedOllamaModel.value == ollamaEntry.ollamaModelName) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(12.dp)
                                                            .clip(CircleShape)
                                                            .background(Color.White)
                                                    )
                                                }
                                            }
                                        }
                                        
                                        val installedList = ollamaModelsList.value
                                        val isInstalled = installedList.contains(ollamaEntry.ollamaModelName)
                                        
                                        if (ollamaPulling.value && selectedOllamaModel.value == ollamaEntry.ollamaModelName) {
                                            Spacer(Modifier.height(10.dp))
                                            LinearProgressIndicator(progress = ollamaPullProgress.value, modifier = Modifier.fillMaxWidth())
                                            Spacer(Modifier.height(6.dp))
                                            Text(ollamaPullMsg.value, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        } else if (!isInstalled) {
                                            Spacer(Modifier.height(10.dp))
                                            Button(
                                                onClick = {
                                                    settingsScope.launch {
                                                        ollamaPulling.value = true
                                                        ollamaPullProgress.value = 0f
                                                        ollamaPullMsg.value = "正在下载 ${ollamaEntry.displayName}…"
                                                        val ok = AiOllamaManager.pullModel(ollamaEntry.ollamaModelName) { p, msg ->
                                                            ollamaPullProgress.value = p
                                                            ollamaPullMsg.value = msg
                                                        }
                                                        ollamaPulling.value = false
                                                        if (ok) {
                                                            SnackbarHelper.show(ctx, "${ollamaEntry.displayName} 下载完成", Snackbar.LENGTH_SHORT, null)
                                                            // Update installed models list
                                                            val updatedList = AiOllamaManager.getInstalledModels()
                                                            AiTermuxPrefs.saveInstalledOllamaModels(ctx, updatedList)
                                                        } else {
                                                            SnackbarHelper.show(ctx, "模型下载失败", Snackbar.LENGTH_LONG, null)
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("下载模型")
                                            }
                                        } else {
                                            Spacer(Modifier.height(10.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Text(
                                                    text = "✓ 已下载",
                                                    fontSize = 12.sp,
                                                    color = Color(0xFF16A34A),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Button(
                                                    onClick = {
                                                        settingsScope.launch {
                                                            val ok = AiOllamaManager.deleteModel(ollamaEntry.ollamaModelName)
                                                            if (ok) {
                                                                SnackbarHelper.show(ctx, "模型已删除", Snackbar.LENGTH_SHORT, null)
                                                                val updatedList = AiOllamaManager.getInstalledModels()
                                                                AiTermuxPrefs.saveInstalledOllamaModels(ctx, updatedList)
                                                            }
                                                        }
                                                    }
                                                ) {
                                                    Text("删除")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(8.dp)) }
            }

            if (provider != "local") {
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
                                    ctx,
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
                        enabled = provider != "local" && apiKey.isNotBlank() && !testing,
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
                            if (provider == "local") {
                                if (!AiLocalModel.isLocalModelReady()) {
                                    AiLocalModel.resetLocalModelConfigIfConfigured()
                                    testResult = "❌ 请先完成本地大模型的下载与配置"
                                    return@Button
                                }
                                val localCfg = AiTermuxConfig(
                                    providerConfig = AiProviderConfig(
                                        provider = "local", apiKey = "", apiBaseUrl = "",
                                        model = AiLocalModel.getSelectedModel()?.displayName ?: "本地模型",
                                        temperature = temperature,
                                        localModelId = AiLocalModel.getSelectedModelId()
                                    ),
                                    customSystemPrompt = customPrompt,
                                    isConfigured = true
                                )
                                vm.updateConfig(localCfg)
                                SnackbarHelper.show(ctx, "本地大模型已配置，进入对话界面", Snackbar.LENGTH_SHORT, null)
                                return@Button
                            }
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
                            SnackbarHelper.show(ctx, "配置已保存，进入对话界面", Snackbar.LENGTH_SHORT, null)
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

private fun formatByteCount(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
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

    // -------- 首次进入对话页：提示训练本地模型 --------
    var showFirstTrainHint by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val cfg = AiTermuxPrefs.getConfig(ctx)
        if (cfg.providerConfig.provider == "local" && !AiTermuxPrefs.isTrainHintShown(ctx)) {
            showFirstTrainHint = true
        }
    }
    top.yukonga.miuix.kmp.overlay.OverlayDialog(
        title = "让本地模型越用越懂 Termux 操作",
        summary = "检测到您现在使用的是本地模型。可前往设置页 → Termux Agent → 「训练本地模型」进行 System Prompt 蒸馏训练：\n\n• 如果配置了备用在线大模型，将由在线老师全自动出题、批改、评分，并自动把每轮的教训追加到 System Prompt 末尾。\n• 如果没有备用在线大模型，您可以手动打分并给出改进建议，系统还会提供启发式参考评分。",
        show = showFirstTrainHint,
        onDismissRequest = { showFirstTrainHint = false; AiTermuxPrefs.markTrainHintShown(ctx) },
        content = {
            androidx.compose.foundation.layout.Column {
                top.yukonga.miuix.kmp.basic.TextButton(
                    text = "现在去训练（推荐）",
                    onClick = {
                        showFirstTrainHint = false
                        AiTermuxPrefs.markTrainHintShown(ctx)
                        val it = android.content.Intent(ctx, com.termux.app.activities.AiLocalTrainerActivity::class.java)
                        ctx.startActivity(it)
                    },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                )
                androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.height(6.dp))
                top.yukonga.miuix.kmp.basic.TextButton(
                    text = "稍后再说",
                    onClick = { showFirstTrainHint = false; AiTermuxPrefs.markTrainHintShown(ctx) },
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                )
            }
        }
    )
    LaunchedEffect(vm.messages.size, vm.isLoading) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.size - 1)
    }

    // 启动本地推理服务（Ollama 或 Llama）
    LaunchedEffect(vm.config.providerConfig.provider) {
        if (vm.config.providerConfig.provider == "local") {
            val engineType = AiTermuxPrefs.getLocalEngineType(ctx)
            if (engineType == "ollama") {
                // 启动 Ollama 服务
                if (AiOllamaManager.isOllamaInstalled() && !AiOllamaManager.isOllamaRunning()) {
                    android.util.Log.i("AiChatScreen", "Starting Ollama service...")
                    AiOllamaManager.startOllamaService()
                }
            }
            // llama 引擎由 AiLocalModel 自动管理
        }
    }

    Box {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = "Termux Agent",
                    subtitle = run {
                        val isLocal = vm.useLocalModel
                        val cfg = vm.config.providerConfig
                        val modelName = if (isLocal) cfg.localModelId.ifBlank { "本地模型" } else cfg.model.ifBlank { "在线模型" }
                        val providerLabel = if (isLocal) "本地模型" else "在线模型"
                        "$providerLabel · $modelName"
                    },
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
                    // 模型切换开关（仅当本地模式且配置了备用在线模型时显示）
                    if (vm.shouldShowModelSwitch()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(top = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_terminal),
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = if (vm.useLocalModel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Text(
                                text = if (vm.useLocalModel) "本地模型" else "在线模型",
                                style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface),
                                modifier = Modifier.weight(1f)
                            )
                            Switch(
                                checked = !vm.useLocalModel,
                                onCheckedChange = { vm.updateLocalModelSelection(!it) }
                            )
                        }
                        HorizontalDivider(color = if (isDark) Color(0xFF2A2A2A) else Color(0xFFE8E8E8))
                    }
                    // 选中的附件标签
                    pendingAttachment?.let { (fileName, filePath, sizeB) ->
                        val sizeStr = when {
                            sizeB >= 1024 * 1024 -> "%.1f MB".format(sizeB.toFloat() / (1024 * 1024))
                            sizeB >= 1024 -> "%.1f KB".format(sizeB.toFloat() / 1024)
                            else -> "$sizeB B"
                        }
                        val attachCardBg = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)
                        val attachBorder = if (isDark) Color(0xFF2C2C2E) else Color(0xFFE8E8E8)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(top = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(attachCardBg)
                                .then(Modifier.border(0.5.dp, attachBorder, RoundedCornerShape(12.dp)))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_upload),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MiuixTheme.colorScheme.primary
                            )
                            Text(
                                text = fileName,
                                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = sizeStr,
                                style = TextStyle(fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            )
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable { pendingAttachment = null },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "移除附件",
                                    modifier = Modifier.size(14.dp),
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
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0xFF242424) else Color(0xFFFFFFFF))
                                .then(Modifier.border(0.5.dp, if (isDark) Color(0xFF3A3A3A) else Color(0xFFE8E8E8), CircleShape))
                                .clickable { filePickerLauncher.launch(arrayOf("*/*")) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_upload),
                                contentDescription = "上传文件/图片",
                                modifier = Modifier.size(20.dp),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                        TextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            label = "需要 Agent 做什么…",
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester),
                            useLabelAsPlaceholder = true,
                            singleLine = false,
                            maxLines = 4
                        )
                        val canSend = (inputText.isNotBlank() || pendingAttachment != null) && !vm.isLoading
                        val sendBtnBg = when {
                            vm.isStreaming -> Color(0xFFDC2626)
                            canSend -> MiuixTheme.colorScheme.primary
                            isDark -> Color(0xFF333333)
                            else -> Color(0xFFE0E0E0)
                        }
                        val sendBtnIconTint = when {
                            canSend || vm.isStreaming -> Color.White
                            else -> Color.Gray
                        }
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(sendBtnBg)
                                .clickable(enabled = canSend || vm.isStreaming) {
                                    if (vm.isStreaming) {
                                        vm.cancelGeneration()
                                    } else {
                                        val text = inputText.trim()
                                        if ((text.isNotBlank() || pendingAttachment != null)) {
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
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (vm.isLoading && !vm.isStreaming) {
                                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                            } else if (vm.isStreaming) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "停止生成",
                                    modifier = Modifier.size(20.dp),
                                    tint = Color.White
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.Send,
                                    contentDescription = "发送",
                                    tint = sendBtnIconTint,
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
        colors = listOf(Color(0xFF7C3AED), Color(0xFFEC4899))
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(gradient)
            .padding(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_auto_awesome),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color.White
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "你好，我是 Termux Agent",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "用自然语言管理你的终端 —— 执行命令、管理文件、连接 VNC/SSH、启动 QEMU 虚拟机。",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 12.5.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(13.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val examples = listOf("🔧 执行命令", "📦 安装软件包", "🖥️ VNC/SSH", "💻 QEMU 虚拟机")
            for (ex in examples) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.White.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        ex,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickChips(vm: AiTermuxViewModel, inputText: String, setInput: (String) -> Unit) {
    val suggestions = listOf(
        "查看当前目录",
        "安装 Python 包",
        "新建 SSH 会话",
        "启动 QEMU 虚拟机",
        "清理缓存文件"
    )
    val isDark = isSystemInDarkTheme()
    val chipBg = if (isDark) Color(0xFF242424) else Color(0xFFFFFFFF)
    val chipBorder = if (isDark) Color(0xFF3A3A3A) else Color(0xFFE8E8E8)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        suggestions.forEach { s ->
            Box(
                modifier = Modifier
                    .height(34.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(chipBg)
                    .then(Modifier.border(0.5.dp, chipBorder, RoundedCornerShape(999.dp)))
                    .clickable {
                        if (!vm.isLoading) vm.sendUserMessage(s)
                    }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 13.dp)) {
                    Text(
                        s,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface
                    )
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
            .clip(RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
            .background(if (isDark) Color(0xFF242424) else Color(0xFFF3F3F3))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        val dotColor = if (isDark) Color(0xFFAAA) else Color(0xFF888)
        repeat(3) { i ->
            var offsetY by remember { mutableStateOf(0f) }
            LaunchedEffect(i) {
                while (true) {
                    kotlinx.coroutines.delay((i * 150).toLong())
                    offsetY = -3f
                    kotlinx.coroutines.delay(300)
                    offsetY = 0f
                    kotlinx.coroutines.delay(600)
                }
            }
            Box(
                Modifier
                    .size(7.dp)
                    .graphicsLayer { translationY = offsetY }
                    .clip(CircleShape)
                    .background(dotColor)
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
        // 本地模型准备中卡片（样式类似深度思考；一旦有思考或回复就自动隐藏）
        if (!isUser && msg.preparingStatus != null) {
            PreparingBlock(status = msg.preparingStatus!!, details = msg.preparingDetails, isDark = isDark)
            Spacer(Modifier.height(6.dp))
        }
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
                RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp)
            } else {
                RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)
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
                                    SnackbarHelper.show(ctx, "已复制", Snackbar.LENGTH_SHORT, null)
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
        WindowDialog(
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
                                SnackbarHelper.show(ctx, "已复制到剪贴板", Snackbar.LENGTH_SHORT, null)
                            }
                        )
                    }
                }
            }
        )
    }
}

/** -------------------- 本地模型准备中卡片 -------------------- */

@Composable
private fun PreparingBlock(status: String, details: List<String>, isDark: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val bg = if (isDark) Color(0xFF1A1A2E) else Color(0xFFF0F0F8)
    val textColor = if (isDark) Color(0xFFB0B0C8) else Color(0xFF555570)
    val headerColor = if (isDark) Color(0xFF8888AA) else Color(0xFF7777A0)
    val accent = Color(0xFF6366F1)
    // 3 个圆点循环跳动指示器
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(350)
            tick = (tick + 1) % 3
        }
    }
    Column(
        modifier = Modifier
            .padding(end = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // ---------- 折叠态头部 ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0..2) {
                    val alpha = if (i == tick) 1f else 0.25f
                    val size = if (i == tick) 8.dp else 6.dp
                    Box(
                        modifier = Modifier
                            .size(size)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = alpha))
                    )
                }
            }
            Text(
                text = "正在准备本地调用",
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = headerColor
                )
            )
            Spacer(Modifier.weight(1f))
            if (expanded && details.isNotEmpty()) {
                Text(
                    text = "${details.size} 条日志",
                    style = TextStyle(fontSize = 11.sp, color = headerColor.copy(alpha = 0.75f))
                )
            } else if (!expanded) {
                Text(
                    text = "点击展开",
                    style = TextStyle(fontSize = 11.sp, color = headerColor.copy(alpha = 0.6f))
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp).then(
                    if (expanded) Modifier.graphicsLayer { rotationZ = 180f } else Modifier
                ),
                tint = headerColor
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = status,
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = textColor
            )
        )

        // ---------- 展开态：详细运行日志 ----------
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            // 浅色分隔线（用 Box 代替 HorizontalDivider，避免 API 差异）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(headerColor.copy(alpha = 0.2f))
            )
            Spacer(Modifier.height(8.dp))
            if (details.isEmpty()) {
                Text(
                    text = "运行信息收集中…通常 3 秒内会出现命令行和加载进度日志",
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = textColor.copy(alpha = 0.6f),
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isDark) Color(0xFF111122) else Color(0xFFE8E8F4))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        details.forEach { ln ->
                            Text(
                                text = ln,
                                style = TextStyle(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    fontSize = 10.5.sp,
                                    lineHeight = 15.sp,
                                    color = textColor.copy(alpha = 0.9f)
                                ),
                                softWrap = true
                            )
                        }
                    }
                }
                if (details.size > 8) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "↑ 可上下滑动查看更多日志",
                        style = TextStyle(
                            fontSize = 10.sp,
                            color = headerColor.copy(alpha = 0.6f)
                        )
                    )
                }
            }
        }
    }
}

/** -------------------- 深度思考内容（可折叠）-------------------- */

@Composable
private fun ReasoningBlock(reasoning: String, isDone: Boolean, isDark: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val bg = if (isDark) Color(0xFF1A1A2E) else Color(0xFFF0F0F8)
    val textColor = if (isDark) Color(0xFFB0B0C8) else Color(0xFF555570)
    val headerColor = if (isDark) Color(0xFF8888AA) else Color(0xFF7777A0)
    val accent = Color(0xFF6366F1)

    Column(
        modifier = Modifier
            .then(Modifier.padding(end = 40.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .then(
                Modifier.border(
                    0.5.dp,
                    if (isDark) Color(0xFF2C2C3E) else Color(0xFFE0E0EC),
                    RoundedCornerShape(14.dp)
                )
            )
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_auto_awesome),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = accent
            )
            Text(
                text = "深度思考",
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = headerColor
                )
            )
            Spacer(Modifier.weight(1f))
            if (!expanded) {
                Text(
                    text = if (isDone) "已完成" else "进行中",
                    style = TextStyle(fontSize = 11.sp, color = headerColor)
                )
            }
            Icon(
                imageVector = Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(16.dp).then(
                    if (expanded) Modifier.graphicsLayer { rotationZ = 180f } else Modifier
                ),
                tint = headerColor
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = reasoning.trim(),
                style = TextStyle(
                    fontSize = 12.5.sp,
                    lineHeight = 19.sp,
                    color = textColor
                )
            )
        }
    }
}

/** -------------------- 技能卡片 -------------------- */

@Composable
private fun SkillCard(msgId: String, card: SkillCardData, errorMsg: String?, isDark: Boolean, vm: AiTermuxViewModel) {
    // Sub Agent / Search Agent 使用可折叠大卡片
    if (card.skillType == SkillType.SUB_AGENT || card.skillType == SkillType.SEARCH_AGENT) {
        AgentSkillCard(msgId = msgId, card = card, errorMsg = errorMsg, isDark = isDark, vm = vm)
        return
    }

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
        SkillType.CUSTOM_COMMAND, SkillType.COMPILE_CODE -> R.drawable.ic_terminal
        SkillType.RUN_VM_QEMU, SkillType.CREATE_VM_QEMU,
        SkillType.VM_LIST -> R.drawable.ic_computer
        SkillType.CONNECT_VNC -> R.drawable.ic_vnc
        SkillType.CONNECT_SSH -> R.drawable.ic_ssh
        SkillType.LIST_REMOTE_CONNECTIONS, SkillType.CONNECT_REMOTE_CONNECTION -> R.drawable.ic_link
        SkillType.FILE_LIST, SkillType.FILE_READ,
        SkillType.FILE_WRITE, SkillType.FILE_DELETE,
        SkillType.FILE_GENERATE, SkillType.FILE_MODIFY -> R.drawable.ic_files
        SkillType.PACKAGE_INSTALL, SkillType.PACKAGE_UNINSTALL, SkillType.APP_INSTALL -> R.drawable.ic_download
        SkillType.APP_UNINSTALL -> R.drawable.ic_delete
        SkillType.ASK_USER -> R.drawable.ic_help
        SkillType.CONFIRM_DANGEROUS -> R.drawable.ic_warning
        SkillType.SCHEDULE_TASK -> R.drawable.ic_service_notification
        SkillType.GET_DEVICE_STATUS -> R.drawable.ic_info
        SkillType.CLIPBOARD_READ, SkillType.CLIPBOARD_WRITE -> R.drawable.ic_copy
        SkillType.SUB_AGENT -> R.drawable.ic_code
        SkillType.SEARCH_AGENT -> R.drawable.ic_search
        SkillType.WEB_SEARCH -> R.drawable.ic_web
    }

    val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFFAFAFA)
    val borderColor = if (isDark) Color(0xFF2C2C2C) else Color(0xFFE8E8E8)

    val clickable = card.skillType in setOf(
        SkillType.NEW_SESSION, SkillType.CLOSE_SESSION, SkillType.RUN_COMMAND,
        SkillType.CAPTURE_OUTPUT, SkillType.CONNECT_SSH, SkillType.CONNECT_VNC,
        SkillType.CONNECT_REMOTE_CONNECTION,
        SkillType.RUN_VM_QEMU, SkillType.CREATE_VM_QEMU,
        SkillType.VM_LIST, SkillType.CUSTOM_COMMAND, SkillType.EXIT_TERMUX,
        SkillType.APP_INSTALL, SkillType.APP_UNINSTALL,
        SkillType.PACKAGE_UNINSTALL, SkillType.WEB_SEARCH
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

/** -------------------- Agent 技能卡片（可折叠大卡片）-------------------- */

@Composable
private fun AgentSkillCard(msgId: String, card: SkillCardData, errorMsg: String?, isDark: Boolean, vm: AiTermuxViewModel) {
    var expanded by remember { mutableStateOf(false) }
    val isSubAgent = card.skillType == SkillType.SUB_AGENT
    val agentLabel = if (isSubAgent) "Sub Agent" else "Search Agent"
    val agentIcon = if (isSubAgent) R.drawable.ic_code else R.drawable.ic_search

    val (statusColor, statusBg, statusText) = when {
        card.status == SkillStatus.RUNNING -> Triple(Color(0xFF2563EB), Color(0xFF2563EB).copy(alpha = 0.12f), "执行中")
        card.status == SkillStatus.COMPLETED -> Triple(Color(0xFF16A34A), Color(0xFF16A34A).copy(alpha = 0.12f), "已完成")
        card.status == SkillStatus.FAILED -> Triple(Color(0xFFDC2626), Color(0xFFDC2626).copy(alpha = 0.12f), "失败")
        else -> Triple(Color(0xFF64748B), Color(0xFF64748B).copy(alpha = 0.12f), "未知")
    }

    val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFFAFAFA)
    val borderColor = if (isDark) Color(0xFF2C2C2C) else Color(0xFFE8E8E8)

    val outputLines = card.output?.lines() ?: emptyList()
    val lastTwoLines = if (outputLines.size >= 2) {
        outputLines.takeLast(2).joinToString("\n")
    } else {
        outputLines.joinToString("\n")
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cardBg)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header row
                Row(
                    modifier = Modifier
                        .padding(14.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(56.dp)
                            .height(56.dp)
                            .clip(RoundedCornerShape(28.dp))
                            .background(statusBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(agentIcon),
                            contentDescription = null,
                            modifier = Modifier.size(26.dp),
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
                                style = TextStyle(
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(statusBg)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = statusText,
                                    fontSize = 10.sp,
                                    color = statusColor,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "$agentLabel · ${card.description}",
                            style = TextStyle(
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isDark) Color(0xFF2A2A2A) else Color(0xFFF0F0F0))
                            .clickable { expanded = !expanded }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (expanded) "收起 ▲" else "展开 ▼",
                            style = TextStyle(
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        )
                    }
                }

                // Content section
                if (expanded) {
                    HorizontalDivider(color = borderColor)
                    Column(modifier = Modifier.padding(14.dp)) {
                        if (!card.output.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(text = "💭", fontSize = 13.sp)
                                Text(
                                    text = "执行过程",
                                    style = TextStyle(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isDark) Color(0xFF111) else Color(0xFF1E1E1E))
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = card.output.orEmpty(),
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        color = Color(0xFFD4D4D4),
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        lineHeight = 18.sp
                                    )
                                )
                            }
                        }

                        if (!errorMsg.isNullOrBlank() || card.status == SkillStatus.FAILED) {
                            val errText = errorMsg ?: card.description
                            if (errText.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    modifier = Modifier
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
                    }
                } else {
                    // Collapsed: show status and last 2 lines
                    HorizontalDivider(color = borderColor)
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (card.status == SkillStatus.RUNNING) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Text(
                                    text = "$agentLabel 正在执行…",
                                    style = TextStyle(fontSize = 12.sp, color = statusColor)
                                )
                            } else {
                                Icon(
                                    painter = painterResource(
                                        if (card.status == SkillStatus.COMPLETED) R.drawable.ic_info
                                        else R.drawable.ic_error
                                    ),
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = statusColor
                                )
                                Text(
                                    text = "$agentLabel ${if (card.status == SkillStatus.COMPLETED) "执行完成" else "执行失败"}",
                                    style = TextStyle(fontSize = 12.sp, color = statusColor)
                                )
                            }
                        }
                        if (lastTwoLines.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = lastTwoLines,
                                style = TextStyle(
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    lineHeight = 18.sp
                                ),
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
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
