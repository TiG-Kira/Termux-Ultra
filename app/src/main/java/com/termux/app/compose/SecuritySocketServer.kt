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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    @Volatile
    private var portFile: File? = null
    // 8 线程：Agent 判定会阻塞池线程（最长 30s），太少会被并发脚本检查占满
    private val pool = Executors.newFixedThreadPool(8)

    @Synchronized
    fun start(context: Context) {
        if (running) {
            Log.w(TAG, "Server already running")
            return
        }
        running = true

        val ready = CountDownLatch(1)
        serverThread = Thread {
            try {
                serverSocket = ServerSocket(0)
                val port = serverSocket!!.localPort

                // 写端口文件（先删旧的再写，幂等）
                val sockDir = File(context.filesDir.parentFile, "files/sock")
                sockDir.mkdirs()
                val pf = File(sockDir, PORT_FILE)
                if (pf.exists()) pf.delete()
                pf.writeText(port.toString())
                pf.setReadable(true, false)
                portFile = pf

                Log.i(TAG, "Server listening on 127.0.0.1:$port (port file: ${pf.absolutePath})")

                ready.countDown()  // ← 端口文件写完，通知 start() 可以返回了

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
                ready.countDown()  // error 时也要释放，避免 start() 永远阻塞
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

        // 等端口文件写完（最多 2s），确保 shell 启动时一定能读到端口
        if (!ready.await(2, TimeUnit.SECONDS)) {
            Log.e(TAG, "Server failed to start within timeout")
            running = false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        try { serverThread?.join(1000) } catch (_: Exception) {}
        serverThread = null
        serverSocket = null
        // 删 port 文件：shell hook 检测不到 port 文件 → 自动跳过全部检测
        try { portFile?.delete() } catch (_: Exception) {}
        portFile = null
        pool.shutdownNow()
    }

    private fun handleClient(context: Context, client: Socket) {
        try {
            // 接收端读超时：客户端连上但不发数据/协议异常时，不永久占用池线程
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8), true)

            val lines = mutableListOf<String>()
            val sb = StringBuilder()
            val NEWLINE = 10 // '\n'.code
            var totalChars = 0
            while (true) {
                val c = try {
                    reader.read()
                } catch (e: java.net.SocketTimeoutException) {
                    Log.w(TAG, "read timeout: client connected but sent no complete request")
                    break
                }
                if (c == -1) break
                if (c == NEWLINE) {
                    lines.add(sb.toString())
                    sb.clear()
                    if (lines.lastOrNull() == "END") break
                } else {
                    sb.append(c.toChar())
                }
                // 请求上限 64KB：防超大/恶意请求拖死接收端
                if (++totalChars > 64 * 1024) {
                    Log.w(TAG, "request too large, aborting read")
                    break
                }
            }
            if (lines.isEmpty()) return

            val method = lines[0].trim()
            val body = lines.drop(1).dropLast(1)

            Log.i(TAG, "Request: $method (${body.size} body lines)")

            try {
                when (method) {
                    "CHECK_CMD" -> handleCheckCmd(context, body, writer)
                    "CHECK_SCRIPT" -> handleCheckScript(context, body, writer)
                    "PING" -> { writer.println("PONG"); writer.println("END") }
                    else -> { writer.println("ERROR"); writer.println("unknown: $method"); writer.println("END") }
                }
            } catch (t: Throwable) {
                // 兜底：任何处理异常都必须返回响应，否则 shell 会一直等到 read -t 超时。
                // 返回 ALLOW + error 原因：终端自动放行，但能看到服务器端异常原因
                Log.e(TAG, "handle $method failed, fallback ALLOW", t)
                try {
                    val err = (t.message ?: t.javaClass.simpleName).replace('\n', ' ').take(200)
                    writer.println("ALLOW")
                    writer.println("error=$err")
                    writer.println("END")
                } catch (_: Exception) {}
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

    /**
     * 解析脚本文件：相对路径按 Termux home 解析（shell 的 cwd 通常是 $HOME）。
     */
    private fun resolveScriptFile(context: Context, path: String): File? {
        val candidates = if (path.startsWith("/")) {
            listOf(path)
        } else {
            val home = File(context.filesDir.parentFile, "files/home")
            listOf(path, File(home, path).absolutePath)
        }
        for (c in candidates) {
            val f = File(c)
            if (f.exists() && f.isFile) return f
        }
        return null
    }

    private fun handleCheckScript(context: Context, body: List<String>, writer: PrintWriter) {
        if (body.isEmpty()) {
            writer.println("ERROR"); writer.println("missing command"); writer.println("END"); return
        }
        val command = body[0].trim()
        // 从命令里提取脚本路径（shell 只发命令字符串，路径由 server 解析）
        val path = RiskConfirmManager.extractScriptPath(command)
        if (path == null) {
            // 提取不到路径 → 退回普通命令检测
            writeResponse(writer, detectCommand(context, command))
            return
        }
        val file = resolveScriptFile(context, path)
        if (file == null) {
            // 文件不存在 → 仅静态检测命令本身
            writeResponse(writer, detectCommand(context, command))
            return
        }
        val content = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
        writeResponse(writer, detectScript(context, file.absolutePath, content))
    }

    private fun detectCommand(context: Context, command: String): DetectResult {
        val level = RiskConfirmManager.getProtectionLevel(context)
        if (level == RiskConfirmManager.ProtectionLevel.OFF) {
            return DetectResult.Allow()
        }
        val detection = RiskCommandDetector.detect(command, inNativeTermux = true)
        if (detection.isDangerous) {
            return DetectResult.Deny(
                reason = detection.description,
                riskType = detection.riskType?.displayName
            )
        }
        // 漏网脚本兜底：命令本身不危险，但可能是脚本执行（如 nohup bash x.sh），
        // 读文件内容做本地静态检测（不走 Agent，保证 CHECK_CMD 3s 内返回）
        val path = RiskConfirmManager.extractScriptPath(command)
        if (path != null) {
            val file = resolveScriptFile(context, path)
            if (file != null) {
                val content = try {
                    file.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    ""
                }
                if (content.isNotBlank()) {
                    val expanded = try {
                        RiskCommandDetector.expandShellVarsPublic(content)
                    } catch (e: Exception) {
                        content
                    }
                    val dets = try {
                        RiskCommandDetector.detectScript(expanded)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    if (dets.isNotEmpty()) {
                        val first = dets.first()
                        return DetectResult.Deny(
                            reason = "脚本包含 ${first.detection.description}（行 ${first.lineNumber}）",
                            riskType = first.detection.riskType?.displayName
                        )
                    }
                }
            }
        }
        return DetectResult.Allow()
    }

    private fun detectScript(context: Context, scriptPath: String, content: String): DetectResult {
        return try {
            val trimmed = if (content.length > 50 * 1024) content.take(50 * 1024) else content

            val agentEnabled = AgentScriptJudge.isAvailable(context)
            val level = RiskConfirmManager.getProtectionLevel(context)
            val useAgent = agentEnabled && level != RiskConfirmManager.ProtectionLevel.OFF

            if (useAgent) {
                // judgeContent 内部自带 withTimeout（云端 10s / 本地 30s），
                // 超时/异常都会返回结果，不会永久阻塞
                val result = AgentScriptJudge.judgeContent(context, scriptPath, trimmed)
                if (result.agentResponded) {
                    if (result.verdict == AgentScriptJudge.Verdict.DANGEROUS) {
                        return DetectResult.Deny(
                            reason = result.reason.ifBlank { "Agent 判定为危险脚本" },
                            riskType = result.riskType
                        )
                    }
                    return DetectResult.Allow()
                }
            }
            localScriptDetect(trimmed)
        } catch (t: Throwable) {
            // 任何异常都不能让检测流程卡死/无响应，一律放行但返回异常原因
            Log.w(TAG, "脚本检测异常，放行: ${t.message}")
            DetectResult.Allow((t.message ?: t.javaClass.simpleName).take(200))
        }
    }

    /** 仅本地静态检测脚本内容（不走 Agent，保证快速返回） */
    private fun localScriptDetect(trimmed: String): DetectResult {
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
                DetectResult.Allow()
            }
        } catch (e: Exception) {
            Log.w(TAG, "本地脚本检测异常: ${e.message}")
            DetectResult.Allow("本地脚本检测异常: ${e.message}")
        }
    }

    private fun writeResponse(writer: PrintWriter, result: DetectResult) {
        when (result) {
            is DetectResult.Allow -> {
                writer.println("ALLOW")
                if (!result.error.isNullOrBlank()) {
                    writer.println("error=${result.error.replace('\n', ' ').take(200)}")
                }
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
        /** 放行。error 非空表示服务器检测异常，命令仍放行但把原因返回给终端显示 */
        data class Allow(val error: String? = null) : DetectResult()
        data class Deny(val reason: String, val riskType: String? = null) : DetectResult()
    }
}
