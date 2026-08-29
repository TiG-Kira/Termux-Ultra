package com.termux.app.compose

import android.content.Context
import android.util.Log
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
import java.io.File

/** Ollama模型条目 */
data class OllamaModelEntry(
    val id: String,
    val displayName: String,
    val description: String,
    val ollamaModelName: String,  // ollama pull 使用的模型名
    val sizeDescription: String,
    val recommendedRamMB: Int
)

/** 内置可选Ollama模型列表 */
val OLLAMA_MODELS: List<OllamaModelEntry> = listOf(
    OllamaModelEntry(
        id = "llama3.2-1b",
        displayName = "Llama 3.2 (1B)",
        description = "Meta最新轻量模型，适合移动设备",
        ollamaModelName = "llama3.2:1b",
        sizeDescription = "约 1.3 GB",
        recommendedRamMB = 3072
    ),
    OllamaModelEntry(
        id = "qwen2.5-1.5b",
        displayName = "Qwen 2.5 (1.5B)",
        description = "阿里云开源模型，中文能力强",
        ollamaModelName = "qwen2.5:1.5b",
        sizeDescription = "约 1.1 GB",
        recommendedRamMB = 3072
    ),
    OllamaModelEntry(
        id = "gemma3-1b",
        displayName = "Gemma 3 (1B)",
        description = "Google开源模型，性能优秀",
        ollamaModelName = "gemma3:1b",
        sizeDescription = "约 1.0 GB",
        recommendedRamMB = 3072
    ),
    OllamaModelEntry(
        id = "phi3-mini",
        displayName = "Phi 3 Mini",
        description = "微软轻量模型，推理速度快",
        ollamaModelName = "phi3:mini",
        sizeDescription = "约 2.0 GB",
        recommendedRamMB = 4096
    ),
    OllamaModelEntry(
        id = "deepseek-r1-1.5b",
        displayName = "DeepSeek R1 (1.5B)",
        description = "深度思考模型，推理能力强",
        ollamaModelName = "deepseek-r1:1.5b",
        sizeDescription = "约 1.2 GB",
        recommendedRamMB = 3072
    )
)

/** Ollama管理器 */
object AiOllamaManager {
    private const val TAG = "AiOllamaManager"
    private const val OLLAMA_PORT = 11434

    @Volatile
    private var appContext: Context? = null

    /** 初始化 */
    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun context(): Context? = appContext

    /** Termux PREFIX 目录 */
    private fun prefixDir(): String = TermuxConstants.TERMUX_PREFIX_DIR_PATH

    /** Termux home 目录 */
    private fun homeDir(): File = TermuxConstants.TERMUX_HOME_DIR

    /** Ollama 可执行文件路径 */
    fun ollamaPath(): String = "${prefixDir()}/bin/ollama"

    /** 检查 Ollama 是否已安装 */
    fun isOllamaInstalled(): Boolean {
        val ollamaFile = File(ollamaPath())
        val exists = ollamaFile.exists() && ollamaFile.canExecute()
        Log.d(TAG, "isOllamaInstalled: path=${ollamaPath()}, exists=${ollamaFile.exists()}, canExe=${ollamaFile.canExecute()}, result=$exists")
        return exists
    }

    /** 检查 Ollama 服务是否在运行（纯检查不抛异常） */
    fun isOllamaRunning(): Boolean {
        // 方法1: 检查端口（最简单可靠）
        runCatching {
            val sock = java.net.Socket()
            sock.connect(java.net.InetSocketAddress("127.0.0.1", OLLAMA_PORT), 500)
            sock.close()
            Log.d(TAG, "isOllamaRunning: port $OLLAMA_PORT open")
            return true
        }
        Log.d(TAG, "isOllamaRunning: port $OLLAMA_PORT not open")
        return false
    }

    /** 获取已安装的模型列表 */
    suspend fun getInstalledModels(): List<String> = withContext(Dispatchers.IO) {
        if (!isOllamaInstalled()) return@withContext emptyList()
        
        // 方法1: 优先通过 HTTP API 获取（服务在运行时）
        if (isOllamaRunning()) {
            runCatching {
                val url = java.net.URL("http://127.0.0.1:$OLLAMA_PORT/api/tags")
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2000
                    readTimeout = 2000
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val body = conn.inputStream.bufferedReader().readText()
                    Log.i(TAG, "getInstalledModels: HTTP response: ${body.take(200)}")
                    conn.disconnect()
                    return@withContext parseModelListFromApi(body)
                }
                conn.disconnect()
            }
        }
        
        // 方法2: 通过 shell 命令
        val result = runShellCommand("export OLLAMA_HOST=127.0.0.1:$OLLAMA_PORT; '${ollamaPath()}' list 2>&1")
        Log.i(TAG, "getInstalledModels: exit=${result.exitCode}, stdout=${result.stdout.take(200)}")
        if (!result.success) return@withContext emptyList()
        parseModelList(result.stdout)
    }
    
    /** 从 Ollama HTTP API 响应解析模型列表 */
    private fun parseModelListFromApi(json: String): List<String> {
        val models = mutableListOf<String>()
        runCatching {
            val gson = com.google.gson.Gson()
            val map = gson.fromJson(json, Map::class.java)
            val modelsArr = map["models"] as? List<*>
            modelsArr?.forEach { item ->
                val m = item as? Map<*, *>
                val name = m?.get("name") as? String
                if (name != null) models.add(name)
            }
        }
        Log.i(TAG, "parseModelListFromApi: found $models")
        return models
    }

    private fun parseModelList(output: String): List<String> {
        val models = mutableListOf<String>()
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.size > 1) {
            for (i in 1 until lines.size) {
                val parts = lines[i].trim().split(Regex("\\s+"))
                if (parts.isNotEmpty()) {
                    models.add(parts[0])
                }
            }
        }
        return models
    }

    /** 运行 shell 命令 */
    private suspend fun runShellCommand(command: String, timeoutSeconds: Int = 300): ShellResult = withContext(Dispatchers.IO) {
        val ctx = context() ?: return@withContext ShellResult(false, null, "", "Context 未初始化")
        
        Log.i(TAG, "runShellCommand: command=${command.take(100)}, timeout=${timeoutSeconds}s")
        
        val shellPath = resolveTermuxShell()
        if (shellPath == null) {
            Log.e(TAG, "runShellCommand: 找不到 shell 环境")
            return@withContext ShellResult(false, null, "", "找不到 shell 环境")
        }
        Log.i(TAG, "runShellCommand: using shell=$shellPath")

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
                ctx,
                executionCommand,
                null,
                shellEnvClient,
                false
            )
        } catch (e: Exception) {
            Log.e(TAG, "TermuxTask 创建失败", e)
            return@withContext ShellResult(false, null, "", "TermuxTask 创建失败: ${e.message}")
        }

        Log.i(TAG, "runShellCommand: 开始执行命令")
        
        val startTime = System.currentTimeMillis()
        val timeoutMs = timeoutSeconds * 1000L
        var completed = false

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            delay(200)
            if (executionCommand.hasExecuted() || executionCommand.resultData.exitCode != null) {
                completed = true
                Log.i(TAG, "runShellCommand: 命令执行完成, exitCode=${executionCommand.resultData.exitCode}")
                break
            }
            // 每30秒输出一次状态
            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            if (elapsed > 0 && elapsed % 30 == 0L) {
                Log.d(TAG, "runShellCommand: 已等待 ${elapsed}s，命令仍在执行...")
            }
        }

        if (!completed) {
            Log.w(TAG, "runShellCommand: 命令超时 (${timeoutSeconds}s)")
            runCatching { termuxTask?.killIfExecuting(ctx, false) }
            return@withContext ShellResult(false, null, "", "命令执行超时（${timeoutSeconds}秒）")
        }

        val resultData = executionCommand.resultData
        val stdout = resultData.stdout.toString()
        val stderr = resultData.stderr.toString()
        val exitCode = resultData.exitCode

        Log.i(TAG, "runShellCommand: exitCode=$exitCode, stdout.length=${stdout.length}, stderr.length=${stderr.length}")
        if (stderr.isNotEmpty()) {
            Log.w(TAG, "runShellCommand: stderr=${stderr.take(500)}")
        }

        ShellResult(
            success = completed && exitCode == 0,
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr
        )
    }

    /** Shell 执行结果 */
    private data class ShellResult(
        val success: Boolean,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String
    )

    /** 解析 Termux shell 路径 */
    private fun resolveTermuxShell(): String? {
        // 方法1: 从 TermuxShellUtils 获取
        val binDir = TermuxShellUtils.getDefaultBinPath()
        if (binDir.isNotEmpty()) {
            for (shellBinary in arrayOf("bash", "login", "zsh", "sh", "dash")) {
                val shellFile = File(binDir, shellBinary)
                if (shellFile.canExecute()) {
                    Log.i(TAG, "找到 shell: ${shellFile.absolutePath}")
                    return shellFile.absolutePath
                }
            }
        }
        
        // 方法2: 检查 Termux PREFIX/bin 目录
        val prefixBinDir = File("${TermuxConstants.TERMUX_PREFIX_DIR_PATH}/bin")
        if (prefixBinDir.exists() && prefixBinDir.isDirectory) {
            for (shellBinary in arrayOf("bash", "login", "zsh", "sh", "dash")) {
                val shellFile = File(prefixBinDir, shellBinary)
                if (shellFile.canExecute()) {
                    Log.i(TAG, "从 prefix/bin 找到 shell: ${shellFile.absolutePath}")
                    return shellFile.absolutePath
                }
            }
        }
        
        // 方法3: 使用系统 shell
        val systemShell = File("/system/bin/sh")
        if (systemShell.canExecute()) {
            Log.i(TAG, "使用系统 shell: ${systemShell.absolutePath}")
            return systemShell.absolutePath
        }
        
        // 方法4: 检查其他可能的路径
        val altPaths = listOf(
            "/system/bin/bash",
            "/system/bin/dash",
            "/bin/sh",
            "/bin/bash"
        )
        for (path in altPaths) {
            val file = File(path)
            if (file.canExecute()) {
                Log.i(TAG, "找到备用 shell: $path")
                return path
            }
        }
        
        Log.e(TAG, "找不到可用的 shell 环境")
        return null
    }

    /** 安装 Ollama */
    suspend fun installOllama(onProgress: (Float, String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val ctx = context()
        if (ctx == null) {
            Log.e(TAG, "installOllama: Context 未初始化")
            onProgress(0f, "内部错误：Context 未初始化，请重启应用")
            return@withContext false
        }

        Log.i(TAG, "installOllama: 开始安装 Ollama")
        Log.i(TAG, "installOllama: prefixDir=${prefixDir()}")
        Log.i(TAG, "installOllama: homeDir=${homeDir().absolutePath}")
        
        onProgress(0f, "正在更新 Termux 包索引…")
        val updateResult = runShellCommand("pkg update -y 2>&1", timeoutSeconds = 300)
        Log.i(TAG, "pkg update: exit=${updateResult.exitCode}, success=${updateResult.success}")
        if (!updateResult.success) {
            Log.w(TAG, "pkg update 失败: ${updateResult.stderr.take(300)}")
            onProgress(0f, "包索引更新失败，可能网络异常")
            // 即使更新失败也继续尝试安装
        }

        onProgress(0.2f, "正在安装 Ollama 依赖（curl、tar）…")
        val depsResult = runShellCommand("pkg install -y curl tar 2>&1", timeoutSeconds = 300)
        Log.i(TAG, "deps install: exit=${depsResult.exitCode}, success=${depsResult.success}")
        if (!depsResult.success) {
            Log.e(TAG, "依赖安装失败: ${depsResult.stderr.take(300)}")
            onProgress(0f, "依赖安装失败: ${depsResult.stderr.take(100)}")
            return@withContext false
        }

        onProgress(0.4f, "正在下载 Ollama 二进制文件…")
        val installScript = """
            set -e
            cd '${homeDir().absolutePath}'
            echo "下载目录: ${'$'}(pwd)"
            # 检测架构并选择正确的二进制
            ARCH=${'$'}(uname -m)
            echo "检测到架构: ${'$'}ARCH"
            case "${'$'}ARCH" in
                aarch64|arm64)
                    OLLAMA_URL="https://ollama.com/download/ollama-linux-arm64.tgz"
                    OLLAMA_FILE="ollama-linux-arm64.tgz"
                    ;;
                x86_64|amd64)
                    OLLAMA_URL="https://ollama.com/download/ollama-linux-amd64.tgz"
                    OLLAMA_FILE="ollama-linux-amd64.tgz"
                    ;;
                *)
                    echo "不支持的架构: ${'$'}ARCH，尝试 arm64"
                    OLLAMA_URL="https://ollama.com/download/ollama-linux-arm64.tgz"
                    OLLAMA_FILE="ollama-linux-arm64.tgz"
                    ;;
            esac
            if [ ! -f "${'$'}OLLAMA_FILE" ]; then
                echo "开始下载 ollama (${'$'}OLLAMA_URL)..."
                curl -L --fail -o "${'$'}OLLAMA_FILE" "${'$'}OLLAMA_URL" 2>&1
                echo "下载完成，文件大小: ${'$'}(stat -c%s "${'$'}OLLAMA_FILE" 2>/dev/null || ls -la "${'$'}OLLAMA_FILE")"
            else
                echo "ollama 文件已存在 (${'$'}OLLAMA_FILE)，跳过下载"
            fi
            # 解压 tgz 找到 ollama 二进制
            tar -xzf "${'$'}OLLAMA_FILE" 2>&1
            tar_exit="${'$'}?"
            if [ "${'$'}tar_exit" -ne 0 ]; then
                echo "tar 解压失败 exit=${'$'}tar_exit"
                exit "${'$'}tar_exit"
            fi
            OLLAMA_BIN=""
            if [ -f "./ollama" ]; then
                OLLAMA_BIN="./ollama"
            elif [ -f "./ollama-linux-arm64" ]; then
                OLLAMA_BIN="./ollama-linux-arm64"
            elif [ -f "./bin/ollama" ]; then
                OLLAMA_BIN="./bin/ollama"
            else
                echo "解压后找不到 ollama 二进制，tgz 内容:"
                tar -tzf "${'$'}OLLAMA_FILE" | head -20
                exit 1
            fi
            chmod +x "${'$'}OLLAMA_BIN"
            mkdir -p '${prefixDir()}/bin'
            cp "${'$'}OLLAMA_BIN" '${ollamaPath()}'
            chmod +x '${ollamaPath()}'
            echo "Ollama 安装到: ${ollamaPath()}"
            ls -la '${ollamaPath()}'
            echo 'DONE'
        """.trimIndent()

        Log.i(TAG, "installOllama: 执行安装脚本")
        val result = runShellCommand(installScript, timeoutSeconds = 600)
        Log.i(TAG, "ollama install: exit=${result.exitCode}, success=${result.success}")
        Log.i(TAG, "ollama install: stdout=${result.stdout.take(300)}")
        if (result.stderr.isNotEmpty()) {
            Log.w(TAG, "ollama install: stderr=${result.stderr.take(300)}")
        }

        if (result.success && isOllamaInstalled()) {
            Log.i(TAG, "ollama install: 安装成功")
            onProgress(1f, "Ollama 安装完成")
            true
        } else {
            Log.e(TAG, "ollama install: 安装失败, success=${result.success}, isInstalled=${isOllamaInstalled()}")
            onProgress(0f, "Ollama 安装失败：${result.stderr.take(200)}")
            false
        }
    }

    /** 拉取模型 */
    suspend fun pullModel(modelName: String, onProgress: (Float, String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "pullModel: start, model=$modelName")
        
        if (!isOllamaInstalled()) {
            Log.e(TAG, "pullModel: Ollama 未安装，无法下载模型")
            onProgress(0f, "请先安装 Ollama")
            return@withContext false
        }

        // 确保 Ollama 服务在运行，不运行就启动
        if (!isOllamaRunning()) {
            Log.i(TAG, "pullModel: Ollama 未运行，启动服务...")
            onProgress(0f, "正在启动 Ollama 服务…")
            val started = startOllamaService()
            if (!started) {
                Log.e(TAG, "pullModel: Ollama 服务启动失败，尝试直接运行 ollama pull")
            } else {
                Log.i(TAG, "pullModel: Ollama 服务启动成功，等待初始化...")
                delay(3000)
            }
        } else {
            Log.i(TAG, "pullModel: Ollama 已在运行")
        }

        onProgress(0.05f, "正在下载模型 $modelName …")
        
        // 方案1: 通过 HTTP API 下载（服务在运行时）
        if (isOllamaRunning()) {
            Log.i(TAG, "pullModel: 使用 HTTP API 下载模型")
            try {
                val url = java.net.URL("http://127.0.0.1:$OLLAMA_PORT/api/pull")
                val bodyMap = mapOf("name" to modelName, "stream" to true)
                val gson = com.google.gson.Gson()
                val bodyBytes = gson.toJson(bodyMap).toByteArray(Charsets.UTF_8)
                
                val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 60 * 60 * 1000  // 1小时超时
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    doInput = true
                }
                conn.outputStream.use { it.write(bodyBytes) }
                
                val code = conn.responseCode
                Log.i(TAG, "pullModel: HTTP POST responseCode=$code")
                
                if (code in 200..299) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, Charsets.UTF_8))
                    var line: String?
                    var lastStatus = ""
                    var lastTotal = 0L
                    var lastCompleted = 0L
                    while (true) {
                        line = reader.readLine()
                        if (line == null) break
                        if (line.isBlank()) continue
                        runCatching {
                            val resp = gson.fromJson(line, Map::class.java)
                            val status = resp["status"] as? String ?: ""
                            val total = resp["total"] as? Double ?: (resp["total"] as? Long)?.toDouble() ?: 0.0
                            val completed = resp["completed"] as? Double ?: (resp["completed"] as? Long)?.toDouble() ?: 0.0
                            if (status != lastStatus || total != lastTotal.toDouble() || completed != lastCompleted.toDouble()) {
                                lastStatus = status
                                lastTotal = total.toLong()
                                lastCompleted = completed.toLong()
                                val progress = if (total > 0) (completed / total).toFloat() else 0f
                                val msg = if (total > 0) {
                                    "$status (${(completed/1024/1024).toInt()}MB / ${(total/1024/1024).toInt()}MB)"
                                } else {
                                    status
                                }
                                onProgress(progress * 0.95f + 0.05f, msg)
                                Log.d(TAG, "pullModel progress: $msg")
                            }
                        }
                    }
                    conn.disconnect()
                    
                    // 下载完成后验证
                    delay(1000)
                    val installedNow = getInstalledModels()
                    val ok = installedNow.any { it.startsWith(modelName) || modelName.startsWith(it) }
                    if (ok) {
                        onProgress(1f, "模型 $modelName 下载完成")
                        Log.i(TAG, "pullModel: HTTP 下载成功, model=$modelName")
                        return@withContext true
                    } else {
                        Log.w(TAG, "pullModel: HTTP 下载完成但验证失败，重试命令行方式")
                    }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                    Log.e(TAG, "pullModel: HTTP 错误: $err")
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "pullModel: HTTP API 异常", e)
            }
        }
        
        // 方案2: 命令行 fallback
        Log.i(TAG, "pullModel: 使用命令行 fallback")
        onProgress(0.05f, "通过命令行下载模型 $modelName …")
        val script = """
            export OLLAMA_HOST=127.0.0.1:$OLLAMA_PORT
            echo "开始 pull $modelName ..."
            '${ollamaPath()}' pull $modelName 2>&1
            echo "pull 完成，exit=$?"
        """.trimIndent()

        val result = runShellCommand(script, timeoutSeconds = 3600)
        Log.i(TAG, "pullModel: exit=${result.exitCode}, success=${result.success}")
        Log.i(TAG, "pullModel: stdout=${result.stdout.take(500)}")
        if (result.stderr.isNotEmpty()) {
            Log.e(TAG, "pullModel: stderr=${result.stderr.take(500)}")
        }
        
        if (result.success && (result.stdout.contains("pulling") || result.stdout.contains("success") || result.exitCode == 0)) {
            onProgress(1f, "模型 $modelName 下载完成")
            true
        } else {
            val errMsg = buildString {
                append("模型下载失败")
                if (result.stderr.isNotEmpty()) append(": ${result.stderr.take(200)}")
                else if (result.stdout.isNotEmpty()) append(": ${result.stdout.take(200)}")
            }
            Log.e(TAG, "pullModel: $errMsg")
            onProgress(0f, errMsg)
            false
        }
    }

    /** 启动 Ollama 服务 */
    suspend fun startOllamaService(): Boolean = withContext(Dispatchers.IO) {
        if (!isOllamaInstalled()) {
            Log.e(TAG, "startOllamaService: Ollama 未安装 at ${ollamaPath()}")
            return@withContext false
        }
        if (isOllamaRunning()) {
            Log.i(TAG, "startOllamaService: Ollama 已在运行")
            return@withContext true
        }

        val ctx = context()
        if (ctx == null) {
            Log.e(TAG, "startOllamaService: Context 未初始化")
            return@withContext false
        }
        
        Log.i(TAG, "startOllamaService: 正在启动 Ollama 服务...")
        Log.i(TAG, "startOllamaService: ollamaPath=${ollamaPath()}")
        
        // 先杀掉可能残留的旧进程
        runShellCommand("pkill -f 'ollama serve' 2>/dev/null; sleep 1", timeoutSeconds = 5)

        // 使用 nohup + 完整环境变量启动
        val script = """
            export OLLAMA_HOST=127.0.0.1:$OLLAMA_PORT
            export OLLAMA_MODELS='${homeDir().absolutePath}/.ollama/models'
            export PATH='${prefixDir()}/bin:${'$'}PATH'
            export LD_LIBRARY_PATH='${prefixDir()}/lib'
            mkdir -p '${homeDir().absolutePath}/.ollama'
            nohup '${ollamaPath()}' serve > /tmp/ollama.log 2>&1 &
            echo "PID=${'$'}!"
            sleep 2
            cat /tmp/ollama.log 2>/dev/null | head -20
        """.trimIndent()

        val result = runShellCommand(script, timeoutSeconds = 5)
        Log.i(TAG, "startOllamaService: exitCode=${result.exitCode}, stdout=${result.stdout.take(200)}, stderr=${result.stderr.take(200)}")
        
        // 等待服务启动
        delay(3000)
        
        // 检查日志
        val logCheck = runCatching {
            val logFile = File("/tmp/ollama.log")
            if (logFile.exists()) {
                val content = logFile.readText().take(500)
                Log.i(TAG, "startOllamaService: ollama log: $content")
            }
        }
        
        if (isOllamaRunning()) {
            Log.i(TAG, "startOllamaService: Ollama 服务启动成功")
            return@withContext true
        }
        
        // 再等几秒重试检查
        delay(3000)
        if (isOllamaRunning()) {
            Log.i(TAG, "startOllamaService: Ollama 服务启动成功 (第二次检查)")
            return@withContext true
        }
        
        Log.e(TAG, "startOllamaService: Ollama 服务启动失败")
        false
    }

    /** 停止 Ollama 服务 */
    suspend fun stopOllamaService(): Boolean = withContext(Dispatchers.IO) {
        val script = "pkill -f 'ollama serve' 2>/dev/null; sleep 1; echo 'stopped'"
        val result = runShellCommand(script, timeoutSeconds = 10)
        result.success
    }

    /** 删除模型 */
    suspend fun deleteModel(modelName: String): Boolean = withContext(Dispatchers.IO) {
        if (!isOllamaInstalled()) return@withContext false
        
        val script = "ollama rm $modelName 2>&1"
        val result = runShellCommand(script, timeoutSeconds = 60)
        result.success
    }

    /** 使用 Ollama 进行流式推理 */
    fun chatStreamOllama(
        modelName: String,
        messages: List<OpenAiMessage>,
        temperature: Float = 0.7f,
        isCancelled: () -> Boolean
    ): Flow<StreamChunk> = flow {
        if (!isOllamaRunning()) {
            // 尝试启动服务
            val started = startOllamaService()
            if (!started) {
                emit(StreamChunk.Error("Ollama 服务启动失败"))
                return@flow
            }
            delay(1000)
        }

        val url = java.net.URL("http://127.0.0.1:$OLLAMA_PORT/api/chat")
        
        val requestMessages = messages.map { m ->
            linkedMapOf("role" to m.role, "content" to m.content)
        }
        
        val reqBody = linkedMapOf(
            "model" to modelName,
            "messages" to requestMessages,
            "stream" to true,
            "options" to linkedMapOf(
                "temperature" to temperature
            )
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
                Log.e(TAG, "Ollama API error: $err")
                emit(StreamChunk.Error("Ollama 推理服务异常: $err"))
                return@flow
            }

            val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val fullText = StringBuilder()
            var line: String?
            
            while (true) {
                if (isCancelled()) {
                    emit(StreamChunk.Cancelled)
                    break
                }
                line = reader.readLine()
                if (line == null) break
                
                runCatching {
                    val json = gson.fromJson(line, java.util.LinkedHashMap::class.java)
                    val message = json["message"] as? Map<*, *>
                    val content = message?.get("content") as? String
                    val done = json["done"] as? Boolean ?: false
                    
                    if (!content.isNullOrEmpty()) {
                        fullText.append(content)
                        emit(StreamChunk.Content(content))
                    }
                    
                    if (done) break
                }
            }
            
            emit(StreamChunk.Done(fullText = fullText.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "Ollama 流式推理失败", e)
            emit(StreamChunk.Error("Ollama 推理失败: ${e.message ?: e.javaClass.simpleName}"))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
