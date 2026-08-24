package com.termux.app.compose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.location.LocationManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.termux.app.TermuxService
import com.termux.app.TermuxActivity
import com.termux.app.activities.QemuVmActivity
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.shell.TermuxTask
import com.termux.app.ssh.SshConnection
import com.termux.app.ssh.SshConnectionManager
import com.termux.app.vnc.VncConnection
import com.termux.app.vnc.VncConnectionManager
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
import java.net.URLEncoder
import java.net.URL

/** ---------- 技能执行器 ---------- */

data class SkillExecutionResult(
    val success: Boolean,
    val message: String,
    val skillCard: SkillCardData? = null,
    val status: String = if (success) "success" else "failed"
)

/** AI 回复假输出检测结果 */
data class FakeOutputCheck(
    val isFake: Boolean,       // 是否检测到假输出
    val violations: List<String> // 违反的禁令条目
)

object SkillExecutor {

    private const val TERMUX_ROOT = "/data/data/com.termux"
    private val HOME_DIR = "$TERMUX_ROOT/files/home"

    /** 检查无限制模式是否激活 */
    private fun isUnlimitedMode(context: Context): Boolean {
        return AiTermuxPrefs.isUnlimitedModeActive(context)
    }

    /** 危险操作检测：返回危险原因，不危险返回 null。
     * 无限制模式下跳过所有危险检测。 */
    fun checkDangerous(context: Context, skillType: SkillType, params: JsonObject): String? {
        // 无限制模式：跳过所有危险检测
        if (isUnlimitedMode(context)) return null

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
                if (cmd.isBlank()) return null
                val result = RiskCommandDetector.detect(cmd)
                if (result.isDangerous) {
                    // 如果是 su/sudo，检查是否包装了其他危险命令
                    if (result.riskType == RiskCommandDetector.RiskType.SU_SUDO) {
                        val wrapped = extractWrappedCommandForCheck(cmd)
                        if (wrapped != null) {
                            val wrappedResult = RiskCommandDetector.detect(wrapped)
                            if (wrappedResult.isDangerous && wrappedResult.riskType != RiskCommandDetector.RiskType.SU_SUDO) {
                                return wrappedResult.description
                            }
                        }
                    }
                    result.description
                } else null
            }
            SkillType.CLOSE_ALL_SESSIONS -> "将关闭所有正在运行的终端会话，未保存的内容会丢失"
            SkillType.EXIT_TERMUX -> "将退出 Termux 应用，所有运行中的进程会终止"
            else -> null
        }
    }

    /** 从 su/sudo 命令中提取被包装的子命令（用于 Agent 危险检测） */
    private fun extractWrappedCommandForCheck(command: String): String? {
        val trimmed = command.trim()
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

    /** 从 AI 回复内容中解析技能块（支持多种代码块标记、直接 JSON、以及行业标准 tool_call 格式） */
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

        // 策略 2: 解析行业标准 <tool_call> / <tool_call> XML 格式
        parseToolCallBlocks(content, results, seen)

        // 策略 3: 直接在文本中查找包含 skillType 的 JSON 对象（兜底）
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

    /** 解析行业标准 <tool_call> / <tool_call> XML 格式的技能调用 */
    private fun parseToolCallBlocks(
        content: String,
        results: MutableList<Pair<String, JsonObject>>,
        seen: MutableSet<String>
    ) {
        val toolCallPattern = Regex(
            """<tool_call>([\s\S]*?)</tool_call>""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in toolCallPattern.findAll(content)) {
            val block = match.groupValues.getOrNull(1)?.trim() ?: continue
            val namePattern = Regex(
                """<tool_name>\s*([\s\S]*?)\s*</tool_name>""",
                RegexOption.DOT_MATCHES_ALL
            )
            val nameMatch = namePattern.find(block) ?: continue
            val skillType = nameMatch.groupValues[1].trim()
            if (skillType.isBlank() || !isValidSkillType(skillType)) continue
            var params = JsonObject()
            val paramPattern = Regex(
                """<parameter\s+name\s*=\s*["']([^"']+)["']\s*>([\s\S]*?)</parameter>""",
                RegexOption.DOT_MATCHES_ALL
            )
            val jsonObject = JsonObject()
            paramPattern.findAll(block).forEach { pm ->
                val pname = pm.groupValues[1].trim()
                val pval = pm.groupValues[2].trim()
                try { jsonObject.add(pname, JsonPrimitive(pval)) } catch (_: Exception) { }
            }
            params = jsonObject
            val key = """$skillType:${params}"""
            if (key !in seen) {
                seen.add(key)
                results.add(skillType to params)
            }
        }
    }

    /** 解析 <new_tool> 块，提取 AI 自主创造的新技能 */
    fun parseNewToolBlocks(content: String): List<Map<String, String>> {
        val results = mutableListOf<Map<String, String>>()
        val pattern = Regex(
            """<new_tool>([\s\S]*?)</new_tool>""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in pattern.findAll(content)) {
            val block = match.groupValues.getOrNull(1)?.trim() ?: continue
            val tool = mutableMapOf<String, String>()
            val tagPattern = Regex(
                """<(tool_name\|description\|system_prompt\|skill_json\|implementation_type)>[\s\S]*?</\1>""",
                RegexOption.DOT_MATCHES_ALL
            )
            for (tagMatch in tagPattern.findAll(block)) {
                val tagName = tagMatch.groupValues[1].trim()
                val tagStart = tagMatch.value.indexOf(">") + 1
                val tagEnd = tagMatch.value.lastIndexOf("</")
                val tagValue = if (tagStart >= 0 && tagEnd > tagStart) {
                    tagMatch.value.substring(tagStart, tagEnd).trim()
                } else ""
                tool[tagName] = tagValue
            }
            if (tool.containsKey("tool_name") && tool["tool_name"]!!.isNotBlank()) {
                results.add(tool)
            }
        }
        return results
    }

    private fun isValidSkillType(type: String): Boolean {
        return try {
            SkillType.valueOf(type.uppercase())
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

        // 2. 移除所有 <tool_call> 行业标准格式块
        val toolCallStrip = Regex(
            """""<tool_call>([\s\S]*?)</tool_call>""""",
            RegexOption.DOT_MATCHES_ALL
        )
        result = toolCallStrip.replace(result, """""").trim()

        // 3. 移除直接内联的 JSON 技能对象
        val jsonPattern = Regex("""\{[^{}]*"skillType"[^{}]*\}""", RegexOption.DOT_MATCHES_ALL)
        result = jsonPattern.replace(result, "").trim()

        // 4. 过滤 AI 编造的伪造结果段落
        //    删除以 "技能执行结果"、"技能名称:"、"操作:"、"状态:" 开头的伪造结果块
        val fakeResultPattern = Regex(
            """[\r\n]*[*_#\s]*技能执行结果[*_#\s]*[\r\n]+[\s\S]*?(?=[\r\n]{2,}|$)""",
            RegexOption.IGNORE_CASE
        )
        result = fakeResultPattern.replace(result, "")

        // 5. 过滤以 "技能名称:"、"操作:"、"状态:" 等开头的连续伪造行
        val fakeLinePattern = Regex(
            """^[*_#\s]*(技能名称|操作(?:说明)?|状态(?:说明)?|详细信息)[:：].*$""",
            RegexOption.MULTILINE
        )
        result = fakeLinePattern.replace(result, "")

        // 6. 过滤 AI 虚构的 "---" 分隔线（在伪造结果上下文中）
        val separatorPattern = Regex("""^[*_#\s]*-{3,}[*_#\s]*$""", RegexOption.MULTILINE)
        result = separatorPattern.replace(result, "")

        // 7. 清理残留的空行
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
    fun detectFakeOutput(content: String, preParsedSkillCount: Int = -1, context: Context? = null): FakeOutputCheck {
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
                // 仅检查真正可疑的模式（捏造具体数据），排除 AI 对技能执行的合法描述
                val fabricatedDataPatterns = listOf(
                    """共\s+\d+\s+个""",
                    """\d+\s+个会话""",
                    """\d+\s+个文件""",
                    """\d+\s+个进程""",
                    "共找到",
                    "运行中进程",
                )
                var foundFake = false
                for (pattern in fabricatedDataPatterns) {
                    if (Regex(pattern).containsMatchIn(afterBlock)) {
                        violations.add("【禁令1】技能卡片后存在伪造的执行结果描述（包含「$pattern」）")
                        foundFake = true
                        break
                    }
                }
                if (!foundFake) {
                    val commonDescPatterns = listOf(
                        "执行结果", "已执行", "已完成", "执行成功", "执行完毕",
                        "技能结果", "skill result", "详细信息",
                        "操作说明", "结果说明",
                        "正在读取", "正在检查", "正在扫描",
                        "输出", "output", "结果如下"
                    )
                    for (pattern in commonDescPatterns) {
                        if (afterBlock.contains(pattern, ignoreCase = true)) {
                            val skillTypesInBlock = mutableListOf<String>()
                            for (r in allRanges) {
                                try {
                                    val json = JsonParser.parseString(
                                        content.substring(r.first, r.last).trim()
                                    ).asJsonObject
                                    skillTypesInBlock.add(json.get("skillType")?.asString ?: "")
                                } catch (_: Exception) { }
                            }
                            if (skillTypesInBlock.isEmpty()) {
                                violations.add("【禁令1】技能卡片后存在可疑的执行结果描述（包含「$pattern」但未识别到技能类型）")
                            }
                            break
                        }
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
            // 使用更具体的模式组合，避免误判 AI 对技能执行的合法描述
            val fakeActionPatterns = listOf(
                "已创建.*(文件|会话|进程|目录)",
                "已连接.*(SSH|VNC|远程)",
                "已安装.*(软件|包|package)",
                "已发送.*(命令|请求)",
                "读取了.*(文件|目录|内容)",
                "写入了.*(文件|内容)",
                "删除了.*(文件|目录)",
                "执行结果.*(显示|为|是)",
                "列出了.*(文件|目录|会话|进程)",
                "操作成功.*(创建|连接|安装|写入|删除)",
            )
            for (pattern in fakeActionPatterns) {
                if (Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(content)) {
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
        // 无限制模式下跳过禁令5，由用户自行评判
        if (context != null && isUnlimitedMode(context)) {
            // 无限制模式：跳过禁令5检测
        } else {
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
            SkillType.LIST_REMOTE_CONNECTIONS -> execListRemoteConnections(context)
            SkillType.CONNECT_REMOTE_CONNECTION -> execConnectRemoteConnection(context, termuxService, params)
            SkillType.FILE_LIST -> execFileList(params)
            SkillType.FILE_READ -> execFileRead(params)
            SkillType.FILE_WRITE -> execFileWrite(params)
            SkillType.FILE_DELETE -> execFileDelete(params)
            SkillType.FILE_GENERATE -> execFileGenerate(params)
            SkillType.FILE_MODIFY -> execFileModify(params)
            SkillType.RUN_COMMAND -> execRunCommand(context, termuxService, params)
            SkillType.CAPTURE_OUTPUT -> execCaptureOutput(context, termuxService, params)
            SkillType.PACKAGE_INSTALL -> execPackageInstall(context, termuxService, params)
            SkillType.PACKAGE_UNINSTALL -> execPackageUninstall(context, termuxService, params)
            SkillType.APP_INSTALL -> execAppInstall(context, termuxService, params)
            SkillType.APP_UNINSTALL -> execAppUninstall(context, termuxService, params)
            SkillType.COMPILE_CODE -> execCompileCode(context, termuxService, params)
            SkillType.SUB_AGENT -> execSubAgent(context, termuxService, params)
            SkillType.SEARCH_AGENT -> execSearchAgent(context, termuxService, params)
            SkillType.WEB_SEARCH -> execWebSearch(context, termuxService, params)
            SkillType.CONFIRM_DANGEROUS -> SkillExecutionResult(false, "危险操作需在 UI 中确认后执行")
            SkillType.SCHEDULE_TASK -> execScheduleTask(context, params)
            SkillType.GET_DEVICE_STATUS -> execGetDeviceStatus(context, termuxService, params)
            SkillType.GET_CURRENT_SESSION -> execGetCurrentSession(context, termuxService)
            SkillType.CLIPBOARD_READ -> execClipboardRead(context)
            SkillType.CLIPBOARD_WRITE -> execClipboardWrite(context, params)
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
        val unlimited = isUnlimitedMode(context)
        return@withContext try {
            val session = termuxService.createTermuxSession(
                null, null, null, null, false, name
            )
            if (session != null) {
                val ts = session.getTerminalSession()
                val handle = ts.mHandle.toString()
                val displayName = ts.mSessionName ?: "Terminal"
                // 无限制模式：自动将会话转到前台
                if (unlimited) {
                    val i = Intent(context, TermuxActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    i.putExtra("sessionHandle", handle)
                    context.startActivity(i)
                }
                SkillExecutionResult(
                    true, if (unlimited) "已创建并激活终端会话" else "已生成终端会话卡片",
                    SkillCardData(
                        skillType = SkillType.NEW_SESSION,
                        title = if (unlimited) "已新建并激活终端会话" else "已新建终端会话",
                        description = "会话名称: $displayName" + if (unlimited) "" else "（点击卡片以初始化终端）",
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

    private fun resolveTermuxShell(): String? {
        val binDir = TermuxShellUtils.getDefaultBinPath()
        if (binDir.isNotEmpty()) {
            for (shellBinary in arrayOf("bash", "login", "zsh", "sh")) {
                val shellFile = java.io.File(binDir, shellBinary)
                if (shellFile.canExecute()) return shellFile.absolutePath
            }
        }
        val systemShell = java.io.File("/system/bin/sh")
        if (systemShell.canExecute()) return systemShell.absolutePath
        return null
    }

    private suspend fun execCaptureOutput(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        val command = if (params.has("command")) params.get("command").asString else ""
        if (command.isBlank()) return@withContext SkillExecutionResult(false, "命令为空")
        val timeoutSeconds = (if (params.has("timeout")) params.get("timeout").asInt else 30).coerceIn(5, 120)
        val description = if (params.has("description")) params.get("description").asString else command

        // 无限制模式下跳过危险命令检测
        if (!isUnlimitedMode(context)) {
            val danger = Regex("""rm\s+-[a-zA-Z]*r[a-zA-Z]*\s+/\b|mkfs|dd\s+if=.*/dev/block|:\(\)\{ :\|:\& \};:""")
            if (danger.containsMatchIn(command)) {
                return@withContext SkillExecutionResult(false, "检测到危险命令，已拒绝执行")
            }
        }

        return@withContext try {
            val shellPath = resolveTermuxShell()
                ?: return@withContext SkillExecutionResult(false, "找不到可用的 shell 环境")

            val executionCommand = ExecutionCommand(
                System.currentTimeMillis().toInt(),
                shellPath,
                arrayOf("-c", command),
                null,
                null,
                true,
                false
            )

            val shellEnvClient = TermuxShellEnvironmentClient()
            val termuxTask = TermuxTask.execute(
                context,
                executionCommand,
                null,
                shellEnvClient,
                false
            )

            if (termuxTask == null) {
                val errCode = executionCommand.resultData.getErrCode()
                val errMsg = executionCommand.resultData.errorsList
                    ?.joinToString("; ") { it.message ?: "" }
                    ?.ifBlank { "创建执行任务失败 (code=$errCode)" }
                    ?: "创建执行任务失败"
                return@withContext SkillExecutionResult(false, errMsg)
            }

            val resultData = executionCommand.resultData
            val startTime = System.currentTimeMillis()
            val timeoutMs = timeoutSeconds * 1000L
            var completed = false

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                delay(200)
                if (executionCommand.hasExecuted() || resultData.exitCode != null) {
                    completed = true
                    break
                }
            }

            if (!completed) {
                runCatching {
                    termuxTask.killIfExecuting(context, false)
                }
            }

            val stdout = resultData.stdout.toString()
            val stderr = resultData.stderr.toString()
            val combined = buildString {
                if (stdout.isNotBlank()) append(stdout.trim())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append(stderr.trim())
                }
            }
            val output = if (combined.isBlank()) {
                if (completed) "(命令执行完成但无输出)" else "(命令执行超时或无输出，命令可能仍在运行中)"
            } else {
                combined
            }

            val truncatedOutput = if (output.length > 30000) output.take(30000) + "\n...(输出已截断，超过30000字符)" else output
            val exitCode = resultData.exitCode

            SkillExecutionResult(
                success = completed || output.isNotBlank(),
                message = if (completed) "命令已完成${if (exitCode != null) " (exit=$exitCode)" else ""}" else "命令可能仍在运行，已捕获部分输出",
                skillCard = SkillCardData(
                    skillType = SkillType.CAPTURE_OUTPUT,
                    title = description,
                    description = command,
                    status = if (completed) SkillStatus.COMPLETED else SkillStatus.RUNNING,
                    command = command,
                    output = truncatedOutput,
                    partialOutput = !completed
                )
            )
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            SkillExecutionResult(false, "执行出错: $msg")
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
        val unlimited = isUnlimitedMode(context)
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
                val handle = ts.mHandle.toString()
                // 无限制模式：自动将 SSH 会话转到前台
                if (unlimited) {
                    val i = Intent(context, TermuxActivity::class.java)
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    i.putExtra("sessionHandle", handle)
                    context.startActivity(i)
                }
                SkillExecutionResult(
                    true, if (unlimited) "已创建 SSH 连接会话" else "已生成 SSH 连接卡片",
                    SkillCardData(
                        skillType = SkillType.CONNECT_SSH,
                        title = "SSH 连接",
                        description = "$username@$host:$port" + if (unlimited) "" else "（点击卡片以连接）",
                        status = SkillStatus.COMPLETED,
                        sessionId = handle,
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

    private suspend fun execListRemoteConnections(context: Context): SkillExecutionResult = withContext(Dispatchers.IO) {
        val sshMgr = SshConnectionManager(context)
        val vncMgr = VncConnectionManager(context)
        val sshList = sshMgr.connections
        val vncList = vncMgr.connections

        val sb = StringBuilder()
        sb.appendLine("=== 已保存的远程连接 ===")
        sb.appendLine()

        if (sshList.isNotEmpty()) {
            sb.appendLine("【SSH 连接】")
            for (ssh in sshList) {
                sb.appendLine("- [SSH] ${ssh.name} (${ssh.host}:${ssh.port}) [id: ${ssh.id}]")
            }
            sb.appendLine()
        }

        if (vncList.isNotEmpty()) {
            sb.appendLine("【VNC 连接】")
            for (vnc in vncList) {
                sb.appendLine("- [VNC] ${vnc.name} (${vnc.host}:${vnc.port}) [id: ${vnc.id}]")
            }
            sb.appendLine()
        }

        val total = sshList.size + vncList.size
        if (total == 0) {
            sb.appendLine("暂无已保存的远程连接。")
        } else {
            sb.appendLine("共 $total 个连接。使用 connectionId 指定要连接的连接。")
        }

        SkillExecutionResult(
            true, "已列出远程连接",
            SkillCardData(
                skillType = SkillType.LIST_REMOTE_CONNECTIONS,
                title = "远程连接列表",
                description = "${sshList.size} 个 SSH + ${vncList.size} 个 VNC",
                status = SkillStatus.COMPLETED,
                output = sb.toString()
            )
        )
    }

    private suspend fun execConnectRemoteConnection(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        val connectionId = if (params.has("connectionId")) params.get("connectionId").asString else ""
        val typeFilter = if (params.has("type")) params.get("type").asString else ""
        if (connectionId.isBlank()) return@withContext SkillExecutionResult(false, "未指定连接 ID 或名称")
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")

        val sshMgr = SshConnectionManager(context)
        val vncMgr = VncConnectionManager(context)

        var foundSsh: SshConnection? = null
        var foundVnc: VncConnection? = null

        if (typeFilter.isBlank() || typeFilter == "ssh") {
            foundSsh = sshMgr.connections.firstOrNull {
                it.id == connectionId || it.name == connectionId
            }
        }
        if (typeFilter.isBlank() || typeFilter == "vnc") {
            foundVnc = vncMgr.connections.firstOrNull {
                it.id == connectionId || it.name == connectionId
            }
        }

        when {
            foundSsh != null -> {
                val ssh = foundSsh
                val sshCmd = buildString {
                    append("ssh ")
                    if (ssh.port != 22) append("-p ${ssh.port} ")
                    append("${ssh.username}@${ssh.host}")
                    if (ssh.privateKeyPath.isNotBlank()) {
                        insert(0, "ssh -i '${ssh.privateKeyPath}' ")
                    } else if (ssh.password.isNotBlank()) {
                        insert(0, "sshpass -p '${ssh.password.replace("'", "'\\''")}' ")
                    }
                }
                val session = termuxService.createTermuxSession(
                    null, arrayOf("-c", sshCmd), null, null, false, "SSH-${ssh.name}"
                )
                if (session != null) {
                    val ts = session.getTerminalSession()
                    val handle = ts.mHandle.toString()
                    SkillExecutionResult(
                        true, "已生成 SSH 连接卡片",
                        SkillCardData(
                            skillType = SkillType.CONNECT_REMOTE_CONNECTION,
                            title = "SSH 连接: ${ssh.name}",
                            description = "${ssh.username}@${ssh.host}:${ssh.port}（点击卡片以连接）",
                            status = SkillStatus.COMPLETED,
                            sessionId = handle,
                            sessionName = ts.mSessionName ?: "SSH-${ssh.name}",
                            connectionAddress = "${ssh.host}:${ssh.port}"
                        )
                    )
                } else {
                    SkillExecutionResult(false, "创建 SSH 会话失败")
                }
            }
            foundVnc != null -> {
                val vnc = foundVnc
                SkillExecutionResult(
                    true, "已生成 VNC 连接卡片",
                    SkillCardData(
                        skillType = SkillType.CONNECT_REMOTE_CONNECTION,
                        title = "VNC 连接: ${vnc.name}",
                        description = "${vnc.host}:${vnc.port}（点击卡片以连接）",
                        status = SkillStatus.COMPLETED,
                        connectionAddress = "${vnc.host}:${vnc.port}"
                    )
                )
            }
            else -> SkillExecutionResult(false, "未找到匹配的远程连接: $connectionId")
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

        // 高危命令二次确认（如果已经由 Agent 流程确认过则跳过）
        // 无限制模式下跳过所有风险确认
        if (!RiskConfirmManager.shouldSkipRiskCheck() && !isUnlimitedMode(context)) {
            val confirmed = RiskConfirmManager.requestConfirmation(context, command)
            if (!confirmed) {
                return@withContext SkillExecutionResult(
                    false,
                    context.getString(com.termux.R.string.access_denied)
                )
            }
        }

        return@withContext try {
            val unlimited = isUnlimitedMode(context)
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
                        true, if (unlimited) "命令已自动执行" else "已生成命令卡片",
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
                    val handle = ts.mHandle.toString()
                    // 无限制模式：自动将会话转到前台
                    if (unlimited) {
                        val i = Intent(context, TermuxActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        i.putExtra("sessionHandle", handle)
                        context.startActivity(i)
                    }
                    SkillExecutionResult(
                        true, if (unlimited) "命令已自动执行" else "已生成命令卡片",
                        SkillCardData(
                            skillType = SkillType.RUN_COMMAND,
                            title = "新会话执行命令",
                            description = command,
                            status = SkillStatus.COMPLETED,
                            sessionId = handle,
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

    private suspend fun execPackageUninstall(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult {
        if (termuxService == null) return SkillExecutionResult(false, "Termux 服务未连接")
        val pkgs = try {
            val arr = params.getAsJsonArray("packages")
            (0 until arr.size()).map { arr[it].asString }
        } catch (_: Exception) { emptyList() }
        if (pkgs.isEmpty()) return SkillExecutionResult(false, "未指定卸载包")
        val command = "pkg remove -y ${pkgs.joinToString(" ")}"
        val wrappedParams = JsonObject().apply {
            addProperty("command", command)
            addProperty("sessionName", "卸载-${pkgs.first()}")
        }
        return execRunCommand(context, termuxService, wrappedParams)
    }

    private suspend fun execFileGenerate(params: JsonObject): SkillExecutionResult {
        val pathParam = if (params.has("path")) params.get("path").asString else ""
        if (pathParam.isBlank()) return SkillExecutionResult(false, "未指定文件路径")
        val content = if (params.has("content")) params.get("content").asString else ""
        val path = resolvePath(pathParam)
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            SkillExecutionResult(
                true, "已生成文件",
                SkillCardData(
                    skillType = SkillType.FILE_GENERATE,
                    title = "生成文件",
                    description = "$path (${content.length} 字符)",
                    status = SkillStatus.COMPLETED,
                    filePath = path
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "生成文件出错: ${e.message}")
        }
    }

    private suspend fun execFileModify(params: JsonObject): SkillExecutionResult {
        val pathParam = if (params.has("path")) params.get("path").asString else ""
        if (pathParam.isBlank()) return SkillExecutionResult(false, "未指定文件路径")
        val path = resolvePath(pathParam)
        val operations = try {
            val arr = params.getAsJsonArray("operations")
            (0 until arr.size()).map { arr[it].asJsonObject }
        } catch (_: Exception) { emptyList() }
        if (operations.isEmpty()) return SkillExecutionResult(false, "未指定修改操作")

        return try {
            val file = File(path)
            if (!file.exists()) return SkillExecutionResult(false, "文件不存在: $path")
            if (!file.isFile) return SkillExecutionResult(false, "不是文件: $path")
            if (file.length() > 1024 * 1024) return SkillExecutionResult(false, "文件过大（>1MB），无法修改")

            var content = file.readText()
            val lines = content.lines().toMutableList()

            for (op in operations) {
                when (op.get("type")?.asString) {
                    "replace" -> {
                        val search = op.get("search")?.asString ?: ""
                        val replace = op.get("replace")?.asString ?: ""
                        content = content.replace(search, replace)
                    }
                    "insert" -> {
                        val line = op.get("line")?.asInt ?: 0
                        val insertContent = op.get("content")?.asString ?: ""
                        val safeLine = line.coerceIn(0, lines.size)
                        lines.add(safeLine, insertContent)
                    }
                    "delete" -> {
                        val line = op.get("line")?.asInt ?: 0
                        if (line in 1..lines.size) {
                            lines.removeAt(line - 1)
                        }
                    }
                }
            }

            if (operations.any { it.get("type")?.asString == "insert" || it.get("type")?.asString == "delete" }) {
                content = lines.joinToString("\n")
            }

            file.writeText(content)
            SkillExecutionResult(
                true, "已修改文件",
                SkillCardData(
                    skillType = SkillType.FILE_MODIFY,
                    title = "修改文件",
                    description = "$path (${operations.size} 处修改)",
                    status = SkillStatus.COMPLETED,
                    filePath = path,
                    output = content
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "修改文件出错: ${e.message}")
        }
    }

    private suspend fun execAppInstall(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        if (!AiTermuxPrefs.isRootAvailable()) return@withContext SkillExecutionResult(false, "APP_INSTALL 需要 ROOT 权限，但设备未获取 ROOT")
        val apkPath = if (params.has("apkPath")) params.get("apkPath").asString else ""
        if (apkPath.isBlank()) return@withContext SkillExecutionResult(false, "未指定 APK 路径")
        val resolvedPath = resolvePath(apkPath)
        val file = File(resolvedPath)
        if (!file.exists()) return@withContext SkillExecutionResult(false, "APK 文件不存在: $resolvedPath")

        val command = "su -c pm install -r \"$resolvedPath\""
        val wrappedParams = JsonObject().apply {
            addProperty("command", command)
            addProperty("description", "安装 APK (ROOT)")
        }
        execCaptureOutput(context, termuxService, wrappedParams)
    }

    private suspend fun execAppUninstall(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        if (!AiTermuxPrefs.isRootAvailable()) return@withContext SkillExecutionResult(false, "APP_UNINSTALL 需要 ROOT 权限，但设备未获取 ROOT")
        val packageName = if (params.has("packageName")) params.get("packageName").asString else ""
        if (packageName.isBlank()) return@withContext SkillExecutionResult(false, "未指定应用包名")

        val command = "su -c pm uninstall \"$packageName\""
        val wrappedParams = JsonObject().apply {
            addProperty("command", command)
            addProperty("description", "卸载应用 (ROOT)")
        }
        execCaptureOutput(context, termuxService, wrappedParams)
    }

    private suspend fun execCompileCode(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        val command = if (params.has("command")) params.get("command").asString else ""
        if (command.isBlank()) return@withContext SkillExecutionResult(false, "未指定编译命令")
        val description = if (params.has("description")) params.get("description").asString else "编译代码"
        val timeout = if (params.has("timeout")) params.get("timeout").asInt else 60

        val wrappedParams = JsonObject().apply {
            addProperty("command", command)
            addProperty("description", description)
            addProperty("timeout", timeout)
        }
        val result = execCaptureOutput(context, termuxService, wrappedParams)

        // 分析编译结果
        val rawOutput = result.skillCard?.output ?: ""
        val isSuccess = result.success && !rawOutput.contains("error", ignoreCase = true)
        val hasErrors = rawOutput.contains("error:", ignoreCase = true) ||
                rawOutput.contains("Error:", ignoreCase = true) ||
                rawOutput.contains("FAILED", ignoreCase = true)

        val compileStatus = buildString {
            appendLine("=== 编译结果 ===")
            appendLine("项目: $description")
            appendLine("编译状态: ${if (isSuccess && !hasErrors) "✅ 成功" else "❌ 失败"}")
            if (result.message.contains("exit=")) {
                appendLine("退出码: ${result.message.substringAfter("exit=").trim()}")
            }
            appendLine()

            if (hasErrors) {
                // 提取错误信息
                val errorLines = rawOutput.lines()
                    .filter { it.contains("error:", ignoreCase = true) ||
                            it.contains("Error:", ignoreCase = true) ||
                            it.contains("FAILED", ignoreCase = true) }
                    .take(20)

                if (errorLines.isNotEmpty()) {
                    appendLine("--- 错误信息 ---")
                    for (line in errorLines) {
                        appendLine("  $line")
                    }
                    appendLine()
                }

                // 提取警告信息（仅显示前5条）
                val warningLines = rawOutput.lines()
                    .filter { it.contains("warning:", ignoreCase = true) ||
                            it.contains("Warning:", ignoreCase = true) }
                    .take(5)
                if (warningLines.isNotEmpty()) {
                    appendLine("--- 警告 (前${warningLines.size}条) ---")
                    for (line in warningLines) {
                        appendLine("  $line")
                    }
                    appendLine()
                }
            } else {
                // 成功时显示统计信息
                val warnCount = rawOutput.lines().count {
                    it.contains("warning:", ignoreCase = true)
                }
                if (warnCount > 0) {
                    appendLine("编译成功，但有 $warnCount 条警告。")
                } else {
                    appendLine("编译成功，无错误无警告。")
                }
            }

            appendLine()
            appendLine("--- 完整输出 ---")
            appendLine(rawOutput.take(5000))
        }

        SkillExecutionResult(
            success = isSuccess && !hasErrors,
            message = if (isSuccess && !hasErrors) "编译成功" else "编译失败",
            skillCard = SkillCardData(
                skillType = SkillType.COMPILE_CODE,
                title = "编译: $description",
                description = if (isSuccess && !hasErrors) "编译成功" else "编译失败",
                status = if (isSuccess && !hasErrors) SkillStatus.COMPLETED else SkillStatus.FAILED,
                output = compileStatus
            )
        )
    }

    private suspend fun execSubAgent(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        val task = if (params.has("task")) params.get("task").asString else "子任务"
        val instructions = if (params.has("instructions")) params.get("instructions").asString else ""
        val agentContext = if (params.has("context")) params.get("context").asString else ""
        val commandsParam = if (params.has("commands")) params.get("commands").asString else ""

        val fullInstructions = buildString {
            appendLine("# 子 Agent 任务: $task")
            if (agentContext.isNotBlank()) {
                appendLine("## 上下文: $agentContext")
            }
            appendLine("## 指令:")
            appendLine(instructions.ifBlank { "请根据任务描述执行相应操作。" })
        }

        if (commandsParam.isNotBlank()) {
            // 自动执行模式：直接执行指定命令，返回结果
            val script = buildString {
                appendLine("echo '=== 子 Agent 任务开始 ==='")
                appendLine("echo \"任务: $task\"")
                appendLine("echo '--- 任务说明 ---'")
                appendLine("cat << 'SUBAGENTEOF'")
                appendLine(fullInstructions)
                appendLine("SUBAGENTEOF")
                appendLine("echo '--- 开始执行 ---'")
                appendLine(commandsParam)
                appendLine("echo '--- 执行结束 ---'")
                appendLine("echo \"子 Agent 任务 [$task] 已完成\"")
            }

            val wrappedParams = JsonObject().apply {
                addProperty("command", script)
                addProperty("description", task)
                addProperty("timeout", 120)
            }
            val result = execCaptureOutput(context, termuxService, wrappedParams)

            // 格式化结果，添加子 Agent 任务摘要
            val formattedOutput = buildString {
                appendLine("=== 子 Agent 执行结果 ===")
                appendLine("任务: $task")
                appendLine("状态: ${if (result.success) "成功" else "失败"}")
                if (result.message.isNotBlank()) appendLine("详情: ${result.message}")
                appendLine()
                appendLine("--- 执行输出 ---")
                appendLine(result.skillCard?.output ?: "(无输出)")
            }

            SkillExecutionResult(
                result.success,
                result.message,
                SkillCardData(
                    skillType = SkillType.SUB_AGENT,
                    title = "子 Agent: $task",
                    description = if (result.success) "任务已完成" else "任务执行失败",
                    status = if (result.success) SkillStatus.COMPLETED else SkillStatus.FAILED,
                    output = formattedOutput
                )
            )
        } else {
            // 非自动执行模式：创建子会话，写入指令
            val script = buildString {
                appendLine("cat > /tmp/sub_agent_task.md << 'SUBAGENTEOF'")
                appendLine(fullInstructions)
                appendLine("SUBAGENTEOF")
                appendLine("echo '=== 子 Agent 任务已创建 ==='")
                appendLine("echo \"任务: $task\"")
                appendLine("echo \"指令已写入 /tmp/sub_agent_task.md\"")
                appendLine("echo '请在终端中完成任务后回复主 AI。'")
            }

            val wrappedParams = JsonObject().apply {
                addProperty("command", script)
                addProperty("description", task)
                addProperty("sessionName", "SubAgent-$task")
            }
            val result = execCaptureOutput(context, termuxService, wrappedParams)

            SkillExecutionResult(
                result.success,
                result.message,
                SkillCardData(
                    skillType = SkillType.SUB_AGENT,
                    title = "子 Agent: $task",
                    description = "子会话已创建，等待执行",
                    status = SkillStatus.COMPLETED,
                    output = result.skillCard?.output ?: "子 Agent 任务已创建"
                )
            )
        }
    }

    private suspend fun execSearchAgent(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        val searchType = if (params.has("searchType")) params.get("searchType").asString else "name"
        val query = if (params.has("query")) params.get("query").asString else ""
        val searchPath = if (params.has("path")) params.get("path").asString else "~"
        val fileType = if (params.has("fileType")) params.get("fileType").asString else ""
        val resolvedPath = resolvePath(searchPath)

        val command = when (searchType) {
            "name" -> {
                val namePattern = if (query.isBlank()) "*" else "*${query}*"
                "find '$resolvedPath' -maxdepth 5 -name '$namePattern' -type f 2>/dev/null | head -200"
            }
            "content" -> {
                val typeFilter = if (fileType.isNotBlank()) "-name '*.$fileType'" else "-type f"
                "find '$resolvedPath' -maxdepth 5 $typeFilter -exec grep -l '$query' {} \\; 2>/dev/null | head -200"
            }
            "type" -> {
                "find '$resolvedPath' -maxdepth 5 -name '*.$fileType' -type f 2>/dev/null | head -200"
            }
            else -> "find '$resolvedPath' -maxdepth 5 -name '*${query}*' -type f 2>/dev/null | head -200"
        }

        val description = when (searchType) {
            "name" -> "搜索文件名: $query"
            "content" -> "搜索文件内容: $query"
            "type" -> "搜索文件类型: .$fileType"
            else -> "搜索: $query"
        }

        val wrappedParams = JsonObject().apply {
            addProperty("command", command)
            addProperty("description", description)
        }
        val result = execCaptureOutput(context, termuxService, wrappedParams)

        // 解析搜索结果并添加摘要
        val rawOutput = result.skillCard?.output ?: ""
        val lines = rawOutput.lines().filter { it.isNotBlank() && !it.startsWith("(命令") }
        val resultCount = lines.size

        val searchSummary = buildString {
            appendLine("=== 搜索结果 ===")
            appendLine("搜索类型: ${when(searchType) { "name" -> "文件名"; "content" -> "文件内容"; "type" -> "文件类型"; else -> "通用" }}")
            appendLine("搜索路径: $resolvedPath")
            appendLine("搜索关键词: ${query.ifBlank { "(全部)" }}")
            if (fileType.isNotBlank()) appendLine("文件类型过滤: .$fileType")
            appendLine("结果数量: $resultCount 个文件")
            appendLine()
            if (resultCount > 0) {
                appendLine("--- 搜索结果 ---")
                appendLine(rawOutput)
                appendLine()
                appendLine("--- 分析 ---")
                when {
                    resultCount == 0 -> appendLine("未找到匹配的文件。")
                    resultCount <= 10 -> appendLine("找到 $resultCount 个匹配文件，请查看上方列表。")
                    else -> appendLine("找到 $resultCount 个匹配文件（可能已截断），建议缩小搜索范围。")
                }
            } else {
                appendLine("未找到匹配的文件。请尝试：")
                appendLine("- 检查搜索路径是否正确")
                appendLine("- 使用更宽泛的关键词")
                appendLine("- 确认文件是否存在")
            }
        }

        SkillExecutionResult(
            result.success,
            result.message,
            SkillCardData(
                skillType = SkillType.SEARCH_AGENT,
                title = "搜索: $query",
                description = "$resultCount 个结果",
                status = if (result.success) SkillStatus.COMPLETED else SkillStatus.FAILED,
                output = searchSummary
            )
        )
    }

    private suspend fun execWebSearch(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        val query = if (params.has("query")) params.get("query").asString else ""
        val mode = if (params.has("mode")) params.get("mode").asString else "search"
        if (query.isBlank()) return@withContext SkillExecutionResult(false, "未指定搜索关键词或 URL")
        val maxResults = if (params.has("maxResults")) params.get("maxResults").asInt else 5

        val command = when (mode) {
            "fetch" -> {
                "curl -sL --max-time 30 '$query' 2>/dev/null | head -500"
            }
            else -> {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                "curl -sL --max-time 15 \"https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1\" 2>/dev/null | python3 -c \"" +
                        "import sys,json;" +
                        "d=json.load(sys.stdin);" +
                        "print(d.get('AbstractText','') or d.get('Heading','No results'));" +
                        "for t in d.get('RelatedTopics',[])[:$maxResults]:" +
                        " print(t.get('Text','') if isinstance(t,dict) else t)" +
                        "\" 2>/dev/null || echo '搜索失败，请检查网络连接'"
            }
        }

        val description = if (mode == "fetch") "抓取网页: $query" else "搜索: $query"
        val wrappedParams = JsonObject().apply {
            addProperty("command", command)
            addProperty("description", description)
        }
        execCaptureOutput(context, termuxService, wrappedParams)
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
            SkillType.APP_INSTALL,
            SkillType.APP_UNINSTALL,
            SkillType.PACKAGE_UNINSTALL,
            SkillType.SUB_AGENT,
            SkillType.SEARCH_AGENT,
            SkillType.WEB_SEARCH,
            SkillType.CONNECT_REMOTE_CONNECTION -> {
                card.sessionId?.let { handle ->
                    try {
                        val intent = Intent(context, TermuxActivity::class.java)
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        intent.putExtra("sessionHandle", handle.toLongOrNull() ?: 0L)
                        context.startActivity(intent)
                    } catch (_: Exception) { }
                }
                if (card.sessionId.isNullOrBlank()) {
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

    private suspend fun execScheduleTask(context: Context, params: JsonObject): SkillExecutionResult =
        withContext(Dispatchers.IO) {
            val task = if (params.has("task")) params.get("task").asString else ""
            val delayMinutes = if (params.has("delayMinutes")) params.get("delayMinutes").asLong else 0
            val repeat = if (params.has("repeat")) params.get("repeat").asString else "once"
            val command = if (params.has("command")) params.get("command").asString else null

            if (task.isBlank()) {
                return@withContext SkillExecutionResult(false, "定时任务缺少 task 参数")
            }

            val prefs: SharedPreferences = context.getSharedPreferences("ai_scheduled_tasks", Context.MODE_PRIVATE)
            val taskId = System.currentTimeMillis().toString()
            val taskJson = """{"id":"$taskId","task":"$task","delayMinutes":$delayMinutes,"repeat":"$repeat","command":${if (command != null) "\"$command\"" else "null"},"createdAt":${System.currentTimeMillis()}}"""

            prefs.edit().putString("task_$taskId", taskJson).apply()

            val repeatLabel = when (repeat) {
                "hourly" -> "每小时"
                "daily" -> "每天"
                else -> "单次"
            }
            val delayLabel = if (delayMinutes > 0) "${delayMinutes}分钟后" else "立即"

            SkillExecutionResult(
                true,
                "定时任务已创建：$task（$delayLabel，$repeatLabel）",
                SkillCardData(
                    skillType = SkillType.SCHEDULE_TASK,
                    title = "⏰ 定时任务",
                    description = "$delayLabel · $repeatLabel · $task",
                    status = SkillStatus.COMPLETED
                )
            )
        }

    private suspend fun execGetCurrentSession(
        context: Context,
        termuxService: TermuxService?
    ): SkillExecutionResult = withContext(Dispatchers.Main.immediate) {
        if (termuxService == null) return@withContext SkillExecutionResult(false, "Termux 服务未连接")
        return@withContext try {
            val sessions = termuxService.getTermuxSessions()
            val activity = context as? Activity
            val currentHandle = activity?.intent?.getLongExtra("sessionHandle", -1L) ?: -1L
            val currentSession = sessions.find {
                it.getTerminalSession().mHandle.toString() == currentHandle.toString()
            }
            val currentInfo = if (currentSession != null) {
                val ts = currentSession.getTerminalSession()
                "- ${ts.mSessionName ?: "Terminal"} [handle=${ts.mHandle}] 运行中=${ts.isRunning} (当前活跃)"
            } else {
                "(未在任何特定会话中)"
            }
            val allInfo = sessions.joinToString("\n") {
                val ts = it.getTerminalSession()
                val marker = if (ts.mHandle.toString() == currentHandle.toString()) " ◀ 当前" else ""
                "- ${ts.mSessionName ?: "Terminal"} [handle=${ts.mHandle}] 运行中=${ts.isRunning}$marker"
            }
            val output = if (sessions.isEmpty()) "当前无运行会话" else "当前活跃会话：\n$currentInfo\n\n全部会话：\n$allInfo"
            SkillExecutionResult(
                true, output,
                SkillCardData(
                    skillType = SkillType.GET_CURRENT_SESSION,
                    title = "当前会话",
                    description = if (currentSession != null) currentSession.getTerminalSession().mSessionName ?: "Terminal" else "无活跃会话",
                    status = SkillStatus.COMPLETED,
                    output = output,
                    sessionId = currentHandle.toString().takeIf { it != "-1" }
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "获取当前会话失败: ${e.message}")
        }
    }

    private suspend fun execClipboardRead(context: Context): SkillExecutionResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            if (text.isBlank()) {
                SkillExecutionResult(
                    true, "剪贴板为空",
                    SkillCardData(
                        skillType = SkillType.CLIPBOARD_READ,
                        title = "剪贴板",
                        description = "剪贴板当前为空",
                        status = SkillStatus.COMPLETED,
                        output = "(剪贴板为空)",
                        clipboardContent = ""
                    )
                )
            } else {
                val truncated = if (text.length > 5000) text.take(5000) + "...(已截断)" else text
                SkillExecutionResult(
                    true, "已读取剪贴板",
                    SkillCardData(
                        skillType = SkillType.CLIPBOARD_READ,
                        title = "剪贴板内容",
                        description = "长度: ${text.length} 字符",
                        status = SkillStatus.COMPLETED,
                        output = truncated,
                        clipboardContent = text
                    )
                )
            }
        } catch (e: Exception) {
            SkillExecutionResult(false, "读取剪贴板失败: ${e.message}")
        }
    }

    private suspend fun execClipboardWrite(
        context: Context,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        val content = if (params.has("content")) params.get("content").asString else ""
        if (content.isBlank()) return@withContext SkillExecutionResult(false, "写入内容为空")
        return@withContext try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Termux Agent", content))
            SkillExecutionResult(
                true, "已写入剪贴板",
                SkillCardData(
                    skillType = SkillType.CLIPBOARD_WRITE,
                    title = "已写入剪贴板",
                    description = "长度: ${content.length} 字符",
                    status = SkillStatus.COMPLETED,
                    clipboardWriteContent = content.take(200)
                )
            )
        } catch (e: Exception) {
            SkillExecutionResult(false, "写入剪贴板失败: ${e.message}")
        }
    }

    private suspend fun execGetDeviceStatus(
        context: Context,
        termuxService: TermuxService?,
        params: JsonObject
    ): SkillExecutionResult = withContext(Dispatchers.IO) {
        val infoType = if (params.has("infoType")) params.get("infoType").asString else "all"

        val build = StringBuilder()
        var hasAnyData = false

        if (infoType == "battery" || infoType == "all") {
            val batteryInfo = queryBatteryStatus(context)
            if (batteryInfo.isNotBlank()) hasAnyData = true
            build.appendLine("=== 电池状态 ===")
            build.appendLine(batteryInfo.ifBlank { "不可用" })
            build.appendLine()
        }

        if (infoType == "network" || infoType == "all") {
            val networkInfo = queryNetworkStatus(context)
            if (networkInfo.isNotBlank()) hasAnyData = true
            build.appendLine("=== 网络状态 ===")
            build.appendLine(networkInfo.ifBlank { "不可用" })
            build.appendLine()
        }

        if (infoType == "location" || infoType == "all") {
            val locationInfo = queryLocationStatus(context)
            if (locationInfo.isNotBlank()) hasAnyData = true
            build.appendLine("=== 位置信息 ===")
            build.appendLine(locationInfo.ifBlank { "不可用（缺少位置权限）" })
            build.appendLine()
        }

        if (infoType != "battery" && infoType != "network" && infoType != "location" && infoType != "all") {
            return@withContext SkillExecutionResult(
                false,
                "未知的 infoType: $infoType（支持: battery, network, location, all）"
            )
        }

        val result = build.toString().trimEnd()

        SkillExecutionResult(
            success = hasAnyData,
            message = if (hasAnyData) "设备状态查询成功" else "设备状态查询无结果",
            SkillCardData(
                skillType = SkillType.GET_DEVICE_STATUS,
                title = "📱 设备状态",
                description = result.take(300),
                output = result,
                status = if (hasAnyData) SkillStatus.COMPLETED else SkillStatus.FAILED
            )
        )
    }

    private fun queryBatteryStatus(context: Context): String {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent == null) return "无法获取电池信息"

            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            val technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)

            val pct = if (level >= 0 && scale > 0) "%.1f%%".format(level * 100.0 / scale) else "未知"
            val statusStr = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
                BatteryManager.BATTERY_STATUS_FULL -> "已满"
                else -> "未知"
            }
            val pluggedStr = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_AC -> "AC 交流电"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "无线"
                else -> "未连接"
            }

            buildString {
                appendLine("电量: $pct")
                appendLine("状态: $statusStr")
                appendLine("充电方式: $pluggedStr")
                if (temperature >= 0) appendLine("温度: ${temperature / 10.0}°C")
                if (voltage >= 0) appendLine("电压: ${voltage}mV")
                if (!technology.isNullOrBlank()) appendLine("技术: $technology")
            }
        } catch (e: Exception) {
            "查询电池状态失败: ${e.message}"
        }
    }

    @Suppress("DEPRECATION")
    private fun queryNetworkStatus(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork
                val caps = network?.let { cm.getNetworkCapabilities(it) }
                if (caps == null) return "无网络连接"

                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝移动数据"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "蓝牙"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    else -> "其他"
                }
                val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val validated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) else connected

                buildString {
                    appendLine("类型: $type")
                    appendLine("已连接: ${if (connected) "是" else "否"}")
                    appendLine("网络可用: ${if (validated) "是" else "否"}")
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                        appendLine("非计费网络: 是")
                }
            } else {
                val info = cm.activeNetworkInfo
                if (info == null || !info.isConnected) return "无网络连接"

                buildString {
                    appendLine("类型: ${info.typeName}")
                    appendLine("已连接: 是")
                    appendLine("网络可用: ${if (info.isAvailable) "是" else "否"}")
                    if (info.subtypeName.isNotBlank()) appendLine("子类型: ${info.subtypeName}")
                }
            }
        } catch (e: Exception) {
            "查询网络状态失败: ${e.message}"
        }
    }

    private fun queryLocationStatus(context: Context): String {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val networkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            buildString {
                appendLine("GPS 开关: ${if (gpsEnabled) "已开启" else "已关闭"}")
                appendLine("网络定位开关: ${if (networkEnabled) "已开启" else "已关闭"}")

                val lastKnown = try {
                    lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                } catch (_: SecurityException) { null }

                if (lastKnown != null) {
                    appendLine("最后位置: ${lastKnown.latitude}, ${lastKnown.longitude}")
                    appendLine("精度: ${lastKnown.accuracy}m")
                    val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(lastKnown.time))
                    appendLine("时间: $time")
                } else {
                    appendLine("无已知位置（可能缺少位置权限或从未请求过位置）")
                }
            }
        } catch (e: SecurityException) {
            "缺少位置访问权限（ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION）"
        } catch (e: Exception) {
            "查询位置失败: ${e.message}"
        }
    }
}

/** ---------- AI API 调用器 ---------- */

object AiApiClient {

    suspend fun chat(
        config: AiProviderConfig,
        messages: List<OpenAiMessage>
    ): ChatCompletionResponse = withContext(Dispatchers.IO) {
        // 本地大模型：走设备端 llama.cpp 推理
        if (config.provider == "local") {
            return@withContext AiLocalModel.completeLocal(config, messages)
        }
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
    ): Flow<StreamChunk> {
        // 本地大模型：走设备端 llama.cpp 流式推理
        if (config.provider == "local") {
            return AiLocalModel.chatStreamLocal(config, messages, isCancelled)
        }
        return flow {
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
