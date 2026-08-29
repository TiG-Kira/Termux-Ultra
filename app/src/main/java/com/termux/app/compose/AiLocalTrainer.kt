package com.termux.app.compose

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.termux.app.activities.AiTermuxActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.atomic.AtomicBoolean

// --------- 事件类型（发给 UI 的更新流） ---------
sealed class LocalTrainerEvent {
    /** 状态变更：running / paused / finished / error */
    data class StatusChanged(val status: String, val message: String? = null) : LocalTrainerEvent()
    /** 预估剩余时间更新 */
    data class EtaUpdated(val remainingRounds: Int, val avgRoundMs: Long, val etaText: String) : LocalTrainerEvent()
    /** 流程日志（发给「详细训练流程」区） */
    data class Step(val roundIndex: Int, val title: String, val detail: String) : LocalTrainerEvent()
    /** 老师出题事件（用于「完整对话」区） */
    data class TeacherQuestion(val roundIndex: Int, val text: String) : LocalTrainerEvent()
    /** 学生回答事件 */
    data class StudentAnswer(val roundIndex: Int, val text: String, val durationMs: Long) : LocalTrainerEvent()
    /** 老师评分&建议事件 */
    data class TeacherCritique(
        val roundIndex: Int, val score: Int, val critique: String, val memoryPatch: String,
        val durationMs: Long
    ) : LocalTrainerEvent()
    /** 一轮完成（已写入记忆） */
    data class RoundDone(val round: LocalTrainRound, val learnedNowCount: Int) : LocalTrainerEvent()
    /** 发生错误（不致命，会进入 pause） */
    data class ErrorOccurred(val roundIndex: Int, val message: String) : LocalTrainerEvent()
    /** 训练会话快照更新（每轮结束后发） */
    data class SessionSnapshot(val session: LocalTrainSession) : LocalTrainerEvent()
    /** 等待用户手动评分（仅无在线老师场景发射） */
    data class WaitingForUserRating(
        val roundIndex: Int,
        val question: String,
        val studentAnswer: String,
        val suggestedScore: Int,
        val suggestedCritique: String,
        val suggestedMemoryPatch: String
    ) : LocalTrainerEvent()
}

/** UI 端评分回传结果（引擎内部订阅，不直接暴露给 UI 渲染） */
internal data class UserRatingProvided(
    val roundIndex: Int,
    val score: Int,
    val critique: String,
    val memoryPatch: String
)


/** 本地模型训练引擎（System Prompt 蒸馏迭代） */
object AiLocalTrainer {

    /** UI → 引擎：回传用户手动评分的通道 */
    internal val userRatingChannel = kotlinx.coroutines.channels.Channel<UserRatingProvided>(
        capacity = kotlinx.coroutines.channels.Channel.BUFFERED
    )
    /** UI 调用：提交手动评分 */
    fun provideUserRating(roundIndex: Int, score: Int, critique: String, memoryPatch: String) {
        val v = UserRatingProvided(roundIndex, score.coerceIn(0,100), critique.trim(), memoryPatch.trim())
        userRatingChannel.trySend(v)
    }


    private const val TAG = "AiLocalTrainer"
    private const val DEFAULT_ROUNDS = 10

    /** 与在线老师对话（非训练时，用于讨论训练方向） */
    suspend fun chatWithTeacher(context: Context, userMessage: String): String {
        val cfg = AiTermuxPrefs.getFallbackOnlineConfig(context)
        val providerCfg = AiProviderConfig(
            provider = "custom",
            apiKey = cfg.apiKey,
            apiBaseUrl = cfg.baseUrl,
            model = cfg.model,
            temperature = cfg.temperature
        )
        val history = AiTermuxPrefs.getTeacherChatHistory(context)
        val teacherSys = """你是 Termux Agent 本地大模型的训练老师。用户现在不在训练中，而是想和你讨论训练方向。
请根据用户的需求，给出具体的训练建议、应该出什么样的题目、哪些技能或知识点需要重点练习。
如果用户说的方向很具体（比如"我想练习权限控制"），请给出 3-5 个具体的训练建议。
用中文回答。"""
        val msgs = mutableListOf<OpenAiMessage>()
        msgs.add(OpenAiMessage("system", teacherSys))
        // 加入之前的对话历史（最近 10 轮）
        history.takeLast(20).forEach { msgs.add(OpenAiMessage(it.role, it.content)) }
        msgs.add(OpenAiMessage("user", userMessage))
        
        val resp = AiApiClient.chat(context, providerCfg, msgs)
        val reply = resp.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        
        // 保存对话历史
        AiTermuxPrefs.appendTeacherChatHistory(context, "user", userMessage)
        AiTermuxPrefs.appendTeacherChatHistory(context, "assistant", reply)
        
        return reply
    }

    /** 获取与老师的对话历史 */
    fun getTeacherChatHistory(context: Context): List<OpenAiMessage> {
        return AiTermuxPrefs.getTeacherChatHistory(context)
    }

    /** 清除与老师的对话历史 */
    fun clearTeacherChatHistory(context: Context) {
        AiTermuxPrefs.clearTeacherChatHistory(context)
    }

    /** 启动/继续训练。若 session.status=="paused"，从下一轮继续；否则新开 */
    fun runTraining(
        context: Context,
        session: LocalTrainSession,
        isCancelled: () -> Boolean
    ): Flow<LocalTrainerEvent> {
        return flow {
            val onlineReady = AiTermuxPrefs.isFallbackOnlineConfigReady(context)
            val autoTeacher = session.teacher == "online_fallback" && onlineReady
            Log.i(TAG, "runTraining: teacher=${session.teacher}, onlineReady=$onlineReady, autoTeacher=$autoTeacher")

            emit(LocalTrainerEvent.StatusChanged("running", if (autoTeacher) "在线老师全自动训练中" else "半自动训练（无在线评分）"))
            emit(LocalTrainerEvent.Step(-1, "初始化训练", if (autoTeacher) "检测到备用在线大模型可用，将全自动：出题→本地回答→评分→记忆→循环" else "无备用在线模型：仅自动出题和本地回答，评分需手动查看"))

            session.status = "running"
            AiTermuxPrefs.saveLastTrainSession(context, session)
            emit(LocalTrainerEvent.SessionSnapshot(session))

            var roundCursor = session.rounds.size
            val targetRounds = session.targetRounds

            loop@ while (roundCursor < targetRounds && !isCancelled() && session.status == "running") {
                val t0 = System.currentTimeMillis()
                val thisIdx = roundCursor + 1
                emit(LocalTrainerEvent.Step(thisIdx, "第 ${thisIdx}/$targetRounds 轮：开始", ""))

                // ===== Step A: 在线老师出题 =====
                emit(LocalTrainerEvent.Step(thisIdx, "Step A/4：在线老师出题", "构造多样化题目（技能/限制/场景）…"))
                val question: String = runCatching {
                    val qPrompt = buildQuestionPrompt(thisIdx, targetRounds, AiTermuxPrefs.getLearnedMemoryBlock(context))
                    if (autoTeacher) {
                        callOnlineNonStream(context, qPrompt, expectJsonKey = "question").orEmpty()
                    } else {
                        generateManualQuestion(thisIdx, targetRounds)
                    }
                }.getOrElse { e ->
                    Log.e(TAG, "出题失败", e)
                    emit(LocalTrainerEvent.ErrorOccurred(thisIdx, "出题失败: ${e.message}"))
                    generateManualQuestion(thisIdx, targetRounds)
                }

                emit(LocalTrainerEvent.TeacherQuestion(thisIdx, question))
                emit(LocalTrainerEvent.Step(thisIdx, "Step A/4：题目已生成", question.take(120) + if (question.length > 120) "…" else ""))

                // ===== Step B: 本地模型回答 =====
                emit(LocalTrainerEvent.Step(thisIdx, "Step B/4：本地模型回答", "等待本地模型推理…"))
                val answerT0 = System.currentTimeMillis()
                val answer: String = runCatching {
                    callLocalComplete(context, listOf(OpenAiMessage("user", question)))
                }.getOrElse { e ->
                    Log.e(TAG, "本地模型回答失败", e)
                    emit(LocalTrainerEvent.ErrorOccurred(thisIdx, "本地模型回答失败: ${e.message}"))
                    "（本地模型不可用，跳过本轮）"
                }
                val answerDuration = System.currentTimeMillis() - answerT0

                emit(LocalTrainerEvent.StudentAnswer(thisIdx, answer, answerDuration))
                emit(LocalTrainerEvent.Step(thisIdx, "Step B/4：回答完成", answer.take(120) + if (answer.length > 120) "…" else ""))

                // ===== Step C: 评分 =====
                var score: Int = 0
                var critique: String = ""
                var memoryPatch: String = ""

                if (autoTeacher) {
                    emit(LocalTrainerEvent.Step(thisIdx, "Step C/4：在线老师评分", "正在评分并提取教训…"))
                    val cT0 = System.currentTimeMillis()
                    val critResult = runCatching {
                        val cPrompt = buildCritiquePrompt(thisIdx, question, answer, AiTermuxPrefs.getLearnedMemoryBlock(context))
                        callOnlineNonStreamForCritique(context, cPrompt)
                    }.getOrElse { e ->
                        Log.e(TAG, "评分失败", e)
                        emit(LocalTrainerEvent.ErrorOccurred(thisIdx, "评分失败: ${e.message}"))
                        CritiqueResult(score = 0, critique = "评分失败", memoryPatch = "")
                    }
                    score = critResult.score
                    critique = critResult.critique
                    memoryPatch = critResult.memoryPatch
                    val cDuration = System.currentTimeMillis() - cT0
                    emit(LocalTrainerEvent.TeacherCritique(thisIdx, score, critique, memoryPatch, cDuration))
                } else {
                    // 半自动：用 suggestRating 生成建议，等用户评分
                    val suggested = suggestRating(question, answer)
                    emit(LocalTrainerEvent.WaitingForUserRating(
                        roundIndex = thisIdx,
                        question = question,
                        studentAnswer = answer,
                        suggestedScore = suggested.first,
                        suggestedCritique = suggested.second,
                        suggestedMemoryPatch = suggested.third
                    ))
                    emit(LocalTrainerEvent.Step(thisIdx, "Step C/4：等待用户评分", "请在弹窗中评分"))

                    // 使用建议值作为默认（用户可以在 UI 中覆盖）
                    score = suggested.first
                    critique = suggested.second
                    memoryPatch = suggested.third
                }

                // ===== Step D: 追加记忆 =====
                emit(LocalTrainerEvent.Step(thisIdx, "Step D/4：追加记忆", if (memoryPatch.isNotBlank()) memoryPatch.take(100) + "…" else "（无新教训）"))
                if (memoryPatch.isNotBlank()) {
                    AiTermuxPrefs.appendLearnedMemory(context, memoryPatch)
                }

                // 更新 session
                val durationMs = System.currentTimeMillis() - t0
                val round = LocalTrainRound(
                    roundIndex = thisIdx,
                    question = question,
                    studentAnswer = answer,
                    score = score,
                    critique = critique,
                    memoryPatch = memoryPatch,
                    durationMs = durationMs,
                    status = "done"
                )
                session.rounds.add(round)
                session.status = if (roundCursor + 1 >= targetRounds) "finished" else "running"
                AiTermuxPrefs.saveLastTrainSession(context, session)

                val learnedCount = if (memoryPatch.isNotBlank()) 1 else 0
                emit(LocalTrainerEvent.RoundDone(round, learnedCount))
                emit(LocalTrainerEvent.SessionSnapshot(session))

                val eta = updateEta(session, roundCursor + 1, t0, targetRounds)
                if (eta != null) emit(eta)

                roundCursor++
            }

            val doneCount = session.rounds.count { it.status == "done" }
            val memLen = AiTermuxPrefs.getLearnedMemoryBlock(context).length
            emit(LocalTrainerEvent.StatusChanged("finished", "训练完成！已完成 $doneCount 轮；记忆块长度=$memLen chars。所有教训会自动追加到 System Prompt 尾部生效。"))
        }.flowOn(Dispatchers.IO)
    }

    // -------- 内部辅助 --------
    fun updateEta(
        session: LocalTrainSession, finishedRounds: Int, roundStart: Long, target: Int
    ): LocalTrainerEvent.EtaUpdated? {
        val remain = (target - finishedRounds).coerceAtLeast(0)
        if (finishedRounds <= 0) return null
        val recentDur = System.currentTimeMillis() - roundStart
        // 滚动平均：用历史 avg 或当前单轮做初值
        val avgMs = if (session.avgRoundMs <= 0L) recentDur
        else (session.avgRoundMs * 0.7 + recentDur * 0.3).toLong()
        session.avgRoundMs = avgMs
        val totalMs = avgMs * remain
        val sec = totalMs / 1000
        val mm = sec / 60
        val ss = sec % 60
        val etaText = if (remain <= 0) "已完成" else "约 ${mm}分${ss}秒（剩余 $remain 轮，每轮约 ${avgMs/1000}s）"
        return LocalTrainerEvent.EtaUpdated(remain, avgMs, etaText)
    }

    fun buildQuestionPrompt(idx: Int, total: Int, memory: String): List<OpenAiMessage> {
        // Termux Agent 核心守则摘要（摘自完整 System Prompt，供教师出题覆盖）
        val agentRules = """
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📜 Termux Agent 系统守则摘要（请据此出题覆盖第 3/4 类题型）
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

【身份与工作方式】
- 我是 Termux Agent，通过输出技能卡片操控 Termux 执行操作
- 我本身不能执行命令、看不到文件、没有执行结果
- 真实唯一来源是 [技能结果]，不是我编造的

【技能调用格式】
- 必须使用 <tool_call> XML 格式调用技能
- 禁止编造结果：输出卡片后不能声称已执行
- 类别A技能（NEW_SESSION、RUN_COMMAND 等）只生成卡片，需用户点击
- 类别B技能（CLOSE_SESSION、FILE_WRITE 等）立即执行
- 类别C技能（FILE_READ、CAPTURE_OUTPUT 等）有真实返回值

【Agent 绝对禁令】
1. 禁止编造技能执行结果
2. 禁止伪造 [技能结果] 内容
3. 禁止声称操作已完成（卡片需点击的）
4. 技能卡片只生成一次，不能重复

【Linux / Android 终端安全守则】
- 高危命令（dd、rm -rf /、mkfs）必须多次警告并二次确认
- 文件操作仅限 /data/data/com.termux/ 路径沙盒
- 禁止 ".." 路径逃逸、禁止 /etc /proc /sys 等系统目录
- 命令注入防护：用户输入作为参数时用单引号包裹
- 不假设包已安装/文件存在/进程运行，用技能验证
""".trimIndent()

        val system = """你是 Termux Android 终端场景下的 AI 训练老师。你的工作是：给本地学生模型出 1 道题目。
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📌 出题题型必须覆盖以下 4 大类（尽量均匀分布）：
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

第 1 类 - Linux / Termux 命令操作（基础能力）
  命令解释 / 命令查错 / 操作步骤 / 权限问题 / 软件包管理 / 文件处理
  脚本改错 / 环境变量 / 管道与重定向 / 进程管理 / 网络工具

第 2 类 - Shell 脚本与安全（中级能力）
  bash 循环/条件/函数/正则 / 文件权限与安全 / 路径沙盒 / 命令注入防护
  进程信号与管理 / 管道与重定向进阶

第 3 类 - Termux Agent 技能调用格式（Agent 核心能力）
  <tool_call> XML 格式 vs skill 代码块 / 三类技能区别与使用时机
  类别A卡片需用户点击 / 类别B立即执行 / 类别C有返回值
  禁止编造结果 / 禁止声称已执行 / 禁止重复生成相同卡片

第 4 类 - Agent 安全守则 / 边界 / 错误处理（Agent 安全能力）
  高危命令警告与二次确认 / 路径沙盒 / 命令注入防护
  环境状态不假设 / 重复执行防护 / 空输出处理 / 连续失败处理
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

具体要求：
1. 题目必须是中文，贴近真实使用场景
2. 严格只输出 JSON，key 必须包含：{"question": "..."}，不要 Markdown 代码块，不要额外说明
3. 根据已学过的教训记忆，针对性出薄弱环节（如果记忆显示学生编造命令参数，就多出查命令参数是否正确的题）
4. 参考下方 Termux Agent 守则摘要，确保第 3/4 类题目覆盖到位

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
当前轮次：第 $idx / $total 轮
已学过的教训记忆：
${memory.ifBlank { "(暂无)" }}
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

$agentRules
"""
        return listOf(
            OpenAiMessage("system", system),
            OpenAiMessage("user", "请出第 $idx 题（从上述 4 类中任选一类，保证覆盖面）。严格输出 JSON {\"question\": \"...\"}。")
        )
    }

    fun generateManualQuestion(idx: Int, total: Int): String {
        val bank = listOf(
            "如何查看当前目录下按大小排序的前 10 个文件？给出命令。",
            "用户运行 pkg install python 报错 E: Unable to locate package，请分析 3 种可能原因和解决办法。",
            "写一个 bash for 循环：把当前目录所有 .txt 文件重命名为 .txt.bak",
            "如何在 Termux 中查看监听的端口和对应的进程？",
            "chmod 755 和 chmod 644 分别是什么权限？分别适合文件还是目录？",
            "如何 grep 递归搜索当前目录，并且只显示匹配的文件名？",
            "为什么执行 ./script.sh 提示 Permission denied？给出两种解决方法。",
            "如何用 tar 打包 /data/data/com.termux/files/home/backup 目录并同时 gzip 压缩？",
            "ps aux 输出中的 VSZ 和 RSS 分别是什么意思？",
            "如何把 stderr 和 stdout 都重定向到 app.log 同时仍然在终端看到输出？",
        )
        val q = bank[(idx - 1) % bank.size]
        return "$q（第 $idx / $total 题）"
    }

    data class CritiqueResult(val score: Int, val critique: String, val memoryPatch: String)

    fun buildCritiquePrompt(roundIndex: Int, question: String, studentAnswer: String, memoryBlock: String): List<OpenAiMessage> {
        val sys = """你是 Termux 终端场景下严厉但教学质量高的 AI 训练批改老师。
你的角色是：评估【本地学生模型】对题目的回答，评分并输出批改建议，然后产出一段「教训记忆」追加到学生的 System Prompt，让学生下一次不再犯同样的错误。

输出必须严格是 JSON，key：
- score: 0..100 整数
- critique: 中文批改，包含：哪里对、哪里错、哪里啰嗦、哪里编造了命令参数、正确答案应是什么
- memoryPatch: 中文教训条目，格式为 "• 关于 <topic>：<规则/教训>"，每一条以 • 开头，多条用换行分隔；如果这题学生回答完美得 95+ 分，这个 key 给空字符串，不要乱塞。

严禁：不要编造错误的命令参数来纠正学生；如果你自己拿不准参数，就明确说「需要 man 命令确认」。

已存在的教训记忆（避免重复写完全相同的条目）：
${memoryBlock.ifBlank { "(暂无)" }}
"""
        val user = """【第 $roundIndex 轮 待批改】
题目：$question

学生回答：
$studentAnswer
"""
        return listOf(OpenAiMessage("system", sys), OpenAiMessage("user", user))
    }

    /** 在线模型非流式调用，返回某个 JSON key 的值（简单提取） */
    private suspend fun callOnlineNonStream(context: Context, msgs: List<OpenAiMessage>, expectJsonKey: String): String? {
        val cfg = AiTermuxPrefs.getFallbackOnlineConfig(context)
        val providerCfg = AiProviderConfig(
            provider = "custom",
            apiKey = cfg.apiKey,
            apiBaseUrl = cfg.baseUrl,
            model = cfg.model,
            temperature = cfg.temperature
        )
        val resp = runCatching { AiApiClient.chat(context, providerCfg, msgs) }.getOrElse {
            Log.e(TAG, "callOnlineNonStream 失败: ${it.message}")
            throw it
        }
        val txt = resp.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        Log.d(TAG, "callOnlineNonStream 原始输出=${txt.take(300)}")
        if (txt.isBlank()) return null
        return extractJsonField(txt, expectJsonKey) ?: stripFirstJsonString(txt)
    }

    /** 在线批改调用：解析 critique / score / memoryPatch 三个 JSON 字段 */
    private suspend fun callOnlineNonStreamForCritique(context: Context, msgs: List<OpenAiMessage>): CritiqueResult {
        val cfg = AiTermuxPrefs.getFallbackOnlineConfig(context)
        val providerCfg = AiProviderConfig(
            provider = "custom",
            apiKey = cfg.apiKey,
            apiBaseUrl = cfg.baseUrl,
            model = cfg.model,
            temperature = cfg.temperature
        )
        val resp = runCatching { AiApiClient.chat(context, providerCfg, msgs) }.getOrElse {
            Log.e(TAG, "在线批改失败: ${it.message}")
            return CritiqueResult(0, "在线模型异常：${it.message}", "")
        }
        val txt = resp.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        Log.d(TAG, "批改模型输出前500chars: ${txt.take(500)}")
        val score = (extractJsonField(txt, "score")?.toIntOrNull() ?: 0).coerceIn(0, 100)
        val critique = extractJsonField(txt, "critique").orEmpty().ifBlank { stripFirstJsonString(txt) }
        val memoryPatch = extractJsonField(txt, "memoryPatch").orEmpty()
        return CritiqueResult(score, critique, memoryPatch)
    }

    /** 本地模型非流式回答 — 直接调 AiLocalModel.completeLocal，绝不 fallback 到在线 */
    private suspend fun callLocalComplete(context: Context, msgs: List<OpenAiMessage>): String {
        val cfg = AiTermuxPrefs.getConfig(context).providerConfig
            .copy(provider = "local")  // 强制本地，忽略 fallback
        val resp = try {
            // 用 completeLocalViaServer：优先 llama-server HTTP（与聊天页一致），server 不可用时回退 llama-cli
            // 比 completeLocal 多了稳定的 ChatML 解析和 server 模式加速
            AiLocalModel.completeLocalViaServer(cfg, msgs)
        } catch (t: Throwable) {
            Log.e(TAG, "callLocalComplete 异常: ${t.message}")
            throw t
        }
        if (resp.error != null) {
            throw IllegalStateException(resp.error.message ?: "本地模型返回错误")
        }
        val text = resp.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        if (text.isBlank()) throw IllegalStateException("本地模型返回为空")
        return text
    }

    // ----------------- 通用 JSON 字段提取 -----------------
    fun extractJsonField(text: String, key: String): String? {
        // 宽松提取 "key": "value" 或 "key": number 或 "key": null
        val qKey = Regex.escape("\"$key\"")
        val re = Regex("""$qKey\s*:\s*("((?:[^"\\]|\\.)*)"|(-?\d+)|null)""")
        val m = re.find(text) ?: return null
        val g = m.groupValues
        if (g[2].isNotEmpty()) return unescapeJson(g[2])  // string value
        if (g[3].isNotEmpty()) return g[3]               // number
        return ""
    }
    fun unescapeJson(s: String): String {
        var out = s
        out = out.replace("\\\"", "\"")
        out = out.replace("\\n", "\n")
        out = out.replace("\\t", "\t")
        out = out.replace("\\r", "\r")
        out = out.replace("\\\\", "\\")
        return out
    }
    fun stripFirstJsonString(s: String): String {
        // 兜底：尝试取第一段被引号包围的大段中文
        val re = Regex(""""([^"]{10,})"""")
        return re.find(s)?.groupValues?.get(1) ?: s
    }
    // -------- 启发式评分参考（无在线老师时，给用户一个建议分+理由+建议记忆） --------
    internal fun suggestRating(question: String, answer: String): Triple<Int, String, String> {
        val q = question
        val a = answer.trim()
        if (a.isBlank()) return Triple(0, "回答为空：本地模型没有输出任何内容。请检查本地模型是否正常。", "• 空回答时请重试本地推理，若持续为空请更换模型或检查模型文件")

        var score = 60
        val reasons = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val mems = mutableListOf<String>()

        // 长度
        val len = a.length
        if (len < 15) { score -= 20; reasons.add("回答过短($len chars)，大概率没有解决问题"); mems.add("不要给出空泛的一字/一句回答，除非明确问是否；否则应给出命令+解释") }
        else if (len > 2500) { score -= 10; reasons.add("回答过于冗长"); mems.add("回答需要简洁：先给关键命令，再简短解释，默认不超过一屏") }
        else { score += 5; reasons.add("篇幅适中") }

        // 是否包含命令
        val cmdKeywords = listOf("apt", "pkg ", "ls ", "chmod", "grep", "find ", "tar ", "ps ", "du -", "df -", "cat ", "mv ", "cp ", "chown", "mkdir", "rm -")
        val hasCmd = a.contains("```") || cmdKeywords.any { it in a }
        if (hasCmd) score += 15 else { score -= 15; warnings.add("回答里几乎没有命令；Termux 场景题目通常要求给具体命令") }

        // 危险命令警告缺失
        val dangerous = listOf("rm -rf /", "rm -rf ~", ":(){ :|:& };:", "mkfs", "dd of=/dev/")
        val foundDanger = dangerous.filter { it in a }
        if (foundDanger.isNotEmpty()) {
            score -= 40
            warnings.add("出现极度危险命令：${foundDanger.joinToString()}，且未加警告")
            mems.add("输出任何涉及 rm -rf /dd of=/dev/mkfs 等高危命令之前，必须先加一行【警告：此命令有破坏性，请确认路径】")
        }

        // 给出很多参数但未提示 man 确认
        val flagsCount = Regex("""(^|\s)(-[a-zA-Z]+|--[a-zA-Z][\w-]*)""").findAll(a).count()
        val hasManHint = a.contains("man ") || a.contains("--help") || a.contains("请确认参数") || a.contains("实际 man 手册")
        if (flagsCount >= 3 && !hasManHint) {
            score -= 10
            warnings.add("列出了 ≥3 个命令参数但未提示 man 确认，可能编造参数")
            mems.add("给出多个(≥3)命令参数时，末尾加一句：以上参数请使用 man 或 --help 实际确认，避免编造参数")
        }

        // 是否有中文解释
        val chineseCount = a.count { it.code in 0x4E00..0x9FFF }
        val chineseRatio = chineseCount.toDouble() / len.coerceAtLeast(1)
        if (hasCmd && chineseRatio < 0.10) { score -= 8; warnings.add("只有命令没有中文解释，用户不知道在干嘛"); mems.add("给出命令后，必须追加 1-2 句中文解释：命令做什么、为什么这样写、输出会是什么") }
        else if (chineseRatio > 0.05) { score += 5; reasons.add("包含中文解释") }

        // 谦虚/谨慎
        val cautious = listOf("可能", "大概", "请确认", "建议先", "视情况", "通常", "如果失败可尝试")
        if (cautious.any { it in a }) score += 5 else score -= 3

        score = score.coerceIn(0, 100)

        val builder = StringBuilder()
        builder.append("【建议参考评分】$score / 100\n")
        builder.append("参考理由：\n")
        reasons.take(4).forEach { builder.append("  ✓ ").append(it).append('\n') }
        if (warnings.isNotEmpty()) {
            builder.append("需要改进：\n")
            warnings.forEach { builder.append("  ! ").append(it).append('\n') }
        }
        if (mems.isEmpty() && score >= 80) {
            builder.append("整体表现良好，可继续保持。是否要把本次优秀表现的要点固化到 System Prompt？（可在下方输入补丁）\n")
        }
        val suggestedMem = if (mems.isNotEmpty()) {
            mems.joinToString("\n")
        } else if (score >= 85) {
            val good = mutableListOf<String>()
            if (hasCmd) good.add("• 回答命令行题目时，请优先给出可复制运行的具体命令，再附上简短中文解释")
            good.add("• 如果命令中列出了 ≥3 个参数，请追加一句：请使用 man 或 --help 确认参数版本差异")
            good.joinToString("\n")
        } else ""
        return Triple(score, builder.toString().trim(), suggestedMem)
    }

}
