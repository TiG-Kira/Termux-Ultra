fp = r'D:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\AiLocalModel.kt'
with open(fp, 'r', encoding='utf-8-sig') as f:
    content = f.read()

old_func = '''    /** 覆盖流式推理，逐片段 emit */
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
    }'''

new_func = '''    /** 覆盖流式推理，逐片段 emit */
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

        var proc: Process? = null
        try {
            val pb = buildProcess(entry, promptFile, config.temperature)
            pb.redirectErrorStream(false)
            proc = pb.start()

            // 在后台线程读取 stderr，防止管道缓冲区满导致卡死
            val stderrThread = Thread({
                try {
                    proc?.errorStream?.bufferedReader()?.use { it.readText() }
                } catch (_: Exception) {}
            }, "llama-stderr").apply { isDaemon = true; start() }

            val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
            val fullText = StringBuilder()
            var line: String?
            while (true) {
                if (isCancelled()) {
                    proc.destroy()
                    proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                    emit(StreamChunk.Cancelled)
                    break
                }
                line = reader.readLine() ?: break
                if (line.isBlank()) continue
                fullText.append(line).append('\n')
                emit(StreamChunk.Content(line + "\n"))
            }
            proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            stderrThread.join(3000)
            val output = fullText.toString().trim('\n')
            emit(StreamChunk.Done(fullText = output))
        } catch (e: Exception) {
            android.util.Log.e("AiLocalModel", "Local inference failed", e)
            emit(StreamChunk.Error("本地模型推理失败：${e.message ?: e.javaClass.simpleName}"))
        } finally {
            try { proc?.destroy() } catch (_: Exception) {}
            promptFile.delete()
        }
    }'''

if old_func in content:
    content = content.replace(old_func, new_func)
    print('Fix chatStreamLocal: OK')
else:
    print('Fix chatStreamLocal: NOT FOUND')

with open(fp, 'w', encoding='utf-8') as f:
    f.write(content)
print('Done.')
