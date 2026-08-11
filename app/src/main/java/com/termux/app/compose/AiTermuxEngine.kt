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
        val blockPattern = Regex(
            """```[a-zA-Z]*\s*[\r\n]+(.*?)[\r\n]+```""",
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

        // 策略 2: 直接在文本中查找包含 skillType 的 JSON 对象（兜底）
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

        // 1. 移除所有包含 skillType 的代码块
        val blockPattern = Regex(
            """```[a-zA-Z]*\s*[\r\n]+(.*?)[\r\n]+```""",
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
     */
    fun detectFakeOutput(content: String): FakeOutputCheck {
        val violations = mutableListOf<String>()
        val lower = content.lowercase()

        // 禁令 1：禁止在技能卡片后声称操作已执行/已完成
        // 检测在 skill 代码块之后出现的伪造结果描述
        val skillBlockEnd = Regex("""```[a-zA-Z]*\s*[\r\n]+(.*?)[\r\n]+```""", RegexOption.DOT_MATCHES_ALL)
        val skillBlocks = skillBlockEnd.findAll(content).toList()

        if (skillBlocks.isNotEmpty()) {
            // 获取最后一个 skill 代码块的结束位置
            val lastBlockEnd = skillBlocks.last().range.last
            val afterBlock = content.substring(lastBlockEnd.coerceAtMost(content.length - 1)).trim()

            // 如果代码块之后还有内容，检查是否为伪造结果
            if (afterBlock.isNotBlank()) {
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

                // 检测编造的文件路径/系统信息
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

                // 检测编造的 "---" 分隔线 + 结果段落
                if (Regex("""-{3,}""").containsMatchIn(afterBlock) &&
                    (afterBlock.contains("技能") || afterBlock.contains("结果") || afterBlock.contains("输出"))) {
                    violations.add("【禁令3】技能卡片后存在伪造的分隔线和结果段落")
                }
            }
        }

        // 禁令 4：禁止在没有 skill 代码块时假装执行了操作
        // 如果回复中声称"执行了"但没有任何技能卡片
        if (skillBlocks.isEmpty()) {
            val fakeActionPatterns = listOf(
                "已执行", "已完成", "已发送", "已创建", "已连接", "已安装",
                "列出了", "读取了", "写入了", "删除了",
                "执行结果", "操作成功", "操作完成"
            )
            for (pattern in fakeActionPatterns) {
                if (lower.contains(pattern)) {
                    violations.add("【禁令4】回复声称操作「$pattern」但未输出任何技能卡片")
                    break
                }
            }
        }

        // 禁令 5：检测虚构的命令输出（如假装看到了文件列表的详细输出）
        val fakeOutputPatterns = listOf(
            """\[D\]\s+\S+""",  // 模拟 FILE_LIST 输出
            """\[F\]\s+\S+""",
            """total\s+\d+""",  // 模拟 ls 输出
            """^\s*\d+\s+\S+\s+\S+""",  // 模拟 ps 输出格式
            """PID\s+USER\s+COMMAND""",
            """/proc/\d+/status""",
            """MemTotal|MemFree|MemAvailable""",
            """[<>]\s*[0-9a-fA-F]+\s*bytes""",  // 模拟网络输出
            """\d+\s+imported""",  // 模拟包管理输出
            """\d+\s+not upgraded""",
            """Setting up\s+""",
            """Processing triggers""",
        )
        for (pattern in fakeOutputPatterns) {
            if (Regex(pattern, RegexOption.MULTILINE).containsMatchIn(content)) {
                // 只有在没有 skill 代码块的情况下才报告（有 skill 可能是用户输入的）
                if (skillBlocks.isEmpty() || !content.substring(
                    skillBlocks.first().range.first.coerceAtLeast(0),
                    skillBlocks.last().range.last.coerceAtMost(content.length - 1)
                ).contains(pattern)) {
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
            SkillType.CUSTOM_COMMAND -> execRunCommand(context, termuxService, params)
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
                    true, "已创建新会话",
                    SkillCardData(
                        skillType = SkillType.NEW_SESSION,
                        title = "已新建终端会话",
                        description = "会话名称: $displayName",
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
                SkillExecutionResult(
                    true, "已退出 Termux",
                    SkillCardData(
                        skillType = SkillType.EXIT_TERMUX,
                        title = "已退出 Termux",
                        description = "应用已退出",
                        status = SkillStatus.COMPLETED
                    )
                )
            } else {
                SkillExecutionResult(false, "无法获取 Activity 上下文，退出失败")
            }
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

            // 在新会话中执行
            val sessionName = "capture-${command.take(20).replace(Regex("[^a-zA-Z0-9]"), "_")}"
            val session = termuxService.createTermuxSession(
                null, arrayOf("-c", wrappedCmd), null, null, false, sessionName
            )

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
                message = if (completed) "命令执行完成，已捕获输出" else "命令可能仍在运行，已捕获部分输出",
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
                true, "正在连接 VNC: $address",
                SkillCardData(
                    skillType = SkillType.CONNECT_VNC,
                    title = "VNC 连接",
                    description = "地址: $address",
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
                    true, "已在新会话启动 SSH 连接到 $host",
                    SkillCardData(
                        skillType = SkillType.CONNECT_SSH,
                        title = "SSH 连接",
                        description = "$username@$host:$port",
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
                        true, "命令已发送到会话 \"${ts.mSessionName}\"",
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
                        true, "已在新会话执行命令",
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
                activity?.finishAffinity()
            }
            else -> { }
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
}
