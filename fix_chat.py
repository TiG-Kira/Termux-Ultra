fp = r'D:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\AiLocalModel.kt'
with open(fp, 'r', encoding='utf-8-sig') as f:
    lines = f.readlines()

# Find and replace the chatStreamLocal function body (lines 357-384)
# Replace lines 357-384 (0-indexed: 356-383)
new_body = [
    '        var proc: Process? = null\n',
    '        try {\n',
    '            val pb = buildProcess(entry, promptFile, config.temperature)\n',
    '            pb.redirectErrorStream(false)\n',
    '            proc = pb.start()\n',
    '\n',
    '            // 在后台线程读取 stderr，防止管道缓冲区满导致卡死\n',
    '            val stderrThread = Thread({\n',
    '                try {\n',
    '                    proc?.errorStream?.bufferedReader()?.use { it.readText() }\n',
    '                } catch (_: Exception) {}\n',
    '            }, "llama-stderr").apply { isDaemon = true; start() }\n',
    '\n',
    '            val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))\n',
    '            val fullText = StringBuilder()\n',
    '            var line: String?\n',
    '            while (true) {\n',
    '                if (isCancelled()) {\n',
    '                    proc.destroy()\n',
    '                    proc.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)\n',
    '                    emit(StreamChunk.Cancelled)\n',
    '                    break\n',
    '                }\n',
    '                line = reader.readLine() ?: break\n',
    '                if (line.isBlank()) continue\n',
    '                fullText.append(line).append(\'\\n\')\n',
    '                emit(StreamChunk.Content(line + "\\n"))\n',
    '            }\n',
    '            proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)\n',
    '            stderrThread.join(3000)\n',
    '            val output = fullText.toString().trim(\'\\n\')\n',
    '            emit(StreamChunk.Done(fullText = output))\n',
    '        } catch (e: Exception) {\n',
    '            android.util.Log.e("AiLocalModel", "Local inference failed", e)\n',
    '            emit(StreamChunk.Error("本地模型推理失败：${e.message ?: e.javaClass.simpleName}"))\n',
    '        } finally {\n',
    '            try { proc?.destroy() } catch (_: Exception) {}\n',
    '            promptFile.delete()\n',
    '        }\n',
]

lines[356:384] = new_body

with open(fp, 'w', encoding='utf-8') as f:
    f.writelines(lines)
print('Done.')

# Verify
with open(fp, 'r', encoding='utf-8-sig') as f:
    new_lines = f.readlines()
for i in range(356, 396):
    if i < len(new_lines):
        print(f'{i+1}: {new_lines[i]}', end='')
