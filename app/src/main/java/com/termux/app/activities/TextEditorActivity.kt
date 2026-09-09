package com.termux.app.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.core.view.WindowCompat
import com.termux.app.compose.TextEditorScreen
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.NavigationHelper
import java.io.File

/**
 * Termux 内部文本编辑器 Activity。
 *
 * 启动参数：
 *   Intent.EXTRA_TEXT (String)       —— 初始文件绝对路径（自定义 key "file_path"）
 *   "file_path" (String)             —— 初始文本
 *   "read_only" (Boolean)             —— 只读模式（可选）
 */
class TextEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val filePath = intent.getStringExtra("file_path")
        val initialText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val readOnly = intent.getBooleanExtra("read_only", false)

        val file: File? = if (filePath != null) File(filePath) else null

        val content = when {
            initialText != null -> initialText
            file != null && file.exists() -> try { file.readText() } catch (_: Exception) { "" }
            else -> ""
        }

        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                KiTerminalTheme {
                    TextEditorScreen(
                        initialContent = content,
                        filePath = filePath,
                        readOnly = readOnly,
                        onSave = { savePath, newText ->
                            if (!readOnly) {
                                try {
                                    val target = File(savePath)
                                    target.parentFile?.mkdirs()
                                    target.writeText(newText)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        this, "保存失败: ${e.message}", android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                    return@TextEditorScreen false
                                }
                            }
                            true
                        },
                        onClose = { finish() }
                    )
                }
            }
        }
    }
}
