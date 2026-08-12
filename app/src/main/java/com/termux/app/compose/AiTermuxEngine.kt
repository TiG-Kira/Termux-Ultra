package com.termux.app.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.termux.app.TermuxService
import com.termux.app.TermuxActivity
import com.termux.app.activities.QemuVmActivity
import com.gaurav.avnc.ui.vnc.VncActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** ---------- 技能执行器 ---------- */

data class SkillExecutionResult(
    val success: Boolean,
    val message: String,
    val skillCard: SkillCardData? = null
)

/** AI 回复假输出检测结果 */
data class FakeOutputCheck(
    val isFake: Boolean,       // 是否检测到假输出
    val violations: List<String> // 违反的禁令条目
)

object SkillExecutor {

    private const val TERMUX_ROOT = "/data/data/com.termux"
    private val HOME_DIR = "$TERMUX_ROOT/files/home"

    /** 危险操作检测：返回危险原因，不危险返回 null */
    fun checkDangerous(skillType: SkillType, params: JsonObject): String? {
        return when (skillType) {
            SkillType.FILE_DELETE -> {
                val path = if (params.has("path")) params.get("path").asString else ""
                when {
                    path.isBlank() -> null
                    path == "/" || path == TERMUX_ROOT -> "禁止删除根目录"
                    File(path).canonicalPath.let { it == TERMUX_ROOT || it == "/data/data" } ->
                        "禁止删除 Termux 根目录，这会导致整个应用数据丢失"
                    path.endsWith("/*") || path.endsWith("/.*") ->
                        "递归删除整个目录下的所有文件，可能导致数据丢失"
                    else -> null
                }
            }
            SkillType.RUN_COMMAND -> {
                val cmd = if (params.has("command")) params.get("command").asString else ""
                val trimmed = cmd.trim()
                when {
                    trimmed.isBlank() -> null
                    Regex("""\brm\s+(-[a-zA-Z]*r[a-zA-Z]*\s+|--recursive\s+).*(\.\s*$|/\s*$|\*|\\${'$'}HOME|\\${'$'}PREFIX)""")
                        .containsMatchIn(trimmed) ->
                        "递归删除命令可能导致大量数据丢失"
                    trimmed.startsWith("rm -rf /") || trimmed.startsWith("rm -rf /*") ->
                        "禁止执行 rm -rf / ，会破坏整个系统"
                    trimmed.contains("dd if=") && trimmed.contains("of=") ->
                        "dd 直接写入磁盘可能破坏数据"
                    trimmed.contains(":(){ :|:& };:") || trimmed.contains("fork bomb") ->
                        "fork bomb 会耗尽系统资源"
                    trimmed.contains("mkfs") ->
                        "格式化文件系统会导致数据丢失"
                    else -> null
                }
            }
            SkillType.CLOSE_ALL_SESSIONS -> "将关闭所有正在运行的终端会话，未保存的内容会丢失"
            SkillType.EXIT_TERMUX -> "将退出 Termux 应用，所有运行中的进程会终止"
            else -> null
        }
    }

    /** 从 AI 回复内容中解析技能块（支持多种代码块标记或直接 JSON） */
    fun parseSkillBlocks(content: String): List<Pair<String, JsonObject>> {
        val results = mutableListOf<Pair<String, JsonObject>>()
        val seen = mutableSetOf<String>()

        // 策略 1: 匹配 ```skill / ```json / ```javascript 等代码块包裹的 JSON
        // 宽容的正则：允许 closing ``` 前没有换行符
        val blockPattern = Regex(
            """```[a-zA-Z]*\s*\r?\n([\s\S]*?)[\r\n]*```""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in blockPattern.findAll(content)) {
            val jsonStr = match.groupValues.getOrNull(1)?.trim() ?: continue
            try {
                val json = JsonParser.parseString(jsonStr).asJsonObject
                val skillType = json.get("skillType")?.asString
                if (skillType != null && skillType.isNotBlank()) {
                    val params = json.getAsJsonObject("params") ?: JsonObject()
                    val key = "$skillType:${params}"
                    if (key !in seen) {
                        seen.add(key)
                        results.add(skillType to params)
                    }
                }
            } catch (_: Exception) { }
        }

        // 策略 2: 直接在文本中查找包含 skillType 的 JSON 对象（兜底，与策略1互补）
        if (results.isEmpty()) {
            val jsonPattern = Regex("""\{[^{}]*"skillType"[^{}]*\}""", RegexOption.DOT_MATCHES_ALL)
            for (match in jsonPattern.findAll(content)) {
                try {
                    val jsonStr = match.value.trim()
                    val json = JsonParser.parseString(jsonStr).asJsonObject
                    val skillType = json.get("skillType")?.asString
                    if (skillType != null && skillType.isNotBlank()) {
                        val params = json.getAsJsonObject("params") ?: JsonObject()
                        results.add(skillType to params)
                    }
                } catch (_: Exception) { }
            }
        }

        return results
    }

    private fun isValidSkillType(type: String): Boolean {
        return try {
            SkillType.valueOf(type)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 提取技能块以外的纯文本回复，过滤 AI 编造的伪造结果 */
    fun stripSkillBlocks(content: String): String {
        var result = content

        // 1. 移除所有包含 skillType 的代码块（使用宽容的正则）
        val blockPattern = Regex(
            """```[a-zA-Z]*\s*\r?\n([\s\S]*?)[\r\n]*```""",
            RegexOption.DOT_MATCHES_ALL
        )
        result = blockPattern.replace(result) { match ->
            val jsonStr = match.groupValues.getOrNull(1)?.trim() ?: return@replace match.value
            try {
                val json = JsonParser.parseString(jsonStr).asJsonObject
                val skillType = json.get("skillType")?.asString
                if (skillType != null && skillType.isNotBlank()) "" else match.value
            } catch (_: Exception) {
                match.value
            }
        }

        // 2. 移除直接内联的 JSON 技能对象
        val jsonPattern = Regex("""\{[^{}]*"skillType"[^{}]*\}""", RegexOption.DOT_MATCHES_ALL)
        result = jsonPattern.replace(result, "").trim()

        // 3. 过滤 AI 编造的伪造结果段落
        //    删除以 "技能执行结果"、"技能名称:"、"操作:"、"状态:" 开头的伪造结果块
        val fakeResultPattern = Regex(
            """[\r\n]*[*_#\s]*技能执行结果[*_#\s]*[\r\n]+[\s\S]*?(?=[\r\n]{2,}|$)""",
            RegexOption.IGNORE_CASE
        )
        result = fakeResultPattern.replace(result, "")

        // 4. 过滤以 "技能名称:"、"操作:"、"状态:" 等开头的连续伪造行
        val fakeLinePattern = Regex(
            """^[*_#\s]*(技能名称|操作(?:说明)?|状态(?:说明)?|详细信息)[:：].*$""",
            RegexOption.MULTILINE
        )
        result = fakeLinePattern.replace(result, "")

        // 5. 过滤 AI 虚构的 "---" 分隔线（在伪造结果上下文中）
        val separatorPattern = Regex("""^[*_#\s]*-{3,}[*_#\s]*$""", RegexOption.MULTILINE)
        result = separatorPattern.replace(result, "")

        // 6. 清理残留的空行
        result = result.replace(Regex("""\n{3,}"""), "\n\n")

        return result.trim()
    }

    /**
     * 检测 AI 回复中的假输出（幻觉）。
     * 基于 System Prompt 中的绝对禁令，检测以下模式：
     * - AI 声称操作已执行/已完成
     * - AI 编造了执行结果/输出文本
     * - AI 假装看到了文件列表/进程信息等
     * - AI 在技能卡片后添加了伪造的结果段落
     *
     * @param content AI 回复的完整文本
     * @param preParsedSkillCount 预先解析到的技能块数量（用于交叉验证，避免正则遗漏导致误判）
     */
    fun detectFakeOutput(content: String, preParsedSkillCount: Int = -1): FakeOutputCheck {
        val violations = mutableListOf<String>()
        val lower = content.lowercase()

        // ---------- 统一的技能块检测（与 parseSkillBlocks 保持一致）----------

        // 策略 1: 匹配 ```skill / ```json 等代码块包裹的 JSON（更宽容的正则）
        // 允许 AI 输出中 closing ``` 前没有换行符的情况
        val blockRegex = Regex(
            """```[a-zA-Z]*\s*\r?\n([\s\S]*?)[\r\n]*```""",
            RegexOption.DOT_MATCHES_ALL
        )
        val blockMatches = blockRegex.findAll(content).toList()

        // 策略 2: 直接在文本中查找包含 skillType 的 JSON 对象（兜底）
        val inlineRegex = Regex("""\{[^{}]*"skillType"[^{}]*\}""", RegexOption.DOT_MATCHES_ALL)
        val inlineMatches = inlineRegex.findAll(content).toList()

        // 合并去重的技能块范围
        data class BlockRange(val first: Int, val last: Int)
        val allRanges = mutableListOf<BlockRange>()

        for (m in blockMatches) {
            val jsonStr = m.groupValues.getOrNull(1)?.trim() ?: continue
            try {
                val json = JsonParser.parseString(jsonStr).asJsonObject
                val skillType = json.get("skillType")?.asString
                if (skillType != null && skillType.isNotBlank()) {
                    allRanges.add(BlockRange(m.range.first, m.range.last))
                }
            } catch (_: Exception) { }
        }
        for (m in inlineMatches) {
            try {
                val json = JsonParser.parseString(m.value).asJsonObject
                val skillType = json.get("skillType")?.asString
                if (skillType != null && skillType.isNotBlank()) {
                    // 内联 JSON 的范围就是它自身
                    allRanges.add(BlockRange(m.range.first, m.range.last))
                }
            } catch (_: Exception) { }
        }

        val hasSkillBlocks = allRanges.isNotEmpty() || preParsedSkillCount > 0

        // 如果预先解析到技能块但本函数正则没匹配到，以预先解析为准
        val effectiveHasSkills = if (preParsedSkillCount >= 0 && preParsedSkillCount > 0) true else hasSkillBlocks

        // ---------- 禁令 1-3：检查技能卡片之后是否有伪造结果 ----------
        if (hasSkillBlocks && allRanges.isNotEmpty()) {
            // 获取最后一个技能块的结束位置
            val lastBlockEnd = allRanges.maxOf { it.last }
            val afterBlock = content.substring(lastBlockEnd.coerceAtMost(content.length - 1)).trim()

            if (afterBlock.isNotBlank()) {
                // 禁令 1：伪造的执行结果描述
                val fakeResultPatterns = listOf(
                    "执行结果", "已执行", "已完成", "执行成功", "执行完毕",
                    "技能结果", "skill result",
                    "输出", "output", "详细信息",
                    "操作说明", "结果说明",
                    "正在读取", "正在检查", "正在扫描",
                    """共 \d+ 个""", "\\d+ 个会话", "\\d+ 个文件"
                )
                for (pattern in fakeResultPatterns) {
                    if (afterBlock.contains(pattern, ignoreCase = true)) {
                        violations.add("【禁令1】技能卡片后存在伪造的执行结果描述（包含「$pattern」）")
                        break
                    }
                }

                // 禁令 2：伪造的系统/文件信息
                val fakeInfoPatterns = listOf(
                    "/proc/", "/data/data/com.termux", "qemu-system",
                    "进程列表", "内存使用", "磁盘空间",
                    "uptime", "load average", "cpu usage"
                )
                for (pattern in fakeInfoPatterns) {
                    if (afterBlock.contains(pattern, ignoreCase = true)) {
                        violations.add("【禁令2】技能卡片后存在伪造的系统/文件信息（包含「$pattern」）")
                        break
                    }
                }

                // 禁令 3：伪造的分隔线 + 结果段落
                if (Regex("""-{3,}""").containsMatchIn(afterBlock) &&
                    (afterBlock.contains("技能") || afterBlock.contains("结果") || afterBlock.contains("输出"))) {
                    violations.add("【禁令3】技能卡片后存在伪造的分隔线和结果段落")
                }
            }
        }

        // ---------- 禁令 4/6/7/8：仅在确认无技能块时才检查 ----------
        // 使用 effectiveHasSkills 进行交叉验证：如果预解析有技能，即使正则没匹配到也跳过这些检查
        if (!effectiveHasSkills) {
            val noSkillViolations = mutableListOf<String>()

            // 禁令 4：假装执行了操作但无技能卡片
            val fakeActionPatterns = listOf(
                "已执行", "已完成", "已发送", "已创建", "已连接", "已安装",
                "列出了", "读取了", "写入了", "删除了",
                "执行结果", "操作成功", "操作完成"
            )
            for (pattern in fakeActionPatterns) {
                if (lower.contains(pattern)) {
                    noSkillViolations.add("【禁令4】回复声称操作「$pattern」但未输出任何技能卡片")
                    break
                }
            }

            // 禁令 6：自信式幻觉
            val confidentHollowPatterns = listOf(
                "不存在", "没有这个", "无法直接执行", "无法为你执行",
                "不支持", "不允许", "权限不足", "无法访问",
                "当前环境中没有", "该功能尚未", "暂时不支持",
                "没有权限", "不在允许的路径", "禁用的文件系统",
                "我目前没有", "我没有这个功能", "我无法执行",
            )
            for (pattern in confidentHollowPatterns) {
                if (lower.contains(pattern)) {
                    noSkillViolations.add("【禁令6】自信式幻觉：声称「$pattern」但未提供任何技能或真实依据")
                    break
                }
            }

            // 禁令 7：捏造技能
            val fakeSkillPatterns = listOf(
                "技能卡片", "智能日程", "天气查询", "外卖下单",
                "快递查询", "语音助手", "翻译技能", "搜索技能",
            )
            for (pattern in fakeSkillPatterns) {
                if (lower.contains(pattern) && lower.contains("已")) {
                    noSkillViolations.add("【禁令7】捏造不存在的技能：「$pattern」")
                    break
                }
            }

            // 禁令 8：提供解决方案式幻觉
            val solutionPattern = Regex("(解决方案|解决方法|解决办法)[\\s\\S]*?(请在|您可以|建议你|建议您)", RegexOption.IGNORE_CASE)
            if (solutionPattern.containsMatchIn(content) && content.length > 200) {
                noSkillViolations.add("【禁令8】逃避执行：不使用技能执行操作，反而提供用户自行操作的\"解决方案\"")
            }

            violations.addAll(noSkillViolations)
        }

        // ---------- 禁令 5：虚构的命令/系统输出 ----------
        // 即使有技能块，也要检查技能块之外是否有伪造输出
        val fakeOutputPatterns = listOf(
            """\[D\]\s+\S+""",
            """\[F\]\s+\S+""",
            """total\s+\d+""",
            """^\s*\d+\s+\S+\s+\S+""",
            """PID\s+USER\s+COMMAND""",
            """/proc/\d+/status""",
            """MemTotal|MemFree|MemAvailable""",
            """[<>]\s*[0-9a-fA-F]+\s*bytes""",
            """\d+\s+imported""",
            """\d+\s+not upgraded""",
            """Setting up\s+""",
            """Processing triggers""",
        )
        for (pattern in fakeOutputPatterns) {
            if (Regex(pattern, RegexOption.MULTILINE).containsMatchIn(content)) {
                // 如果有技能块，检查伪造输出是否在技能块范围内
                if (hasSkillBlocks && allRanges.isNotEmpty()) {
                    val firstBlockStart = allRanges.minOf { it.first }
                    val lastBlockEnd = allRanges.maxOf { it.last }
                    val insideBlocks = content.substring(
                        firstBlockStart.coerceAtLeast(0),
                        lastBlockEnd.coerceAtMost(content.length - 1)
                    ).contains(pattern)
                    if (!insideBlocks) {
                        violations.add("【禁令5】回复包含虚构的命令/系统输出")
                        break
                    }
                } else {
                    violations.add("【禁令5】回复包含虚构的命令/系统输出")
                    break
                }
            }
        }

        return FakeOutputCheck(violations.isNotEmpty(), violations)
    }

    /** 执行单个技能 */
    suspend fun executeSkill(
        context: Context,
        termuxService: TermuxService?,
        skillTypeStr: String,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        val skillType = try { SkillType.valueOf(skillTypeStr) } catch (_: Exception) { null }

        // ASK_USER 是交互类技能，直接生成卡片返回
        if (skillType == SkillType.ASK_USER) {
            return@withContext execAskUser(params)
        }

        // 未知技能类型但包含 command —— 兜底为 RUN_COMMAND 执行
        if (skillType == null) {
            val hasCommand = params.has("command")
            if (hasCommand) {
                android.util.Log.w("AiTermux", "未知技能类型 '$skillTypeStr'，兜底为 RUN_COMMAND 执行")
                return@withContext execRunCommand(context, termuxService, params)
            }
            return@withContext SkillExecutionResult(false, "未知的技能类型: $skillTypeStr（无 command 参数可兜底执行）")
        }

        when (skillType) {
            SkillType.NEW_SESSION -> execNewSession(context, termuxService, params)
            SkillType.CLOSE_SESSION -> execCloseSession(context, termuxService, params)
            SkillType.CLOSE_ALL_SESSIONS -> execCloseAllSessions(context, termuxService)
            SkillType.EXIT_TERMUX -> execExitTermux(context)
            SkillType.GET_SESSION_INFO -> execGetSessionInfo(context, termuxService)
            SkillType.RUN_VM_QEMU -> execRunVmQemu(context, params)
            SkillType.CREATE_VM_QEMU -> execCreateVmQemu(context, params)
            SkillType.VM_LIST -> execVmList(context, termuxService, params)
            SkillType.CONNECT_VNC -> execConnectVnc(context, params)
            SkillType.CONNECT_SSH -> execConnectSsh(context, termuxService, params)
            SkillType.FILE_LIST -> execFileList(params)
            SkillType.FILE_READ -> execFileRead(params)
            SkillType.FILE_WRITE -> execFileWrite(params)
            SkillType.FILE_DELETE -> execFileDelete(params)
            SkillType.RUN_COMMAND -> execRunCommand(context, termuxService, params)
            SkillType.CAPTURE_OUTPUT -> execCaptureOutput(context, termuxService, params)
            SkillType.PACKAGE_INSTALL -> execPackageInstall(context, termuxService, params)
            SkillType.CONFIRM_DANGEROUS -> SkillExecutionResult(false, "危险操作需在 UI 中确认后执行")
            SkillType.CUSTOM_COMMAND -> {
                when {
                    params.has("activityClass") -> execOpenActivity(context, params)
                    params.has("action") -> execSendBroadcast(context, params)
                    params.has("command") -> execRunCommand(context, termuxService, params)
                    else -> execRunCommand(context, termuxService, params)
                }
            }
        }
    }

    // ---- 交互类技能 ----

    private fun execAskUser(params: JsonObject): SkillExecutionResult {
        val question = if (params.has("question")) params.get("question").asString else ""
        val type = if (params.has("type")) params.get("type").asString else "text"
        val placeholder = if (params.has("placeholder")) params.get("placeholder").asString else null
        val options = if (params.has("options")) {
            try {
                val arr = params.getAsJsonArray("options")
                (0 until arr.size()).map { arr[it].asString }
            } catch (_: Exception) { emptyList() }
        } else emptyList()

        val typeTitle = when (type) {
            "single" -> "单选"
            "multi" -> "多选"
            else -> "请输入"
        }
        return SkillExecutionResult(
            true, "向用户提问：$question",
            SkillCardData(
                skillType = SkillType.ASK_USER,
                title = "AI 需要你的回答（$typeTitle）",
                description = question,
                status = SkillStatus.RUNNING,
                askQuestion = question,
                askType = type,
                askOptions = options.ifEmpty { null },
                askPlaceholder = placeholder
            )
        )
    }

    // ---- 会话管理（全部需要 Main 线程 —— TermuxService/TerminalSession 内部使用 Handler）----

    private suspend fun execNewSession(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.Main.immediate) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        val name = if (params.has("name")) params.get("name").asString else null
        return@withContext try {
            val session = termuxService.createTermuxSession(
                null, null, null, null, false, name
            )
            if (session != null) {
                val ts = session.getTerminalSession()
                val handle = ts.mHandle.toString()
                val displayName = ts.mSessionName ?: "Terminal"
                SkillExecutionResult(
                    true, "已生成终端会话卡片",
                    SkillCardData(
                        skillType = SkillType.NEW_SESSION,
                        title = "已新建终端会话",
                        description = "会话名称: $displayName（点击卡片以初始化终端）",
                        status = SkillStatus.COMPLETED,
                        sessionId = handle,
                        sessionName = displayName
                    )
                )
            } else {
                SkillExecutionResult(false, "创建会话失败：返回值为空")
            }
        } catch (e: Exception) {
            SkillExecutionResult(false, "创建会话出错: ${e.message}",
                skillCard = SkillCardData(
                    skillType = SkillType.NEW_SESSION,
                    title = "新建终端失败",
                    description = e.message ?: "未知错误",
                    status = SkillStatus.FAILED
                )
            )
        }
    }

    private suspend fun execCloseSession(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.Main.immediate) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        val sessionId = if (params.has("sessionId")) params.get("sessionId").asString else ""
        if (sessionId.isBlank()) return@withContext SkillExecutionResult(false, "未指定 sessionId")
        return@withContext try {
            val sessions = termuxService.getTermuxSessions()
            val target = sessions.find {
                val ts = it.getTerminalSession()
                ts.mHandle.toString() == sessionId || ts.mSessionName == sessionId
            }
            if (target != null) {
                val ts = target.getTerminalSession()
                val displayName = ts.mSessionName ?: "Terminal"
                termuxService.removeTermuxSession(ts)
                SkillExecutionResult(
                    true, "已关闭会话 $displayName",
                    SkillCardData(
                        skillType = SkillType.CLOSE_SESSION,
                        title = "已关闭会话",
                        description = "会话: $displayName",
                        status = SkillStatus.COMPLETED,
                        sessionName = displayName
                    )
                )
            } else {
                SkillExecutionResult(false, "未找到会话: $sessionId")
            }
        } catch (e: Exception) {
            SkillExecutionResult(false, "关闭会话出错: ${e.message}")
        }
    }

    private suspend fun execCloseAllSessions(
        context: Context,
        termuxService: TermuxService?
    ): SkillExecutionResult = withContext(Dispatchers.Main.immediate) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        return@withContext try {
            val sessions = termuxService.getTermuxSessions().toList()
            sessions.forEach { termuxService.removeTermuxSession(it.getTerminalSession()) }
            SkillExecutionResult(
                true, "已关闭全部 ${sessions.size} 个会话",
                SkillCardData(
                    skillType = SkillType.CLOSE_ALL_SESSIONS,
                    title = "已关闭全部会话",
                    description = "共 ${sessions.size} 个会话被关闭",
                    status = SkillStatus.COMPLETED
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "关闭全部会话出错: ${e.message}")
        }
    }

    private suspend fun execExitTermux(context: Context): SkillExecutionResult = withContext(Dispatchers.Main.immediate) {
        return@withContext try {
            val activity = context as? Activity
            if (activity != null) {
                activity.finishAffinity()
            } else {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(homeIntent)
            }
            SkillExecutionResult(
                true, "已退出 Termux",
                SkillCardData(
                    skillType = SkillType.EXIT_TERMUX,
                    title = "已退出 Termux",
                    description = "应用已退出",
                    status = SkillStatus.COMPLETED
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "退出 Termux 出错: ${e.message}")
        }
    }

    private suspend fun execGetSessionInfo(
        context: Context,
        termuxService: TermuxService?
    ): SkillExecutionResult = withContext(Dispatchers.Main.immediate) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        return@withContext try {
            val sessions = termuxService.getTermuxSessions()
            val info = sessions.joinToString("\n") {
                val ts = it.getTerminalSession()
                "- ${ts.mSessionName ?: "Terminal"} [handle=${ts.mHandle}] 运行中=${ts.isRunning}"
            }
            val desc = if (sessions.isEmpty()) "当前无运行会话" else "共 ${sessions.size} 个会话"
            SkillExecutionResult(
                true, info.ifBlank { "无会话" },
                SkillCardData(
                    skillType = SkillType.GET_SESSION_INFO,
                    title = "会话信息",
                    description = desc,
                    status = SkillStatus.COMPLETED,
                    output = info.ifBlank { "当前没有运行中的会话" }
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "获取会话信息出错: ${e.message}")
        }
    }

    // ---- 虚拟机管理（startActivity 切到 Main 更稳妥）----

    private suspend fun execRunVmQemu(context: Context, params: JsonObject): SkillExecutionResult = withContext(Dispatchers.Main.immediate) {
        val vmName = if (params.has("vmName")) params.get("vmName").asString else ""
        return@withContext try {
            val intent = Intent(context, QemuVmActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (vmName.isNotBlank()) intent.putExtra("vmName", vmName)
            context.startActivity(intent)
            SkillExecutionResult(
                true, "已打开 QEMU 虚拟机管理页",
                SkillCardData(
                    skillType = SkillType.RUN_VM_QEMU,
                    title = "打开 QEMU 虚拟机",
                    description = if (vmName.isNotBlank()) "虚拟机：$vmName" else "虚拟机管理页",
                    status = SkillStatus.COMPLETED,
                    vmName = vmName.ifBlank { null }
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "打开 QEMU 虚拟机失败: ${e.message}")
        }
    }

    private suspend fun execCreateVmQemu(context: Context, params: JsonObject): SkillExecutionResult = withContext(Dispatchers.Main.immediate) {
        return@withContext try {
            val intent = Intent(context, QemuVmActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra("createNew", true)
            if (params.has("vmName")) intent.putExtra("vmName", params.get("vmName").asString)
            if (params.has("memoryMB")) intent.putExtra("memoryMB", params.get("memoryMB").asInt)
            if (params.has("diskGB")) intent.putExtra("diskGB", params.get("diskGB").asInt)
            if (params.has("cpuCores")) intent.putExtra("cpuCores", params.get("cpuCores").asInt)
            context.startActivity(intent)
            SkillExecutionResult(
                true, "已打开新建 QEMU 虚拟机配置页",
                SkillCardData(
                    skillType = SkillType.CREATE_VM_QEMU,
                    title = "新建 QEMU 虚拟机",
                    description = "正在配置新建虚拟机...",
                    status = SkillStatus.COMPLETED,
                    vmName = if (params.has("vmName")) params.get("vmName").asString else null
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "新建 QEMU 虚拟机失败: ${e.message}")
        }
    }

    // ---- 虚拟机管理 ----

    private suspend fun execVmList(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        val command = if (params.has("command")) params.get("command").asString else ""
        val description = if (params.has("description")) params.get("description").asString else "列出虚拟机"
        if (command.isBlank()) {
            return@withContext SkillExecutionResult(false, "VM_LIST 需要 command 参数")
        }
        val cmdParams = JsonObject().apply {
            addProperty("command", command)
            if (params.has("sessionName")) addProperty("sessionName", params.get("sessionName").asString)
            else addProperty("sessionName", "VM-List")
        }
        val result = execRunCommand(context, termuxService, cmdParams)
        if (result.success) {
            result.copy(
                skillCard = result.skillCard?.copy(
                    skillType = SkillType.VM_LIST,
                    title = description
                ) ?: SkillCardData(
                    skillType = SkillType.VM_LIST,
                    title = description,
                    description = command,
                    status = SkillStatus.COMPLETED
                )
            )
        } else {
            result
        }
    }

    // ---- 命令执行（带输出捕获） ----

    private suspend fun execCaptureOutput(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        val command = if (params.has("command")) params.get("command").asString else ""
        if (command.isBlank()) return@withContext SkillExecutionResult(false, "命令为空")
        val timeoutSeconds = if (params.has("timeout")) params.get("timeout").asInt else 10
        val description = if (params.has("description")) params.get("description").asString else command

        // 安全检查
        val danger = Regex("""rm\s+-[a-zA-Z]*r[a-zA-Z]*\s+/\b|mkfs|dd\s+if=.*/dev/block|:\(\)\{ :\|:\& \};:""")
        if (danger.containsMatchIn(command)) {
            return@withContext SkillExecutionResult(false, "检测到危险命令，已拒绝执行")
        }

        return@withContext try {
            val outputFile = "$TERMUX_ROOT/files/home/.ai_capture_${System.currentTimeMillis()}.txt"
            // 包装命令：执行 + 写入输出文件 + 标记完成
            val wrappedCmd = """
                $command > '$outputFile' 2>&1
                echo '__AI_COMMAND_DONE__' >> '$outputFile'
            """.trimIndent()

            // 在新会话中执行（切到主线程，因为 createTermuxSession 内部 Handler 依赖主线程 Looper）
            val sessionName = "capture-${command.take(20).replace(Regex("[^a-zA-Z0-9]"), "_")}"
            val session = withContext(Dispatchers.Main.immediate) {
                termuxService.createTermuxSession(
                    null, arrayOf("-c", wrappedCmd), null, null, false, sessionName
                )
            }

            if (session == null) {
                return@withContext SkillExecutionResult(false, "创建会话失败")
            }

            val ts = session.getTerminalSession()

            // 等待命令完成（轮询检查标记文件 + 超时）
            var output = ""
            var completed = false
            val startTime = System.currentTimeMillis()
            val timeoutMs = timeoutSeconds * 1000L
            val pollInterval = 200L

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                delay(pollInterval)
                try {
                    val file = File(outputFile)
                    if (file.exists()) {
                        val content = file.readText()
                        if (content.contains("__AI_COMMAND_DONE__")) {
                            output = content.removeSuffix("__AI_COMMAND_DONE__").trim()
                            completed = true
                            break
                        }
                    }
                } catch (_: Exception) { }
            }

            // 超时后如果还没完成，尝试读取已有输出
            if (!completed) {
                try {
                    val file = File(outputFile)
                    if (file.exists()) {
                        output = file.readText().removeSuffix("__AI_COMMAND_DONE__").trim()
                    }
                } catch (_: Exception) { }
                if (output.isBlank()) output = "(命令执行超时或无输出，命令可能仍在运行中)"
            }

            // 清理：异步删除临时文件（不阻塞）
            runCatching { File(outputFile).delete() }

            val truncatedOutput = if (output.length > 20000) output.take(20000) + "\n...(输出已截断，超过20000字符)" else output

            SkillExecutionResult(
                success = completed || output.isNotBlank(),
                message = if (completed) "命令已完成，已捕获输出" else "命令可能仍在运行，已捕获部分输出",
                skillCard = SkillCardData(
                    skillType = SkillType.CAPTURE_OUTPUT,
                    title = description,
                    description = command,
                    status = SkillStatus.COMPLETED,
                    sessionId = ts.mHandle.toString(),
                    sessionName = ts.mSessionName,
                    command = command,
                    output = truncatedOutput
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "执行出错: ${e.message}")
        }
    }

    // ---- 远程连接 ----

    private suspend fun execConnectVnc(context: Context, params: JsonObject): SkillExecutionResult = withContext(Dispatchers.Main.immediate) {
        val address = if (params.has("address")) params.get("address").asString else ""
        if (address.isBlank()) return@withContext SkillExecutionResult(false, "未指定 VNC 地址")
        val password = if (params.has("password")) params.get("password").asString else ""
        return@withContext try {
            val intent = Intent(context, VncActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val parts = address.split(":")
            val host = parts.getOrNull(0) ?: address
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 5900
            intent.putExtra("host", host)
            intent.putExtra("port", port)
            if (password.isNotBlank()) intent.putExtra("password", password)
            context.startActivity(intent)
            SkillExecutionResult(
                true, "已生成 VNC 连接卡片",
                SkillCardData(
                    skillType = SkillType.CONNECT_VNC,
                    title = "VNC 连接",
                    description = "地址: $address（点击卡片以连接）",
                    status = SkillStatus.COMPLETED,
                    connectionAddress = address
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "VNC 连接失败: ${e.message}")
        }
    }

    private suspend fun execConnectSsh(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.Main.immediate) {
        val host = if (params.has("host")) params.get("host").asString else ""
        if (host.isBlank()) return@withContext SkillExecutionResult(false, "未指定 SSH 主机")
        val port = if (params.has("port")) params.get("port").asInt else 22
        val username = if (params.has("username")) params.get("username").asString else "root"
        val password = if (params.has("password")) params.get("password").asString else ""
        return@withContext try {
            if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
            val sshCmd = buildString {
                append("ssh ")
                if (port != 22) append("-p $port ")
                append("$username@$host")
                if (password.isNotBlank()) {
                    insert(0, "sshpass -p '${password.replace("'", "'\\''")}' ")
                }
            }
            val session = termuxService.createTermuxSession(
                null, arrayOf("-c", sshCmd), null, null, false, "SSH-$host"
            )
            if (session != null) {
                val ts = session.getTerminalSession()
                SkillExecutionResult(
                    true, "已生成 SSH 连接卡片",
                    SkillCardData(
                        skillType = SkillType.CONNECT_SSH,
                        title = "SSH 连接",
                        description = "$username@$host:$port（点击卡片以连接）",
                        status = SkillStatus.COMPLETED,
                        sessionId = ts.mHandle.toString(),
                        sessionName = ts.mSessionName ?: "SSH-$host",
                        connectionAddress = "$host:$port"
                    )
                )
            } else {
                SkillExecutionResult(false, "创建 SSH 会话失败")
            }
        } catch (e: Exception) {
            SkillExecutionResult(false, "SSH 连接失败: ${e.message}")
        }
    }

    // ---- 文件操作 ----

    private fun resolvePath(raw: String?): String {
        if (raw.isNullOrBlank()) return HOME_DIR
        var path = raw.replace("~", HOME_DIR)
        if (!path.startsWith("/")) path = "$HOME_DIR/$path"
        val canonical = File(path).canonicalPath
        // 限制在 /data/data/com.termux/ 范围内
        return if (canonical.startsWith(TERMUX_ROOT)) canonical else HOME_DIR
    }

    private suspend fun execFileList(params: JsonObject): SkillExecutionResult {
        val pathParam = if (params.has("path")) params.get("path").asString else ""
        val path = resolvePath(pathParam)
        return try {
            val dir = File(path)
            if (!dir.exists()) return SkillExecutionResult(false, "目录不存在: $path")
            if (!dir.isDirectory) return SkillExecutionResult(false, "不是目录: $path")
            val entries = dir.listFiles()?.map { f ->
                val type = if (f.isDirectory) "[D]" else if (f.isFile) "[F]" else "[?]"
                val size = if (f.isFile) humanSize(f.length()) else ""
                "$type ${f.name}  $size"
            }?.sorted() ?: emptyList()
            val output = entries.joinToString("\n").ifBlank { "(空目录)" }
            SkillExecutionResult(
                true, "列出目录 $path",
                SkillCardData(
                    skillType = SkillType.FILE_LIST,
                    title = "目录列表",
                    description = path,
                    status = SkillStatus.COMPLETED,
                    filePath = path,
                    output = output
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "列出目录出错: ${e.message}",
                SkillCardData(
                    skillType = SkillType.FILE_LIST,
                    title = "目录列表失败",
                    description = "$path\n错误: ${e.message}",
                    status = SkillStatus.FAILED,
                    filePath = path
                )
            )
        }
    }

    private suspend fun execFileRead(params: JsonObject): SkillExecutionResult {
        val pathParam = if (params.has("path")) params.get("path").asString else ""
        if (pathParam.isBlank()) return SkillExecutionResult(false, "未指定文件路径")
        val path = resolvePath(pathParam)
        return try {
            val file = File(path)
            if (!file.exists()) return SkillExecutionResult(false, "文件不存在: $path")
            if (!file.isFile) return SkillExecutionResult(false, "不是文件: $path")
            if (file.length() > 1024 * 1024) {
                return SkillExecutionResult(false, "文件过大（>1MB），请使用终端命令查看")
            }
            val content = file.readText()
            SkillExecutionResult(
                true, "已读取文件",
                SkillCardData(
                    skillType = SkillType.FILE_READ,
                    title = "读取文件",
                    description = path,
                    status = SkillStatus.COMPLETED,
                    filePath = path,
                    output = content
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "读取文件出错: ${e.message}")
        }
    }

    private suspend fun execFileWrite(params: JsonObject): SkillExecutionResult {
        val pathParam = if (params.has("path")) params.get("path").asString else ""
        if (pathParam.isBlank()) return SkillExecutionResult(false, "未指定文件路径")
        val content = if (params.has("content")) params.get("content").asString else ""
        val append = if (params.has("append")) params.get("append").asBoolean else false
        val path = resolvePath(pathParam)
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            if (append) file.appendText(content) else file.writeText(content)
            SkillExecutionResult(
                true, if (append) "已追加写入文件" else "已写入文件",
                SkillCardData(
                    skillType = SkillType.FILE_WRITE,
                    title = if (append) "追加写入文件" else "写入文件",
                    description = "$path (${content.length} 字符)",
                    status = SkillStatus.COMPLETED,
                    filePath = path
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "写入文件出错: ${e.message}")
        }
    }

    private suspend fun execFileDelete(params: JsonObject): SkillExecutionResult {
        val pathParam = if (params.has("path")) params.get("path").asString else ""
        if (pathParam.isBlank()) return SkillExecutionResult(false, "未指定文件路径")
        val path = resolvePath(pathParam)
        val canonical = File(path).canonicalPath
        if (canonical == TERMUX_ROOT) return SkillExecutionResult(false, "禁止删除 Termux 根目录！")
        if (canonical == HOME_DIR) return SkillExecutionResult(false, "禁止删除家目录！")
        return try {
            val file = File(path)
            if (!file.exists()) return SkillExecutionResult(false, "文件不存在: $path")
            val ok = file.deleteRecursively()
            if (ok) {
                SkillExecutionResult(
                    true, "已删除 $path",
                    SkillCardData(
                        skillType = SkillType.FILE_DELETE,
                        title = "已删除文件",
                        description = path,
                        status = SkillStatus.COMPLETED,
                        filePath = path
                    )
                )
            } else {
                SkillExecutionResult(false, "删除失败: $path")
            }
        } catch (e: Exception) {
            SkillExecutionResult(false, "删除文件出错: ${e.message}")
        }
    }

    // ---- 命令与包（切到 Main 线程 —— 涉及 TerminalSession.write / createTermuxSession，内部用 Handler）----

    private suspend fun execRunCommand(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.Main.immediate) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        val command = if (params.has("command")) params.get("command").asString else ""
        if (command.isBlank()) return@withContext SkillExecutionResult(false, "命令为空")
        val sessionId = if (params.has("sessionId")) params.get("sessionId").asString else ""
        val sessionName = if (params.has("sessionName")) params.get("sessionName").asString else null

        val danger = Regex("""rm\s+-[a-zA-Z]*r[a-zA-Z]*\s+/\b|mkfs|dd\s+if=.*/dev/block|:\(\)\{ :\|:\& \};:""")
        if (danger.containsMatchIn(command)) {
            return@withContext SkillExecutionResult(false, "检测到危险命令，已拒绝执行。请手动在终端中确认后运行。")
        }

        return@withContext try {
            // 优先用 sessionId 或 sessionName 查找已有会话
            val lookUpKey = sessionId.ifBlank { sessionName ?: "" }
            if (lookUpKey.isNotBlank()) {
                val sessions = termuxService.getTermuxSessions()
                val target = sessions.find {
                    val ts = it.getTerminalSession()
                    ts.mHandle.toString() == lookUpKey || ts.mSessionName == lookUpKey
                }
                if (target != null) {
                    val ts = target.getTerminalSession()
                    // 确保会话在前台可见
                    val i = Intent(context, TermuxActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    i.putExtra("sessionHandle", ts.mHandle)
                    context.startActivity(i)
                    if (!ts.isRunning) delay(1500)
                    // 写入命令（追加换行）
                    ts.write(command + "\n")
                    SkillExecutionResult(
                        true, "已生成命令卡片",
                        SkillCardData(
                            skillType = SkillType.RUN_COMMAND,
                            title = "执行命令",
                            description = command,
                            status = SkillStatus.COMPLETED,
                            sessionId = ts.mHandle.toString(),
                            sessionName = ts.mSessionName,
                            command = command
                        )
                    )
                } else {
                    SkillExecutionResult(false, "未找到指定会话: $lookUpKey")
                }
            } else {
                val newSession = termuxService.createTermuxSession(
                    null, arrayOf("-c", command), null, null, false, sessionName
                )
                if (newSession != null) {
                    val ts = newSession.getTerminalSession()
                    SkillExecutionResult(
                        true, "已生成命令卡片",
                        SkillCardData(
                            skillType = SkillType.RUN_COMMAND,
                            title = "新会话执行命令",
                            description = command,
                            status = SkillStatus.COMPLETED,
                            sessionId = ts.mHandle.toString(),
                            sessionName = ts.mSessionName ?: "Terminal",
                            command = command
                        )
                    )
                } else {
                    SkillExecutionResult(false, "创建会话失败")
                }
            }
        } catch (e: Exception) {
            SkillExecutionResult(false, "执行命令出错: ${e.message}")
        }
    }

    private suspend fun execPackageInstall(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult {
        if (termuxService == null) return SkillExecutionResult(false, "Termux 服务未连接")
        val pkgs = try {
            val arr = params.getAsJsonArray("packages")
            (0 until arr.size()).map { arr[it].asString }
        } catch (_: Exception) { emptyList() }
        if (pkgs.isEmpty()) return SkillExecutionResult(false, "未指定安装包")
        val useContainer = if (params.has("useContainer")) params.get("useContainer").asBoolean else false
        val command = if (useContainer) {
            val runInContainer = "$HOME_DIR/run_in_container.sh"
            "apt-get update && apt-get install -y ${pkgs.joinToString(" ")}"
                .let { if (File(runInContainer).exists()) "bash $runInContainer \"$it\"" else "pkg install -y ${pkgs.joinToString(" ")}" }
        } else {
            "pkg update && pkg install -y ${pkgs.joinToString(" ")}"
        }
        val wrappedParams = JsonObject().apply {
            addProperty("command", command)
            addProperty("sessionName", "安装-${pkgs.first()}")
        }
        return execRunCommand(context, termuxService, wrappedParams)
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1fK".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1fM".format(mb)
        return "%.1fG".format(mb / 1024.0)
    }

    /** 点击技能卡片时跳转（会话卡片跳转到对应会话等） */
    fun onSkillCardClick(context: Context, card: SkillCardData) {
        when (card.skillType) {
            SkillType.NEW_SESSION,
            SkillType.CLOSE_SESSION,
            SkillType.RUN_COMMAND,
            SkillType.CAPTURE_OUTPUT,
            SkillType.CUSTOM_COMMAND,
            SkillType.VM_LIST,
            SkillType.CONNECT_SSH -> {
                card.sessionId?.let { handle ->
                    try {
                        val intent = Intent(context, TermuxActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.putExtra("sessionHandle", handle.toLongOrNull() ?: 0L)
                        context.startActivity(intent)
                    } catch (_: Exception) { }
                }
            }
            SkillType.CONNECT_VNC -> {
                card.connectionAddress?.let { addr ->
                    try {
                        val intent = Intent(context, VncActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val parts = addr.split(":")
                        intent.putExtra("host", parts.getOrNull(0) ?: addr)
                        intent.putExtra("port", parts.getOrNull(1)?.toIntOrNull() ?: 5900)
                        context.startActivity(intent)
                    } catch (_: Exception) { }
                }
            }
            SkillType.RUN_VM_QEMU,
            SkillType.CREATE_VM_QEMU -> {
                val intent = Intent(context, QemuVmActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                card.vmName?.let { intent.putExtra("vmName", it) }
                if (card.skillType == SkillType.CREATE_VM_QEMU) intent.putExtra("createNew", true)
                context.startActivity(intent)
            }
            SkillType.EXIT_TERMUX -> {
                val activity = context as? Activity
                if (activity != null) {
                    activity.finishAffinity()
                } else {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(homeIntent)
                }
            }
            else -> { }
        }
    }

    // ---- 自定义技能实现 ----

    private suspend fun execOpenActivity(context: Context, params: JsonObject): SkillExecutionResult =
        withContext(Dispatchers.Main.immediate) {
            return@withContext try {
                val activityClass = if (params.has("activityClass")) params.get("activityClass").asString else ""
                if (activityClass.isBlank()) {
                    return@withContext SkillExecutionResult(false, "activityClass 参数为空")
                }
                val extras = if (params.has("extras")) {
                    runCatching { params.getAsJsonObject("extras") }.getOrNull()
                } else null

                val intent = Intent()
                intent.setClassName(context, activityClass)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                extras?.entrySet()?.forEach { (key, value) ->
                    when {
                        value.isJsonPrimitive && value.asJsonPrimitive.isString ->
                            intent.putExtra(key, value.asString)
                        value.isJsonPrimitive && value.asJsonPrimitive.isNumber ->
                            intent.putExtra(key, value.asLong)
                        value.isJsonPrimitive && value.asJsonPrimitive.isBoolean ->
                            intent.putExtra(key, value.asBoolean)
                    }
                }
                context.startActivity(intent)
                SkillExecutionResult(
                    true, "已打开页面: $activityClass",
                    SkillCardData(
                        skillType = SkillType.CUSTOM_COMMAND,
                        title = "打开页面",
                        description = activityClass,
                        status = SkillStatus.COMPLETED
                    )
                )
            } catch (e: Exception) {
                SkillExecutionResult(false, "打开页面出错: ${e.message}")
            }
        }

    private suspend fun execSendBroadcast(context: Context, params: JsonObject): SkillExecutionResult =
        withContext(Dispatchers.Main.immediate) {
            return@withContext try {
                val action = if (params.has("action")) params.get("action").asString else ""
                if (action.isBlank()) {
                    return@withContext SkillExecutionResult(false, "action 参数为空")
                }
                val extras = if (params.has("extras")) {
                    runCatching { params.getAsJsonObject("extras") }.getOrNull()
                } else null

                val intent = Intent(action)
                extras?.entrySet()?.forEach { (key, value) ->
                    when {
                        value.isJsonPrimitive && value.asJsonPrimitive.isString ->
                            intent.putExtra(key, value.asString)
                        value.isJsonPrimitive && value.asJsonPrimitive.isNumber ->
                            intent.putExtra(key, value.asLong)
                        value.isJsonPrimitive && value.asJsonPrimitive.isBoolean ->
                            intent.putExtra(key, value.asBoolean)
                    }
                }
                context.sendBroadcast(intent)
                SkillExecutionResult(
                    true, "已发送广播: $action",
                    SkillCardData(
                        skillType = SkillType.CUSTOM_COMMAND,
                        title = "发送广播",
                        description = action,
                        status = SkillStatus.COMPLETED
                    )
                )
            } catch (e: Exception) {
                SkillExecutionResult(false, "发送广播出错: ${e.message}")
            }
        }
}

/** ---------- AI API 调用器 ---------- */

object AiApiClient {

    suspend fun chat(
        config: AiProviderConfig,
        messages: List<OpenAiMessage>
    ): ChatCompletionResponse = withContext(Dispatchers.IO) {
        try {
            val baseUrl = config.apiBaseUrl.trimEnd('/')
            val url = URL("$baseUrl/chat/completions")
            val bodyObj = ChatCompletionRequest(
                model = config.model,
                messages = messages,
                temperature = config.temperature
            )
            val body = Gson().toJson(bodyObj)

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 60_000
                readTimeout = 120_000
                setRequestProperty("Content-Type", "application/json")
                if (config.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                }
                doOutput = true
                doInput = true
            }

            DataOutputStream(conn.outputStream).use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = stream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
            try {
                Gson().fromJson(respText, ChatCompletionResponse::class.java)
                    ?: ChatCompletionResponse(error = ChatCompletionResponse.ApiError("空响应"))
            } catch (parseE: Exception) {
                ChatCompletionResponse(
                    error = ChatCompletionResponse.ApiError(
                        "解析响应失败($code): ${respText.take(200)}"
                    )
                )
            }
        } catch (e: Exception) {
            ChatCompletionResponse(
                error = ChatCompletionResponse.ApiError("请求失败: ${e.message ?: "未知错误"}")
            )
        }
    }

    /**
     * 流式调用 AI，通过 Flow 逐块返回内容。
     * 支持 OpenAI 兼容的 SSE 格式（data: {...}）。
     * 发送方在外部通过 isCancelled 检查是否停止。
     */
    fun chatStream(
        config: AiProviderConfig,
        messages: List<OpenAiMessage>,
        isCancelled: () -> Boolean
    ): Flow<StreamChunk> = flow {
        try {
            val baseUrl = config.apiBaseUrl.trimEnd('/')
            val url = URL("$baseUrl/chat/completions")
            val bodyObj = ChatCompletionRequest(
                model = config.model,
                messages = messages,
                temperature = config.temperature,
                stream = true
            )
            val body = Gson().toJson(bodyObj)

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 60_000
                readTimeout = 120_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                if (config.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                }
                doOutput = true
                doInput = true
            }

            DataOutputStream(conn.outputStream).use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errorText = conn.errorStream?.bufferedReader()?.use(BufferedReader::readText) ?: ""
                emit(StreamChunk.Error("HTTP $code: ${errorText.take(200)}"))
                return@flow
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            var line: String?
            var fullContent = StringBuilder()
            var fullReasoning = StringBuilder()
            val rawResponse = StringBuilder()  // 收集原始 SSE 数据
            var hasContent = false
            var doneReceived = false
            var inThinkBlock = false
            var thinkTagBuffer = StringBuilder()
            var hasSentReasoningDone = false  // 是否已发送 ReasoningDone 事件

            while (true) {
                if (isCancelled()) {
                    conn.disconnect()
                    emit(StreamChunk.Cancelled)
                    break
                }
                line = reader.readLine()
                if (line == null) {
                    // 连接关闭
                    android.util.Log.d("AiTermux", "Connection closed: contentLen=${fullContent.length}, reasoningLen=${fullReasoning.length}")
                    emit(StreamChunk.Done(
                        fullContent.toString(),
                        fullReasoning.toString(),
                        rawResponse.toString()
                    ))
                    break
                }
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (!trimmed.startsWith("data:")) continue
                val dataStr = trimmed.removePrefix("data:").trim()

                // 收集原始 SSE 数据
                rawResponse.append(line).append("\n")

                if (dataStr == "[DONE]") {
                    android.util.Log.d("AiTermux", "[DONE] -> Done (hasContent=$hasContent, reasoningLen=${fullReasoning.length})")
                    // 如果有思考内容但未发送 ReasoningDone，先发送
                    if (fullReasoning.isNotEmpty() && !hasSentReasoningDone) {
                        emit(StreamChunk.ReasoningDone)
                        hasSentReasoningDone = true
                    }
                    emit(StreamChunk.Done(
                        fullContent.toString(),
                        fullReasoning.toString(),
                        rawResponse.toString()
                    ))
                    break
                }

                try {
                    val json = JsonParser.parseString(dataStr).asJsonObject
                    val choices = json.getAsJsonArray("choices")
                    if (choices != null && choices.size() > 0) {
                        val delta = choices.get(0).asJsonObject.getAsJsonObject("delta")

                        // 优先处理 reasoning_content（如果 API 支持）
                        // 注意：JSON null 需要特殊处理
                        val reasoningElement = delta?.get("reasoning_content")
                        val reasoningContent = if (reasoningElement != null && !reasoningElement.isJsonNull) {
                            reasoningElement.asString?.takeIf { it.isNotBlank() }
                        } else null

                        if (reasoningContent != null) {
                            fullReasoning.append(reasoningContent)
                            emit(StreamChunk.Reasoning(reasoningContent))
                        }

                        // 处理 content 字段
                        val content = delta?.get("content")?.asString
                        if (content != null && content.isNotBlank()) {
                            android.util.Log.d("AiTermux", "content delta (len=${content.length}): ${content.take(80)}${if (content.length > 80) "..." else ""}")

                            if (reasoningContent != null) {
                                // API 已提供 reasoning_content，直接使用 content
                                // 当开始接收实际内容时，标记思考完成
                                if (fullReasoning.isNotEmpty() && !hasSentReasoningDone) {
                                    hasSentReasoningDone = true
                                    emit(StreamChunk.ReasoningDone)
                                }
                                fullContent.append(content)
                                hasContent = true
                                emit(StreamChunk.Content(content))
                            } else {
                                // 从 content 中解析 <think> 标签（如果有的话）
                                val processed = processContentDelta(content, inThinkBlock, thinkTagBuffer)
                                inThinkBlock = processed.inThink
                                if (processed.reasoning.isNotEmpty()) {
                                    fullReasoning.append(processed.reasoning)
                                    emit(StreamChunk.Reasoning(processed.reasoning))
                                }
                                if (processed.reasoningDone) {
                                    hasSentReasoningDone = true
                                    emit(StreamChunk.ReasoningDone)
                                }
                                if (processed.content.isNotEmpty()) {
                                    fullContent.append(processed.content)
                                    hasContent = true
                                    emit(StreamChunk.Content(processed.content))
                                }
                            }
                        }

                        // 检查 finish_reason
                        val finishReason = choices.get(0).asJsonObject.get("finish_reason")?.asString
                        if (finishReason != null && finishReason != "null") {
                            android.util.Log.d("AiTermux", "finish_reason=$finishReason, hasContent=$hasContent")
                            // 如果有思考内容但未发送 ReasoningDone，先发送
                            if (fullReasoning.isNotEmpty() && !hasSentReasoningDone) {
                                emit(StreamChunk.ReasoningDone)
                                hasSentReasoningDone = true
                            }
                            emit(StreamChunk.Done(
                                fullContent.toString(),
                                fullReasoning.toString(),
                                rawResponse.toString()
                            ))
                            break
                        }
                    }
                } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            emit(StreamChunk.Error("请求失败: ${e.message ?: "未知错误"}"))
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * 处理 content 字段中的 <think> 标签。
 * 模型可能把思考内容放在 content 字段里，用 <think> 和 </think> 标签包装。
 * 此函数将内容分离为思考部分和实际回复部分。
 */
private data class ProcessedContent(
    val reasoning: String = "",
    val content: String = "",
    val reasoningDone: Boolean = false,
    val inThink: Boolean = false
)

private fun processContentDelta(
    delta: String,
    inThink: Boolean,
    tagBuffer: StringBuilder
): ProcessedContent {
    var reasoning = StringBuilder()
    var content = StringBuilder()
    var currentInThink = inThink
    var reasoningDone = false
    var remaining = delta

    // 如果之前有未完成的标签（如 "<thi" 被截断），先拼接
    if (tagBuffer.isNotEmpty()) {
        remaining = tagBuffer.toString() + remaining
        tagBuffer.clear()
    }

    while (remaining.isNotEmpty()) {
        if (currentInThink) {
            // 正在思考区域，查找 </think> 结束标签
            val endIdx = remaining.indexOf("</think>")
            if (endIdx >= 0) {
                // 找到结束标签
                val thinkPart = remaining.substring(0, endIdx)
                if (thinkPart.isNotEmpty()) {
                    reasoning.append(thinkPart)
                }
                currentInThink = false
                reasoningDone = true
                remaining = remaining.substring(endIdx + 7) // 跳过 </think>
                // 跳过标签后的空白
                remaining = remaining.trimStart()
                if (remaining.isEmpty()) break
            } else {
                // 检查是否是被截断的 </think> 标签（以 "<" 开头且与 "</think" 前缀匹配）
                if (remaining.startsWith("<") && "</think".startsWith(remaining)) {
                    // 可能是被截断的 </think> 标签，保存到缓冲区
                    tagBuffer.append(remaining)
                    remaining = ""
                } else {
                    reasoning.append(remaining)
                    remaining = ""
                }
            }
        } else {
            // 不在思考区域，查找 <think> 开始标签
            val startIdx = remaining.indexOf("<think>")
            if (startIdx >= 0) {
                // 找到开始标签
                if (startIdx > 0) {
                    content.append(remaining.substring(0, startIdx))
                }
                currentInThink = true
                remaining = remaining.substring(startIdx + 7) // 跳过 <think>
                remaining = remaining.trimStart()
                if (remaining.isEmpty()) break
            } else {
                // 检查是否是被截断的 <think> 标签（以 "<" 开头且与 "<think" 前缀匹配）
                if (remaining.startsWith("<") && "<think".startsWith(remaining)) {
                    // 可能是被截断的 <think> 标签，保存到缓冲区
                    tagBuffer.append(remaining)
                    remaining = ""
                } else {
                    content.append(remaining)
                    remaining = ""
                }
            }
        }
    }

    return ProcessedContent(
        reasoning = reasoning.toString(),
        content = content.toString(),
        reasoningDone = reasoningDone,
        inThink = currentInThink
    )
}

/** 流式响应的分片类型 */
sealed class StreamChunk {
    data class Content(val delta: String) : StreamChunk()
    data class Reasoning(val delta: String) : StreamChunk()
    data object ReasoningDone : StreamChunk()
    data class Done(val fullText: String, val fullReasoning: String = "", val rawResponse: String = "") : StreamChunk()
    data class Error(val message: String) : StreamChunk()
    object Cancelled : StreamChunk()
}
