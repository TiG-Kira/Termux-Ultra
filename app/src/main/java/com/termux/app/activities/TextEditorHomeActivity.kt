package com.termux.app.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.NavigationHelper
import com.termux.app.compose.RecentFilesManager
import com.termux.app.compose.TextEditorHomeScreen
import java.io.File
import java.io.FileOutputStream

/**
 * 文本编辑器主页 Activity：显示最近编辑文件，提供「新建」/「打开」入口。
 */
class TextEditorHomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)

            // 文件选择器：从系统选择文件后，拿到路径再打开编辑器
            var pendingOpenPath by remember { mutableStateOf<String?>(null) }

            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let {
                    val path = copyToTempAndGetPath(it)
                    if (path != null) {
                        pendingOpenPath = path
                    }
                }
            }

            // 一旦有了待打开的路径，立即跳转到编辑器
            androidx.compose.runtime.LaunchedEffect(pendingOpenPath) {
                pendingOpenPath?.let { path ->
                    openEditor(path)
                    pendingOpenPath = null
                }
            }

            CompositionLocalProvider(
                LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                KiTerminalTheme {
                    TextEditorHomeScreen(
                        onClose = { finish() },
                        onNewFile = { openEditor(null) },
                        onPickFile = { filePickerLauncher.launch("*/*") },
                        onOpenRecent = { path -> openEditor(path) }
                    )
                }
            }
        }
    }

    /** 启动 TextEditorActivity 打开文件（path=null 表示新建）。 */
    private fun openEditor(filePath: String?) {
        val intent = Intent(this, TextEditorActivity::class.java)
        if (filePath != null) {
            intent.putExtra("file_path", filePath)
            // 打开即视为一次最近使用（保存时也会记录）
            RecentFilesManager.addRecent(this, filePath)
        }
        startActivity(intent)
    }

    /**
     * 把系统返回的 Uri 复制到应用私有目录下的临时文件，返回绝对路径。
     * 这样 TextEditorActivity 就可以用普通 File API 来读写。
     */
    private fun copyToTempAndGetPath(uri: Uri): String? {
        return try {
            val resolver = contentResolver
            val fileName = resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex)
                else null
            } ?: "imported_${System.currentTimeMillis()}"

            val dest = File(cacheDir, "editor_import/$fileName").apply {
                parentFile?.mkdirs()
            }
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
            dest.absolutePath
        } catch (e: Exception) {
            null
        }
    }
}
