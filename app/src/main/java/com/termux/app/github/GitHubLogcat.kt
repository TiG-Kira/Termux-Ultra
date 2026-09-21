package com.termux.app.github

import com.termux.app.utils.LogManager

/**
 * 收集本机「最近一阵」的应用 Logcat，用于随反馈 Issue 一起提交。
 * 优先读取真实 logcat 缓冲区；不可用时回退到 [LogManager] 已落盘的日志。
 */
object GitHubLogcat {

    private const val DEFAULT_MAX_LINES = 400
    private const val DEFAULT_MAX_CHARS = 24_000

    fun recent(maxLines: Int = DEFAULT_MAX_LINES, maxChars: Int = DEFAULT_MAX_CHARS): String {
        val text = readSystemLogcat(maxLines).takeIf { it.isNotBlank() }
            ?: readAppLog(maxLines)
        return clamp(text, maxChars)
    }

    private fun readSystemLogcat(maxLines: Int): String = runCatching {
        // Android 7+ 起应用只能读到自己进程的日志，因此这里无需额外按 pid 过滤
        val process = ProcessBuilder("logcat", "-d", "-t", maxLines.toString(), "-v", "time")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        process.destroy()
        text.trimEnd()
    }.getOrDefault("")

    private fun readAppLog(maxLines: Int): String = runCatching {
        LogManager.getInstance().getAllLogs()
            .take(maxLines)
            .asReversed()
            .joinToString("\n") { entry ->
                "[${entry.getFormattedTime()}] [${entry.getLevelString()}] [${entry.tag}] ${entry.message}"
            }
    }.getOrDefault("")

    /** 从尾部裁剪，保证最新的日志一定被保留 */
    private fun clamp(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return "…(省略较早日志)\n" + text.takeLast(maxChars)
    }
}
