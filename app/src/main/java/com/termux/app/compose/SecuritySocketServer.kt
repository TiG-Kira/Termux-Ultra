package com.termux.app.compose

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Shell 安全检测 Server（TCP localhost）。
 *
 * 架构：shell 层通过 PROMPT_COMMAND / preexec 把命令发给本地 TCP server，
 * 我们做风险检测，返回 ALLOW 或 DENY。
 *
 * 协议（纯文本，UTF-8）：
 *   请求: METHOD\n<body>\nEND\n
 *   响应: ALLOW\nEND\n  或  DENY\nreason=...\nEND\n
 *
 * 端口号写到 {app_home}/sock/termux-security.port，
 * shell hook 读此文件获取端口后 nc 127.0.0.1 $PORT 连接。
 */
object SecuritySocketServer {

    private const val TAG = "SecuritySocket"
    private const val PORT_FILE = "termux-security.port"

    @Volatile
    private var running = false

    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Synchronized
    fun start(context: Context) {
        if (running) {
            Log.w(TAG, "Server already running")
            return
        }
        running = true

        serverThread = Thread {
            try {
                serverSocket = ServerSocket(0)
                val port = serverSocket!!.localPort

                // 写端口文件
                val sockDir = File(context.filesDir.parentFile, "files/sock")
                sockDir.mkdirs()
                val portFile = File(sockDir, PORT_FILE)
                portFile.writeText(port.toString())
                portFile.setReadable(true, false)

                Log.i(TAG, "Server listening on 127.0.0.1:$port (port file: ${portFile.absolutePath})")

                while (running) {
                    val client = try {
                        serverSocket!!.accept()
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "accept error: ${e.message}")
                        break
                    }
                    pool.execute { handleClient(context, client) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server fatal error", e)
            } finally {
                running = false
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
                Log.i(TAG, "Server stopped")
            }
        }
        serverThread!!.name = "SecuritySocketServer"
        serverThread!!.isDaemon = true
        serverThread!!.start()
    }

    @Synchronized
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { serverThread?.join(1000) } catch (_: Exception) {}
        serverThread = null
        serverSocket = null
        pool.shutdownNow()
    }

    private fun handleClient(context: Context, client: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8), true)

            val lines = mutableListOf<String>()
            val sb = StringBuilder()
            val NEWLINE = 10 // '\n'.code
            while (true) {
                val c = reader.read()
                if (c == -1) break
                if (c == NEWLINE) {
                    lines.add(sb.toString())
                    sb.clear()
                    if (lines.lastOrNull() == "END") break
                } else {
                    sb.append(c.toChar())
                }
            }
            if (lines.isEmpty()) return

            val method = lines[0].trim()
            val body = lines.drop(1).dropLast(1)

            Log.i(TAG, "Request: $method (${body.size} body lines)")

            when (method) {
                "CHECK_CMD" -> handleCheckCmd(context, body, writer)
                "CHECK_SCRIPT" -> handleCheckScript(context, body, writer)
                "PING" -> { writer.println("PONG"); writer.println("END") }
                else -> { writer.println("ERROR"); writer.println("unknown: $method"); writer.println("END") }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Client error", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleCheckCmd(context: Context, body: List<String>, writer: PrintWriter) {
        val command = body.firstOrNull() ?: run {
            writer.println("ERROR"); writer.println("missing command"); writer.println("END"); return
        }
        val result = detectCommand(context, command.trim())
        writeResponse(writer, result)
    }

    private fun handleCheckScript(context: Context, body: List<String>, writer: PrintWriter) {
        if (body.isEmpty()) {
            writer.println("ERROR"); writer.println("missing path"); writer.println("END"); return
        }
        val scriptPath = body[0].trim()
        val content = body.drop(1).joinToString(separator = "\n")
        val result = detectScript(context, scriptPath, content)
        writeResponse(writer, result)
    }

    private fun detectCommand(context: Context, command: String): DetectResult {
        val level = RiskConfirmManager.getProtectionLevel(context)
        if (level == RiskConfirmManager.ProtectionLevel.OFF) {
            return DetectResult.Allow
        }
        val detection = RiskCommandDetector.detect(command, inNativeTermux = true)
        if (detection.isDangerous) {
            return DetectResult.Deny(
                reason = detection.description,
                riskType = detection.riskType?.displayName
            )
        }
        return DetectResult.Allow
    }

    private fun detectScript(context: Context, scriptPath: String, content: String): DetectResult {
        val trimmed = if (content.length > 50 * 1024) content.take(50 * 1024) else content

        val agentEnabled = AgentScriptJudge.isAvailable(context)
        val level = RiskConfirmManager.getProtectionLevel(context)
        val useAgent = agentEnabled && level != RiskConfirmManager.ProtectionLevel.OFF

        if (useAgent) {
            val cfg = AiTermuxPrefs.getConfig(context).providerConfig
            val timeoutMs = if (cfg.provider == "local") 30000L else 10000L
            val result = try {
                runWithTimeout(timeoutMs) {
                    AgentScriptJudge.judgeContent(context, scriptPath, trimmed)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Agent 检测失败/超时，退回本地: ${e.message}")
                null
            }
            if (result != null && result.agentResponded) {
                if (result.verdict == AgentScriptJudge.Verdict.DANGEROUS) {
                    return DetectResult.Deny(
                        reason = result.reason.ifBlank { "Agent 判定为危险脚本" },
                        riskType = result.riskType
                    )
                }
                return DetectResult.Allow
            }
        }

        return try {
            val expanded = RiskCommandDetector.expandShellVarsPublic(trimmed)
            val detections = RiskCommandDetector.detectScript(expanded)
            if (detections.isNotEmpty()) {
                val first = detections.first()
                DetectResult.Deny(
                    reason = "脚本包含 ${first.detection.description}（行 ${first.lineNumber}）",
                    riskType = first.detection.riskType?.displayName
                )
            } else {
                DetectResult.Allow
            }
        } catch (e: Exception) {
            Log.w(TAG, "本地脚本检测异常: ${e.message}")
            DetectResult.Allow
        }
    }

    private fun runWithTimeout(timeoutMs: Long, block: () -> AgentScriptJudge.JudgeResult?): AgentScriptJudge.JudgeResult? {
        val ref = AtomicReference<AgentScriptJudge.JudgeResult?>(null)
        val t = Thread {
            try { ref.set(block()) } catch (_: Throwable) {}
        }
        t.isDaemon = true
        t.start()
        t.join(timeoutMs)
        return if (t.isAlive) null else ref.get()
    }

    private fun writeResponse(writer: PrintWriter, result: DetectResult) {
        when (result) {
            is DetectResult.Allow -> {
                writer.println("ALLOW")
                writer.println("END")
            }
            is DetectResult.Deny -> {
                val reason = result.reason.replace(oldValue = "\n", newValue = " ")
                writer.println("DENY")
                writer.println("reason=$reason")
                if (!result.riskType.isNullOrBlank()) {
                    writer.println("risk_type=${result.riskType}")
                }
                writer.println("END")
            }
        }
    }

    sealed class DetectResult {
        data object Allow : DetectResult()
        data class Deny(val reason: String, val riskType: String? = null) : DetectResult()
    }
}
