package com.termux.app.compose

import android.content.Context
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.shell.TermuxTask
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 本地大模型支持（通过 Termux 的 llama.cpp 在设备上运行 GGUF 模型）。
 *
 * 设计说明：
 * - 模型存放于 Termux home 下 `.local/share/termux-ultra/llm/`。
 * - 推理依赖 Termux 的 `llama.cpp` 包（提供 llama-cli / llama-server 二进制）。
 * - 采用 Qwen2.5-1.5B-Instruct（Q4_K_M）作为面向 Android 设备的轻量模型，
 *   在资源占用与生成质量之间取得平衡。
 * - 本地大模型资源占用较高，界面上方需向用户提示。
 */

/** 本地模型条目 */
data class LocalModelEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val maxTokens: Int,
    val recommendedRamMB: Int,
    val maxContext: Int = 4096
)

/** 内置可选本地模型目录（当前提供适合 Android 设备的轻量模型） */
val LOCAL_MODELS: List<LocalModelEntry> = listOf(
    LocalModelEntry(
        id = "qwen2.5-1.5b-q4km",
        displayName = "Qwen2.5-1.5B-Instruct",
        description = "面向 Android 设备的对话模型（Q4_K_M 量化，约 950MB）",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        downloadUrl = "https://hf-mirror.com/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
        sizeBytes = 990_000_000L,
        maxTokens = 512,
        recommendedRamMB = 2048
    )
)

object AiLocalModel {
    private const val PREFS_NAME = "local_llm_prefs"
    private const val KEY_SELECTED_MODEL_ID = "selected_model_id"
    private const val KEY_DOWNLOADED_AT = "downloaded_at"

    @Volatile
    private var appContext: android.content.Context? = null

    /** 初始化应用context（在 ViewModel 或 Activity 中调用一次） */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun context(): Context? = appContext

    /** Termux PREFIX 目录 */
    private fun prefixDir(): String = TermuxConstants.TERMUX_PREFIX_DIR_PATH

    /** Termux home 目录 */
    private fun homeDir(): File = TermuxConstants.TERMUX_HOME_DIR

    /** llama.cpp 可能的二进制路径列表（按优先级尝试） */
    private fun llamaCliCandidates(): List<String> {
        val binDir = prefixDir() + "/bin"
        return listOf(
            "$binDir/llama-cli",
            "$binDir/llama-cli-android",
            "$binDir/llama-server",
            "$binDir/llama-main",
            "$binDir/llama-simple",
            "$binDir/main"
        )
    }

    /** 获取实际可用的 llama 可执行文件路径（若不存在返回 null） */
    fun llamaCliPath(): String {
        for (candidate in llamaCliCandidates()) {
            val f = File(candidate)
            if (f.exists() && f.canExecute()) return candidate
        }
        // 兜底返回最常见的路径
        return prefixDir() + "/bin/llama-cli"
    }

    /** 模型根目录 */
    fun modelDir(): File = File(homeDir(), ".local/share/termux-ultra/llm")

    /** 本地大模型运行时临时目录 */
    private fun runtimeDir(): File = File(homeDir(), ".local/share/termux-ultra/run")

    fun getSelectedModelId(): String =
        context()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_SELECTED_MODEL_ID, null) ?: ""

    fun getSelectedModel(): LocalModelEntry? =
        LOCAL_MODELS.firstOrNull { it.id == getSelectedModelId() }

    fun setSelectedModelId(id: String) {
        context()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_SELECTED_MODEL_ID, id)?.apply()
    }

    fun getDownloadedAt(): Long =
        context()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getLong(KEY_DOWNLOADED_AT, 0L) ?: 0L

    private fun setDownloadedAt(ts: Long) {
        context()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putLong(KEY_DOWNLOADED_AT, ts)?.apply()
    }

    fun modelFile(entry: LocalModelEntry): File = File(modelDir(), entry.fileName)

    /** 是否已安装（文件存在且非空） */
    fun isModelInstalled(entry: LocalModelEntry): Boolean {
        val f = modelFile(entry)
        return f.exists() && f.length() > 0
    }

    /** 当前是否已配置本地模型（已选模型且已安装） */
    fun isLocalModelReady(): Boolean {
        val entry = getSelectedModel() ?: return false
        return isModelInstalled(entry)
    }

    /**
     * 查找 llama 相关可执行文件是否存在（按优先级列表）。
     * 相比原实现，我们兼容更多可能的二进制名。
     */
    private fun findLlamaBinary(): File? {
        for (candidate in llamaCliCandidates()) {
            val f = File(candidate)
            if (f.exists() && f.canExecute()) return f
        }
        // 最后扫一遍 bin 目录看有没有看起来像 llama 开头的可执行文件
        val binDir = File(prefixDir(), "bin")
        if (binDir.exists() && binDir.isDirectory) {
            binDir.listFiles()?.forEach { f ->
                val name = f.name.lowercase()
                if (name.startsWith("llama") && f.canExecute()) return f
            }
        }
        return null
    }


    // ============================================================
    // llama-server 常驻管理（避免每次对话重新加载模型）
    // ============================================================

    private const val llamaServerPort = 8088

    private data class ServerMeta(
        val modelId: String,
        val port: Int,
        val pid: Long,
        val startedAt: Long
    )

    private fun serverMetaFile(): File = File(runtimeDir(), "llama-server-meta.json")

    private fun findLlamaServerBinary(): File? {
        val binDir = prefixDir() + "/bin"
        val candidates = listOf(
            "$binDir/llama-server",
            "$binDir/llama-server-android",
            "$binDir/server"
        )
        for (c in candidates) {
            val f = File(c)
            if (f.exists() && f.canExecute()) return f
        }
        val bd = File(binDir)
        if (bd.exists()) {
            bd.listFiles()?.forEach { f ->
                val n = f.name.lowercase()
                if (n.contains("server") && f.canExecute() && n.startsWith("llama")) return f
            }
        }
        return null
    }

    private fun readServerMeta(): ServerMeta? {
        val f = serverMetaFile()
        if (!f.exists()) return null
        return runCatching {
            val text = f.readText(Charsets.UTF_8)
            val gson = com.google.gson.Gson()
            gson.fromJson(text, ServerMeta::class.java)
        }.getOrNull()
    }

    private fun writeServerMeta(meta: ServerMeta) {
        runCatching {
            val dir = runtimeDir()
            if (!dir.exists()) dir.mkdirs()
            val gson = com.google.gson.Gson()
            serverMetaFile().writeText(gson.toJson(meta), Charsets.UTF_8)
        }
    }

    private fun clearServerMeta() {
        runCatching { serverMetaFile().delete() }
    }

    private fun isProcessAlive(pid: Long): Boolean {
        return runCatching {
            val pb = ProcessBuilder("kill", "-0", pid.toString())
            applyTermuxEnv(pb)
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            p.exitValue() == 0
        }.getOrDefault(false)
    }

    private fun pingServerPort(port: Int, timeoutMs: Int = 500): Boolean {
        return runCatching {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", port), timeoutMs)
                true
            }
        }.getOrDefault(false)
    }

    private fun serverLogFile(): File {
        val dir = runtimeDir()
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "llama-server.log")
    }

    fun stopServer() {
        val meta = readServerMeta() ?: return
        runCatching {
            ProcessBuilder("kill", "-TERM", meta.pid.toString()).also { applyTermuxEnv(it) }.start()
                .waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            if (isProcessAlive(meta.pid)) {
                ProcessBuilder("kill", "-KILL", meta.pid.toString()).also { applyTermuxEnv(it) }.start()
                    .waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        clearServerMeta()
    }

    private suspend fun ensureServerStarted(entry: LocalModelEntry): Boolean = withContext(Dispatchers.IO) {
        val serverBin = findLlamaServerBinary() ?: return@withContext false
        val existing = readServerMeta()

        if (existing != null && existing.modelId == entry.id && existing.port == llamaServerPort) {
            if (pingServerPort(existing.port)) {
                return@withContext true
            }
            clearServerMeta()
        }

        if (existing != null && existing.modelId != entry.id) {
            stopServer()
        }

        val logFile = serverLogFile()
        runCatching { if (logFile.exists()) logFile.delete() }
        val pidFile = File(runtimeDir(), "llama-server.pid.tmp")
        runCatching { pidFile.delete() }

        // 通过 shell -c 启动 server 并捕获 PID ($!)
        val binQ = "'" + serverBin.absolutePath.replace("'", "'\\''") + "'"
        val modelQ = "'" + modelFile(entry).absolutePath.replace("'", "'\\''") + "'"
        val logQ = "'" + logFile.absolutePath.replace("'", "'\\''") + "'"
        val pidQ = "'" + pidFile.absolutePath.replace("'", "'\\''") + "'"

        val serverArgs = "$binQ --host 127.0.0.1 --port $llamaServerPort -m $modelQ -c ${entry.maxContext} -t 4 --no-mmap"
        val shellCmd = "nohup $serverArgs >> $logQ 2>&1 & echo \$! > $pidQ"

        try {
            val shell = resolveTermuxShell() ?: "/system/bin/sh"
            val pb = ProcessBuilder(shell, "-c", shellCmd)
            applyTermuxEnv(pb)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)

            // 读取 PID 文件
            var pid: Long = 0
            val deadlineRead = System.currentTimeMillis() + 3000L
            while (System.currentTimeMillis() < deadlineRead) {
                if (pidFile.exists() && pidFile.length() > 0) {
                    try {
                        pid = pidFile.readText().trim().toLongOrNull() ?: 0L
                        if (pid > 0) break
                    } catch (_: Exception) {}
                }
                Thread.sleep(200)
            }
            runCatching { pidFile.delete() }

            // 兜底：用 pgrep 找 llama-server
            if (pid <= 0L) {
                runCatching {
                    val pgrepPb = ProcessBuilder("pgrep", "-f", "llama-server")
                    applyTermuxEnv(pgrepPb)
                    val p = pgrepPb.start()
                    val out = p.inputStream.bufferedReader().readText().trim()
                    p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    if (out.isNotEmpty()) {
                        pid = out.lineSequence().firstOrNull()?.toLongOrNull() ?: 0L
                    }
                }
            }

            if (pid > 0L) {
                writeServerMeta(ServerMeta(entry.id, llamaServerPort, pid, System.currentTimeMillis()))
            }

            // 等待端口就绪（最多 60 秒，模型加载较慢）
            val deadline = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < deadline) {
                if (pingServerPort(llamaServerPort)) return@withContext true
                Thread.sleep(1000)
            }
            android.util.Log.w("AiLocalModel", "llama-server 启动超时, log=" + (runCatching { logFile.readText().take(1500) }.getOrNull() ?: "(empty)"))
            clearServerMeta()
            return@withContext false
        } catch (e: Exception) {
            android.util.Log.e("AiLocalModel", "启动 llama-server 失败", e)
            clearServerMeta()
            return@withContext false
        }
    }

    private fun chatStreamLocalViaServer(
        entry: LocalModelEntry,
        messages: List<OpenAiMessage>,
        config: AiProviderConfig,
        isCancelled: () -> Boolean
    ): Flow<StreamChunk> = flow {
        val url = java.net.URL("http://127.0.0.1:$llamaServerPort/v1/chat/completions")
        val reqBody = linkedMapOf(
            "model" to entry.id,
            "messages" to messages.map { m -> linkedMapOf("role" to m.role, "content" to m.content) },
            "stream" to true,
            "temperature" to config.temperature,
            "max_tokens" to entry.maxTokens
        )
        val gson = com.google.gson.Gson()
        val bodyBytes = gson.toJson(reqBody).toByteArray(Charsets.UTF_8)

        var conn: java.net.HttpURLConnection? = null
        try {
            conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 600_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Length", bodyBytes.size.toString())
                doOutput = true
                doInput = true
            }
            conn.outputStream.use { it.write(bodyBytes) }

            val code = conn.responseCode
            if (code != 200) {
                val err = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull() ?: "HTTP $code"
                emit(StreamChunk.Error("本地推理服务异常: $err"))
                return@flow
            }

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val fullText = StringBuilder()
            var line: String?
            while (true) {
                if (isCancelled()) {
                    emit(StreamChunk.Cancelled)
                    break
                }
                line = reader.readLine()
                if (line == null) break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                runCatching {
                    val json = gson.fromJson(data, java.util.LinkedHashMap::class.java)
                    val choices = json["choices"] as? List<*>
                    val first = choices?.firstOrNull() as? Map<*, *>
                    val delta = first?.get("delta") as? Map<*, *>
                    val content = delta?.get("content") as? String
                    if (!content.isNullOrEmpty()) {
                        fullText.append(content)
                        emit(StreamChunk.Content(content))
                    }
                }
            }
            emit(StreamChunk.Done(fullText = fullText.toString()))
        } catch (e: Exception) {
            android.util.Log.e("AiLocalModel", "server 流式推理失败", e)
            emit(StreamChunk.Error("本地推理服务失败: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** llama.cpp 是否已安装（检查二进制存在且可执行，再用 --version 验证） */
    fun isLlamaCppInstalled(): Boolean {
        val binFile = findLlamaBinary() ?: return false
        return runCatching {
            val pb = ProcessBuilder(binFile.absolutePath, "--version")
            // 注入 Termux 必要的环境变量，避免 so 加载失败
            val env = pb.environment()
            env["PATH"] = prefixDir() + "/bin:" + prefixDir() + "/bin/applets:" + (env["PATH"] ?: "")
            env["LD_LIBRARY_PATH"] = prefixDir() + "/lib"
            env["PREFIX"] = prefixDir()
            env["HOME"] = homeDir().absolutePath
            env["TMPDIR"] = prefixDir() + "/tmp"
            env["ANDROID_DATA"] = "/data"
            env["ANDROID_ROOT"] = "/system"
            pb.redirectErrorStream(true)
            pb.directory(homeDir())
            val proc = pb.start()
            // 读取输出避免缓冲区卡死
            proc.inputStream.bufferedReader().use { it.readText() }
            val ok = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            ok && proc.exitValue() == 0
        }.getOrDefault(false)
    }

    /** 已安装模型占用磁盘空间 */
    fun getInstalledModelSize(): Long {
        val entry = getSelectedModel() ?: return 0L
        val f = modelFile(entry)
        return if (f.exists()) f.length() else 0L
    }

    /** 发现本地模型未就绪时，清除记录并重置 AI 配置里的 local provider flag */
    fun resetLocalModelConfigIfConfigured() {
        val c = appContext ?: return
        c.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(KEY_SELECTED_MODEL_ID)
            ?.remove(KEY_DOWNLOADED_AT)
            ?.apply()
        runCatching {
            val cfg = AiTermuxPrefs.getConfig(c)
            if (cfg.providerConfig.provider == "local") {
                val reset = cfg.copy(
                    providerConfig = cfg.providerConfig.copy(provider = "custom"),
                    isConfigured = false
                )
                AiTermuxPrefs.saveConfig(c, reset)
            }
        }
    }

    /** 删除本地大模型，返回释放的字节数 */
    fun deleteModel(): Long {
        val dir = modelDir()
        var freed = 0L
        if (dir.exists()) {
            dir.listFiles()?.forEach { f ->
                freed += f.length()
                f.delete()
            }
        }
        context()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.remove(KEY_SELECTED_MODEL_ID)
            ?.remove(KEY_DOWNLOADED_AT)
            ?.apply()
        // 删除后重置 Agent 到未配置状态
        runCatching {
            val c = appContext
            if (c != null) {
                val cfg = AiTermuxPrefs.getConfig(c)
                val reset = cfg.copy(
                    providerConfig = cfg.providerConfig.copy(provider = "custom"),
                    isConfigured = false
                )
                AiTermuxPrefs.saveConfig(c, reset)
            }
        }
        return freed
    }

    /**
     * 解析 Termux 可用的 shell 路径（与 execCaptureOutput 中保持一致）。
     */
    private fun resolveTermuxShell(): String? {
        val binDir = TermuxShellUtils.getDefaultBinPath()
        if (binDir.isNotEmpty()) {
            for (shellBinary in arrayOf("bash", "login", "zsh", "sh")) {
                val shellFile = File(binDir, shellBinary)
                if (shellFile.canExecute()) return shellFile.absolutePath
            }
        }
        val systemShell = File("/system/bin/sh")
        if (systemShell.canExecute()) return systemShell.absolutePath
        return null
    }

    /**
     * 使用 Termux 标准执行管道运行一条命令并返回（stdout+stderr、exitCode）。
     * 这是项目中 CAPTURE_OUTPUT 技能的同款方式，确保环境变量完整。
     */
    private data class ShellResult(
        val success: Boolean,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String
    )

    private suspend fun runTermuxShell(
        context: Context,
        command: String,
        timeoutSeconds: Int = 300
    ): ShellResult = withContext(Dispatchers.IO) {
        val shellPath = resolveTermuxShell()
            ?: return@withContext ShellResult(false, null, "", "找不到可用的 shell 环境")

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
        val termuxTask = try {
            TermuxTask.execute(
                context,
                executionCommand,
                null,
                shellEnvClient,
                false
            )
        } catch (e: Exception) {
            android.util.Log.e("AiLocalModel", "TermuxTask.execute 创建失败", e)
            return@withContext ShellResult(false, null, "", "TermuxTask 创建失败: ${e.message}")
        }

        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000L
        var completed = false

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(200)
            if (executionCommand.hasExecuted() || executionCommand.resultData.exitCode != null) {
                completed = true
                break
            }
        }

        if (!completed) {
            runCatching { termuxTask?.killIfExecuting(context, false) }
        }

        val resultData = executionCommand.resultData
        val stdout = resultData.stdout.toString()
        val stderr = resultData.stderr.toString()
        val exitCode = resultData.exitCode

        ShellResult(
            success = completed && exitCode == 0,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr
        )
    }

    /**
     * 下载本地大模型（含进度回调）。
     * 若 llama.cpp 未安装，先执行 `pkg update && pkg install -y llama.cpp`（使用标准 Termux 环境）。
     * onProgress(progress 0..1, statusMessage)
     */
    suspend fun downloadModel(
        entry: LocalModelEntry,
        onProgress: (Float, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val context = context()
            if (context == null) {
                onProgress(0f, "内部错误：Context 未初始化")
                return@withContext false
            }

            // Check network connectivity first
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val network = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(network)
            if (capabilities == null || !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                onProgress(0f, "无网络连接，请检查网络设置")
                return@withContext false
            }

            // 1. 确保 llama.cpp 已安装（若未安装则先更新包列表再安装）
            if (!isLlamaCppInstalled()) {
                onProgress(0f, "正在更新 Termux 包索引（pkg update）…")
                android.util.Log.i("AiLocalModel", "开始: pkg update")
                val updateResult = runTermuxShell(
                    context,
                    "pkg update -y 2>&1",
                    timeoutSeconds = 300
                )
                android.util.Log.i("AiLocalModel", "pkg update exit=${updateResult.exitCode}, out=${updateResult.stdout.take(200)}")

                if (!updateResult.success && !updateResult.stdout.contains("Updating", ignoreCase = true)) {
                    // pkg update 失败不立即中止，因为源可能已更新过；记录日志后继续
                    android.util.Log.w("AiLocalModel", "pkg update exit=${updateResult.exitCode}, stderr=${updateResult.stderr.take(200)}")
                }

                onProgress(0f, "正在安装 llama.cpp 运行时（可能需要数分钟）…")

                // 依次尝试多种候选包名 + 多种前端参数（apt/pkg 双通路）
                val candidatePackages = listOf(
                    "llama.cpp",
                    "llama-cpp",
                    "llamaccp",
                    "llama",
                    "cpp-llama"
                )

                var installed = false
                val installLogs = StringBuilder()
                for (pkg in candidatePackages) {
                    for (manager in listOf("pkg", "apt")) {
                        val cmd = "$manager install -y $pkg 2>&1"
                        android.util.Log.i("AiLocalModel", "尝试: $cmd")
                        onProgress(0f, "正在安装 $pkg（通过 $manager）…")
                        val r = runTermuxShell(context, cmd, timeoutSeconds = 900)
                        android.util.Log.i(
                            "AiLocalModel",
                            "  exit=${r.exitCode}, stdout=${r.stdout.take(300)}, stderr=${r.stderr.take(300)}"
                        )
                        installLogs.append("[$manager $pkg] exit=${r.exitCode}\n")
                        installLogs.append("stdout: ${r.stdout.take(500)}\n")
                        installLogs.append("stderr: ${r.stderr.take(500)}\n---\n")
                        if (r.success && isLlamaCppInstalled()) {
                            installed = true
                            break
                        }
                        if (isLlamaCppInstalled()) {
                            installed = true
                            break
                        }
                    }
                    if (installed) break
                }

                // 最后兜底：尝试从源码编译 llama.cpp（编译选项走 make，需要 build-essential 和 cmake）
                if (!installed) {
                    android.util.Log.w("AiLocalModel", "pkg/apt 安装失败，尝试编译安装兜底")
                    onProgress(0f, "包安装失败，正在准备编译环境（可能较慢）…")
                    val buildEnvCmd = "pkg install -y build-essential cmake git 2>&1"
                    val buildEnv = runTermuxShell(context, buildEnvCmd, timeoutSeconds = 900)
                    android.util.Log.i("AiLocalModel", "build env exit=${buildEnv.exitCode}, out=${buildEnv.stdout.take(300)}")

                    val compileScript = """
                        cd '${homeDir().absolutePath}' || exit 1
                        if [ ! -d llama.cpp-src ]; then
                            git clone --depth 1 https://github.com/ggerganov/llama.cpp.git llama.cpp-src 2>&1
                        fi
                        cd llama.cpp-src || exit 1
                        mkdir -p build-android && cd build-android
                        cmake .. -DLLAMA_BLAS=OFF -DLLAMA_NATIVE=ON -DCMAKE_BUILD_TYPE=Release 2>&1
                        make -j4 llama-cli llama-server 2>&1
                        cp -f bin/llama-cli '${prefixDir()}/bin/llama-cli' 2>/dev/null
                        cp -f bin/llama-server '${prefixDir()}/bin/llama-server' 2>/dev/null
                        chmod 755 '${prefixDir()}/bin/llama-cli' '${prefixDir()}/bin/llama-server' 2>/dev/null
                        echo 'DONE'
                    """.trimIndent()
                    val r = runTermuxShell(context, compileScript, timeoutSeconds = 1800)
                    android.util.Log.i(
                        "AiLocalModel",
                        "  compile exit=${r.exitCode}, stdout=${r.stdout.take(500)}, stderr=${r.stderr.take(500)}"
                    )
                    installed = isLlamaCppInstalled()
                }

                if (!isLlamaCppInstalled()) {
                    android.util.Log.e("AiLocalModel", "llama.cpp 安装失败，详情:\n$installLogs")
                    onProgress(
                        0f,
                        "llama.cpp 安装失败：请手动在 Termux 终端中执行「pkg update && pkg install -y llama.cpp」，或检查网络后重试"
                    )
                    resetLocalModelConfigIfConfigured()
                    return@withContext false
                }
            }

            // 2. 准备目录
            val dir = modelDir()
            if (!dir.exists()) dir.mkdirs()

            val target = modelFile(entry)
            val tmp = File(dir, "${entry.fileName}.part")

            // 2.5 如果临时文件已存在，支持断点续传（先看已下载多少字节）
            var existingBytes = 0L
            if (tmp.exists() && tmp.length() > 0) {
                existingBytes = tmp.length()
            }

            // 3. 下载模型（优先用 Termux curl，支持续传；失败回退到 HttpURLConnection）
            onProgress(0f, "开始下载模型…")
            var downloadSuccess = false
            var downloadLog = ""

            run tryCurl@ {
                val curlCmd = buildString {
                    append("curl -L --fail -C - --retry 3 --retry-delay 5")
                    append(" --connect-timeout 20 --max-time 7200")
                    append(" -o '${tmp.absolutePath}' '${entry.downloadUrl}' 2>&1")
                }
                android.util.Log.i("AiLocalModel", "使用 curl 下载: $curlCmd")

                // curl 下载是异步进度，这里起一个后台线程刷新进度（避免引入额外协程作用域）
                var stopMonitor = false
                val monitorThread = Thread({
                    while (!stopMonitor && !Thread.interrupted()) {
                        try {
                            Thread.sleep(1000)
                        } catch (_: InterruptedException) {
                            break
                        }
                        if (tmp.exists()) {
                            val cur = tmp.length()
                            if (entry.sizeBytes > 0) {
                                val p = (cur.toDouble() / entry.sizeBytes).coerceAtMost(0.99).toFloat()
                                onProgress(p, "下载中 ${(p * 100).toInt()}%  ($cur/${entry.sizeBytes} 字节)")
                            } else {
                                onProgress(0f, "下载中 ${cur / 1024 / 1024} MB…")
                            }
                        }
                    }
                }, "curl-monitor").apply { isDaemon = true; start() }

                val curlResult = runTermuxShell(context, curlCmd, timeoutSeconds = 7200)
                stopMonitor = true
                monitorThread.interrupt()
                monitorThread.join(500)
                android.util.Log.i(
                    "AiLocalModel",
                    "curl exit=${curlResult.exitCode}, stdout=${curlResult.stdout.take(200)}, stderr=${curlResult.stderr.take(200)}"
                )
                downloadSuccess = curlResult.success
                downloadLog = curlResult.stdout + "\n" + curlResult.stderr

                // curl exit=33 表示范围不支持（服务器不支持续传），删掉 part 重来
                if (!downloadSuccess && curlResult.exitCode == 33) {
                    tmp.delete()
                    val curlCmdNoResume = "curl -L --fail --retry 3 --retry-delay 5" +
                        " --connect-timeout 20 --max-time 7200" +
                        " -o '${tmp.absolutePath}' '${entry.downloadUrl}' 2>&1"
                    val r2 = runTermuxShell(context, curlCmdNoResume, timeoutSeconds = 7200)
                    downloadSuccess = r2.success
                    downloadLog = r2.stdout + "\n" + r2.stderr
                }

                if (downloadSuccess && tmp.exists() && tmp.length() > 0) return@tryCurl

                // curl 失败但文件存在且较大，可能进度显示有问题，也视为成功后续校验
                if (tmp.exists() && tmp.length() >= entry.sizeBytes * 0.9f) {
                    downloadSuccess = true
                    return@tryCurl
                }
            }

            // 兜底：使用 HttpURLConnection 直接下载
            if (!downloadSuccess) {
                android.util.Log.w("AiLocalModel", "curl 下载失败，使用 HttpURLConnection 兜底。curl 日志: ${downloadLog.take(300)}")
                onProgress(0f, "curl 下载失败，切换到内置下载器…")
                if (tmp.exists()) tmp.delete()
                existingBytes = 0L

                val connection = java.net.URL(entry.downloadUrl)
                    .openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 20_000
                connection.readTimeout = 120_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 TermuxUltra/1.0")
                if (existingBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=$existingBytes-")
                }
                connection.connect()

                val code = connection.responseCode
                if (code !in 200..299 && code != 206) {
                    onProgress(0f, "下载失败：HTTP $code")
                    connection.disconnect()
                    return@withContext false
                }

                val total = if (code == 206) {
                    existingBytes + connection.contentLengthLong
                } else {
                    connection.contentLengthLong
                }
                val started = System.currentTimeMillis()
                var downloaded = existingBytes

                val fos = if (code == 206 && existingBytes > 0) {
                    java.io.FileOutputStream(tmp, true)
                } else {
                    java.io.FileOutputStream(tmp)
                }

                connection.inputStream.use { ins ->
                    fos.use { outs ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val read = ins.read(buf)
                            if (read < 0) break
                            outs.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val p = (downloaded.toDouble() / total).toFloat()
                                val secs = (System.currentTimeMillis() - started).coerceAtLeast(1000) / 1000.0
                                val speed = (downloaded - existingBytes) / 1024.0 / 1024.0 / secs
                                onProgress(
                                    p,
                                    "下载中 ${(p * 100).toInt()}%  (${"%.1f".format(speed)} MB/s)"
                                )
                            } else {
                                onProgress(0f, "下载中 ${downloaded / 1024 / 1024} MB…")
                            }
                        }
                    }
                }
                connection.disconnect()
                downloadSuccess = true
            }

            // 4. 校验并重命名
            if (!tmp.exists() || tmp.length() == 0L) {
                onProgress(0f, "下载内容为空，请重试")
                tmp.delete()
                return@withContext false
            }

            // 文件大小合理性检查（>= 80% 预期体积即视为通过；有些模型文件实际大小略有差异）
            if (entry.sizeBytes > 0 && tmp.length() < entry.sizeBytes * 0.6f) {
                onProgress(
                    0f,
                    "下载文件过小（仅 ${tmp.length() / 1024 / 1024}MB，预期约 ${entry.sizeBytes / 1024 / 1024}MB），请检查网络后重试"
                )
                tmp.delete()
                return@withContext false
            }

            target.delete()
            if (!tmp.renameTo(target)) {
                // rename 失败兜底：复制再删除
                onProgress(0.95f, "正在整理文件…")
                try {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                } catch (e: Exception) {
                    onProgress(0f, "下载完成但写入最终文件失败: ${e.message}")
                    return@withContext false
                }
            }

            // 确保模型文件权限可读
            try {
                target.setReadable(true, false)
            } catch (_: Exception) {}

            setSelectedModelId(entry.id)
            setDownloadedAt(System.currentTimeMillis())
            onProgress(1f, "模型下载完成")
            true
        } catch (e: Exception) {
            try {
                val tmp = File(modelDir(), "${entry.fileName}.part")
                tmp.delete()
            } catch (_: Exception) {}
            android.util.Log.e("AiLocalModel", "Download failed: ${e.javaClass.name}", e)
            val detail = e.toString()
            val errorMsg = when {
                e is java.net.UnknownHostException -> "无法连接服务器，请检查网络或VPN"
                e is java.net.SocketTimeoutException -> "连接超时，请检查网络"
                e is javax.net.ssl.SSLException -> "SSL连接失败，请检查网络或VPN"
                e is java.io.IOException && e.message != null -> "网络错误：${e.message}"
                detail.contains("403") -> "下载被拒绝(403)，请检查网络"
                detail.contains("404") -> "文件不存在(404)，链接可能已失效"
                else -> "下载失败(${e.javaClass.simpleName})，请检查网络后重试"
            }
            resetLocalModelConfigIfConfigured()
            onProgress(0f, errorMsg)
            false
        }
    }

    /** 构造 Qwen chat 模板提示词 */
    fun buildChatPrompt(messages: List<OpenAiMessage>): String {
        val sb = StringBuilder()
        for (m in messages) {
            val role = when (m.role) {
                "system" -> "system"
                "assistant" -> "assistant"
                else -> "user"
            }
            sb.append("<|im_start|>$role\n${m.content}<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /** llama-cli 进程（根据实际可用的二进制路径构造） */
    /** llama-cli 进程（根据实际可用的二进制路径构造）：一次性纯输出模式 */
    private fun buildProcess(entry: LocalModelEntry, promptFile: File, temperature: Float): ProcessBuilder {
        val cliPath = findLlamaBinary()?.absolutePath ?: llamaCliPath()
        val args = mutableListOf<String>()
        args.add(cliPath)
        args.addAll(listOf("-m", modelFile(entry).absolutePath))
        args.addAll(listOf("-c", entry.maxContext.toString()))
        args.addAll(listOf("-n", entry.maxTokens.toString()))
        args.addAll(listOf("--temp", temperature.toString()))
        args.addAll(listOf("-s", "1"))
        args.addAll(listOf("-f", promptFile.absolutePath))
        args.add("--no-prompt")
        args.add("--no-color")
        args.add("--silent-prompt")
        args.add("--log-disable")
        args.addAll(listOf("-t", "4"))
        return ProcessBuilder(args)
    }

    /**
     * 构造 llama 推理进程时注入完整环境变量，避免 .so 加载失败。
     */
    private fun applyTermuxEnv(pb: ProcessBuilder) {
        val env = pb.environment()
        env["PATH"] = prefixDir() + "/bin:" + prefixDir() + "/bin/applets:" + (env["PATH"] ?: "")
        env["LD_LIBRARY_PATH"] = prefixDir() + "/lib"
        env["PREFIX"] = prefixDir()
        env["HOME"] = homeDir().absolutePath
        env["TMPDIR"] = prefixDir() + "/tmp"
        env["TERM"] = "xterm-256color"
        env["ANDROID_DATA"] = "/data"
        env["ANDROID_ROOT"] = "/system"
        env["EXTERNAL_STORAGE"] = "/sdcard"
        pb.directory(homeDir())
    }

    private fun writePromptFile(prompt: String): File? {
        return try {
            val dir = runtimeDir()
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "prompt_${System.currentTimeMillis()}.txt")
            f.writeText(prompt, Charsets.UTF_8)
            f
        } catch (e: Exception) {
            null
        }
    }

    private fun requireReady(): String? {
        val entry = getSelectedModel() ?: return "未配置本地大模型，请先完成下载配置"
        if (!isLlamaCppInstalled()) {
            resetLocalModelConfigIfConfigured()
            return "未检测到 llama.cpp，请先在「本地大模型」中完成下载与自动配置"
        }
        if (!isModelInstalled(entry)) {
            resetLocalModelConfigIfConfigured()
            return "本地模型文件不存在，请重新下载"
        }
        return null
    }

    /** stdout 解析状态（供 llama-cli fallback 路径过滤 banner/prompt echo） */
    private enum class ParseState { BANNER, PROMPT_ECHO, RESPONSE, DONE }


    /** 覆盖流式推理，逐片段 emit */
    /** 覆盖流式推理：优先 llama-server（避免重复加载），失败 fallback 到 llama-cli */
    fun chatStreamLocal(
        config: AiProviderConfig,
        messages: List<OpenAiMessage>,
        isCancelled: () -> Boolean
    ): Flow<StreamChunk> = flow {
        val notReady = requireReady()
        if (notReady != null) {
            emit(StreamChunk.Error(notReady))
            return@flow
        }
        val entry = getSelectedModel()!!

        val serverBin = findLlamaServerBinary()
        if (serverBin != null) {
            emit(StreamChunk.Prepare("正在启动本地推理服务…", "使用 llama-server 常驻模式以加速后续对话"))
            val serverReady = ensureServerStarted(entry)
            if (serverReady && pingServerPort(llamaServerPort)) {
                emit(StreamChunk.Prepare("模型已就绪，正在推理…", "通过本地 HTTP 接口调用"))
                var serverSucceeded = false
                try {
                    chatStreamLocalViaServer(entry, messages, config, isCancelled).collect { chunk ->
                        when (chunk) {
                            is StreamChunk.Content -> {
                                serverSucceeded = true
                                emit(chunk)
                            }
                            is StreamChunk.Done -> {
                                serverSucceeded = true
                                emit(chunk)
                            }
                            is StreamChunk.Error -> {
                                android.util.Log.w("AiLocalModel", "server 推理出错，回退到 cli: ${chunk.message}")
                                throw java.util.concurrent.CancellationException("server-fallback")
                            }
                            is StreamChunk.Cancelled -> {
                                emit(chunk)
                                return@collect
                            }
                            else -> emit(chunk)
                        }
                    }
                    if (serverSucceeded) return@flow
                } catch (cancel: java.util.concurrent.CancellationException) {
                    if (cancel.message != "server-fallback") {
                        return@flow
                    }
                } catch (_: Exception) {}
            } else {
                emit(StreamChunk.Prepare("常驻模式启动失败，切换到直接调用模式…", "fallback to llama-cli"))
            }
        }

        val promptText = buildChatPrompt(messages)
        val promptFile = writePromptFile(promptText)
            ?: run { emit(StreamChunk.Error("无法写入提示词临时文件")); return@flow }

        emit(StreamChunk.Prepare("正在加载模型并推理…", "直接调用 llama-cli 子进程"))

        var proc: Process? = null
        try {
            val pb = buildProcess(entry, promptFile, config.temperature)
            applyTermuxEnv(pb)
            pb.redirectErrorStream(false)
            proc = pb.start()

            val stderrLines = java.util.concurrent.CopyOnWriteArrayList<String>()
            val stderrThread = Thread({
                try {
                    proc?.errorStream?.bufferedReader(Charsets.UTF_8)?.forEachLine { line ->
                        stderrLines.add(line)
                    }
                } catch (_: Exception) {}
            }, "llama-stderr").apply { isDaemon = true; start() }

            val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
            val fullText = StringBuilder()

            var state = ParseState.BANNER
            var nonMatchingLines = 0

            val assistantMarker = "<|im_start|>assistant"
            val endMarker = "<|im_end|>"

            var line: String?
            while (true) {
                if (isCancelled()) {
                    proc.destroy()
                    proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    emit(StreamChunk.Cancelled)
                    break
                }
                line = reader.readLine() ?: break
                val trimmed = line.trimEnd()

                when (state) {
                    ParseState.BANNER -> {
                        if (trimmed.contains("<|im_start|>")) {
                            state = ParseState.PROMPT_ECHO
                            nonMatchingLines = 0
                            if (trimmed.contains(assistantMarker)) {
                                val idx = trimmed.indexOf(assistantMarker)
                                val after = trimmed.substring(idx + assistantMarker.length).trim()
                                val content = after.removePrefix(">").trimStart(' ', '\n', '	')
                                if (content.isNotEmpty() && content != endMarker) {
                                    fullText.append(content).append('\n')
                                    emit(StreamChunk.Content(content + "\n"))
                                    state = ParseState.RESPONSE
                                }
                                continue
                            }
                        } else {
                            val t = trimmed.trimStart { it <= ' ' || it == '█' || it == '=' || it == '-' || it == '>' || it == '<' }
                            if (t.startsWith("Loading model") || t.startsWith("build") || t.startsWith("model ") || t.startsWith("llama_model_loader")) {
                                val first80 = if (trimmed.length > 80) trimmed.take(80) else trimmed
                                emit(StreamChunk.Prepare("加载模型中…", first80))
                                nonMatchingLines = 0
                            } else if (trimmed.isNotBlank()) {
                                nonMatchingLines++
                                if (nonMatchingLines >= 5) {
                                    android.util.Log.w("AiLocalModel", "No ChatML markers detected, falling back to plain text capture")
                                    state = ParseState.RESPONSE
                                    fullText.append(trimmed).append('\n')
                                    emit(StreamChunk.Content(trimmed + "\n"))
                                    nonMatchingLines = 0
                                    continue
                                }
                            }
                            continue
                        }
                    }

                    ParseState.PROMPT_ECHO -> {
                        if (trimmed.contains(assistantMarker)) {
                            val idx = trimmed.indexOf(assistantMarker)
                            val after = trimmed.substring(idx + assistantMarker.length).trim()
                            val content = after.removePrefix(">").trimStart(' ', '\n', '	')
                            if (content.isNotEmpty() && content != endMarker) {
                                fullText.append(content).append('\n')
                                emit(StreamChunk.Content(content + "\n"))
                                state = ParseState.RESPONSE
                            }
                            nonMatchingLines = 0
                            continue
                        }
                        nonMatchingLines++
                        if (nonMatchingLines >= 10) {
                            android.util.Log.w("AiLocalModel", "Stuck in PROMPT_ECHO too long, falling back to plain text")
                            state = ParseState.RESPONSE
                            fullText.append(trimmed).append('\n')
                            emit(StreamChunk.Content(trimmed + "\n"))
                            nonMatchingLines = 0
                            continue
                        }
                        continue
                    }

                    ParseState.RESPONSE -> {
                        if (trimmed == endMarker
                            || trimmed.startsWith(endMarker)
                            || trimmed.startsWith("[ Prompt:")
                            || trimmed.startsWith("[Generation")
                            || trimmed.startsWith("generate:")
                            || trimmed.trimStart().startsWith("============")) {
                            state = ParseState.DONE
                            break
                        }
                        if (trimmed.isEmpty()) {
                            if (fullText.isNotEmpty() && !fullText.endsWith("\n")) {
                                fullText.append('\n')
                                emit(StreamChunk.Content("\n"))
                            }
                            continue
                        }
                        val content = if (trimmed.startsWith("> ")) trimmed.removePrefix("> ") else trimmed
                        if (content.isEmpty()) continue
                        fullText.append(content).append('\n')
                        emit(StreamChunk.Content(content + "\n"))
                    }

                    ParseState.DONE -> break
                }
            }

            runCatching { proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) }
            runCatching { stderrThread.join(3000) }

            val output = fullText.toString().trimEnd('\n').trim()
            android.util.Log.d("AiLocalModel", "CLI output length=${output.length}, first200=${output.take(200)}")
            emit(StreamChunk.Done(fullText = output))
        } catch (e: Exception) {
            android.util.Log.e("AiLocalModel", "Local inference (cli) failed", e)
            emit(StreamChunk.Error("本地模型推理失败：${e.message ?: e.javaClass.simpleName}"))
        } finally {
            try { proc?.destroy() } catch (_: Exception) {}
            try { promptFile.delete() } catch (_: Exception) {}
        }
    }

    /** 非流式本地调用，返回兼容响应体 */
    suspend fun completeLocal(
        config: AiProviderConfig,
        messages: List<OpenAiMessage>
    ): ChatCompletionResponse = withContext(Dispatchers.IO) {
        val notReady = requireReady()
        if (notReady != null) {
            return@withContext ChatCompletionResponse(
                error = ChatCompletionResponse.ApiError(notReady)
            )
        }
        val entry = getSelectedModel()!!
        val promptFile = writePromptFile(buildChatPrompt(messages))
            ?: return@withContext ChatCompletionResponse(
                error = ChatCompletionResponse.ApiError("无法写入提示词临时文件")
            )
        try {
            val pb = buildProcess(entry, promptFile, config.temperature)
            applyTermuxEnv(pb)
            pb.redirectErrorStream(false)
            val proc = pb.start()
            proc.errorStream.bufferedReader().use { it.readText() }
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            val output = text.trim('\n')
            if (output.isBlank()) {
                ChatCompletionResponse(error = ChatCompletionResponse.ApiError("本地模型返回为空"))
            } else {
                ChatCompletionResponse(
                    choices = listOf(
                        ChatCompletionResponse.Choice(
                            message = OpenAiMessage("assistant", output)
                        )
                    )
                )
            }
        } catch (e: Exception) {
            ChatCompletionResponse(
                error = ChatCompletionResponse.ApiError("本地模型推理失败：${e.message}")
            )
        } finally {
            promptFile.delete()
        }
    }
}
