package com.termux.app.compose

import android.content.Context
import android.util.Base64
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
    /** 脚本文件读取上限：超大的脚本只读头部，防止 OOM / 拖死服务端 */
    private const val MAX_SCRIPT_READ_BYTES = 64 * 1024

    @Volatile
    private var running = false

    private var serverThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var portFile: File? = null
    // 8 线程：Agent 判定会阻塞池线程（最长 30s），太少会被并发脚本检查占满。
    // 注意：stop() 会 shutdownNow()，start() 必须重建池，否则复用已死池 → 所有请求卡死
    @Volatile
    private var pool: java.util.concurrent.ExecutorService? = null

    // Agent 判定并发上限：Agent 判定慢（最长 10~30s），若大量脚本检查同时占池线程做
    // Agent 判定，会把 8 个池线程占满，后续请求排队、shell 长时间等待。
    // 最多同时 4 个 Agent 判定，超过直接本地检测，保证响应快速返回。
    private val agentSemaphore = java.util.concurrent.Semaphore(4)

    @Synchronized
    fun start(context: Context) {
        if (running) {
            Log.w(TAG, "Server already running")
            return
        }
        running = true

        // 上次 stop() 可能已 shutdownNow()，必须重建线程池
        try { pool?.shutdownNow() } catch (_: Exception) {}
        val exec = Executors.newFixedThreadPool(8)
        pool = exec

        val ready = CountDownLatch(1)
        serverThread = Thread {
            try {
                // 用局部引用，避免 stop() 后旧线程 finally 清空新 start() 的字段
                val srv = ServerSocket(0)
                serverSocket = srv
                val port = srv.localPort

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
                        srv.accept()
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "accept error: ${e.message}")
                        break
                    }
                    val p = pool
                    if (p == null || p.isShutdown) {
                        // 池异常（理论不该发生）：不挂服务器，直接关闭该连接
                        Log.e(TAG, "pool unavailable, closing client")
                        try { client.close() } catch (_: Exception) {}
                        continue
                    }
                    try {
                        p.execute { handleClient(context, client) }
                    } catch (e: Exception) {
                        // 单个客户端投递失败不影响服务器继续服务
                        Log.e(TAG, "pool execute rejected, closing client", e)
                        try { client.close() } catch (_: Exception) {}
                    }
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
        try { pool?.shutdownNow() } catch (_: Exception) {}
        pool = null
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
                // 请求上限 128KB：CHECK_SCRIPT 会带 base64 脚本内容（50KB 内容 ≈ 67KB base64），
                // 64KB 不够；128KB 防超大/恶意请求拖死接收端
                if (++totalChars > 128 * 1024) {
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
        val trimmed = command.trim()
        val result = detectCommand(context, trimmed)
        if (result is DetectResult.Deny) {
            // 危险命令：按增强模式弹窗/snackbar 二次确认（附危险原因），用户同意才放行（PASS）
            val pass = RiskConfirmManager.requestDetectedConfirmationBlocking(
                context, trimmed, result.reason, result.riskType
            )
            writeResponse(writer, if (pass) DetectResult.Allow() else result)
        } else {
            writeResponse(writer, result)
        }
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

    /** 读取脚本内容：超过上限只读头部，防止超大文件 OOM / 读盘拖死服务端 */
    private fun readScriptHead(file: File): String {
        return try {
            if (file.length() <= MAX_SCRIPT_READ_BYTES) {
                file.readText(Charsets.UTF_8)
            } else {
                java.io.FileInputStream(file).use { ins ->
                    val buf = ByteArray(MAX_SCRIPT_READ_BYTES)
                    var off = 0
                    while (off < buf.size) {
                        val n = ins.read(buf, off, buf.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    String(buf, 0, off, Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取脚本失败: ${e.message}")
            ""
        }
    }

    private fun handleCheckScript(context: Context, body: List<String>, writer: PrintWriter) {
        if (body.isEmpty()) {
            writer.println("ERROR"); writer.println("missing command"); writer.println("END"); return
        }
        val command = body[0].trim()
        // 新协议：hook 直接 post 脚本内容（body[1]=path，body[2]=base64 内容）
        // 旧协议兜底：只发命令，路径由 server 解析
        val path = if (body.size > 1 && body[1].isNotBlank()) body[1].trim()
            else RiskConfirmManager.extractScriptPath(command)
        val content = if (body.size > 2 && body[2].isNotBlank()) {
            try {
                String(Base64.decode(body[2], Base64.NO_WRAP), Charsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "CHECK_SCRIPT: base64 解码失败: ${e.message}")
                null
            }
        } else {
            null
        }

        val scriptPath: String
        val scriptContent: String
        if (content != null) {
            // 有内容直接用（hook 按 shell 真实 PWD 读取，最可靠）
            scriptPath = path ?: ""
            scriptContent = content.take(MAX_SCRIPT_READ_BYTES)
        } else if (path != null) {
            // 无内容 → 按路径读取（兜底）
            val file = resolveScriptFile(context, path)
            if (file == null) {
                // 文件不存在 → 仅静态检测命令本身
                Log.i(TAG, "CHECK_SCRIPT: file not found: $path, fallback CHECK_CMD")
                writeResponse(writer, detectCommand(context, command))
                return
            }
            scriptPath = file.absolutePath
            scriptContent = readScriptHead(file)
        } else {
            // 提取不到路径 → 退回普通命令检测
            Log.i(TAG, "CHECK_SCRIPT: no path/content, fallback CHECK_CMD")
            writeResponse(writer, detectCommand(context, command))
            return
        }

        // 判定期间显示"安全检测中"加载弹窗（Agent 判定可能较慢）
        RiskConfirmManager.showAgentLoading("正在检测脚本安全性...")
        val t0 = System.currentTimeMillis()
        val result = try {
            detectScript(context, scriptPath, scriptContent)
        } catch (t: Throwable) {
            Log.w(TAG, "CHECK_SCRIPT 判定异常，放行: ${t.message}")
            DetectResult.Allow((t.message ?: t.javaClass.simpleName).take(200))
        }
        Log.i(TAG, "CHECK_SCRIPT: path=$scriptPath len=${scriptContent.length} done=${System.currentTimeMillis() - t0}ms")

        try {
            if (result is DetectResult.Deny) {
                // 危险脚本：按增强模式弹窗/snackbar 二次确认（附 Agent/本地判定原因）。
                // 统一弹窗宿主会把 Loading 无缝切换为二次确认，避免两个 DialogWindow 叠加渲染失败
                val pass = RiskConfirmManager.requestDetectedConfirmationBlocking(
                    context, command, result.reason, result.riskType
                )
                writeResponse(writer, if (pass) DetectResult.Allow() else result)
            } else {
                writeResponse(writer, result)
            }
        } finally {
            // 二次确认结束（或无需确认）后再关闭 Loading，保证弹窗状态连续不闪烁
            RiskConfirmManager.hideAgentLoading()
        }
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
                val content = readScriptHead(file)
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
        val t0 = System.currentTimeMillis()
        return try {
            val trimmed = if (content.length > 50 * 1024) content.take(50 * 1024) else content

            val agentEnabled = AgentScriptJudge.isAvailable(context)
            val level = RiskConfirmManager.getProtectionLevel(context)
            val useAgent = agentEnabled && level != RiskConfirmManager.ProtectionLevel.OFF

            if (useAgent) {
                // judgeContent 内部自带 withTimeout（云端 10s / 本地 30s），
                // 超时/异常都会返回结果，不会永久阻塞
                Log.i(TAG, "detectScript: agent judgment start")
                // 并发上限：Agent 判定同时最多 4 个，拿不到许可（2s 内）直接本地检测，
                // 避免大量 CHECK_SCRIPT 排队把 8 个池线程占满导致 shell 长时间等待
                if (agentSemaphore.tryAcquire(2, TimeUnit.SECONDS)) {
                    try {
                        val result = AgentScriptJudge.judgeContent(context, scriptPath, trimmed)
                        Log.i(TAG, "detectScript: agent done in ${System.currentTimeMillis() - t0}ms responded=${result.agentResponded} verdict=${result.verdict}")
                        if (result.agentResponded) {
                            if (result.verdict == AgentScriptJudge.Verdict.DANGEROUS) {
                                return DetectResult.Deny(
                                    reason = result.reason.ifBlank { "Agent 判定为危险脚本" },
                                    riskType = result.riskType
                                )
                            }
                            return DetectResult.Allow()
                        }
                        // Agent 未真正回复（超时/异常）：本地检测兜底，本地安全时把
                        // Agent 侧原因带回终端显示，本地危险仍按本地结果拦截
                        val local = localScriptDetect(trimmed)
                        if (local is DetectResult.Allow && result.reason.isNotBlank()) {
                            return DetectResult.Allow("Agent 判定未返回: ${result.reason}")
                        }
                        return local
                    } finally {
                        agentSemaphore.release()
                    }
                } else {
                    Log.w(TAG, "Agent 判定并发忙，跳过 Agent 直接本地检测")
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
        val t0 = System.currentTimeMillis()
        return try {
            val expanded = RiskCommandDetector.expandShellVarsPublic(trimmed)
            val detections = RiskCommandDetector.detectScript(expanded)
            Log.i(TAG, "detectScript: local done in ${System.currentTimeMillis() - t0}ms hits=${detections.size}")
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
                Log.i(TAG, "Response: ALLOW${if (result.error.isNullOrBlank()) "" else " (error: ${result.error.take(60)})"}")
                writer.println("ALLOW")
                if (!result.error.isNullOrBlank()) {
                    writer.println("error=${result.error.replace('\n', ' ').take(200)}")
                }
                writer.println("END")
            }
            is DetectResult.Deny -> {
                Log.i(TAG, "Response: DENY (${result.reason.take(60)})")
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
