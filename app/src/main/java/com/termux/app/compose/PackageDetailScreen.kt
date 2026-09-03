package com.termux.app.compose

import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val AccentBlue = Color(0xFF2563EB)
private val DangerRed = Color(0xFFDC2626)
private val GrayNeutral = Color(0xFF6B7280)

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

    fun runInstallUninstall(isInstall: Boolean) {
        progressTitle = if (isInstall) "正在安装 ${pkg.name}" else "正在卸载 ${pkg.name}"
        progressLog = ""
        progressSuccess = null
        showProgressDialog = true
        scope.launch {
            val (ok, log) = if (isInstall) PkgRepo.install(context, pkg.name) else PkgRepo.uninstall(context, pkg.name)
            progressLog = log
            progressSuccess = ok
        }
    }

    fun startOperation(isInstall: Boolean) {
        scope.launch {
            val hasLocks = PkgRepo.hasLocks(context)
            if (hasLocks) {
                pendingAction = { runInstallUninstall(isInstall) }
                showLockDialog = true
            } else {
                runInstallUninstall(isInstall)
            }
        }
    }

    fun dismissProgress() {
        showProgressDialog = false
        val success = progressSuccess ?: false
        onChanged(success)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "软件包详情",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable(enabled = !showProgressDialog && !showLockDialog) { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = null,
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
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottom = navBarBottomPadding + 16.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = AccentBlue
                )
            } else {
                val d = detail ?: pkg
                val textColor = if (isDark) Color.White else Color.Black
                val subColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.6f)

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                ) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = d.name,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = textColor
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "v${d.version}",
                                fontSize = 14.sp,
                                color = subColor
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (d.isInstalled) AccentBlue.copy(alpha = 0.12f)
                                           else GrayNeutral.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (d.isInstalled) "已安装" else "未安装",
                                fontSize = 12.sp,
                                color = if (d.isInstalled) AccentBlue else GrayNeutral
                            )
                        }
                    }

                    if (d.description.isNotBlank()) {
                        item {
                            Column {
                                Text(text = "描述", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = subColor)
                                Spacer(Modifier.height(4.dp))
                                Text(text = d.description, fontSize = 14.sp, color = textColor, lineHeight = 20.sp)
                            }
                        }
                    }

                    if (d.homepage.isNotBlank()) {
                        item {
                            Column {
                                Text(text = "主页", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = subColor)
                                Spacer(Modifier.height(4.dp))
                                Text(text = d.homepage, fontSize = 14.sp, color = textColor)
                            }
                        }
                    }

                    if (d.depends.isNotEmpty()) {
                        item {
                            Column {
                                Text(text = "依赖", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = subColor)
                                Spacer(Modifier.height(4.dp))
                                Text(text = d.depends.joinToString(", "), fontSize = 14.sp, color = textColor)
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(12.dp))
                        if (d.isInstalled) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(color = DangerRed, shape = RoundedCornerShape(12.dp))
                                    .clickable { startOperation(isInstall = false) }
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("卸载", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(color = AccentBlue, shape = RoundedCornerShape(12.dp))
                                    .clickable { startOperation(isInstall = true) }
                                    .padding(vertical = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("安装", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
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
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    ) {
                        if (progressSuccess == null) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = AccentBlue, strokeWidth = 3.dp)
                            }
                            Spacer(Modifier.height(12.dp))
                        }

                        if (progressLog.isNotBlank()) {
                            val displayLog = if (progressLog.length > 1500) progressLog.substring(progressLog.length - 1500) else progressLog
                            Box(
                                modifier = Modifier.fillMaxWidth()
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
                                    lineHeight = 16.sp
                                )
                            }
                        }

                        if (progressSuccess != null) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = if (progressSuccess == true) "操作成功" else "操作失败",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (progressSuccess == true) AccentBlue else DangerRed
                            )
                            Spacer(Modifier.height(12.dp))
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
