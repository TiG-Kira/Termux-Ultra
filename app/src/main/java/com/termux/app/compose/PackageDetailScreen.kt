package com.termux.app.compose

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
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
import top.yukonga.miuix.kmp.preference.ArrowPreference
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.ui.draw.alpha
import com.termux.R
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val AccentBlue = Color(0xFF2563EB)
private val DangerRed = Color(0xFFDC2626)
private val SuccessGreen = Color(0xFF16A34A)

private enum class DepStatus(val text: String, val color: Color) {
    INSTALLED("已安装", SuccessGreen),
    WILL_INSTALL("将安装", AccentBlue),
    NOT_SATISFIED("不满足", DangerRed)
}

private enum class ConfStatus(val text: String, val color: Color) {
    SATISFIED("已满足", SuccessGreen),
    NOT_SATISFIED("不满足", DangerRed)
}

@Composable
fun PackageDetailScreen(
    pkg: PackageInfo,
    navBarBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onBack: () -> Unit,
    onChanged: (Boolean) -> Unit,
    onOpenPackageDetail: ((PackageInfo) -> Unit)? = null
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
    var showUninstallConfirm by remember { mutableStateOf(false) }
    var showProgressDialog by remember { mutableStateOf(false) }
    var progressTitle by remember { mutableStateOf("") }
    var progressLog by remember { mutableStateOf("") }
    var progressSuccess by remember { mutableStateOf<Boolean?>(null) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // 预加载依赖/冲突包详情 + 已安装包名
    var installedNames by remember { mutableStateOf<Set<String>>(emptySet()) }
    var depDetails by remember { mutableStateOf<Map<String, PackageInfo?>>(emptyMap()) }
    var confDetails by remember { mutableStateOf<Map<String, PackageInfo?>>(emptyMap()) }

    // 观察 LiveUpdateState 的实时 log 和后台恢复请求
    val livePkgLog by LiveUpdateState.pkgLog.collectAsState()
    val pkgStateSnap by LiveUpdateState.pkgState.collectAsState()

    // 后台恢复请求 — 切回前台后自动恢复弹窗
    LaunchedEffect(Unit) {
        LiveUpdateState.pkgResumeRequest.collect { shouldResume ->
            if (shouldResume && LiveUpdateState.hasPkg()) {
                LiveUpdateState.consumeResumeRequest()
                val snap = LiveUpdateState.getPkgStateSnapshot()
                if (snap != null) {
                    showProgressDialog = true
                    progressTitle = when (snap.operation) {
                        LiveUpdateState.PkgOperation.INSTALL -> "正在安装 ${snap.packageName}"
                        LiveUpdateState.PkgOperation.UNINSTALL -> "正在卸载 ${snap.packageName}"
                        else -> "正在处理 ${snap.packageName}"
                    }
                    progressSuccess = null
                }
            }
        }
    }

    LaunchedEffect(pkg.name) {
        isLoading = true
        val d = PkgRepo.getDetail(context, pkg.name) ?: pkg
        detail = d

        // 并行加载已安装包名和依赖/冲突详情
        val installed = PkgRepo.getInstalled(context).map { it.name }.toSet()
        installedNames = installed

        depDetails = d.depends.associate { depName ->
            depName to PkgRepo.getDetail(context, depName)
        }
        confDetails = d.conflicts.associate { confName ->
            confName to PkgRepo.getDetail(context, confName)
        }

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

    fun runInstallUninstall(isInstall: Boolean, forceRemoveLock: Boolean = false, backgrounded: Boolean = false) {
        progressTitle = if (isInstall) "正在安装 ${pkg.name}" else "正在卸载 ${pkg.name}"
        progressLog = ""
        progressSuccess = null
        if (!backgrounded) showProgressDialog = true
        val op = if (isInstall) LiveUpdateState.PkgOperation.INSTALL else LiveUpdateState.PkgOperation.UNINSTALL
        LiveUpdateState.startPkg(op, pkg.name, backgrounded = backgrounded)
        // 使用 LiveUpdateState.pkgScope — 独立于 UI 生命周期，切后台不取消
        LiveUpdateState.pkgScope.launch {
            val result = if (isInstall) {
                PkgRepo.install(context, pkg.name, onOutput = { LiveUpdateState.appendPkgLog(it) })
            } else {
                PkgRepo.uninstall(context, pkg.name, onOutput = { LiveUpdateState.appendPkgLog(it) })
            }
            val ok = result.first
            val log = result.second
            LiveUpdateState.finishPkg(ok)
            if (!ok && !forceRemoveLock && isLockError(log)) {
                progressLog = log
                progressSuccess = false
                if (backgrounded) {
                    // 后台运行遇到锁 — 重新弹窗让用户处理
                    showProgressDialog = true
                } else {
                    showProgressDialog = false
                }
                pendingAction = { runInstallUninstall(isInstall, forceRemoveLock = true, backgrounded = backgrounded) }
                showLockDialog = true
            } else {
                progressLog = log
                progressSuccess = ok
                if (backgrounded) showProgressDialog = true
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

    fun computeCanInstall(target: PackageInfo): Boolean {
        // 依赖项：源内不存在（depDetails 为 null）才视为无法满足；源内存在将随安装一并装好
        val hasUnsatisfiedDep = target.depends.any { depName -> depDetails[depName] == null }
        // 冲突项：当前已安装才算冲突未满足
        val hasInstalledConflict = target.conflicts.any { confName -> confName in installedNames }
        return !hasUnsatisfiedDep && !hasInstalledConflict
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
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { if (!showProgressDialog && !showLockDialog) onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.back),
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
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
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = AccentBlue)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "正在检查依赖和冲突项",
                        fontSize = 13.sp,
                        color = colorScheme.onSurfaceVariantSummary
                    )
                }
            } else {
                val d = detail ?: pkg

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
                    // 安装状态卡 — 仅未安装的软件包显示，置于 TopAppBar 下方、描述上方
                    if (!d.isInstalled) {
                        item {
                            PackageInstallStatusCard(
                                pkgName = d.name,
                                canInstall = computeCanInstall(d)
                            )
                        }
                    }

                    // Description card
                    if (d.description.isNotBlank()) {
                        item {
                            SmallTitle(
                                text = stringResource(R.string.plugin_description),
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

                    // Info section — 每个信息独立一张卡片
                    if (d.homepage.isNotBlank() || d.maintainer.isNotBlank() ||
                        d.size.isNotBlank() || d.license.isNotBlank()) {
                        item {
                            SmallTitle(
                                text = stringResource(R.string.log_level_info),
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }

                    // Homepage — 可跳转，用 ArrowPreference
                    if (d.homepage.isNotBlank()) {
                        item {
                            val isLongUrl = d.homepage.length > 40
                            val summaryText = if (isLongUrl) d.homepage.take(38) + "…" else d.homepage
                            Card(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ArrowPreference(
                                    title = "主页",
                                    summary = summaryText,
                                    onClick = { openHomepage(d.homepage) },
                                    startAction = {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_link),
                                            contentDescription = null,
                                            tint = colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // Maintainer / Size / License — 不可跳转，只 Card + 文本
                    val plainInfoFields = mutableListOf<Pair<String, String>>()
                    if (d.maintainer.isNotBlank()) plainInfoFields.add("维护者" to d.maintainer)
                    if (d.size.isNotBlank()) plainInfoFields.add("大小" to d.size)
                    if (d.license.isNotBlank()) plainInfoFields.add("许可证" to d.license)

                    items(plainInfoFields) { (label, value) ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 14.sp,
                                    color = colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.width(72.dp)
                                )
                                Text(
                                    text = value,
                                    fontSize = 14.sp,
                                    color = colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Dependencies section — 每个依赖一张卡片
                    if (d.depends.isNotEmpty()) {
                        item {
                            SmallTitle(
                                text = "依赖",
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        items(d.depends) { depName ->
                            val depInfo = depDetails[depName]
                            val status = when {
                                depInfo == null -> DepStatus.NOT_SATISFIED
                                depName in installedNames -> DepStatus.INSTALLED
                                else -> DepStatus.WILL_INSTALL
                            }
                            val summaryLine = if (depInfo != null) {
                                val versionPart = depInfo.version.takeIf { it.isNotBlank() }?.let { "v$it" } ?: ""
                                val sectionPart = depInfo.section.takeIf { it.isNotBlank() }
                                val parts = listOfNotNull(versionPart, sectionPart)
                                parts.joinToString(" · ").ifBlank { "暂无相关信息" }
                            } else {
                                "暂无相关信息"
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ArrowPreference(
                                    title = depName,
                                    summary = summaryLine,
                                    onClick = {
                                        if (depInfo != null) {
                                            onOpenPackageDetail?.invoke(
                                                depInfo.copy(isInstalled = depName in installedNames)
                                            )
                                        } else {
                                            Toast.makeText(context, "源内没有此软件包", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    endActions = {
                                        Text(
                                            text = status.text,
                                            fontSize = 13.sp,
                                            color = status.color,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                )
                            }
                        }
                    }

                    // Conflicts section — 每个冲突一张卡片
                    if (d.conflicts.isNotEmpty()) {
                        item {
                            SmallTitle(
                                text = "冲突",
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        items(d.conflicts) { confName ->
                            val confInfo = confDetails[confName]
                            val status = if (confName in installedNames) {
                                ConfStatus.NOT_SATISFIED
                            } else {
                                ConfStatus.SATISFIED
                            }
                            val summaryLine = if (confInfo != null) {
                                val versionPart = confInfo.version.takeIf { it.isNotBlank() }?.let { "v$it" } ?: ""
                                val sectionPart = confInfo.section.takeIf { it.isNotBlank() }
                                val parts = listOfNotNull(versionPart, sectionPart)
                                parts.joinToString(" · ").ifBlank { "暂无相关信息" }
                            } else {
                                "暂无相关信息"
                            }

                            Card(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                ArrowPreference(
                                    title = confName,
                                    summary = summaryLine,
                                    onClick = {
                                        if (confInfo != null) {
                                            onOpenPackageDetail?.invoke(
                                                confInfo.copy(isInstalled = confName in installedNames)
                                            )
                                        } else {
                                            Toast.makeText(context, "源内没有此软件包", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    endActions = {
                                        Text(
                                            text = status.text,
                                            fontSize = 13.sp,
                                            color = status.color,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Bottom install/uninstall button
            if (!isLoading) {
                val d = detail ?: pkg
                val canInstall = computeCanInstall(d)
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
                            onClick = { showUninstallConfirm = true },
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
                            enabled = canInstall,
                            colors = ButtonDefaults.buttonColors(
                                color = AccentBlue
                            )
                        ) {
                            Text(stringResource(R.string.action_styling_install), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White)
                        }
                    }
                }
            }

            OverlayDialog(
                show = showUninstallConfirm,
                title = "确认卸载",
                summary = "确定要卸载 ${pkg.name} 吗？此操作不可撤销。",
                onDismissRequest = { showUninstallConfirm = false },
                content = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            text = stringResource(R.string.cancel),
                            onClick = { showUninstallConfirm = false },
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                showUninstallConfirm = false
                                startOperation(isInstall = false)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(color = DangerRed)
                        ) {
                            Text("确认卸载", color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            )

            OverlayDialog(
                show = showLockDialog,
                title = stringResource(R.string.pkg_manager_busy),
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
                            text = stringResource(R.string.cancel),
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
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Loading indicator + "处理中..." 一行居中
                        if (progressSuccess == null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentBlue, strokeWidth = 3.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.common_processing),
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            // 后台运行按钮
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    text = "后台运行",
                                    onClick = {
                                        LiveUpdateState.markPkgBackgrounded()
                                        showProgressDialog = false
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
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

                        // Log area — 加载中和完成后都显示，实时更新
                        val displayLog = if (progressSuccess == null) livePkgLog else progressLog
                        val clippedLog = if (displayLog.length > 5000) displayLog.substring(displayLog.length - 5000) else displayLog
                        if (clippedLog.isNotBlank()) {
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
                                    text = clippedLog,
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
                                    text = stringResource(R.string.low_android_force_disable_confirm),
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

/**
 * 软件包详情页安装状态卡（对齐 TerminalListScreen.ServiceStatusCard 竖向模式，但不提供收缩按钮）。
 * 仅未安装的软件包显示，置于 TopAppBar 下方、描述上方。
 * - 可安装：绿底 + 右下角对号，标题“已准备好安装”
 * - 不可安装：红底 + 右下角感叹号，标题“暂时无法安装”
 */
@Composable
private fun PackageInstallStatusCard(
    pkgName: String,
    canInstall: Boolean
) {
    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color.Black
    val (cardColor, iconColor, icon) = if (canInstall) {
        Triple(
            if (isDark) Color(0xFF1A3825) else Color(0xFFDFFAE4),
            Color(0xFF36D167),
            Icons.Rounded.CheckCircleOutline
        )
    } else {
        Triple(
            if (isDark) Color(0xFF3B1414) else Color(0xFFFFEBEE),
            Color(0xFFFF5252),
            Icons.Rounded.ErrorOutline
        )
    }
    val title = if (canInstall) "已准备好安装" else "暂时无法安装"
    val desc = if (canInstall) {
        "点击安装按钮开始安装$pkgName，如有需要的依赖也将一并安装"
    } else {
        "$pkgName 有依赖或冲突项无法满足，请检查"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().background(cardColor)) {
            // 右下角半透明水印图标（同 ServiceStatusCard 竖向模式）
            Box(
                modifier = Modifier.fillMaxSize().offset(35.dp, 35.dp),
                contentAlignment = Alignment.BottomEnd
            ) {
                Icon(
                    modifier = Modifier.size(120.dp).alpha(0.8f),
                    imageVector = icon,
                    tint = iconColor,
                    contentDescription = null
                )
            }
            Column(modifier = Modifier.fillMaxWidth().padding(18.dp)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = desc,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = textColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}
