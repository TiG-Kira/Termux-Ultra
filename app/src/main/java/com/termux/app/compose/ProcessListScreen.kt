package com.termux.app.compose

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.Job
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

data class DetailedProcessInfo(
    val pid: Int,
    val name: String,
    val status: String,
    val user: String,
    val cpuPercent: Float,
    val memoryKb: Long,
    val path: String,
    val isTermuxRelated: Boolean = false,
    val isFrozen: Boolean = false
)

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
    var processList by remember { mutableStateOf<List<DetailedProcessInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        refreshProcessData(context) { cpu, gpu, processes ->
            cpuUsage = cpu
            gpuUsage = gpu
            processList = processes
            isLoading = false
        }
    }

    DisposableEffect(Unit) {
        val job = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(2000)
                refreshProcessData(context) { cpu, gpu, processes ->
                    cpuUsage = cpu
                    gpuUsage = gpu
                    processList = processes
                }
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
                } else if (processList.isEmpty()) {
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
                        items = processList,
                        key = { it.pid }
                    ) { process ->
                        ProcessDetailItem(process = process, isDark = isDark)
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
    process: DetailedProcessInfo,
    isDark: Boolean
) {
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
                            text = process.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (process.isTermuxRelated) {
                            TermuxRelatedBadge()
                        }
                        ProcessStatusBadge(status = process.status, isFrozen = process.isFrozen)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ProcessInfoChip(
                            label = stringResource(R.string.process_pid_label),
                            value = "${process.pid}"
                        )
                        ProcessInfoChip(
                            label = stringResource(R.string.process_user_label),
                            value = process.user
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = process.path,
                        fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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

@Composable
private fun ProcessStatusBadge(status: String, isFrozen: Boolean = false) {
    val (text, color) = when {
        isFrozen ->
            stringResource(R.string.process_stopped) to Color(0xFFFF3B30)
        status.contains("S", ignoreCase = false) && !status.contains("T") ->
            stringResource(R.string.process_sleeping) to Color(0xFFFF9500)
        status.contains("R", ignoreCase = false) ->
            stringResource(R.string.process_running) to Color(0xFF34C759)
        status.contains("T", ignoreCase = false) ->
            stringResource(R.string.process_stopped) to Color(0xFFFF3B30)
        status.contains("Z", ignoreCase = false) ->
            stringResource(R.string.process_zombie) to Color(0xFF8E8E93)
        else -> status to Color(0xFF8E8E93)
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
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

private fun refreshProcessData(
    context: Context,
    onResult: (Float, Float, List<DetailedProcessInfo>) -> Unit
) {
    CoroutineScope(Dispatchers.IO).launch {
        val cpuUsage = try {
            readCpuUsage()
        } catch (_: Exception) { 0f }

        val gpuUsage = try {
            readGpuUsage()
        } catch (_: Exception) { 0f }

        val processes = try {
            readDetailedProcesses(context)
        } catch (_: Exception) { emptyList() }

        onResult(cpuUsage, gpuUsage, processes)
    }
}

private fun readDetailedProcesses(context: Context): List<DetailedProcessInfo> {
    val processList = mutableListOf<DetailedProcessInfo>()
    val sessionPids = getSessionPids()

    try {
        val processDir = java.io.File("/proc")
        val pidDirs = processDir.listFiles { file -> file.isDirectory && file.name.all { it.isDigit() } }
            ?: return emptyList()

        for (dir in pidDirs) {
            val pid = dir.name.toIntOrNull() ?: continue

            try {
                val statusFile = java.io.File(dir, "status")
                if (!statusFile.exists() || !statusFile.canRead()) continue

                val statusContent = statusFile.readText()

                val nameLine = statusContent.lines().find { it.startsWith("Name:") }
                val name = nameLine?.substringAfter("Name:")?.trim() ?: "Unknown"

                val stateLine = statusContent.lines().find { it.startsWith("State:") }
                val state = stateLine?.trim()?.split("\\s+".toRegex())?.getOrNull(1) ?: "S"

                val isFrozen = state == "T" || state == "t" || checkFreezerState(pid)

                val uidLine = statusContent.lines().find { it.startsWith("Uid:") }
                val uid = uidLine?.trim()?.split("\\s+".toRegex())?.getOrNull(1)?.toIntOrNull() ?: 0
                val user = resolveUserName(uid)

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

                val vmRSSLine = statusContent.lines().find { it.startsWith("VmRSS:") }
                val memKb = vmRSSLine?.filter { it.isDigit() }?.toLongOrNull() ?: 0L

                val isTermuxRelated = sessionPids.contains(pid) ||
                    name.contains("termux", ignoreCase = true) ||
                    name.contains("com.termux", ignoreCase = true) ||
                    name.contains("qemu", ignoreCase = true) ||
                    name.contains("proot", ignoreCase = true) ||
                    name.contains("ssh", ignoreCase = true) ||
                    name.contains("vnc", ignoreCase = true) ||
                    name.contains("tmux", ignoreCase = true)

                processList.add(
                    DetailedProcessInfo(
                        pid = pid,
                        name = name,
                        status = state,
                        user = user,
                        cpuPercent = 0f,
                        memoryKb = memKb,
                        path = path,
                        isTermuxRelated = isTermuxRelated,
                        isFrozen = isFrozen
                    )
                )
            } catch (_: Exception) {
                continue
            }
        }
    } catch (_: Exception) {
    }

    return processList.sortedWith(
        compareByDescending<DetailedProcessInfo> { it.isTermuxRelated }
            .thenByDescending { !it.isFrozen }
            .thenByDescending { it.memoryKb }
            .thenBy { it.name }
    )
}

private fun checkFreezerState(pid: Int): Boolean {
    return try {
        val freezerFile = java.io.File("/proc/$pid/freezer_state")
        if (freezerFile.exists() && freezerFile.canRead()) {
            val state = freezerFile.readText().trim()
            if (state == "FROZEN" || state == "ON") {
                return true
            }
        }
        val cgroupFile = java.io.File("/proc/$pid/cgroup")
        if (cgroupFile.exists() && cgroupFile.canRead()) {
            val content = cgroupFile.readText()
            if (content.contains("freezer") || content.contains("frozen")) {
                true
            } else {
                false
            }
        } else {
            false
        }
    } catch (_: Exception) {
        false
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

private fun getSessionPids(): Set<Int> {
    val pids = mutableSetOf<Int>()
    try {
        val runtime = Runtime.getRuntime()
        val process = runtime.exec(arrayOf("sh", "-c", "ps -A -o PID,NAME"))
        val reader = process.inputStream.bufferedReader()
        val lines = reader.readLines()
        process.waitFor()

        for (line in lines.drop(1)) {
            val parts = line.trim().split("\\s+".toRegex())
            if (parts.size >= 2) {
                val pid = parts[0].toIntOrNull()
                val name = parts[1]
                if (pid != null && (name.contains("termux", ignoreCase = true) ||
                    name.contains("com.termux", ignoreCase = true) ||
                    name.contains("bash", ignoreCase = true) ||
                    name.contains("mosh", ignoreCase = true) ||
                    name.contains("qemu", ignoreCase = true) ||
                    name.contains("proot", ignoreCase = true) ||
                    name.contains("ssh", ignoreCase = true) ||
                    name.contains("vnc", ignoreCase = true) ||
                    name.contains("tmux", ignoreCase = true))) {
                    pids.add(pid)
                }
            }
        }
    } catch (_: Exception) {
    }
    return pids
}