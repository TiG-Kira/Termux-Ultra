package com.termux.app.compose

import android.content.Context
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * 脚本安全判定 - Agent 参与模式。
 *
 * 流程：
 *   1. 检查 Agent 是否已配置 + 增强防护不为 OFF + 开关已开
 *   2. 先做本地 RiskCommandDetector.detectScript() 作为 baseline
 *   3. 构建脚本内容 + 本地检测结果 → 发送给 Agent
 *   4. Agent 返回结构化 JSON verdict
 *   5. 超时（云端 10s / 本地 30s）自动 fallback 到本地检测
 */
object AgentScriptJudge {

    private const val TAG = "AgentScriptJudge"
    private const val TIMEOUT_CLOUD_MS = 10000L
    private const val TIMEOUT_LOCAL_MS = 30000L

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

    fun judge(context: Context, filePath: String): JudgeResult {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            return JudgeResult(Verdict.SKIP, "脚本文件不存在或不可读")
        }
        val content = try { file.readText() } catch (_: Exception) { return JudgeResult(Verdict.SKIP, "无法读取脚本内容") }
        return judgeContent(context, filePath, content)
    }

    fun judgeContent(context: Context, filePath: String, content: String): JudgeResult {
        val expanded = RiskCommandDetector.expandShellVarsPublic(content)
        val localDetections = RiskCommandDetector.detectScript(expanded)

        if (!isAvailable(context)) {
            return JudgeResult(
                verdict = if (localDetections.isNotEmpty()) Verdict.DANGEROUS else Verdict.SAFE,
                reason = localDetections.firstOrNull()?.detection?.description ?: "Agent 判定未启用",
                riskType = localDetections.firstOrNull()?.detection?.riskType?.displayName,
                agentResponded = false,
                localDetections = localDetections
            )
        }

        val cfg = AiTermuxPrefs.getConfig(context).providerConfig
        val timeoutMs = if (cfg.provider == "local") TIMEOUT_LOCAL_MS else TIMEOUT_CLOUD_MS

        return runBlocking {
            try {
                withTimeout(timeoutMs) {
                    callAgent(context, filePath, content, localDetections)
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Agent 判定超时")
                JudgeResult(
                    verdict = if (localDetections.isNotEmpty()) Verdict.DANGEROUS else Verdict.TIMEOUT,
                    reason = "Agent 判定超时，已使用本地检测结果",
                    riskType = localDetections.firstOrNull()?.detection?.riskType?.displayName,
                    agentResponded = false,
                    localDetections = localDetections
                )
            } catch (e: Exception) {
                Log.e(TAG, "Agent 判定异常: ${e.message}")
                JudgeResult(
                    verdict = if (localDetections.isNotEmpty()) Verdict.DANGEROUS else Verdict.ERROR,
                    reason = "Agent 判定失败: ${e.message}",
                    riskType = localDetections.firstOrNull()?.detection?.riskType?.displayName,
                    agentResponded = false,
                    localDetections = localDetections
                )
            }
        }
    }

    private suspend fun callAgent(
        context: Context,
        filePath: String,
        content: String,
        localDetections: List<RiskCommandDetector.ScriptDetectionResult>
    ): JudgeResult {
        val cfg = AiTermuxPrefs.getConfig(context).providerConfig

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

        val resp = AiApiClient.chat(context, cfg, messages)
        val text = resp.choices.firstOrNull()?.message?.content.orEmpty()

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
}
