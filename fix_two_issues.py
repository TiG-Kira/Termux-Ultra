# -*- coding: utf-8 -*-
"""
两个问题统一修复：
1. 回复内容不对（banner/prompt echo/命令帮助 混入）→ stdout 状态机过滤
2. 每次重复加载模型 → llama-server 常驻 HTTP 推理
改动文件：AiLocalModel.kt（核心）
"""
LOCAL = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\AiLocalModel.kt'
with open(LOCAL, 'r', encoding='utf-8') as f:
    lines = f.readlines()

# 找到 buildProcess 函数和 chatStreamLocal 函数块的位置
bp_start = None
for i, line in enumerate(lines):
    if 'private fun buildProcess(entry: LocalModelEntry' in line:
        bp_start = i
        break
# 找到 buildProcess 结束行（下一个 private fun applyTermuxEnv 之前）
bp_end = None
for i in range(bp_start+1, len(lines)):
    if 'private fun applyTermuxEnv(' in lines[i]:
        bp_end = i
        break
assert bp_start != None and bp_end != None, "buildProcess 范围找不到"
print(f"buildProcess 范围: L{bp_start+1} ~ L{bp_end}")

# === MOD 1: buildProcess 换参数（核心改动）===
new_build_process = """    /** llama-cli 进程（根据实际可用的二进制路径构造） */
    private fun buildProcess(entry: LocalModelEntry, promptFile: File, temperature: Float): ProcessBuilder {
        val cliPath = findLlamaBinary()?.absolutePath ?: llamaCliPath()
        val args = mutableListOf<String>()
        args.add(cliPath)
        args.addAll(listOf("-m", modelFile(entry).absolutePath))
        // ★ 上下文窗口：Qwen 1.5B 实际支持最多 4096，否则推理会截断
        args.addAll(listOf("-c", entry.maxContext.toString()))
        // ★ 双保险禁 banner：--no-display-prompt（旧版名）+ -np / --no-prompt（新版名）
        args.addAll(listOf("--no-display-prompt"))
        args.addAll(listOf("--no-prompt"))
        // ★ 禁 color / 禁预测结果后的 prompt 重打印
        args.addAll(listOf("--no-color"))
        // ★ 用 -f 指定文件（避免 shell 转义）
        args.addAll(listOf("-f", promptFile.absolutePath))
        // 生成 token 上限（与 maxTokens 一致）
        args.addAll(listOf("-n", entry.maxTokens.toString()))
        args.addAll(listOf("--temp", temperature.toString()))
        args.addAll(listOf("-s", "0"))
        return ProcessBuilder(args)
    }

"""
lines = lines[:bp_start] + new_build_process.splitlines(keepends=True) + lines[bp_end:]
print("MOD1 OK: buildProcess 换参数（加上 ctx-size / --no-prompt / --no-color，并移除 --simple-io）")


# 找到 chatStreamLocal 范围
csl_start = None
for i, line in enumerate(lines):
    if line.strip().startswith('/** 覆盖流式推理'):
        csl_start = i
        break
csl_end = None
for i in range(csl_start+1, len(lines)):
    if '/** 非流式本地调用' in lines[i]:
        csl_end = i
        break
assert csl_start != None and csl_end != None, "chatStreamLocal 找不到"
print(f"chatStreamLocal 范围: L{csl_start+1} ~ L{csl_end}")

# === MOD 2: 重写 chatStreamLocal + 增加 llama-server 函数 ===
# 1) 在 chatStreamLocal 之前插入：server 相关常量 + 辅助函数
# 2) 新的 chatStreamLocal：优先 server，fallback 到带过滤的 cli
new_chat_and_server = r"""

    // -------------------- llama-server 常驻复用（避免每次重新加载模型） --------------------
    private val llamaServerPort: Int get() = 8088
    private fun serverRuntimeDir(): File {
        val d = File(runtimeDir(), "llama-server")
        if (!d.exists()) d.mkdirs()
        return d
    }
    private fun serverPidFile(): File = File(serverRuntimeDir(), "server.pid")
    private fun serverMetaFile(): File = File(serverRuntimeDir(), "server.meta")

    data class ServerMeta(val modelId: String, val port: Int, val pid: Long, val startedAt: Long)

    private fun readServerMeta(): ServerMeta? {
        val f = serverMetaFile()
        if (!f.exists()) return null
        return try {
            val lines = f.readLines(Charsets.UTF_8)
            val map = lines.associate {
                val kv = it.split('=', limit = 2)
                kv[0].trim() to (kv.getOrNull(1)?.trim() ?: "")
            }
            ServerMeta(
                modelId = map["modelId"] ?: "",
                port = (map["port"] ?: "0").toIntOrNull() ?: 0,
                pid = (map["pid"] ?: "0").toLongOrNull() ?: 0L,
                startedAt = (map["startedAt"] ?: "0").toLongOrNull() ?: 0L
            )
        } catch (_: Exception) { null }
    }

    private fun writeServerMeta(meta: ServerMeta) {
        runCatching {
            serverMetaFile().writeText("""
                modelId=${meta.modelId}
                port=${meta.port}
                pid=${meta.pid}
                startedAt=${meta.startedAt}
            """.trimIndent(), Charsets.UTF_8)
        }
    }

    private fun isProcessAlive(pid: Long): Boolean {
        if (pid <= 0) return false
        // Android/Linux: kill -0 <pid> 发送空信号检查进程是否存活
        return runCatching {
            val pb = ProcessBuilder("kill", "-0", pid.toString())
            applyTermuxEnv(pb)
            val p = pb.start()
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) == 0
        }.getOrDefault(false)
    }

    /** llama-server 二进制路径 */
    private fun findLlamaServerBinary(): File? {
        val binDir = File(prefixDir(), "bin")
        val candidates = listOf(
            "$binDir/llama-server",
            "$binDir/llama-server-cli",
            "$binDir/server"
        )
        for (c in candidates) {
            val f = File(c)
            if (f.exists() && f.canExecute()) return f
        }
        // 兜底扫描 bin 目录
        if (binDir.exists() && binDir.isDirectory) {
            binDir.listFiles()?.forEach { f ->
                val name = f.name.lowercase()
                if ((name == "llama-server" || name == "server") && f.canExecute()) return f
            }
        }
        return null
    }

    private suspend fun ensureServerStarted(entry: LocalModelEntry): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val existing = readServerMeta()
        val serverBin = findLlamaServerBinary() ?: return@withContext false
        // 如果已有 meta，检查是否是同一个模型、进程是否存活、端口通不通
        if (existing != null && existing.modelId == entry.id && existing.port == llamaServerPort) {
            if (isProcessAlive(existing.pid)) {
                // 再探一下端口是否真的能连
                if (pingServerPort(existing.port)) return@withContext true
            }
            // 进程死了或端口不通 → 清理并重启
            runCatching { serverPidFile().delete() }
            runCatching { serverMetaFile().delete() }
        }
        // 如果有旧 server 不是同一个模型，先 kill 再启动新的
        if (existing != null && existing.modelId != entry.id) {
            if (isProcessAlive(existing.pid)) {
                runCatching { ProcessBuilder("kill", existing.pid.toString()).start().waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
            }
            runCatching { serverPidFile().delete() }
            runCatching { serverMetaFile().delete() }
        }

        // 启动新 server
        return try {
            val args = listOfNotNull(
                serverBin.absolutePath,
                "--host", "127.0.0.1",
                "--port", llamaServerPort.toString(),
                "-m", modelFile(entry).absolutePath,
                "-c", entry.maxContext.toString(),
                "--no-mmap",
                "--no-color"
            )
            val pb = ProcessBuilder(args)
            applyTermuxEnv(pb)
            val logFile = File(serverRuntimeDir(), "server_${System.currentTimeMillis()}.log")
            pb.redirectErrorStream(true)
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            val proc = pb.start()
            val pid = proc.pid().toLong() // Process.toHandle().pid()
            // 写入 meta
            val meta = ServerMeta(
                modelId = entry.id,
                port = llamaServerPort,
                pid = pid,
                startedAt = System.currentTimeMillis()
            )
            writeServerMeta(meta)
            // 写 pidfile
            runCatching { serverPidFile().writeText("$pid", Charsets.UTF_8) }

            // 轮询最多 60s：等 server 真正完成加载并监听端口（llama-server 加载大模型需 15-60s）
            val deadline = System.currentTimeMillis() + 60_000L
            var ready = false
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(1000)
                if (!proc.isAlive) {
                    android.util.Log.w("AiLocalModel", "llama-server 异常退出，log=${logFile.absolutePath}")
                    return@withContext false
                }
                if (pingServerPort(llamaServerPort)) { ready = true; break }
            }
            ready
        } catch (e: Exception) {
            android.util.Log.e("AiLocalModel", "startServer failed", e)
            false
        }
    }

    private fun pingServerPort(port: Int): Boolean {
        return try {
            val s = java.net.Socket()
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 1500)
            s.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /** llama-server 模式流式推理（走 HTTP /v1/chat/completions OpenAI 兼容接口） */
    private fun chatStreamServer(
        config: AiProviderConfig,
        messages: List<OpenAiMessage>,
        isCancelled: () -> Boolean,
        port: Int
    ): Flow<StreamChunk> = flow {
        emit(StreamChunk.Prepare(
            status = "通过常驻 llama-server 推理（模型已加载，无需重新加载）",
            detailLine = "[LLM-Server] POST http://127.0.0.1:$port/v1/chat/completions\n" +
                         "[LLM-Server] stream=true, messages=${messages.size}, temp=${config.temperature}"
        ))
        val url = java.net.URL("http://127.0.0.1:$port/v1/chat/completions")
        val conn = url.openConnection() as java.net.HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.doOutput = true
            conn.connectTimeout = 5000
            conn.readTimeout = 5 * 60 * 1000

            // 构造 OpenAI 兼容 JSON 请求体
            val gson = com.google.gson.Gson()
            val msgJsonList = messages.map { m ->
                hashMapOf("role" to m.role, "content" to m.content)
            }
            val bodyMap = hashMapOf(
                "model" to "qwen-local",
                "stream" to true,
                "temperature" to config.temperature,
                "messages" to msgJsonList
            )
            val bodyBytes = gson.toJson(bodyMap).toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bodyBytes); it.flush() }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "HTTP $code"
                emit(StreamChunk.Error("本地 server 推理失败 (HTTP $code): ${err.take(200)}"))
                return@flow
            }
            val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val sb = StringBuilder()
            var line: String?
            while (true) {
                if (isCancelled()) { emit(StreamChunk.Cancelled); break }
                line = reader.readLine() ?: break
                val raw = line.trimEnd()
                if (raw.isBlank()) continue
                if (!raw.startsWith("data:")) continue
                val data = raw.substring(5).trim()
                if (data == "[DONE]") break
                try {
                    val parsed = com.google.gson.JsonParser.parseString(data).asJsonObject
                    val choice0 = parsed.getAsJsonArray("choices")?.get(0)?.asJsonObject ?: continue
                    val delta = choice0.getAsJsonObject("delta")?.get("content")?.asString
                    if (!delta.isNullOrBlank()) {
                        sb.append(delta)
                        emit(StreamChunk.Content(delta))
                    }
                    if (choice0.get("finish_reason")?.isJsonNull == false && choice0.get("finish_reason")?.asString != null) {
                        break
                    }
                } catch (_: Exception) { /* ignore bad json line */ }
            }
            emit(StreamChunk.Done(fullText = sb.toString()))
        } catch (e: Exception) {
            android.util.Log.e("AiLocalModel", "chatStreamServer error", e)
            emit(StreamChunk.Error("本地 server 推理失败：${e.message ?: e.javaClass.simpleName}"))
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // -------------------- chatStreamLocal: 优先 server，fallback cli（带过滤） --------------------
    /** 覆盖流式推理，逐片段 emit */
    fun chatStreamLocal(
        config: AiProviderConfig,
        messages: List<OpenAiMessage>,
        isCancelled: () -> Boolean
    ): Flow<StreamChunk> = flow {
        fun ts(): String {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            return sdf.format(java.util.Date())
        }

        val notReady = requireReady()
        if (notReady != null) {
            emit(StreamChunk.Error(notReady))
            return@flow
        }
        val entry = getSelectedModel()!!

        // ========== 路径 1：尝试 llama-server 常驻模式 ==========
        val serverBin = findLlamaServerBinary()
        var triedServer = false
        if (serverBin != null && runBlocking { ensureServerStarted(entry) }) {
            triedServer = true
            // 用 server 模式流式推理
            var gotAny = false
            chatStreamServer(config, messages, isCancelled, llamaServerPort).collect { chunk ->
                if (chunk is StreamChunk.Content) gotAny = true
                emit(chunk)
            }
            if (gotAny) return@flow
            // server 模式没拿到内容，fallback 到 cli 模式
        }

        // ========== 路径 2：llama-cli 子进程模式（带状态机输出过滤） ==========
        // Step 1: 构造 + 写入提示词
        emit(StreamChunk.Prepare(
            status = "正在为本地模型构造对话提示词…" + if (triedServer) "（server 不可用，降级为 cli）" else "",
            detailLine = "[${ts()}] 收到 ${messages.size} 条对话消息，组装 ChatML 提示词模板…"
        ))
        val prompt = buildChatPrompt(messages)
        val promptFile = writePromptFile(prompt)
            ?: run { emit(StreamChunk.Error("无法写入提示词临时文件")); return@flow }
        emit(StreamChunk.Prepare(
            status = "正在写入提示词临时文件…",
            detailLine = "[${ts()}] 提示词长度 ${prompt.length} 字符\\n" +
                         "[${ts()}] 临时文件: ${promptFile.absolutePath}"
        ))

        var proc: Process? = null
        try {
            val cliPath = (findLlamaBinary()?.absolutePath ?: llamaCliPath())
            val modelF = modelFile(entry)
            val cmdArgs = listOf(
                cliPath,
                "-m", modelF.absolutePath,
                "-c", entry.maxContext.toString(),
                "--no-display-prompt",
                "--no-prompt",
                "--no-color",
                "-f", promptFile.absolutePath,
                "-n", entry.maxTokens.toString(),
                "--temp", config.temperature.toString(),
                "-s", "0"
            )
            emit(StreamChunk.Prepare(
                status = "正在加载 GGUF 模型（约 ${entry.sizeBytes / 1024 / 1024}MB）并启动推理进程\\n首次加载模型需 5~20 秒，请耐心等待…",
                detailLine = "[${ts()}] 模型文件: ${modelF.absolutePath}\\n" +
                             "[${ts()}] 模型大小: ${entry.sizeBytes / 1024 / 1024} MB\\n" +
                             "[${ts()}] 可执行文件: $cliPath\\n" +
                             "[${ts()}] 完整命令: ${cmdArgs.joinToString(" ")}"
            ))

            val pb = buildProcess(entry, promptFile, config.temperature)
            applyTermuxEnv(pb)
            emit(StreamChunk.Prepare(
                status = "正在注入 Termux 运行环境并启动进程…",
                detailLine = "[${ts()}] 工作目录: ${homeDir().absolutePath}\\n" +
                             "[${ts()}] PREFIX: ${prefixDir()}\\n" +
                             "[${ts()}] LD_LIBRARY_PATH: ${prefixDir()}/lib"
            ))
            pb.redirectErrorStream(false)
            proc = pb.start()

            emit(StreamChunk.Prepare(
                status = "推理进程已启动，等待模型加载完成…",
                detailLine = "[${ts()}] 进程句柄: #${proc.hashCode()}（使用 CLI 输出过滤状态机）"
            ))

            // Step 2: 后台线程读取 stderr，收集前 12 行加载进度日志
            val stderrBuffer = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val stderrThread = Thread({
                try {
                    proc?.errorStream?.bufferedReader(Charsets.UTF_8)?.useLines { ls ->
                        ls.forEachIndexed { idx, ln ->
                            if (idx < 12) stderrBuffer.offer("[${ts()}] [stderr] $ln")
                            else if (idx == 12) stderrBuffer.offer("[${ts()}] [stderr] …(后续 stderr 省略，仅用于防管道阻塞)")
                        }
                    }
                } catch (_: Exception) {}
            }, "llama-stderr").apply { isDaemon = true; start() }

            val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream, Charsets.UTF_8))
            val fullText = StringBuilder()
            var line: String?

            // 先等 2 秒，尝试攒一波初始 stderr 并 emit
            repeat(4) {
                Thread.sleep(500)
                if (stderrBuffer.isNotEmpty()) {
                    val sb = StringBuilder()
                    while (stderrBuffer.isNotEmpty()) sb.append(stderrBuffer.poll()).append('\\n')
                    emit(StreamChunk.Prepare(
                        status = "推理进程已启动，等待模型加载完成…（llama.cpp 加载中）",
                        detailLine = sb.toString().trimEnd('\\n')
                    ))
                }
            }

            // ═══════════════════════════════════════════════════
            // ★★ 状态机解析 stdout：剔除 banner/prompt echo/命令帮助/统计行 ★★
            // ═══════════════════════════════════════════════════
            enum class ParseState { BANNER, PROMPT_ECHO, RESPONSE, DONE }
            var state = ParseState.BANNER
            val assistantPrompt = "> <|im_start|>assistant"

            while (true) {
                if (isCancelled()) {
                    proc.destroy()
                    proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    emit(StreamChunk.Cancelled)
                    break
                }
                if (stderrBuffer.isNotEmpty()) {
                    val sb = StringBuilder()
                    while (stderrBuffer.isNotEmpty()) sb.append(stderrBuffer.poll()).append('\\n')
                    emit(StreamChunk.Prepare(
                        status = "模型加载中（GGUF 解析 / KV Cache 分配）…",
                        detailLine = sb.toString().trimEnd('\\n')
                    ))
                }
                line = reader.readLine() ?: break
                if (line == null) break
                val trimmed = line.trimEnd()

                when (state) {
                    ParseState.BANNER -> {
                        // Banner 结束标志：碰到 prompt echo 行 > <|im_start|>xxx
                        if (trimmed.startsWith("> <|im_start|>")) {
                            if (trimmed.startsWith(assistantPrompt)) {
                                // 直接从 banner 跳到 assistant 头？不合理，还是转 PROMPT_ECHO 状态好
                                state = ParseState.RESPONSE
                            } else {
                                state = ParseState.PROMPT_ECHO
                            }
                        }
                        // 否则丢弃 banner 行（Loading model...、ASCII Logo、命令帮助等）
                    }
                    ParseState.PROMPT_ECHO -> {
                        // Prompt echo 逐行是 system/user 消息回显，跳过
                        // 直到碰到 "> <|im_start|>assistant" 表示真正回复要开始了
                        if (trimmed.startsWith(assistantPrompt)) {
                            state = ParseState.RESPONSE
                        }
                    }
                    ParseState.RESPONSE -> {
                        // 响应停止标志
                        if (trimmed == "<|im_end|>" || trimmed.endsWith("<|im_end|>")) {
                            // 遇到停止标签，停止解析
                            state = ParseState.DONE
                            val clean = trimmed.removeSuffix("<|im_end|>").trimEnd()
                            if (clean.isNotBlank()) {
                                fullText.append(clean).append('\\n')
                                emit(StreamChunk.Content(clean + "\\n"))
                            }
                            break
                        }
                        if (trimmed.startsWith("[ Prompt:") ||
                            trimmed.startsWith("[Generation:") ||
                            trimmed.contains("tokens/s]") ||
                            trimmed.startsWith("Print statistics:") ||
                            trimmed.startsWith("generate: token")) {
                            state = ParseState.DONE
                            break
                        }
                        // 下一轮 prompt 开始（理论上不会发生在 cli 模式一次生成中）
                        if (trimmed.startsWith("> <|im_start|>")) {
                            state = ParseState.DONE
                            break
                        }
                        if (trimmed.isBlank()) {
                            fullText.append('\\n')
                            emit(StreamChunk.Content("\\n"))
                            continue
                        }
                        // ★ 正常回复行：emit
                        fullText.append(trimmed).append('\\n')
                        emit(StreamChunk.Content(trimmed + "\\n"))
                    }
                    ParseState.DONE -> break
                }
            }
            proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            stderrThread.join(3000)
            val output = fullText.toString().trim('\\n')
            emit(StreamChunk.Done(fullText = output))
        } catch (e: Exception) {
            android.util.Log.e("AiLocalModel", "Local inference failed", e)
            emit(StreamChunk.Error("本地模型推理失败：${e.message ?: e.javaClass.simpleName}"))
        } finally {
            try { proc?.destroy() } catch (_: Exception) {}
            promptFile.delete()
        }
    }

"""

# 把旧的 chatStreamLocal 范围（从 /** 覆盖流式推理 */ 注释开始，到 completeLocal 之前）替换
lines = lines[:csl_start] + new_chat_and_server.splitlines(keepends=True) + lines[csl_end:]

with open(LOCAL, 'w', encoding='utf-8') as f:
    f.writelines(lines)
print("MOD2 OK: 重写 chatStreamLocal + 新增 llama-server 常驻模式 + cli 输出状态机过滤")

# === MOD 3: LocalModelEntry 新增 maxContext 字段 ===
# 先读取 AiTermuxModels.kt 看 LocalModelEntry 定义
MODELS = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\AiTermuxModels.kt'
with open(MODELS, 'r', encoding='utf-8') as f:
    mtxt = f.read()

old_entry = """data class LocalModelEntry(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val maxTokens: Int,
    val sha256: String = ""
)"""
if old_entry in mtxt:
    new_entry = """data class LocalModelEntry(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val maxTokens: Int,
    val maxContext: Int = 4096,
    val sha256: String = ""
)"""
    mtxt = mtxt.replace(old_entry, new_entry)
    with open(MODELS, 'w', encoding='utf-8') as f:
        f.write(mtxt)
    print("MOD3 OK: LocalModelEntry 新增 maxContext 字段（默认 4096，Qwen 2.5 1.5B 上下文上限）")
else:
    print("MOD3 SKIP: LocalModelEntry old_entry 未匹配，可能已含 maxContext 字段或格式不同")

print("\n=== 所有改动写入文件 ===")
