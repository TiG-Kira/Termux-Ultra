package com.termux.app.compose.terminal.view

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.termux.app.compose.terminal.engine.TerminalEmulator

/**
 * 终端剪贴板网关：封装系统剪贴板的写入与读取。
 * 粘贴时通过回调将文本送入模拟器并唤醒光标。
 */
internal class TerminalClipboard(
    private val context: Context,
    private val emulatorProvider: () -> TerminalEmulator?,
    private val pokeCursor: () -> Unit
) {

    /** 复制文本到系统剪贴板，空文本忽略。 */
    fun copyText(text: String) {
        if (text.isEmpty()) return
        val clipboardManager = context.getSystemService(ClipboardManager::class.java)
        val clipData = ClipData.newPlainText("", text)
        clipboardManager.setPrimaryClip(clipData)
    }

    /** 读取系统剪贴板并粘贴到终端，剪贴板为空或无文本时忽略。 */
    fun pasteFromClipboard() {
        val emulator = emulatorProvider() ?: return
        val clipboardManager = context.getSystemService(ClipboardManager::class.java)
        val clipData = clipboardManager.primaryClip ?: return
        val clipItem = clipData.getItemAt(0) ?: return
        val text = clipItem.coerceToText(context)?.toString() ?: return
        if (text.isNotEmpty()) {
            pokeCursor()
            emulator.paste(text)
        }
    }
}
