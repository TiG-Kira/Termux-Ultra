package com.termux.app.compose

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.io.File

/**
 * 脚本安全判定 - Agent 参与模式。
 *
 * 关键设计：
 * - 所有 IO + 正则检测 + 网络请求都在 Dispatchers.IO 上执行，绝不阻塞主线程
 * - 脚本大小硬上限 50KB，防止 OOM
 * - 超时（云端 10s / 本地 30s）自动 fallback 到本地检测
 */
object AgentScriptJudge {

    private const val TAG = "AgentScriptJudge"
    private const val TIMEOUT_CLOUD_MS = 10000L
    private const val TIMEOUT_LOCAL_MS = 30000L
    /** 脚本大小硬上限：超过此长度的脚本只做本地检测（50KB） */
    private const val MAX_SCRIPT_BYTES = 50 * 1024

    data class JudgeResult(
        val verdict: Verdict,
        val reason: String,
        val riskType: String? = null,
        val agentResponded: Boolean = false,
        val localDetections: List<RiskCommandDetector.ScriptDetectionResult> = emptyList()
    )

    enum class Verdict {
        SAFE, DANGEROUS, SKIP, TIMEOUT, ERROR
    }

    /** 一条 Agent 判定历史记录 */
    @Serializable
    data class JudgeHistoryEntry(
        val timestamp: Long,
        val scriptPath: String,
        val verdict: String,
        val reason: String,
        val riskType: String? = null,
        val provider: String = ""
    )

    private const val HISTORY_KEY = "agent_judge_history"
    private const val MAX_HISTORY = 50

    /** 记录 Agent 判定历史（仅 Agent 真正回复的判定） */
    fun recordHistory(context: Context, filePath: String, result: JudgeResult) {
        if (!result.agentResponded) return
        val cfg = try {
            AiTermuxPrefs.getConfig(context).providerConfig
        } catch (e: Exception) {
            null
        }
        val entry = JudgeHistoryEntry(
            timestamp = System.currentTimeMillis(),
            scriptPath = filePath,
            verdict = if (result.verdict == Verdict.DANGEROUS) "dangerous" else "safe",
            reason = result.reason,
            riskType = result.riskType,
            provider = cfg?.provider ?: ""
        )
        val prefs = context.getSharedPreferences("termux_preferences", Context.MODE_PRIVATE)
        val list = getHistory(context).toMutableList()
        list.add(0, entry)
        if (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        try {
            prefs.edit().putString(HISTORY_KEY, Json.encodeToString(list)).apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存判定历史失败: ${e.message}")
        }
    }

    /** 读取 Agent 判定历史（最新的在前） */
    fun getHistory(context: Context): List<JudgeHistoryEntry> {
        val prefs = context.getSharedPreferences("termux_preferences", Context.MODE_PRIVATE)
        val raw = prefs.getString(HISTORY_KEY, null) ?: return emptyList()
        return try {
            Json.decodeFromString<List<JudgeHistoryEntry>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 清空 Agent 判定历史 */
    fun clearHistory(context: Context) {
        context.getSharedPreferences("termux_preferences", Context.MODE_PRIVATE)
            .edit().remove(HISTORY_KEY).apply()
    }

    fun isAvailable(context: Context): Boolean {
        val prefs = context.getSharedPreferences("termux_preferences", Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean("agent_script_judge", false)
        if (!enabled) return false

        val level = RiskConfirmManager.getProtectionLevel(context)
        if (level == RiskConfirmManager.ProtectionLevel.OFF) return false

        val cfg = AiTermuxPrefs.getConfig(context).providerConfig
        if (cfg.apiKey.isNullOrBlank() && cfg.provider != "local") return false
        if (cfg.provider == "local" && !AiLocalModel.isLocalModelReady()) return false

        return true
    }

    /**
     * 判定指定脚本文件。
     * 读取 + 正则检测 + Agent 调用 全在 IO 线程执行，不阻塞调用线程。
     */
    fun judge(context: Context, filePath: String): JudgeResult {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            return JudgeResult(Verdict.SKIP, "脚本文件不存在或不可读")
        }
        // 大小硬上限：超过 50KB 只做本地检测
        if (file.length() > MAX_SCRIPT_BYTES) {
            Log.w(TAG, "脚本过大(${file.length()}B)，跳过 Agent 判定，仅本地检测")
            return judgeLocalOnly(context, filePath)
        }
        val content = try {
            file.readText(Charsets.UTF_8)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "读取脚本 OOM: ${oom.message}")
            return JudgeResult(Verdict.SKIP, "脚本读取失败：内存不足")
        } catch (e: Exception) {
            Log.e(TAG, "读取脚本异常: ${e.message}")
            return JudgeResult(Verdict.SKIP, "无法读取脚本内容")
        }
        return judgeContent(context, filePath, content)
    }

    /** 仅本地检测（Agent 不可用或脚本过大时 fallback） */
    private fun judgeLocalOnly(context: Context, filePath: String): JudgeResult {
        return try {
            val content = File(filePath).readText(Charsets.UTF_8).take(MAX_SCRIPT_BYTES)
            val expanded = RiskCommandDetector.expandShellVarsPublic(content)
            val detections = RiskCommandDetector.detectScript(expanded)
            JudgeResult(
                verdict = if (detections.isNotEmpty()) Verdict.DANGEROUS else Verdict.SAFE,
                reason = detections.firstOrNull()?.detection?.description ?: "Agent 判定跳过，使用本地检测",
                riskType = detections.firstOrNull()?.detection?.riskType?.displayName,
                agentResponded = false,
                localDetections = detections
            )
        } catch (e: Exception) {
            JudgeResult(Verdict.SKIP, "本地检测失败: ${e.message}")
        }
    }

    fun judgeContent(context: Context, filePath: String, content: String): JudgeResult {
        // 大小硬上限截断
        val trimmed = if (content.length > MAX_SCRIPT_BYTES) {
            content.take(MAX_SCRIPT_BYTES).also {
                Log.w(TAG, "内容过大(${content.length})，截断到 $MAX_SCRIPT_BYTES")
            }
        } else content

        if (!isAvailable(context)) {
            // 同步 fallback：Agent 不可用时直接本地检测，不走协程
            return try {
                val expanded = RiskCommandDetector.expandShellVarsPublic(trimmed)
                val detections = RiskCommandDetector.detectScript(expanded)
                JudgeResult(
                    verdict = if (detections.isNotEmpty()) Verdict.DANGEROUS else Verdict.SAFE,
                    reason = detections.firstOrNull()?.detection?.description ?: "Agent 判定未启用",
                    riskType = detections.firstOrNull()?.detection?.riskType?.displayName,
                    agentResponded = false,
                    localDetections = detections
                )
            } catch (e: Exception) {
                JudgeResult(Verdict.SKIP, "本地检测失败: ${e.message}")
            }
        }

        val cfg = AiTermuxPrefs.getConfig(context).providerConfig
        val timeoutMs = if (cfg.provider == "local") TIMEOUT_LOCAL_MS else TIMEOUT_CLOUD_MS

        // 关键：不能只用协程 withTimeout —— Agent 调用是阻塞 IO（HttpURLConnection 读 / 子进程 waitFor），
        // 协程取消无法中断阻塞调用，服务端会一直卡到阻塞调用结束（最长 120s+）。
        // 必须用独立线程 + Future.get 硬超时：超时后立即返回，放弃等待线程。
        val result = runWithHardTimeout(timeoutMs) {
            runBlocking(Dispatchers.IO) {
                try {
                    callAgentSafe(context, filePath, trimmed)
                } catch (oom: OutOfMemoryError) {
                    Log.e(TAG, "Agent 判定 OOM: ${oom.message}")
                    localOnlyResult(trimmed).copy(
                        verdict = Verdict.ERROR,
                        reason = "Agent 判定内存溢出，退回本地检测"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Agent 判定异常: ${e.message}")
                    localOnlyResult(trimmed).copy(
                        verdict = Verdict.ERROR,
                        reason = "Agent 判定失败: ${e.message}"
                    )
                }
            }
        }
        // 硬超时 → 本地检测兜底（本地检测已被限定在毫秒级，不会卡住）
        val finalResult = result ?: runBlocking(Dispatchers.IO) { localOnlyResult(trimmed) }.copy(
            verdict = Verdict.TIMEOUT,
            reason = "Agent 判定超时，已使用本地检测结果"
        )
        // 记录 Agent 判定历史（仅 Agent 真正回复的判定）
        recordHistory(context, filePath, finalResult)
        return finalResult
    }

    /**
     * 硬超时执行阻塞任务。
     *
     * 协程 withTimeout 无法中断阻塞调用（HttpURLConnection.read / Process.waitFor 等），
     * 只能靠独立线程 + Future.get(timeout) 做到真正超时。
     * 超时后该线程仍在后台运行（daemon），其结果被丢弃，服务端立即返回。
     */
    private fun <T> runWithHardTimeout(timeoutMs: Long, block: () -> T): T? {
        val future = java.util.concurrent.CompletableFuture<T>()
        Thread {
            try {
                future.complete(block())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }.apply {
            isDaemon = true
            name = "AgentScriptJudge-worker"
        }.start()
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "Agent 调用硬超时(${timeoutMs}ms)，放弃等待，使用本地检测兜底")
            null
        } catch (e: java.util.concurrent.ExecutionException) {
            Log.e(TAG, "Agent 调用异常: ${e.cause?.message ?: e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Agent 调用中断: ${e.message}")
            null
        }
    }

    /** 只做本地检测（在 IO 线程） */
    private suspend fun localOnlyResult(content: String): JudgeResult {
        val detections = withContext(Dispatchers.Default) {
            try {
                val expanded = RiskCommandDetector.expandShellVarsPublic(content)
                RiskCommandDetector.detectScript(expanded)
            } catch (e: Exception) {
                Log.e(TAG, "本地检测异常: ${e.message}")
                emptyList()
            }
        }
        return JudgeResult(
            verdict = if (detections.isNotEmpty()) Verdict.DANGEROUS else Verdict.SAFE,
            reason = detections.firstOrNull()?.detection?.description ?: "Agent 判定未启用",
            riskType = detections.firstOrNull()?.detection?.riskType?.displayName,
            agentResponded = false,
            localDetections = detections
        )
    }

    private suspend fun callAgentSafe(
        context: Context,
        filePath: String,
        content: String
    ): JudgeResult {
        val cfg = AiTermuxPrefs.getConfig(context).providerConfig

        // 本地检测也在 Default dispatcher 做，避免阻塞 IO
        val localDetections = withContext(Dispatchers.Default) {
            try {
                val expanded = RiskCommandDetector.expandShellVarsPublic(content)
                RiskCommandDetector.detectScript(expanded)
            } catch (e: Exception) {
                Log.e(TAG, "本地检测异常: ${e.message}")
                emptyList()
            }
        }

        val systemPrompt = """你是一个 Linux shell 脚本安全审计专家。用户要执行一个脚本，请判断它是否包含危险操作。

## 本地检测结果（仅供参考，可能有漏判或误判）
${if (localDetections.isEmpty()) "无" else localDetections.joinToString("\n") { "- 行${it.lineNumber}: ${it.lineContent.trim().take(80)}" }}

## 你的任务
- 仔细阅读脚本全文，识别：磁盘破坏、提权逃逸、远程连接（curl/wget 下载执行）、数据泄露、反调试、自修改等行为
- 特别注意 eval/source/动态执行、变量拼接命令、base64/hex 编码后的命令等绕过手法
- 如果看不出确切危险行为，判定为 safe

## 输出格式（严格 JSON，不要 markdown）
{"verdict": "safe" 或 "dangerous", "reason": "判定理由（1-2句中文）", "risk_type": "简短分类"}"""

        val userPrompt = """脚本路径: $filePath
脚本内容:
```
${content.take(12000)}
```"""

        val messages = listOf(
            OpenAiMessage(role = "system", content = systemPrompt),
            OpenAiMessage(role = "user", content = userPrompt)
        )

        // 直接使用 Agent 配置的 API URL + Key 发送 POST（短超时快速判定）。
        // 不复用 AiApiClient.chat：其 connect/read 超时高达 60s/120s，远超判定
        // 预算，是服务端被硬超时兜底、长时间卡住的根因。
        val text = if (cfg.provider == "local") {
            val localResp = AiApiClient.chat(context, cfg, messages)
            if (localResp.error != null) {
                throw RuntimeException("本地模型判定失败: ${localResp.error.message}")
            }
            localResp.choices.firstOrNull()?.message?.content.orEmpty()
        } else {
            callAgentHttp(cfg, messages)
                ?: throw RuntimeException("Agent API 请求失败或超时")
        }

        val jsonStr = text
            .substringAfter("```json", text)
            .substringBefore("```")
            .trim()

        var verdict = "safe"
        var reason = ""
        var riskType: String? = null
        try {
            val json = Json.parseToJsonElement(jsonStr).jsonObject
            verdict = json["verdict"]?.jsonPrimitive?.contentOrNull ?: "safe"
            reason = json["reason"]?.jsonPrimitive?.contentOrNull ?: ""
            riskType = json["risk_type"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            val lower = text.lowercase()
            verdict = if ("dangerous" in lower || "危险" in text) "dangerous" else "safe"
            reason = text.take(200)
            riskType = null
        }

        Log.i(TAG, "Agent 判定: verdict=$verdict, risk=$riskType")

        return JudgeResult(
            verdict = if (verdict.lowercase() == "dangerous") Verdict.DANGEROUS else Verdict.SAFE,
            reason = reason,
            riskType = riskType,
            agentResponded = true,
            localDetections = localDetections
        )
    }

    /**
     * 直接向 Agent 配置的 API URL 发送 POST（POST {apiBaseUrl}/chat/completions）。
     *
     * - URL / Key 取自 Agent 设置（AiProviderConfig.apiBaseUrl / apiKey）
     * - 短超时：connect 5s / read 9s，保证在云端判定预算（10s）内失败或成功，
     *   不会拖到硬超时才返回
     * - 详细日志：完整 URL、HTTP code、耗时、响应长度，便于定位连接/鉴权问题
     *
     * @return 解析出的模型回复文本；请求失败/超时/无有效内容时返回 null
     */
    private fun callAgentHttp(cfg: AiProviderConfig, messages: List<OpenAiMessage>): String? {
        val baseUrl = cfg.apiBaseUrl.trimEnd('/')
        if (baseUrl.isBlank()) {
            Log.e(TAG, "Agent API URL 为空，无法发送判定请求")
            return null
        }
        val t0 = System.currentTimeMillis()
        val url = URL("$baseUrl/chat/completions")

        val body = org.json.JSONObject().apply {
            put("model", cfg.model)
            put("temperature", cfg.temperature.toDouble())
            put("stream", false)
            put("max_tokens", 2048)
            put("messages", org.json.JSONArray().apply {
                for (m in messages) {
                    put(org.json.JSONObject().apply {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            })
        }.toString()

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            // 短超时：connect 3s / read 5s，最坏 8s < 云端判定预算 10s。
            // 第三方 API 不稳定时 5s 内快速失败走本地兜底，避免判定长时间等待
            connectTimeout = 3000
            readTimeout = 5000
            setRequestProperty("Content-Type", "application/json")
            if (cfg.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            }
            doOutput = true
            doInput = true
            useCaches = false
        }
        try {
            DataOutputStream(conn.outputStream).use {
                it.write(body.toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val elapsed = System.currentTimeMillis() - t0
            Log.i(TAG, "Agent POST $url -> HTTP $code (${elapsed}ms)")
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.e(TAG, "Agent POST 失败 HTTP $code: ${err.take(300)}")
                return null
            }
            val respText = conn.inputStream.bufferedReader().use { it.readText() }
            Log.i(TAG, "Agent POST 响应 ${respText.length}B (${System.currentTimeMillis() - t0}ms)")
            val json = org.json.JSONObject(respText)
            val choices = json.optJSONArray("choices")
            val content = if (choices != null && choices.length() > 0) {
                choices.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            } else ""
            return content.ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "Agent POST 异常(${System.currentTimeMillis() - t0}ms): ${e.message}")
            return null
        } finally {
            conn.disconnect()
        }
    }
}
