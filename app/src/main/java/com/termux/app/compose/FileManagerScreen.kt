package com.termux.app.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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

@Composable
fun FileManagerScreen() {
    val context = LocalContext.current
    var currentPath by remember { mutableStateOf(File("/data/data/com.termux/files")) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var clipboardMode by remember { mutableStateOf(ClipboardMode.NONE) }
    var clipboardFile by remember { mutableStateOf<File?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showWarningCard by remember { mutableStateOf(false) }

    LaunchedEffect(currentPath) {
        files = currentPath.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
    }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("termux_prefs", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("files_warning_shown", false)) {
            showWarningCard = true
            prefs.edit().putBoolean("files_warning_shown", true).apply()
        }
    }

    val scrollBehavior = MiuixScrollBehavior()

    val canGoUp = currentPath.parentFile != null && !currentPath.absolutePath.equals(ROOT_PATH)

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.files_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    if (canGoUp) {
                        IconButton(onClick = {
                            currentPath = currentPath.parentFile!!
                            selectedFile = null
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                },
                actions = {
                    if (clipboardMode != ClipboardMode.NONE && clipboardFile != null) {
                        IconButton(onClick = {
                            clipboardFile?.let { srcFile ->
                                val destFile = File(currentPath, srcFile.name)
                                if (clipboardMode == ClipboardMode.CUT) {
                                    srcFile.renameTo(destFile)
                                } else {
                                    copyFile(srcFile, destFile)
                                }
                                clipboardMode = ClipboardMode.NONE
                                clipboardFile = null
                                files = currentPath.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_paste),
                                contentDescription = stringResource(R.string.paste),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    if (selectedFile != null) {
                        IconButton(onClick = {
                            clipboardMode = ClipboardMode.COPY
                            clipboardFile = selectedFile
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = stringResource(R.string.copy),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(onClick = {
                            clipboardMode = ClipboardMode.CUT
                            clipboardFile = selectedFile
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_cut),
                                contentDescription = stringResource(R.string.cut),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(onClick = {
                            newFileName = selectedFile?.name ?: ""
                            showRenameDialog = true
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_edit),
                                contentDescription = stringResource(R.string.rename),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        IconButton(onClick = {
                            showDeleteDialog = true
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.delete),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    IconButton(onClick = {
                        newFolderName = ""
                        showNewFolderDialog = true
                    }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = stringResource(R.string.folder),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            if (showWarningCard) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(16.dp))
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
                                .clickable { showWarningCard = false },
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = currentPath.absolutePath,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(12.dp)
                )
            }

            if (files.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = stringResource(R.string.empty_folder),
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp)
                ) {
                    items(files) { fileItem ->
                        FileItem(
                            file = fileItem,
                            isSelected = selectedFile?.absolutePath == fileItem.absolutePath,
                            onClick = {
                                if (fileItem.isDirectory) {
                                    currentPath = fileItem
                                    selectedFile = null
                                } else {
                                    selectedFile = if (selectedFile?.absolutePath == fileItem.absolutePath) null else fileItem
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_message)) },
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                Button(onClick = {
                    selectedFile?.deleteRecursively()
                    selectedFile = null
                    files = currentPath.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
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
        AlertDialog(
            title = { Text(stringResource(R.string.rename)) },
            onDismissRequest = { showRenameDialog = false },
            confirmButton = {
                Button(onClick = {
                    selectedFile?.let { oldFile ->
                        val newFile = File(currentPath, newFileName)
                        oldFile.renameTo(newFile)
                        selectedFile = null
                        files = currentPath.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
                    }
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
                    files = currentPath.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
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

@Composable
private fun FileItem(
    file: File,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.defaultColors(
            color = if (isSelected) MiuixTheme.colorScheme.surfaceVariant else MiuixTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    painter = painterResource(if (file.isDirectory) R.drawable.ic_folder else R.drawable.ic_file),
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                        .padding(end = 12.dp),
                    tint = MiuixTheme.colorScheme.onSurface
                )
                Column {
                    Text(
                        text = file.name,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 2.dp),
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (file.isDirectory) {
                            stringResource(R.string.folder)
                        } else {
                            "${formatFileSize(file.length())} - ${Date(file.lastModified()).toString()}"
                        },
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
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