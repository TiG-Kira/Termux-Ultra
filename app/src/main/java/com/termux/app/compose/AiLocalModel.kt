package com.termux.app.compose

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
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
 * - 推理依赖 Termux 的 `llama.cpp` 包（提供 `llama-cli` 二进制）。
 * - 采用 Qwen2.5-0.5B-Instruct（Q4_K_M）作为面向 Android 设备的轻量模型，
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
    val recommendedRamMB: Int
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

    private fun ctx(): Context? = appContext

    /** Termux PREFIX 目录 */
    private fun prefixDir(): String = TermuxConstants.TERMUX_PREFIX_DIR_PATH

    /** Termux home 目录 */
    private fun homeDir(): File = TermuxConstants.TERMUX_HOME_DIR

    /** llama-cli 二进制路径 */
    fun llamaCliPath(): String = prefixDir() + "/bin/llama-cli"

    /** 模型根目录 */
    fun modelDir(): File = File(homeDir(), ".local/share/termux-ultra/llm")

    /** 本地大模型运行时临时目录 */
    private fun runtimeDir(): File = File(homeDir(), ".local/share/termux-ultra/run")

    fun getSelectedModelId(): String =
        ctx()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_SELECTED_MODEL_ID, null) ?: ""

    fun getSelectedModel(): LocalModelEntry? =
        LOCAL_MODELS.firstOrNull { it.id == getSelectedModelId() }

    fun setSelectedModelId(id: String) {
        ctx()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.putString(KEY_SELECTED_MODEL_ID, id)?.apply()
    }

    fun getDownloadedAt(): Long =
        ctx()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getLong(KEY_DOWNLOADED_AT, 0L) ?: 0L

    private fun setDownloadedAt(ts: Long) {
        ctx()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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

    /** llama.cpp 是否已安装 */
    fun isLlamaCppInstalled(): Boolean = File(llamaCliPath()).exists()

    /** 已安装模型占用磁盘空间 */
    fun getInstalledModelSize(): Long {
        val entry = getSelectedModel() ?: return 0L
        val f = modelFile(entry)
        return if (f.exists()) f.length() else 0L
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
        ctx()?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
     * 下载本地大模型（含进度回调）。
     * 若 llama.cpp 未安装，先执行 `pkg install -y llama.cpp`。
     * onProgress(progress 0..1, statusMessage)
     */
    suspend fun downloadModel(
        entry: LocalModelEntry,
        onProgress: (Float, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Check network connectivity first
            val context = ctx()
            if (context != null) {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val network = connectivityManager?.activeNetwork
                val capabilities = connectivityManager?.getNetworkCapabilities(network)
                if (capabilities == null || !capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    onProgress(0f, "无网络连接，请检查网络设置")
                    return@withContext false
                }
            }
            // 1. 确保 llama.cpp 已安装
            if (!isLlamaCppInstalled()) {
                onProgress(0f, "正在安装 llama.cpp 运行时…")
                runPkgInstall()
                if (!isLlamaCppInstalled()) {
                    onProgress(0f, "llama.cpp 安装失败，请稍后重试")
                    return@withContext false
                }
            }

            val dir = modelDir()
            if (!dir.exists()) dir.mkdirs()

            val target = modelFile(entry)
            val tmp = File(dir, "${entry.fileName}.part")

            // 2. 下载模型
            val connection = java.net.URL(entry.downloadUrl)
                .openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            connection.connect()

            val code = connection.responseCode
            if (code !in 200..299) {
                onProgress(0f, "下载失败：HTTP $code")
                connection.disconnect()
                return@withContext false
            }

            val total = connection.contentLengthLong
            val started = System.currentTimeMillis()
            var downloaded = 0L
            connection.inputStream.use { ins ->
                java.io.FileOutputStream(tmp).use { outs ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = ins.read(buf)
                        if (read < 0) break
                        outs.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val p = (downloaded.toDouble() / total).toFloat()
                            val secs = (System.currentTimeMillis() - started).coerceAtLeast(1000) / 1000.0
                            val speed = downloaded / 1024.0 / 1024.0 / secs
                            onProgress(p, "下载中 ${(p * 100).toInt()}%  (${"%.1f".format(speed)} MB/s)")
                        } else {
                            onProgress(0f, "下载中 ${downloaded / 1024 / 1024} MB…")
                        }
                    }
                }
            }
            connection.disconnect()

            // 3. 校验并重命名
            if (!tmp.exists() || tmp.length() == 0L) {
                onProgress(0f, "下载内容为空，请重试")
                tmp.delete()
                return@withContext false
            }
            target.delete()
            tmp.renameTo(target)

            setSelectedModelId(entry.id)
            setDownloadedAt(System.currentTimeMillis())
            onProgress(1f, "模型下载完成")
            true
        } catch (e: Exception) {
            try {
                val tmp = File(modelDir(), "${entry.fileName}.part")
                tmp.delete()
            } catch (_: Exception) {}
            onProgress(0f, "下载失败：${e.message ?: "未知错误"}")
            false
        }
    }

    private fun runPkgInstall() {
        try {
            val pb = ProcessBuilder(
                prefixDir() + "/bin/sh", "-c",
                "pkg install -y llama.cpp 2>&1"
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
        } catch (_: Exception) {}
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

    /** llama-cli 进程 */
    private fun buildProcess(entry: LocalModelEntry, promptFile: File, temperature: Float): ProcessBuilder {
        return ProcessBuilder(
            llamaCliPath(),
            "-m", modelFile(entry).absolutePath,
            "--no-display-prompt",
            "--simple-io",
            "-f", promptFile.absolutePath,
            "-n", entry.maxTokens.toString(),
            "--temp", temperature.toString(),
            "-s", "0"
        )
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
        if (!isLlamaCppInstalled()) return "未检测到 llama.cpp，请先在「本地大模型」中完成下载与自动配置"
        if (!isModelInstalled(entry)) return "本地模型文件不存在，请重新下载"
        return null
    }

    /** 覆盖流式推理，逐片段 emit */
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
        val promptFile = writePromptFile(buildChatPrompt(messages))
            ?: run { emit(StreamChunk.Error("无法写入提示词临时文件")); return@flow }

        try {
            val pb = buildProcess(entry, promptFile, config.temperature)
            pb.redirectErrorStream(false)
            val proc = pb.start()
            proc.errorStream.bufferedReader().use { it.readText() } // 丢弃加载日志

            val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
            val fullText = StringBuilder()
            var line: String?
            while (true) {
                if (isCancelled()) {
                    proc.destroy()
                    emit(StreamChunk.Cancelled)
                    break
                }
                line = reader.readLine() ?: break
                if (line.isBlank()) continue
                fullText.append(line).append('\n')
                emit(StreamChunk.Content(line + "\n"))
            }
            proc.waitFor()
            val output = fullText.toString().trim('\n')
            emit(StreamChunk.Done(fullText = output))
        } catch (e: Exception) {
            emit(StreamChunk.Error("本地模型推理失败：${e.message ?: "未知错误"}"))
        } finally {
            promptFile.delete()
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