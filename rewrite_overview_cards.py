#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import re
import shutil
from pathlib import Path

FILE = Path(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt')
BACKUP = FILE.with_suffix(FILE.suffix + '.bak2')

shutil.copy2(FILE, BACKUP)

source = FILE.read_text(encoding='utf-8')
lines = source.splitlines(keepends=True)


def find_function_range(name, start_search=0):
    patterns = [rf'^(private\s+)?fun\s+{re.escape(name)}\s*\(']
    start_idx = None
    for i in range(start_search, len(lines)):
        line = lines[i]
        for p in patterns:
            if re.search(p, line):
                start_idx = i
                break
        if start_idx is not None:
            break
    if start_idx is None:
        raise ValueError(f'Could not find function {name}')
    brace_count = 0
    body_started = False
    for i in range(start_idx, len(lines)):
        for ch in lines[i]:
            if ch == '{':
                brace_count += 1
                body_started = True
            elif ch == '}':
                brace_count -= 1
                if body_started and brace_count == 0:
                    return (start_idx + 1, i + 1)
    raise ValueError(f'Could not find end of function {name}')


def replace_range(start_1based, end_1based, new_text):
    global lines
    s = start_1based - 1
    e = end_1based - 1
    lines[s:e+1] = [new_text + '\n']


OVERVIEW_CARD_CONTAINER = r'''
// ============================================================
// Unified Card Container (Wide / Square)
// ============================================================

private val WIDE_CARD_HEIGHT = 160.dp

@Composable
private fun OverviewCardContainer(
    card: OverviewCardConfig,
    onEditClick: () -> Unit,
    wideContent: @Composable () -> Unit,
    squareContent: @Composable () -> Unit
) {
    val isWide = card.size == CardSize.WIDE
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isWide) Modifier.height(WIDE_CARD_HEIGHT)
                else Modifier.aspectRatio(1f)
            )
            .clip(RoundedCornerShape(20.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface)
        ) {
            if (isWide) {
                wideContent()
            } else {
                squareContent()
            }
        }
    }
}

@Composable
private fun CardIconBox(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    iconSize: androidx.compose.ui.unit.Dp = 22.dp
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = tint
        )
    }
}

@Composable
private fun CardStatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
private fun CardProgressBar(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .height(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(RoundedCornerShape(50))
                .background(color)
        )
    }
}
'''

SESSIONS_CARD = r'''
// ============================================================
// Sessions Card
// ============================================================

@Composable
private fun SessionsCard(
    card: OverviewCardConfig,
    runningCount: Int,
    stoppedCount: Int,
    sessions: List<TermuxSession>,
    onSessionClick: (TermuxSession) -> Unit,
    isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val runningColor = Color(0xFF34C759)
    val stoppedColor = Color(0xFFFF3B30)
    OverviewCardContainer(
        card = card,
        onEditClick = onEditClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.overview_card_sessions).uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "$runningCount ${stringResource(R.string.overview_running)}",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                CardIconBox(
                    icon = Icons.Rounded.Memory,
                    tint = runningColor,
                    modifier = Modifier.size(40.dp),
                    iconSize = 22.dp
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(runningColor.copy(alpha = 0.08f))
                        .clickable(enabled = !isEditMode && sessions.isNotEmpty()) {
                            val running = sessions.filter { it.getTerminalSession().isRunning }
                            if (running.isNotEmpty()) onSessionClick(running.first())
                        }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = runningCount.toString(),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = runningColor
                        )
                        Text(
                            text = stringResource(R.string.overview_running),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = runningColor.copy(alpha = 0.85f)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(14.dp))
                        .background(stoppedColor.copy(alpha = 0.08f))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stoppedCount.toString(),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = stoppedColor
                        )
                        Text(
                            text = stringResource(R.string.overview_stopped),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = stoppedColor.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    } {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CardIconBox(
                icon = Icons.Rounded.Memory,
                tint = runningColor,
                modifier = Modifier.size(48.dp),
                iconSize = 26.dp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.overview_card_sessions).uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = runningCount.toString(),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardStatusBadge(
                    text = "${stringResource(R.string.overview_running)} $runningCount",
                    color = runningColor
                )
                CardStatusBadge(
                    text = "${stringResource(R.string.overview_stopped)} $stoppedCount",
                    color = stoppedColor
                )
            }
        }
    }
}
'''

CPU_CARD = r'''
// ============================================================
// CPU Monitor Card
// ============================================================

@Composable
private fun CpuMonitorCard(
    card: OverviewCardConfig,
    usage: Float,
    temperature: Float,
    history: List<Float>,
    isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val cpuMaxCapacity = remember { getCpuMaxCapacity() }
    val ratio = if (cpuMaxCapacity > 0f) usage / cpuMaxCapacity else usage / 100f
    val color = getUsageColor(usage, cpuMaxCapacity)
    val loadLabel = when {
        ratio < 0.3f -> stringResource(R.string.overview_load_low)
        ratio < 0.7f -> stringResource(R.string.overview_load_medium)
        else -> stringResource(R.string.overview_load_high)
    }

    OverviewCardContainer(card = card, onEditClick = onEditClick) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "CPU",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${usage.toInt()}%",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                CardIconBox(
                    icon = Icons.Rounded.Monitor,
                    tint = color,
                    modifier = Modifier.size(40.dp),
                    iconSize = 22.dp
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.overview_load),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        text = loadLabel,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                CardProgressBar(
                    progress = ratio,
                    color = color,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    } {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CardIconBox(
                icon = Icons.Rounded.Monitor,
                tint = color,
                modifier = Modifier.size(48.dp),
                iconSize = 26.dp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "CPU",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${usage.toInt()}%",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            CardProgressBar(
                progress = ratio,
                color = color,
                modifier = Modifier.width(90.dp)
            )
        }
    }
}
'''

GPU_CARD = r'''
// ============================================================
// GPU Monitor Card
// ============================================================

@Composable
private fun GpuMonitorCard(
    card: OverviewCardConfig,
    usage: Float,
    history: List<Float>,
    isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val isGpuAvailable = usage >= 0f
    val hasHistoricalData = MonitorHistory.hasGpuHistory()
    val peakUsage = MonitorHistory.getGpuPeak()
    val color = if (isGpuAvailable) getUsageColor(usage)
                  else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
    val gpuColor = Color(0xFFAF52DE)
    val displayColor = if (isGpuAvailable) color else gpuColor
    val ratio = if (isGpuAvailable) usage / 100f else 0f
    val loadLabel = if (isGpuAvailable) {
        when {
            ratio < 0.3f -> stringResource(R.string.overview_load_low)
            ratio < 0.7f -> stringResource(R.string.overview_load_medium)
            else -> stringResource(R.string.overview_load_high)
        }
    } else null

    OverviewCardContainer(card = card, onEditClick = onEditClick) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = "GPU",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isGpuAvailable) "${usage.toInt()}%"
                               else if (hasHistoricalData) "${peakUsage.toInt()}%"
                               else "N/A",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isGpuAvailable || hasHistoricalData)
                                    MiuixTheme.colorScheme.onSurface
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
                    )
                }
                CardIconBox(
                    icon = Icons.Rounded.Speed,
                    tint = displayColor,
                    modifier = Modifier.size(40.dp),
                    iconSize = 22.dp
                )
            }

            Column {
                if (isGpuAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.overview_usage_rate),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Text(
                            text = loadLabel ?: "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    CardProgressBar(
                        progress = ratio,
                        color = displayColor,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (hasHistoricalData) {
                    Text(
                        text = stringResource(R.string.overview_gpu_peak_hint),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    CardProgressBar(
                        progress = peakUsage / 100f,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text = stringResource(R.string.overview_no_gpu_data),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
                    )
                }
            }
        }
    } {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CardIconBox(
                icon = Icons.Rounded.Speed,
                tint = displayColor,
                modifier = Modifier.size(48.dp),
                iconSize = 26.dp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "GPU",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (isGpuAvailable) "${usage.toInt()}%"
                       else if (hasHistoricalData) "${peakUsage.toInt()}%"
                       else "N/A",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = if (isGpuAvailable || hasHistoricalData)
                            MiuixTheme.colorScheme.onSurface
                        else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (isGpuAvailable) {
                CardProgressBar(
                    progress = ratio,
                    color = displayColor,
                    modifier = Modifier.width(90.dp)
                )
            } else if (hasHistoricalData) {
                Text(
                    text = stringResource(R.string.overview_gpu_peak),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
                )
            } else {
                Text(
                    text = stringResource(R.string.overview_no_gpu_data),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
                )
            }
        }
    }
}
'''

MEMORY_CARD = r'''
// ============================================================
// Memory Monitor Card
// ============================================================

@Composable
fun MemoryMonitorCard(
    card: OverviewCardConfig,
    usage: Float,
    totalKb: Long,
    history: List<Float>,
    isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val color = getUsageColor(usage)
    val ratio = usage / 100f
    val totalGb = totalKb / (1024.0 * 1024.0)
    val usedGb = totalGb * (usage / 100.0)
    val memText = String.format("%.1f / %.1f GB", usedGb, totalGb)

    OverviewCardContainer(card = card, onEditClick = onEditClick) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.overview_card_memory).uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = memText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                CardIconBox(
                    icon = Icons.Rounded.Memory,
                    tint = color,
                    modifier = Modifier.size(40.dp),
                    iconSize = 22.dp
                )
            }

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.overview_memory_used, ""),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        text = "${usage.toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                CardProgressBar(
                    progress = ratio,
                    color = color,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    } {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CardIconBox(
                icon = Icons.Rounded.Memory,
                tint = color,
                modifier = Modifier.size(48.dp),
                iconSize = 26.dp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.overview_card_memory).uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${usage.toInt()}%",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = memText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}
'''

PROCESS_CARD = r'''
// ============================================================
// Process List Card
// ============================================================

@Composable
fun ProcessListCard(
    card: OverviewCardConfig,
    processes: List<ProcessInfo>,
    isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val frozenCount = processes.count { it.isFrozen }
    val runningCount = processes.count { it.isRunning }
    val backgroundCount = processes.count { it.isBackgroundRunning && !it.isFrozen }
    val sleepingCount = processes.count { it.isSleeping && !it.isBackgroundRunning && !it.isFrozen }
    val activeProcesses = processes.filter { !it.isFrozen }
    val frozenProcesses = processes.filter { it.isFrozen }
    val processColor = Color(0xFF5AC8FA)

    OverviewCardContainer(card = card, onEditClick = onEditClick) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.overview_card_processes).uppercase(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${processes.size} ${stringResource(R.string.overview_processes)}",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                }
                CardIconBox(
                    icon = Icons.Rounded.List,
                    tint = processColor,
                    modifier = Modifier.size(40.dp),
                    iconSize = 22.dp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (runningCount > 0) CardStatusBadge(text = "${stringResource(R.string.overview_running)} $runningCount", color = Color(0xFF34C759))
                if (backgroundCount > 0) CardStatusBadge(text = "${stringResource(R.string.overview_background)} $backgroundCount", color = Color(0xFFFF9500))
                if (sleepingCount > 0) CardStatusBadge(text = "${stringResource(R.string.overview_sleeping)} $sleepingCount", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (frozenCount > 0) CardStatusBadge(text = stringResource(R.string.overview_frozen_count, frozenCount), color = MiuixTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(8.dp))

            ProcessListContent(
                modifier = Modifier.weight(1f),
                activeProcesses = activeProcesses,
                frozenProcesses = frozenProcesses
            )
        }
    } {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CardIconBox(
                icon = Icons.Rounded.List,
                tint = processColor,
                modifier = Modifier.size(48.dp),
                iconSize = 26.dp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.overview_card_processes).uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = processes.size.toString(),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(10.dp))
            ProcessListContent(
                modifier = Modifier.fillMaxWidth(),
                activeProcesses = activeProcesses.take(3),
                frozenProcesses = frozenProcesses.take(2),
                compact = true
            )
        }
    }
}

@Composable
private fun ProcessListContent(
    modifier: Modifier = Modifier,
    activeProcesses: List<ProcessInfo>,
    frozenProcesses: List<ProcessInfo>,
    compact: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        if (activeProcesses.isEmpty() && frozenProcesses.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.overview_no_processes),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)
            ) {
                if (!compact) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.overview_process_name),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(R.string.overview_process_status),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.width(44.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.overview_process_cpu),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.width(36.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Right
                        )
                        Text(
                            text = stringResource(R.string.overview_process_mem),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.width(48.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Right
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.12f)
                    )
                }

                activeProcesses.forEach { process ->
                    ProcessItemRow(process = process, compact = compact)
                }

                if (!compact && frozenProcesses.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MiuixTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.overview_frozen_processes),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MiuixTheme.colorScheme.error
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        color = MiuixTheme.colorScheme.error.copy(alpha = 0.25f)
                    )
                    frozenProcesses.forEach { process ->
                        ProcessItemRow(process = process, compact = compact)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessItemRow(process: ProcessInfo, compact: Boolean = false) {
    val stateColor = when {
        process.isFrozen -> MiuixTheme.colorScheme.error
        process.isRunning -> Color(0xFF34C759)
        process.isBackgroundRunning -> Color(0xFFFF9500)
        process.isSleeping -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    val memFormatted = when {
        process.memRssKb >= 1024 * 1024 -> String.format("%.1fG", process.memRssKb / (1024.0 * 1024.0))
        process.memRssKb >= 1024 -> String.format("%.0fM", process.memRssKb / 1024.0)
        else -> "${process.memRssKb}K"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = process.name,
                fontSize = if (compact) 11.sp else 12.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    process.isFrozen -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    process.isTermuxRelated -> MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(stateColor.copy(alpha = 0.14f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = process.stateLabel,
                    fontSize = if (compact) 8.sp else 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = stateColor
                )
            }
        }
        if (compact) {
            Text(
                text = if (process.isFrozen) "—" else "${process.cpuPercent.toInt()}%",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (process.isFrozen)
                    MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                else
                    getUsageColor(process.cpuPercent.coerceIn(0f, 100f))
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (process.isFrozen) "—" else "${process.cpuPercent.toInt()}%",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (process.isFrozen)
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                    else
                        getUsageColor(process.cpuPercent.coerceIn(0f, 100f)),
                    modifier = Modifier.width(36.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Right
                )
                Text(
                    text = if (process.isFrozen) "—" else memFormatted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (process.isFrozen)
                        MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.5f)
                    else
                        getUsageColor(process.memPercent.coerceIn(0f, 100f)),
                    modifier = Modifier.width(48.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Right
                )
            }
        }
    }
}
'''

STOP_ALL_CARD = r'''
// ============================================================
// Stop All Card
// ============================================================

@Composable
fun StopAllCard(
    card: OverviewCardConfig,
    sessionCount: Int,
    isEditMode: Boolean,
    onStopAll: () -> Unit,
    onEditClick: () -> Unit
) {
    var showConfirmDialog by remember { mutableStateOf(false) }
    val accentColor = if (sessionCount > 0) Color(0xFFFF3B30) else MiuixTheme.colorScheme.onSurfaceVariantSummary

    OverviewCardContainer(
        card = card,
        onEditClick = onEditClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clickable(enabled = !isEditMode && sessionCount > 0) { showConfirmDialog = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CardIconBox(
                icon = Icons.Rounded.Stop,
                tint = accentColor,
                modifier = Modifier.size(48.dp),
                iconSize = 26.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.overview_card_stop_all),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.overview_stop_all_subtitle),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                CardStatusBadge(
                    text = if (sessionCount > 0) "$sessionCount ${stringResource(R.string.overview_active)}" else stringResource(R.string.overview_no_sessions),
                    color = accentColor
                )
            }
        }
    } {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clickable(enabled = !isEditMode && sessionCount > 0) { showConfirmDialog = true },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CardIconBox(
                icon = Icons.Rounded.Stop,
                tint = accentColor,
                modifier = Modifier.size(56.dp),
                iconSize = 30.dp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.overview_card_stop_all),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$sessionCount ${stringResource(R.string.overview_active)}",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }

    if (showConfirmDialog) {
        OverlayDialog(
            show = showConfirmDialog,
            onDismissRequest = { showConfirmDialog = false },
            title = stringResource(R.string.overview_card_stop_all),
            content = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = stringResource(R.string.overview_stop_all_confirm),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showConfirmDialog = false },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = stringResource(R.string.ok),
                            onClick = {
                                showConfirmDialog = false
                                onStopAll()
                            },
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        )
    }
}
'''

RESOURCE_ACTION_CARD = r'''
// ============================================================
// Resource Action Card
// ============================================================

@Composable
fun ResourceActionCard(
    card: OverviewCardConfig,
    context: Context,
    isEditMode: Boolean,
    onActionSelected: (String) -> Unit,
    onLaunchAction: (ResourceAction) -> Unit,
    onEditClick: () -> Unit
) {
    val action = card.resourceActionId?.let { ResourceActions.getActionById(context, it) }
    var showSelectDialog by remember { mutableStateOf(false) }
    val accentColor = MiuixTheme.colorScheme.primary

    OverviewCardContainer(
        card = card,
        onEditClick = onEditClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clickable {
                    if (isEditMode) {
                        onEditClick()
                    } else if (action != null) {
                        onLaunchAction(action)
                    } else {
                        showSelectDialog = true
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CardIconBox(
                icon = if (action != null) Icons.Rounded.PlayArrow else Icons.Rounded.Add,
                tint = accentColor,
                modifier = Modifier.size(48.dp),
                iconSize = 26.dp
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (action != null) action.name else stringResource(R.string.overview_resource_action),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (action != null && action.description.isNotEmpty()) action.description
                           else stringResource(R.string.overview_resource_action_desc),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                if (action != null) {
                    val categoryText = when (action.category) {
                        ResourceActionCategory.UTILITY_CENTER -> stringResource(R.string.overview_utility_center)
                        ResourceActionCategory.THIRD_PARTY_CENTER -> stringResource(R.string.overview_third_party_center)
                        ResourceActionCategory.SYSTEM_FUNCTION -> stringResource(R.string.overview_system_function)
                    }
                    CardStatusBadge(text = categoryText, color = accentColor)
                } else {
                    CardStatusBadge(text = stringResource(R.string.overview_tap_to_select), color = accentColor)
                }
            }
        }
    } {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .clickable {
                    if (isEditMode) {
                        onEditClick()
                    } else if (action != null) {
                        onLaunchAction(action)
                    } else {
                        showSelectDialog = true
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CardIconBox(
                icon = if (action != null) Icons.Rounded.PlayArrow else Icons.Rounded.Add,
                tint = accentColor,
                modifier = Modifier.size(56.dp),
                iconSize = 30.dp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (action != null) action.name else stringResource(R.string.overview_resource_action),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (action != null && action.description.isNotEmpty()) action.description
                       else stringResource(R.string.overview_resource_action_desc),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    ResourceActionSelectionDialog(
        context = context,
        show = showSelectDialog,
        currentActionId = card.resourceActionId,
        onActionSelected = { actionId ->
            onActionSelected(actionId)
            showSelectDialog = false
        },
        onDismiss = { showSelectDialog = false }
    )
}
'''

FEATURE_CENTER_CARD = r'''
// ============================================================
// Feature Center Card
// ============================================================

@Composable
fun FeatureCenterCard(
    card: OverviewCardConfig,
    isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val context = LocalContext.current
    val isWide = card.size == CardSize.WIDE

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isWide) WIDE_CARD_HEIGHT else androidx.compose.ui.unit.Dp.Unspecified)
            .aspectRatio(if (isWide) Float.NaN else 1f)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = !isEditMode) {
                val intent = Intent(context, com.termux.app.activities.FeatureCenterActivity::class.java)
                context.startActivity(intent)
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            if (isWide) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brush.linearGradient(listOf(Color(0xFF007AFF), Color(0xFF5856D6)))),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Monitor,
                                contentDescription = null,
                                modifier = Modifier.size(26.dp),
                                tint = Color.White
                            )
                        }
                        Column {
                            Text(
                                text = stringResource(R.string.overview_feature_center_label),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MiuixTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.feature_center_desc),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            R.string.overview_chip_shortcuts,
                            R.string.overview_chip_plugins,
                            R.string.overview_chip_themes
                        ).forEach { resId ->
                            CardStatusBadge(
                                text = stringResource(resId),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(Color(0xFF007AFF), Color(0xFF5856D6)))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Monitor,
                            contentDescription = null,
                            modifier = Modifier.size(30.dp),
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.overview_feature_center_label),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.feature_center_desc),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            R.string.overview_chip_shortcuts,
                            R.string.overview_chip_plugins,
                            R.string.overview_chip_themes
                        ).forEach { resId ->
                            CardStatusBadge(
                                text = stringResource(resId),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                }
            }
        }
    }
}
'''

# ============================================================
# Execute replacements
# ============================================================

# Insert shared container before Sessions Card section
for i in range(find_function_range('SessionsCard')[0] - 1, -1, -1):
    if '// Sessions Card' in lines[i]:
        insert_pos = i
        break
else:
    insert_pos = find_function_range('SessionsCard')[0] - 2

lines[insert_pos:insert_pos] = (OVERVIEW_CARD_CONTAINER + '\n').splitlines(keepends=True)
source_after_insert = ''.join(lines)
lines = source_after_insert.splitlines(keepends=True)

replacements = [
    ('SessionsCard', SESSIONS_CARD),
    ('CpuMonitorCard', CPU_CARD),
    ('GpuMonitorCard', GPU_CARD),
    ('MemoryMonitorCard', MEMORY_CARD),
    ('ProcessListCard', PROCESS_CARD),
    ('StopAllCard', STOP_ALL_CARD),
    ('FeatureCenterCard', FEATURE_CENTER_CARD),
    ('ResourceActionCard', RESOURCE_ACTION_CARD),
]

for func_name, new_text in replacements:
    try:
        r = find_function_range(func_name)
        replace_range(r[0], r[1], new_text)
        source_after_insert = ''.join(lines)
        lines = source_after_insert.splitlines(keepends=True)
    except ValueError as e:
        print(f'Warning: {e}')

final_source = ''.join(lines)
FILE.write_text(final_source, encoding='utf-8')
print('Done. Backup at', BACKUP)
