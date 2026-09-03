package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import com.termux.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val AccentBlue = Color(0xFF2563EB)
private val DangerRed = Color(0xFFDC2626)

@Composable
fun PackageDetailScreen(
    pkg: PackageInfo,
    navBarBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onBack: () -> Unit,
    onChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val colorScheme = MiuixTheme.colorScheme

    var detail by remember { mutableStateOf<PackageInfo?>(pkg) }
    var isLoading by remember { mutableStateOf(true) }
    var showLockDialog by remember { mutableStateOf(false) }
    var showProgressDialog by remember { mutableStateOf(false) }
    var progressTitle by remember { mutableStateOf("") }
    var progressLog by remember { mutableStateOf("") }
    var progressSuccess by remember { mutableStateOf<Boolean?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    LaunchedEffect(pkg.name) {
        isLoading = true
        detail = PkgRepo.getDetail(context, pkg.name) ?: pkg
        isLoading = false
    }

    fun isLockError(log: String): Boolean {
        val lower = log.lowercase()
        return lower.contains("could not get lock") ||
               lower.contains("dpkg is locked") ||
               lower.contains("wait for it to finish") ||
               lower.contains("/var/lib/dpkg/lock") ||
               lower.contains("/var/cache/apt/archives/lock") ||
               lower.contains("cache/apt/archives/lock")
    }

    fun runInstallUninstall(isInstall: Boolean, forceRemoveLock: Boolean = false) {
        progressTitle = if (isInstall) "正在安装 ${pkg.name}" else "正在卸载 ${pkg.name}"
        progressLog = ""
        progressSuccess = null
        showProgressDialog = true
        scope.launch {
            val result = if (isInstall) PkgRepo.install(context, pkg.name) else PkgRepo.uninstall(context, pkg.name)
            val ok = result.first
            val log = result.second
            if (!ok && !forceRemoveLock && isLockError(log)) {
                progressLog = log
                progressSuccess = false
                // Show lock dialog after showing the lock error briefly
                showProgressDialog = false
                pendingAction = { runInstallUninstall(isInstall, forceRemoveLock = true) }
                showLockDialog = true
            } else {
                progressLog = log
                progressSuccess = ok
            }
        }
    }

    fun startOperation(isInstall: Boolean) {
        runInstallUninstall(isInstall, forceRemoveLock = false)
    }

    fun dismissProgress() {
        showProgressDialog = false
        val success = progressSuccess ?: false
        onChanged(success)
    }

    fun openHomepage(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开链接", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = pkg.name,
                subtitle = run {
                    val d = detail ?: pkg
                    val statusText = if (d.isInstalled) "已安装" else "未安装"
                    val versionText = if (d.version.isNotBlank()) "v${d.version}" else ""
                    if (versionText.isNotBlank()) "$versionText | $statusText" else statusText
                },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(
                        onClick = { if (!showProgressDialog && !showLockDialog) onBack() }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
                            tint = colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (!detail?.homepage.isNullOrBlank()) {
                        IconButton(
                            onClick = { detail?.homepage?.let { openHomepage(it) } }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_link),
                                contentDescription = "打开主页",
                                tint = colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = AccentBlue
                )
            } else {
                val d = detail ?: pkg
                val subColor = colorScheme.onSurfaceVariantSummary

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 6.dp,
                        bottom = navBarBottomPadding + 92.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Description card
                    if (d.description.isNotBlank()) {
                        item {
                            SmallTitle(
                                text = "描述",
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                insideMargin = PaddingValues(16.dp)
                            ) {
                                Text(
                                    text = d.description,
                                    fontSize = 14.sp,
                                    color = colorScheme.onSurface,
                                    lineHeight = 20.sp
                                )
                            }
                        }
                    }

                    // Info card: Homepage, Maintainer, Size, License
                    val infoRows = mutableListOf<Pair<String, String>>()
                    if (d.homepage.isNotBlank()) infoRows.add("主页" to d.homepage)
                    if (d.maintainer.isNotBlank()) infoRows.add("维护者" to d.maintainer)
                    if (d.size.isNotBlank()) infoRows.add("大小" to d.size)
                    if (d.license.isNotBlank()) infoRows.add("许可证" to d.license)

                    if (infoRows.isNotEmpty()) {
                        item {
                            SmallTitle(
                                text = "信息",
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                Column {
                                    infoRows.forEachIndexed { index, (label, value) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = label == "主页") { openHomepage(value) }
                                                .padding(vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 14.sp,
                                                color = subColor,
                                                modifier = Modifier.width(56.dp)
                                            )
                                            Text(
                                                text = value,
                                                fontSize = 14.sp,
                                                color = colorScheme.onSurface,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (label == "主页") {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_link),
                                                    contentDescription = null,
                                                    tint = subColor,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        if (index != infoRows.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(vertical = 4.dp),
                                                color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Dependencies card
                    if (d.depends.isNotEmpty()) {
                        item {
                            SmallTitle(
                                text = "依赖",
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                Column {
                                    d.depends.forEachIndexed { index, dep ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = dep,
                                                fontSize = 14.sp,
                                                color = colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        if (index != d.depends.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(vertical = 4.dp),
                                                color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Conflicts card
                    if (d.conflicts.isNotEmpty()) {
                        item {
                            SmallTitle(
                                text = "冲突",
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                Column {
                                    d.conflicts.forEachIndexed { index, conf ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = conf,
                                                fontSize = 14.sp,
                                                color = DangerRed,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        if (index != d.conflicts.lastIndex) {
                                            HorizontalDivider(
                                                modifier = Modifier.padding(vertical = 4.dp),
                                                color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Bottom install/uninstall button
            if (!isLoading) {
                val d = detail ?: pkg
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(colorScheme.surface)
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 12.dp + navBarBottomPadding
                        )
                ) {
                    if (d.isInstalled) {
                        Button(
                            onClick = { startOperation(isInstall = false) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                color = DangerRed
                            )
                        ) {
                            Text("卸载", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                    } else {
                        Button(
                            onClick = { startOperation(isInstall = true) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                color = AccentBlue
                            )
                        ) {
                            Text("安装", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                    }
                }
            }

            OverlayDialog(
                show = showLockDialog,
                title = "包管理器被占用",
                summary = "检测到 Termux 的 apt/dpkg 正在被其他进程占用。\n\n强行解除锁可能导致：\n• 正在进行的安装/升级进程被中断\n• 数据库状态不一致\n• 已下载的包文件残留\n\n建议：先关闭其他正在运行的 Termux 会话，然后再试。",
                onDismissRequest = {
                    showLockDialog = false
                    pendingAction = null
                },
                content = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            text = "取消",
                            onClick = { showLockDialog = false; pendingAction = null },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            text = "强行解除",
                            onClick = {
                                showLockDialog = false
                                val action = pendingAction
                                pendingAction = null
                                scope.launch {
                                    PkgRepo.forceRemoveLocks(context)
                                    Toast.makeText(context, "锁已解除，正在继续...", Toast.LENGTH_SHORT).show()
                                    action?.invoke()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            )

            OverlayDialog(
                show = showProgressDialog,
                title = progressTitle.ifBlank { "正在处理" },
                summary = "",
                onDismissRequest = { if (progressSuccess != null) dismissProgress() },
                content = {
                    val logScrollState = rememberScrollState()
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    ) {
                        // Loading indicator
                        if (progressSuccess == null) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = AccentBlue, strokeWidth = 3.dp)
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        // Result text
                        if (progressSuccess != null) {
                            Text(
                                text = if (progressSuccess == true) "操作成功" else "操作失败",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (progressSuccess == true) AccentBlue else DangerRed
                            )
                            Spacer(Modifier.height(12.dp))
                        }

                        // Log area - only show on failure, scrollable
                        if (progressSuccess == false && progressLog.isNotBlank()) {
                            val displayLog = if (progressLog.length > 5000) progressLog.substring(progressLog.length - 5000) else progressLog
                            Box(
                                modifier = Modifier.fillMaxWidth()
                                    .height(200.dp)
                                    .background(
                                        color = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF5F5F5),
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(12.dp)
                            ) {
                                Text(
                                    text = displayLog,
                                    fontSize = 12.sp,
                                    color = if (isDark) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.7f),
                                    lineHeight = 16.sp,
                                    modifier = Modifier.verticalScroll(logScrollState)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        // Close button at bottom
                        if (progressSuccess != null) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    text = "关闭",
                                    onClick = { dismissProgress() },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}
