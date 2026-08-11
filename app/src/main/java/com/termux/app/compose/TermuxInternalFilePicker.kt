package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * Termux 内部文件选择器。
 *
 * 适用于浏览 /data/data/com.termux/ 目录下的文件（如磁盘镜像、ISO 等），
 * 避免使用系统文件选择器无法直接访问 Termux 私有目录的问题。
 *
 * 使用示例：
 * ```
 * TermuxInternalFilePicker(
 *     show = showPicker,
 *     title = "选择磁盘文件",
 *     fileExtensions = listOf("qcow2", "img", "raw", "vmdk"),
 *     onDismiss = { showPicker = false },
 *     onFileSelected = { path -> diskPath = path }
 * )
 * ```
 *
 * @param show 是否显示 BottomSheet
 * @param title 顶部标题
 * @param startDir 起始目录（绝对路径）。默认 Termux $HOME
 * @param fileExtensions 需要过滤的扩展名（不含点）。空列表表示不限制（只显示文件，过滤不可读项）。
 * @param allowFolders 是否允许把"文件夹"当作选择结果（特殊场景可能用到），默认 false
 * @param onDismiss 关闭回调
 * @param onFileSelected 文件被选中时回调，返回以 $HOME 开头的相对路径或保留绝对路径（当文件不在 $HOME 下时）
 */
@Composable
fun TermuxInternalFilePicker(
    show: Boolean,
    title: String = "选择文件",
    startDir: String = TERMUX_HOME_ABS,
    fileExtensions: List<String> = emptyList(),
    allowFolders: Boolean = false,
    onDismiss: () -> Unit,
    onFileSelected: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var currentDir by remember(show) { mutableStateOf(File(startDir)) }
    var entries by remember(show) { mutableStateOf<List<FileEntry>>(emptyList()) }
    var isLoading by remember(show) { mutableStateOf(true) }

    fun toDisplayPath(f: File): String {
        return f.absolutePath.replace(TERMUX_HOME_ABS, "\$HOME")
    }

    fun toSavedPath(f: File): String {
        val abs = f.absolutePath
        return if (abs.startsWith(TERMUX_HOME_ABS)) {
            "\$HOME" + abs.removePrefix(TERMUX_HOME_ABS)
        } else abs
    }

    suspend fun loadEntries(dir: File) {
        isLoading = true
        val result = withContext(Dispatchers.IO) {
            val list = dir.listFiles() ?: return@withContext emptyList<FileEntry>()
            val folders = mutableListOf<FileEntry>()
            val files = mutableListOf<FileEntry>()
            list.forEach { f ->
                try {
                    if (f.isDirectory && f.canRead()) {
                        folders.add(FileEntry(f, true))
                    } else if (f.isFile && f.canRead()) {
                        if (fileExtensions.isEmpty()) {
                            files.add(FileEntry(f, false))
                        } else {
                            val ext = f.extension.lowercase()
                            if (ext in fileExtensions.map { it.lowercase() }) {
                                files.add(FileEntry(f, false))
                            }
                        }
                    }
                } catch (_: SecurityException) {
                    // 权限不足跳过
                }
            }
            folders.sortBy { it.name.lowercase() }
            files.sortBy { it.name.lowercase() }
            folders + files
        }
        entries = result
        isLoading = false
    }

    LaunchedEffect(currentDir) {
        loadEntries(currentDir)
    }

    OverlayBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 320.dp, max = 560.dp)
            ) {
                // 路径栏 + 返回上级
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(enabled = currentDir.parentFile != null) {
                                scope.launch {
                                    currentDir.parentFile?.let { loadEntries(it) }
                                    currentDir.parentFile?.let { currentDir = it }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (currentDir.parentFile != null)
                                MiuixTheme.colorScheme.primary
                            else
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = toDisplayPath(currentDir),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "加载中...",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                } else if (entries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (fileExtensions.isNotEmpty())
                                "当前目录没有匹配的文件 (${fileExtensions.joinToString("/") { ".$it" }})"
                                else "当前目录为空",
                            fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                } else {
                    val isDark = isSystemInDarkTheme()
                    val itemBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFFAFAFA)

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(entries, key = { it.path }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(itemBg)
                                    .clickable {
                                        if (entry.isFolder) {
                                            scope.launch {
                                                currentDir = entry.file
                                                loadEntries(entry.file)
                                            }
                                        } else {
                                            onFileSelected(toSavedPath(entry.file))
                                        }
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MiuixTheme.colorScheme.surfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            if (entry.isFolder) R.drawable.ic_folder else R.drawable.ic_file
                                        ),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = if (entry.isFolder)
                                            Color(0xFF3F8DD6)
                                        else
                                            MiuixTheme.colorScheme.primary
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.name,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MiuixTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = buildString {
                                            if (entry.isFolder) {
                                                val subCount = try {
                                                    entry.file.listFiles()?.size ?: 0
                                                } catch (_: Exception) { 0 }
                                                append("$subCount 项")
                                            } else {
                                                append(formatFileSize(entry.file.length()))
                                            }
                                        },
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                                if (allowFolders && entry.isFolder) {
                                    TextButton(
                                        text = "选此目录",
                                        onClick = { onFileSelected(toSavedPath(entry.file)) },
                                        modifier = Modifier.wrapContentWidth()
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    )
}

/** 单个文件/文件夹条目，仅内部使用 */
private data class FileEntry(
    val file: File,
    val isFolder: Boolean
) {
    val name: String get() = file.name
    val path: String get() = file.absolutePath
}

/** Termux 主目录绝对路径常量，便于跨模块复用 */
const val TERMUX_HOME_ABS = "/data/data/com.termux/files/home"

/** 友好的文件大小显示（B/KB/MB/GB） */
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    }
}
