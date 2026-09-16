package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.termux.R
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * Termux 内部文本编辑器主界面。
 *
 * @param initialContent 初始文本
 * @param filePath 文件路径（null=新建）
 * @param readOnly 只读模式
 * @param onSave 保存回调，返回 true 表示成功
 * @param onClose 关闭回调
 */
@Composable
fun TextEditorScreen(
    initialContent: String,
    filePath: String?,
    readOnly: Boolean = false,
    onSave: (path: String, content: String) -> Boolean,
    onClose: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val scrollBehavior = MiuixScrollBehavior()
    var currentFilePath by remember { mutableStateOf(filePath) }
    val file = remember(currentFilePath) { if (currentFilePath != null) File(currentFilePath) else null }
    val lang = remember(file) { SyntaxHighlighter.detectLanguage(file) }
    var content by remember { mutableStateOf(initialContent) }
    var modified by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showConfirmExit by remember { mutableStateOf(false) }

    // 保存位置选择器状态
    var showDirPicker by remember { mutableStateOf(false) }
    var showFileNameDialog by remember { mutableStateOf(false) }
    var pickedDir by remember { mutableStateOf<String?>(null) }
    var fileNameInput by remember { mutableStateOf("") }

    val fileName = file?.name ?: "未命名文件"
    val fileInfo = SyntaxHighlighter.fileInfo(file)
    val perms = SyntaxHighlighter.permissions(file)

    fun doSave() {
        if (currentFilePath != null) {
            val ok = onSave(currentFilePath!!, content)
            if (ok) modified = false
        } else {
            // 新建文件 → 先选目录
            showDirPicker = true
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = fileName,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (modified && !readOnly) showConfirmExit = true
                                else onClose()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    if (!readOnly) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { doSave() }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_save),
                                    contentDescription = stringResource(R.string.save),
                                    modifier = Modifier.size(22.dp),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.width(2.dp))
                            IconButton(onClick = { showPermissionDialog = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_terminal),
                                    contentDescription = stringResource(R.string.file_info_permissions),
                                    modifier = Modifier.size(22.dp),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            )
        },

    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            // 文件信息条
            if (file != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isDark) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val infoParts = fileInfo.split(" · ")
                    // 第一行：语言 · 扩展名 · 大小 · 权限 + 保存状态
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.uppercase(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                        infoParts.getOrNull(0)?.let { ext ->
                            Text(
                                text = "· $ext",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        infoParts.getOrNull(1)?.let { size ->
                            Text(
                                text = "· $size",
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        if (perms.isNotBlank()) {
                            Text(
                                text = "· [$perms]",
                                fontSize = 12.sp,
                                color = if (perms.contains('w')) Color(0xFFFF9F0A) else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (modified) "● 未保存" else "✓ 已保存",
                            fontSize = 11.sp,
                            color = if (modified) Color(0xFFFF9F0A) else MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    // 第二行：修改时间
                    infoParts.getOrNull(2)?.let { date ->
                        Text(
                            text = "修改时间 $date",
                            fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // 编辑器主体
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .background(
                        if (isDark) Color(0xFF0A0A0C) else Color(0xFFFFFFFF)
                    )
                    .padding(8.dp)
            ) {
                val lineCount = content.count { it == '\n' } + 1
                Text(
                    text = (1..lineCount).joinToString("\n"),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(end = 8.dp)
                )
                BasicTextField(
                    value = content,
                    onValueChange = {
                        content = it
                        modified = true
                    },
                    readOnly = readOnly,
                    maxLines = Int.MAX_VALUE,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = if (isDark) Color(0xFFE5E5EA) else Color(0xFF1C1C1E),
                        lineHeight = 20.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(start = 40.dp),
                    decorationBox = { innerTextField ->
                        Box {
                            if (content.isEmpty()) {
                                Text(
                                    text = "在此输入...",
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }
        }

        if (showConfirmExit) {
            OverlayDialog(
                show = showConfirmExit,
                onDismissRequest = { showConfirmExit = false },
                title = stringResource(R.string.unsaved_changes),
                summary = "文件有未保存的修改，确定要关闭吗？",
                content = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showConfirmExit = false },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "保存并退出",
                            onClick = {
                                doSave()
                                showConfirmExit = false
                                onClose()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            )
        }

        if (showPermissionDialog && file != null) {
            FilePermissionDialog(
                file = file,
                onDismiss = { showPermissionDialog = false }
            )
        }

        // 保存位置选择器（新建文件时）
        TermuxInternalFilePicker(
            show = showDirPicker,
            title = "选择保存目录",
            allowFolders = true,
            onDismiss = { showDirPicker = false },
            onFileSelected = { path ->
                pickedDir = path
                fileNameInput = "untitled"
                showDirPicker = false
                showFileNameDialog = true
            }
        )

        if (showFileNameDialog && pickedDir != null) {
            OverlayDialog(
                show = true,
                onDismissRequest = { showFileNameDialog = false },
                title = "保存文件",
                summary = "保存到: $pickedDir",
                content = {
                    androidx.compose.material3.OutlinedTextField(
                        value = fileNameInput,
                        onValueChange = { fileNameInput = it },
                        label = { Text("文件名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showFileNameDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = stringResource(R.string.save),
                            onClick = {
                                if (fileNameInput.isNotBlank()) {
                                    val targetPath = File(pickedDir, fileNameInput).absolutePath
                                    val ok = onSave(targetPath, content)
                                    if (ok) {
                                        currentFilePath = targetPath
                                        modified = false
                                    }
                                }
                                showFileNameDialog = false
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            )
        }
    }
}


private fun tokenStyle(type: SyntaxHighlighter.Type, isDark: Boolean): SpanStyle {
    return when (type) {
        SyntaxHighlighter.Type.KEYWORD -> SpanStyle(
            color = if (isDark) Color(0xFFFF6482) else Color(0xFFC74DB6),
            fontWeight = FontWeight.Bold
        )
        SyntaxHighlighter.Type.STRING -> SpanStyle(
            color = if (isDark) Color(0xFF98FB98) else Color(0xFF0A8F08)
        )
        SyntaxHighlighter.Type.COMMENT -> SpanStyle(
            color = if (isDark) Color(0xFF6B7280) else Color(0xFF8B949E)
        )
        SyntaxHighlighter.Type.NUMBER -> SpanStyle(
            color = if (isDark) Color(0xFFFFD700) else Color(0xFF986801)
        )
        SyntaxHighlighter.Type.FUNCTION -> SpanStyle(
            color = if (isDark) Color(0xFF56B6C2) else Color(0xFF4078F2)
        )
        SyntaxHighlighter.Type.OPERATOR, SyntaxHighlighter.Type.BRACE -> SpanStyle(
            color = if (isDark) Color(0xFFD4D4D4) else Color(0xFF383A42)
        )
        SyntaxHighlighter.Type.DEFAULT -> SpanStyle(
            color = if (isDark) Color(0xFFE5E5EA) else Color(0xFF1C1C1E)
        )
    }
}

@Composable
private fun FilePermissionDialog(file: File, onDismiss: () -> Unit) {
    var readable by remember { mutableStateOf(file.canRead()) }
    var writable by remember { mutableStateOf(file.canWrite()) }
    var executable by remember { mutableStateOf(file.canExecute()) }

    OverlayDialog(
        show = true,
        onDismissRequest = onDismiss,
        title = "文件权限",
        summary = file.absolutePath,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PermissionRow("可读 (r)", readable) { readable = it }
                PermissionRow("可写 (w)", writable) { writable = it }
                PermissionRow("可执行 (x)", executable) { executable = it }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = stringResource(R.string.app_settings),
                        onClick = {
                            try {
                                file.setReadable(readable)
                                file.setWritable(writable)
                                file.setExecutable(executable)
                            } catch (_: Exception) {}
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    )
}

@Composable
private fun PermissionRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onToggle
        )
    }
}
