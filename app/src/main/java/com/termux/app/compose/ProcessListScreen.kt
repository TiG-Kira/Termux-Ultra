package com.termux.app.compose

import android.content.Context
import android.os.Process
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 扩展 ProcessInfo，补充 ProcessListScreen 需要的 user / path 信息。 */
private data class DetailedProcess(
    val base: ProcessInfo,
    val user: String,
    val path: String
)

/** 判断进程是否为 Termux 应用本体（com.termux），不允许用户在进程页面关闭它。 */
private fun isTermuxAppSelf(base: ProcessInfo, context: Context): Boolean {
    // 1. 检查进程名是否就是 com.termux
    if (base.name.equals("com.termux", ignoreCase = true)) return true
    // 2. 用当前应用的 pid 对比
    if (base.pid == Process.myPid()) return true
    // 3. 通过 cmdline 检查（兜底）
    return try {
        val cmdlineFile = java.io.File("/proc/${base.pid}/cmdline")
        if (cmdlineFile.exists() && cmdlineFile.canRead()) {
            val cmd = cmdlineFile.readText().substringBefore('\u0000').trim()
            cmd.contains("com.termux", ignoreCase = true)
        } else false
    } catch (_: Exception) { false }
}

private fun killProcessPid(context: Context, pid: Int) {
    try {
        // 用 Termux 的 kill 命令执行，比 android.os.Process.killProcess 更可靠
        Runtime.getRuntime().exec(arrayOf("sh", "-c", "kill -9 $pid || true"))
        Toast.makeText(context, R.string.process_killed, Toast.LENGTH_SHORT).show()
    } catch (_: Exception) {
        try {
            Process.killProcess(pid)
            Toast.makeText(context, R.string.process_killed, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                context,
                context.getString(R.string.process_not_running),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
}

private fun buildDetailedProcessList(processes: List<ProcessInfo>): List<DetailedProcess> {
    return processes.mapNotNull { base ->
        try {
            val dir = java.io.File("/proc/${base.pid}")
            if (!dir.exists()) return@mapNotNull null

            // user
            val statusFile = java.io.File(dir, "status")
            val uid = if (statusFile.exists() && statusFile.canRead()) {
                statusFile.readText().lines()
                    .find { it.startsWith("Uid:") }
                    ?.trim()?.split("\\s+".toRegex())?.getOrNull(1)?.toIntOrNull() ?: 0
            } else 0
            val user = resolveUserName(uid)

            // path
            val exeFile = java.io.File(dir, "exe")
            val path = if (exeFile.exists()) {
                try { exeFile.canonicalPath } catch (_: Exception) { dir.absolutePath }
            } else {
                val cmdLineFile = java.io.File(dir, "cmdline")
                if (cmdLineFile.exists()) {
                    cmdLineFile.readText().replace("\u0000", " ").trim()
                } else {
                    dir.absolutePath
                }
            }

            DetailedProcess(base = base, user = user, path = path)
        } catch (_: Exception) { null }
    }
}

private fun resolveUserName(uid: Int): String {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("id", "-un", "$uid"))
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        if (output.isNotEmpty()) output else "uid:$uid"
    } catch (_: Exception) {
        "uid:$uid"
    }
}

@Composable
fun ProcessListScreen(
    navBarBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onBackPressed: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val isDark = isSystemInDarkTheme()

    var cpuUsage by remember { mutableStateOf(0f) }
    var gpuUsage by remember { mutableStateOf(0f) }
    var detailedList by remember { mutableStateOf<List<DetailedProcess>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    fun doRefresh() {
        CoroutineScope(Dispatchers.IO).launch {
            val cpu = try { readCpuUsage() } catch (_: Exception) { 0f }
            val gpu = try { readGpuUsage() } catch (_: Exception) { 0f }
            val rawList = try { readProcessList() } catch (_: Exception) { emptyList() }
            val detailed = buildDetailedProcessList(rawList)
            cpuUsage = cpu
            gpuUsage = gpu
            detailedList = detailed
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        doRefresh()
    }

    DisposableEffect(Unit) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(2000)
                doRefresh()
            }
        }
        onDispose { job.cancel() }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.process_list_title),
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onBackPressed() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        content = { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    bottom = navBarBottomPadding + 16.dp
                )
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ProcessStatCard(
                            title = stringResource(R.string.process_cpu_usage),
                            value = "${cpuUsage.toInt()}%",
                            subtitle = stringResource(R.string.process_cpu_desc),
                            modifier = Modifier.weight(1f),
                            isDark = isDark,
                            accentColor = MiuixTheme.colorScheme.primary
                        )
                        ProcessStatCard(
                            title = stringResource(R.string.process_gpu_usage),
                            value = "${gpuUsage.toInt()}%",
                            subtitle = stringResource(R.string.process_gpu_desc),
                            modifier = Modifier.weight(1f),
                            isDark = isDark,
                            accentColor = Color(0xFF7C4DFF)
                        )
                    }
                }

                item {
                    SmallTitle(
                        text = stringResource(R.string.process_list_all),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                if (isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.process_loading),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                } else if (detailedList.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(R.string.process_no_processes),
                                fontSize = 14.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                } else {
                    items(
                        items = detailedList,
                        key = { it.base.pid }
                    ) { dp ->
                        ProcessDetailItem(
                            dp = dp,
                            isDark = isDark,
                            onKillClick = {
                                killProcessPid(context, dp.base.pid)
                                doRefresh()
                            },
                            canKill = !isTermuxAppSelf(dp.base, context)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun ProcessStatCard(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    isDark: Boolean,
    accentColor: Color
) {
    MiuixCard(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isDark) Color(0xFF1C1C1E) else Color(0xFFFAFAFA))
                .padding(16.dp)
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
private fun ProcessDetailItem(
    dp: DetailedProcess,
    isDark: Boolean,
    onKillClick: () -> Unit,
    canKill: Boolean
) {
    val base = dp.base

    MiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isDark) Color(0xFF1C1C1E) else Color(0xFFFAFAFA))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = base.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (base.isTermuxRelated) {
                            TermuxRelatedBadge()
                        }
                        ProcessStatusBadge(base = base)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ProcessInfoChip(
                            label = stringResource(R.string.process_pid_label),
                            value = "${base.pid}"
                        )
                        ProcessInfoChip(
                            label = stringResource(R.string.process_user_label),
                            value = dp.user
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = dp.path,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Kill 按钮：Termux 本体进程不显示
                if (canKill) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onKillClick
                            )
                            .background(
                                color = Color(0xFFFF3B30).copy(alpha = 0.15f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.kill_process_pid, base.pid),
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFFFF3B30)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TermuxRelatedBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF5AC8FA).copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        Text(
            text = "Termux",
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = Color(0xFF5AC8FA)
        )
    }
    Spacer(modifier = Modifier.size(4.dp))
}

/**
 * 与 OverviewScreen 卡片使用完全相同的状态判断逻辑（ProcessInfo.stateLabel +
 * 相同的颜色映射），保证进程页面与总览页进程卡片的状态展示一致。
 */
@Composable
private fun ProcessStatusBadge(base: ProcessInfo) {
    val color = when {
        base.isFrozen -> Color(0xFFFF3B30)
        base.isRunning -> Color(0xFF34C759)
        base.isBackgroundRunning -> Color(0xFFFF9500)
        base.isSleeping -> Color(0xFF8E8E93)
        base.state == "D" -> Color(0xFFFF9500)
        base.state == "Z" -> Color(0xFF8E8E93)
        else -> Color(0xFF8E8E93)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = base.stateLabel,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = color
        )
    }
}

@Composable
private fun ProcessInfoChip(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label: ",
            fontSize = 11.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}
