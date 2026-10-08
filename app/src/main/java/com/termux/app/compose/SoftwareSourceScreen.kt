package com.termux.app.compose

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.Add
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 解析后的 apt 软件源条目。
 */
data class AptSource(
    val id: String,              // 唯一 id (hash)
    val file: String,            // 所属文件路径（主 sources.list 或 sources.list.d/xxx.list）
    val lineNumber: Int,         // 在文件中的行号
    val rawLine: String,         // 原始行（deb ...）
    val options: String,         // 方括号内的选项内容（不含方括号），如 "arch=amd64 trusted=yes"
    val url: String,             // 源地址
    val suite: String,           // suite / codename，如 stable, jammy
    val components: String,      // 组件，如 "main contrib non-free"
    val isEnabled: Boolean       // 是否启用（deb 开头且不以 # 开头）
)

private val AccentBlue = Color(0xFF2563EB)

/**
 * 解析一行 apt sources.list 内容。
 * 返回 null 表示不是有效源条目（注释、空行、ppa 等）。
 */
private val debLineRegex = Regex(
    """^\s*deb(?:-src)?\s+(?:\[([^\]]*)\]\s+)?(\S+)\s+(\S+)(?:\s+(.*))?\s*$"""
)

private fun parseSourceLine(line: String, file: String, lineNumber: Int): AptSource? {
    val trimmed = line.trim()
    if (trimmed.isBlank() || trimmed.startsWith("#")) return null
    val match = debLineRegex.find(trimmed) ?: return null
    val options = match.groupValues[1]?.trim().orEmpty()
    val url = match.groupValues[2].trim()
    val suite = match.groupValues[3].trim()
    val components = match.groupValues[4]?.trim().orEmpty()
    val id = "$file:$lineNumber".hashCode().toString(16)
    return AptSource(
        id = id,
        file = file,
        lineNumber = lineNumber,
        rawLine = trimmed,
        options = options,
        url = url,
        suite = suite,
        components = components,
        isEnabled = true
    )
}

/**
 * 把 AptSource 转回 deb 行格式。
 */
private fun AptSource.toDebLine(): String {
    val prefix = "deb"
    val optPart = if (options.isNotBlank()) "[$options] " else ""
    val compPart = components.ifBlank { "" }
    return "$prefix $optPart$url $suite${if (compPart.isNotBlank()) " $compPart" else ""}".trim()
}

/**
 * 读取并解析所有 apt 软件源。
 */
private suspend fun loadAllSources(context: Context): List<AptSource> = withContext(Dispatchers.IO) {
    val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
    val sources = mutableListOf<AptSource>()
    val seenLines = mutableSetOf<String>()

    // 读取主 sources.list
    val mainFile = File("$prefix/etc/apt/sources.list")
    if (mainFile.exists()) {
        mainFile.readLines().forEachIndexed { idx, line ->
            val source = parseSourceLine(line, mainFile.absolutePath, idx)
            if (source != null && source.rawLine !in seenLines) {
                sources.add(source)
                seenLines.add(source.rawLine)
            }
        }
    }

    // 读取 sources.list.d/*.list
    val sourcesDir = File("$prefix/etc/apt/sources.list.d")
    if (sourcesDir.isDirectory) {
        sourcesDir.listFiles { f -> f.name.endsWith(".list") }?.forEach { file ->
            file.readLines().forEachIndexed { idx, line ->
                val source = parseSourceLine(line, file.absolutePath, idx)
                if (source != null && source.rawLine !in seenLines) {
                    sources.add(source)
                    seenLines.add(source.rawLine)
                }
            }
        }
    }

    sources
}

/**
 * 追加一个新源到 sources.list。
 */
private suspend fun addSource(context: Context, source: AptSource): Boolean = withContext(Dispatchers.IO) {
    return@withContext try {
        val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        val file = File("$prefix/etc/apt/sources.list")
        val line = source.toDebLine()
        file.appendText("\n$line\n")
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * 从文件中删除指定行，保存修改。
 * 简化实现：重写整个文件，去掉匹配的 rawLine。
 */
private suspend fun removeSource(context: Context, source: AptSource): Boolean = withContext(Dispatchers.IO) {
    return@withContext try {
        val file = File(source.file)
        if (!file.exists()) return@withContext false
        val lines = file.readLines().toMutableList()
        // 找到匹配的行并删除
        val iter = lines.iterator()
        while (iter.hasNext()) {
            val l = iter.next()
            if (l.trim() == source.rawLine) {
                iter.remove()
                break
            }
        }
        file.writeText(lines.joinToString("\n") + "\n")
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * 更新一个现有源：先删除旧行，再在原位置插入新行。
 * 简化实现：直接重写整个文件，替换匹配行。
 */
private suspend fun updateSource(context: Context, oldSource: AptSource, newSource: AptSource): Boolean = withContext(Dispatchers.IO) {
    return@withContext try {
        val file = File(oldSource.file)
        if (!file.exists()) return@withContext false
        val lines = file.readLines().toMutableList()
        // 替换匹配行
        for (i in lines.indices) {
            if (lines[i].trim() == oldSource.rawLine) {
                lines[i] = newSource.toDebLine()
                break
            }
        }
        file.writeText(lines.joinToString("\n") + "\n")
        true
    } catch (e: Exception) {
        false
    }
}

/**
 * Ping 一个源 URL 来测试连接性。
 */
private suspend fun pingSource(context: Context, url: String): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
    return@withContext try {
        val startTime = System.currentTimeMillis()
        val (code, _) = AppShell.exec(
            context,
            "curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 ${shellQuote(url)} 2>/dev/null",
            timeout = 10
        )
        val elapsed = System.currentTimeMillis() - startTime
        Pair(code == 0, elapsed)
    } catch (e: Exception) {
        Pair(false, 0)
    }
}

/** Shell 安全引用：用单引号包裹并转义内部单引号。 */
private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * 构造函数，暴露给 SettingsScreen 跳转到本页。
 */
fun openSoftwareSourceSettings(context: Context) {
    context.startActivity(android.content.Intent(context, com.termux.app.activities.SoftwareSourceActivity::class.java))
}

@Composable
fun SoftwareSourceScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()
    val glassPage = rememberGlassPageBackdrop()

    var sources by remember { mutableStateOf<List<AptSource>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showEditDialog by remember { mutableStateOf(false) }
    var editingSource by remember { mutableStateOf<AptSource?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var sourceToDelete by remember { mutableStateOf<AptSource?>(null) }
    var pingingId by remember { mutableStateOf<String?>(null) }
    var pingResult by remember { mutableStateOf<Map<String, Pair<Boolean, Long>>>(emptyMap()) }

    LaunchedEffect(Unit) {
        isLoading = true
        sources = loadAllSources(context)
        isLoading = false
    }

    fun refresh() {
        scope.launch {
            isLoading = true
            sources = loadAllSources(context)
            isLoading = false
        }
    }

    fun openAdd() {
        editingIsNew = true
        editingSource = null
        showEditDialog = true
    }

    fun openEdit(source: AptSource) {
        editingIsNew = false
        editingSource = source
        showEditDialog = true
    }

    fun saveSource(url: String, suite: String, components: String, options: String) {
        if (url.isBlank() || suite.isBlank()) {
            Toast.makeText(context, context.getString(R.string.pkg_source_url), Toast.LENGTH_SHORT).show()
            return
        }
        val newSource = AptSource(
            id = if (editingIsNew) System.currentTimeMillis().toString(16) else editingSource!!.id,
            file = if (editingIsNew) {
                TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/etc/apt/sources.list"
            } else {
                editingSource!!.file
            },
            lineNumber = if (editingIsNew) -1 else editingSource!!.lineNumber,
            rawLine = "",
            options = options.trim(),
            url = url.trim(),
            suite = suite.trim(),
            components = components.trim(),
            isEnabled = true
        )
        scope.launch {
            val ok = if (editingIsNew) {
                addSource(context, newSource)
            } else {
                updateSource(context, editingSource!!, newSource)
            }
            if (ok) {
                Toast.makeText(context, context.getString(R.string.pkg_source_saved), Toast.LENGTH_SHORT).show()
                showEditDialog = false
                refresh()
            } else {
                Toast.makeText(context, context.getString(R.string.pkg_source_ping_fail), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun deleteSource(source: AptSource) {
        scope.launch {
            val ok = removeSource(context, source)
            if (ok) {
                Toast.makeText(context, context.getString(R.string.pkg_source_deleted), Toast.LENGTH_SHORT).show()
                sourceToDelete = null
                refresh()
            } else {
                Toast.makeText(context, context.getString(R.string.pkg_source_ping_fail), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun ping(source: AptSource) {
        scope.launch {
            pingingId = source.id
            val result = pingSource(context, source.url)
            pingResult = pingResult + (source.id to result)
            pingingId = null
        }
    }

    Scaffold(
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.pkg_sources_title),
                backdrop = glassPage.backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = { onBack() }) {
                        Icon(
                            imageVector = MiuixGlassIcons.ChevronBackward,
                            contentDescription = stringResource(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    GlassIconButton(onClick = { openAdd() }) {
                        Icon(
                            imageVector = MiuixGlassIcons.Add,
                            contentDescription = stringResource(R.string.pkg_source_add),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .then(glassPage.contentModifier)
                .fillMaxSize()
                .padding(pagePaddingWithoutTop(innerPadding))
        ) {
            if (isLoading) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = AccentBlue)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.initializing),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else if (sources.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_package),
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.pkg_sources_empty),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        // 需要留出 GlassTopAppBar 的高度，否则内容会冲顶栏。
                        // topBarClearance(innerPadding) 就是 Scaffold 给出的 top bar 实际高度
                        // （会随折叠动态变化），额外 +6.dp 是和其他子页面一致的视觉间距。
                        top = topBarClearance(innerPadding) + 6.dp,
                        bottom = 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(sources, key = { it.id }) { source ->
                        SourceCard(
                            source = source,
                            isPinging = pingingId == source.id,
                            pingResult = pingResult[source.id],
                            onPing = { ping(source) },
                            onEdit = { openEdit(source) },
                            onDelete = { sourceToDelete = source }
                        )
                    }
                }
            }
        }

        // 编辑 / 新增 Dialog
        if (showEditDialog) {
            SourceEditDialog(
                isNew = editingIsNew,
                initial = editingSource,
                onDismiss = { showEditDialog = false },
                onSave = { url, suite, components, options ->
                    saveSource(url, suite, components, options)
                }
            )
        }

        // 删除确认 Dialog
        sourceToDelete?.let { source ->
            OverlayDialog(
                show = true,
                title = stringResource(R.string.pkg_source_delete),
                summary = stringResource(R.string.pkg_source_delete_confirm) + "\n\n" + source.url,
                onDismissRequest = { sourceToDelete = null },
                content = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { sourceToDelete = null },
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = { deleteSource(source) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(color = Color(0xFFDC2626))
                        ) {
                            Text("删除", color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun SourceCard(
    source: AptSource,
    isPinging: Boolean,
    pingResult: Pair<Boolean, Long>?,
    onPing: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val parsedUrl = try { Uri.parse(source.url).host ?: source.url } catch (_: Exception) { source.url }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // 标题行：host + 删除/编辑/ping 按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = parsedUrl,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                // 操作按钮行
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Ping
                    GlassIconButton(onClick = onPing) {
                        if (isPinging) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = AccentBlue, strokeWidth = 2.dp)
                        } else {
                            val color = when {
                                pingResult == null -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                pingResult.first -> AccentBlue
                                else -> Color(0xFFDC2626)
                            }
                            Icon(
                                imageVector = Icons.Rounded.NetworkPing,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    // 编辑
                    GlassIconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // 删除
                    GlassIconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = null,
                            tint = Color(0xFFDC2626),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 详情行
            Text(
                text = source.url,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                lineHeight = 16.sp
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = buildString {
                    append(source.suite)
                    if (source.components.isNotBlank()) {
                        append(" · ")
                        append(source.components)
                    }
                },
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )

            // Ping 结果
            pingResult?.let { (ok, ms) ->
                Spacer(Modifier.height(6.dp))
                val text = if (ok) {
                    context.getString(R.string.pkg_source_ping_ok, ms)
                } else {
                    context.getString(R.string.pkg_source_ping_fail)
                }
                Text(
                    text = text,
                    fontSize = 12.sp,
                    color = if (ok) AccentBlue else Color(0xFFDC2626),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SourceEditDialog(
    isNew: Boolean,
    initial: AptSource?,
    onDismiss: () -> Unit,
    onSave: (url: String, suite: String, components: String, options: String) -> Unit
) {
    var url by remember { mutableStateOf(initial?.url.orEmpty()) }
    var suite by remember { mutableStateOf(initial?.suite.orEmpty()) }
    var components by remember { mutableStateOf(initial?.components.orEmpty()) }
    var options by remember { mutableStateOf(initial?.options.orEmpty()) }

    OverlayDialog(
        show = true,
        title = if (isNew) stringResource(R.string.pkg_source_add) else stringResource(R.string.pkg_source_edit),
        summary = "",
        onDismissRequest = onDismiss,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextField(
                    label = stringResource(R.string.pkg_source_url),
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                )
                TextField(
                    label = stringResource(R.string.pkg_source_suite),
                    value = suite,
                    onValueChange = { suite = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                )
                TextField(
                    label = stringResource(R.string.pkg_source_components),
                    value = components,
                    onValueChange = { components = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                )
                TextField(
                    label = stringResource(R.string.pkg_source_options),
                    value = options,
                    onValueChange = { options = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurface)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.cancel),
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onSave(url, suite, components, options) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(color = AccentBlue)
                    ) {
                        Text(stringResource(R.string.save), color = Color.White, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    )
}
