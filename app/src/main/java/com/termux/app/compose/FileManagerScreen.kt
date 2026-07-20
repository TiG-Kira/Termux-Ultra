package com.termux.app.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.termux.R
import java.io.File
import java.util.Date

enum class ClipboardMode {
    NONE, COPY, CUT
}

private const val ROOT_PATH = "/data/data/com.termux"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(
    onOpenFile: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf(File("/data/data/com.termux/files")) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isInSelectionMode by remember { mutableStateOf(false) }
    var clipboardMode by remember { mutableStateOf(ClipboardMode.NONE) }
    var clipboardFiles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showWarningCard by remember { mutableStateOf(false) }
    var showOpenWithDialog by remember { mutableStateOf(false) }
    var fileToOpen by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(currentPath) {
        files = currentPath.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
        selectedFiles = emptySet()
        isInSelectionMode = false
    }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("files_warning_shown", false)) {
            showWarningCard = true
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    val canGoUp = currentPath.parentFile != null && !currentPath.absolutePath.equals(ROOT_PATH)

    BackHandler(enabled = canGoUp) {
        currentPath = currentPath.parentFile!!
    }

    fun refreshFiles() {
        files = currentPath.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = if (isInSelectionMode) {
                    "${selectedFiles.size} ${stringResource(R.string.items)}"
                } else {
                    stringResource(R.string.files_title)
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (isInSelectionMode) {
                        IconButton(onClick = {
                            selectedFiles = emptySet()
                            isInSelectionMode = false
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    } else if (canGoUp) {
                        IconButton(onClick = {
                            currentPath = currentPath.parentFile!!
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_up),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                },
                actions = {
                    if (isInSelectionMode && selectedFiles.isNotEmpty()) {
                        IconButton(onClick = {
                            clipboardMode = ClipboardMode.COPY
                            clipboardFiles = selectedFiles.toSet()
                            selectedFiles = emptySet()
                            isInSelectionMode = false
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = stringResource(R.string.copy),
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = {
                            clipboardMode = ClipboardMode.CUT
                            clipboardFiles = selectedFiles.toSet()
                            selectedFiles = emptySet()
                            isInSelectionMode = false
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_cut),
                                contentDescription = stringResource(R.string.cut),
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        if (selectedFiles.size == 1) {
                            IconButton(onClick = {
                                newFileName = File(selectedFiles.first()).name
                                showRenameDialog = true
                            }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_edit),
                                    contentDescription = stringResource(R.string.rename),
                                    modifier = Modifier.size(24.dp),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                        IconButton(onClick = {
                            showDeleteDialog = true
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.delete),
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (clipboardMode != ClipboardMode.NONE && clipboardFiles.isNotEmpty()) {
                        IconButton(onClick = {
                            clipboardFiles.forEach { srcPath ->
                                val srcFile = File(srcPath)
                                val destFile = File(currentPath, srcFile.name)
                                if (clipboardMode == ClipboardMode.CUT) {
                                    srcFile.renameTo(destFile)
                                } else {
                                    copyFile(srcFile, destFile)
                                }
                            }
                            clipboardMode = ClipboardMode.NONE
                            clipboardFiles = emptySet()
                            refreshFiles()
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_paste),
                                contentDescription = stringResource(R.string.paste),
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }

                    if (!isInSelectionMode) {
                        IconButton(onClick = {
                            newFolderName = ""
                            showNewFolderDialog = true
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_add),
                                contentDescription = stringResource(R.string.folder),
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (showWarningCard) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSystemInDarkTheme()) Color(0xFF2C2C2C) else Color.White
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.files_warning_message),
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f),
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.ok),
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable {
                                        showWarningCard = false
                                        val prefs = context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
                                        prefs.edit().putBoolean("files_warning_shown", true).apply()
                                    },
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSystemInDarkTheme()) Color(0xFF2C2C2C) else Color.White
                    )
                ) {
                    Text(
                        text = currentPath.absolutePath,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            if (files.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 100.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.empty_folder),
                            fontSize = 16.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            } else {
                items(files) { fileItem ->
                    FileItem(
                        file = fileItem,
                        isSelected = selectedFiles.contains(fileItem.absolutePath),
                        isInSelectionMode = isInSelectionMode,
                        onClick = {
                            if (isInSelectionMode) {
                                selectedFiles = if (selectedFiles.contains(fileItem.absolutePath)) {
                                    selectedFiles - fileItem.absolutePath
                                } else {
                                    selectedFiles + fileItem.absolutePath
                                }
                                if (selectedFiles.isEmpty()) isInSelectionMode = false
                            } else {
                                if (fileItem.isDirectory) {
                                    currentPath = fileItem
                                } else {
                                    fileToOpen = fileItem
                                    showOpenWithDialog = true
                                }
                            }
                        },
                        onLongClick = {
                            if (isInSelectionMode) {
                                selectedFiles = if (selectedFiles.contains(fileItem.absolutePath)) {
                                    selectedFiles - fileItem.absolutePath
                                } else {
                                    selectedFiles + fileItem.absolutePath
                                }
                            } else {
                                selectedFiles = setOf(fileItem.absolutePath)
                                isInSelectionMode = true
                            }
                        }
                    )
                }
            }
        }
    }

    if (showOpenWithDialog && fileToOpen != null) {
        val file = fileToOpen!!
        val isShFile = file.name.endsWith(".sh", ignoreCase = true)
        val isDark = isSystemInDarkTheme()
        
        AlertDialog(
            title = { Text(file.name) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "大小: ${android.text.format.Formatter.formatFileSize(context, file.length())}",
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (isShFile) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDark) Color(0xFF3D3514) else Color(0xFFFFF9C4)
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.shell_script_warning),
                                    fontSize = 12.sp,
                                    color = if (isDark) Color.White else Color.Black
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    Text(stringResource(R.string.select_open_method), fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpenFile(file.absolutePath, "cat \"${file.absolutePath}\"")
                                showOpenWithDialog = false
                                fileToOpen = null
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(stringResource(R.string.view_content))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpenFile(file.absolutePath, "vi \"${file.absolutePath}\"")
                                showOpenWithDialog = false
                                fileToOpen = null
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(stringResource(R.string.edit_file))
                    }
                    
                    if (isShFile) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("exec_command", "bash \"${file.absolutePath}\"")
                                    clipboard.setPrimaryClip(clip)
                                    showOpenWithDialog = false
                                    fileToOpen = null
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(stringResource(R.string.copy_exec_command))
                        }
                    }
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onOpenFile(file.absolutePath, "bash \"${file.absolutePath}\"")
                                showOpenWithDialog = false
                                fileToOpen = null
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_terminal),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(stringResource(R.string.execute_bash))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("path", file.absolutePath)
                                clipboard.setPrimaryClip(clip)
                                showOpenWithDialog = false
                                fileToOpen = null
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_copy),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(stringResource(R.string.copy_path))
                    }
                }
            },
            onDismissRequest = {
                showOpenWithDialog = false
                fileToOpen = null
            },
            confirmButton = {
                TextButton(onClick = {
                    showOpenWithDialog = false
                    fileToOpen = null
                }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            dismissButton = {}
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text("${stringResource(R.string.delete_confirm_message)} (${selectedFiles.size})") },
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                Button(onClick = {
                    selectedFiles.forEach { path ->
                        File(path).deleteRecursively()
                    }
                    selectedFiles = emptySet()
                    isInSelectionMode = false
                    refreshFiles()
                    showDeleteDialog = false
                }) {
                    Text(stringResource(R.string.delete_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showRenameDialog) {
        val renameFile = File(selectedFiles.first())
        AlertDialog(
            title = { Text(stringResource(R.string.rename)) },
            onDismissRequest = { showRenameDialog = false },
            confirmButton = {
                Button(onClick = {
                    val newFile = File(renameFile.parentFile, newFileName)
                    renameFile.renameTo(newFile)
                    selectedFiles = emptySet()
                    isInSelectionMode = false
                    refreshFiles()
                    showRenameDialog = false
                }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            text = {
                TextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    label = { Text(stringResource(R.string.file_name)) }
                )
            }
        )
    }

    if (showNewFolderDialog) {
        AlertDialog(
            title = { Text(stringResource(R.string.folder)) },
            onDismissRequest = { showNewFolderDialog = false },
            confirmButton = {
                Button(onClick = {
                    val newFolder = File(currentPath, newFolderName)
                    newFolder.mkdirs()
                    refreshFiles()
                    showNewFolderDialog = false
                }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            text = {
                TextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text(stringResource(R.string.file_name)) }
                )
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    file: File,
    isSelected: Boolean,
    isInSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MiuixTheme.colorScheme.surfaceVariant else MiuixTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isInSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            Icon(
                painter = painterResource(if (file.isDirectory) R.drawable.ic_folder else R.drawable.ic_file),
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .padding(end = 12.dp),
                tint = MiuixTheme.colorScheme.onSurface
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface
                )
                Text(
                    text = if (file.isDirectory) {
                        val count = file.listFiles()?.size ?: 0
                        "$count ${stringResource(R.string.items)}"
                    } else {
                        "${formatFileSize(file.length())} - ${Date(file.lastModified()).toString()}"
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            if (file.isDirectory && !isInSelectionMode) {
                IconButton(onClick = { onClick() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_right),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${String.format("%.2f", bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${String.format("%.2f", bytes / (1024.0 * 1024))} MB"
        else -> "${String.format("%.2f", bytes / (1024.0 * 1024 * 1024))} GB"
    }
}

private fun copyFile(src: File, dest: File) {
    if (src.isDirectory) {
        dest.mkdirs()
        src.listFiles()?.forEach { child ->
            copyFile(child, File(dest, child.name))
        }
    } else {
        src.copyTo(dest, overwrite = true)
    }
}
