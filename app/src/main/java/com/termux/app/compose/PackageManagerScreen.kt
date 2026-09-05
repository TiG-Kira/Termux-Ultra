package com.termux.app.compose

import android.content.Context
import com.termux.R
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.shared.models.ExecutionCommand
import com.termux.shared.shell.TermuxShellEnvironmentClient
import com.termux.shared.shell.TermuxShellUtils
import com.termux.shared.shell.TermuxTask
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

object AppShell {

    suspend fun exec(context: Context, command: String, timeout: Int = 60): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val shell = resolveShell()
            if (shell == null) return@withContext Pair(-1, "找不到 shell")

            val ec = ExecutionCommand(
                System.currentTimeMillis().toInt(),
                shell,
                arrayOf("-c", command),
                null, null, true, false
            )
            val client = TermuxShellEnvironmentClient()
            val task = try {
                TermuxTask.execute(context, ec, null, client, false)
            } catch (e: Exception) {
                return@withContext Pair(-1, e.message ?: "执行失败")
            }

            val deadline = System.currentTimeMillis() + timeout * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(150)
                if (ec.hasExecuted() || ec.resultData.exitCode != null) break
            }
            runCatching { task.killIfExecuting(context, false) }

            val rd = ec.resultData
            val out = rd.stdout.toString()
            val err = rd.stderr.toString()
            val code = rd.exitCode ?: -1
            Pair(code, if (out.isNotBlank()) out else err)
        }

    private fun resolveShell(): String? {
        val binDir = TermuxShellUtils.getDefaultBinPath()
        if (binDir.isNotEmpty()) {
            for (name in arrayOf("bash", "login", "zsh", "sh")) {
                val f = File(binDir, name)
                if (f.exists() && f.canExecute()) return f.absolutePath
            }
        }
        val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        for (name in arrayOf("bash", "sh")) {
            val f = File("$prefix/bin", name)
            if (f.exists() && f.canExecute()) return f.absolutePath
        }
        return null
    }
}

data class PackageInfo(
    val name: String,
    val version: String = "",
    val description: String = "",
    val isInstalled: Boolean = false,
    val homepage: String = "",
    val depends: List<String> = emptyList(),
    val maintainer: String = "",
    val conflicts: List<String> = emptyList(),
    val license: String = "",
    val size: String = ""
)

object PkgRepo {

    suspend fun getInstalled(context: Context): List<PackageInfo> {
        val (code, output) = AppShell.exec(context, "pkg list-installed 2>/dev/null")
        if (code != 0) return emptyList()
        val result = mutableListOf<PackageInfo>()
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Last")) continue
            val nameVersion = trimmed.substringBefore('\t')
            val slashIdx = nameVersion.indexOf('/')
            if (slashIdx > 0) {
                val name = nameVersion.substring(0, slashIdx)
                val version = nameVersion.substring(slashIdx + 1)
                if (name.isNotBlank()) result.add(PackageInfo(name, version, isInstalled = true))
            }
        }
        return result
    }

    suspend fun getAvailableAll(context: Context): List<PackageInfo> {
        val (code, output) = AppShell.exec(context, "pkg list-all 2>/dev/null", timeout = 60)
        if (code != 0) return emptyList()
        val installedNames = getInstalledNames(context)
        val result = mutableListOf<PackageInfo>()
        val seen = mutableSetOf<String>()
        val pkgNameRegex = Regex("^[a-z0-9][a-z0-9+._-]*$")
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("Last")) continue
            if (trimmed.startsWith("Installed package ")) continue
            // Skip repository URL lines and apt repo markers
            if (trimmed.contains("://")) continue
            if (trimmed.startsWith("[") && "]" in trimmed) continue
            val slashIdx = trimmed.indexOf('/')
            if (slashIdx > 0) {
                val beforeSlash = trimmed.substring(0, slashIdx).trim()
                val actualName = if (beforeSlash.startsWith("Package ")) {
                    beforeSlash.removePrefix("Package ").trim()
                } else {
                    beforeSlash
                }
                val versionPart = trimmed.substring(slashIdx + 1).substringBefore(' ').trim()
                if (actualName.isNotBlank() && actualName !in seen && actualName !in installedNames
                    && pkgNameRegex.matches(actualName)) {
                    seen.add(actualName)
                    result.add(PackageInfo(actualName, versionPart, isInstalled = false))
                }
            }
        }
        return result
    }

    suspend fun searchAvailable(context: Context, keyword: String): List<PackageInfo> {
        if (keyword.isBlank()) return emptyList()
        val (code, output) = AppShell.exec(context, "pkg search ${keyword} 2>/dev/null")
        if (code != 0) return emptyList()
        val result = mutableListOf<PackageInfo>()
        val installedNames = getInstalledNames(context)
        val seen = mutableSetOf<String>()
        val pkgNameRegex = Regex("^[a-z0-9][a-z0-9+._-]*$")
        for (line in output.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            // Skip repository URL lines and apt repo markers
            if (trimmed.contains("://")) continue
            if (trimmed.startsWith("[") && "]" in trimmed) continue
            val slashIdx = trimmed.indexOf('/')
            if (slashIdx > 0) {
                val beforeSlash = trimmed.substring(0, slashIdx).trim()
                val actualName = if (beforeSlash.contains("package", ignoreCase = true)) {
                    beforeSlash.substringAfterLast(' ').ifBlank { beforeSlash }
                } else {
                    beforeSlash
                }
                val versionPart = trimmed.substring(slashIdx + 1).substringBefore(' ').trim()
                if (actualName.isNotBlank() && actualName !in seen && pkgNameRegex.matches(actualName)) {
                    seen.add(actualName)
                    result.add(
                        PackageInfo(
                            name = actualName,
                            version = versionPart,
                            isInstalled = installedNames.contains(actualName)
                        )
                    )
                }
            }
        }
        return result
    }

    suspend fun getDetail(context: Context, name: String): PackageInfo? {
        val installed = getInstalledNames(context)
        val (code, output) = AppShell.exec(context, "pkg show $name 2>/dev/null")
        if (code != 0 && output.isBlank()) return null

        val fields = mutableMapOf<String, String>()
        var lastKey = ""
        for (rawLine in output.lines()) {
            val line = rawLine
            if (line.startsWith(" ") || line.startsWith("\t")) {
                if (lastKey.isNotEmpty()) fields[lastKey] = (fields[lastKey] ?: "") + "\n" + line.trim()
            } else if (":" in line) {
                val colonIdx = line.indexOf(':')
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                lastKey = key
                fields[key] = value
            }
        }

        val depends = (fields["Depends"] ?: "").split(',').map { it.trim() }.filter { it.isNotBlank() }
        val conflicts = (fields["Conflicts"] ?: "").split(',').map { it.trim() }.filter { it.isNotBlank() }

        return PackageInfo(
            name = fields["Package"] ?: name,
            version = fields["Version"] ?: "",
            description = fields["Description"] ?: "",
            homepage = fields["Homepage"] ?: "",
            depends = depends,
            isInstalled = installed.contains(name),
            maintainer = fields["Maintainer"] ?: "",
            conflicts = conflicts,
            license = fields["License"] ?: "",
            size = fields["Size"] ?: ""
        )
    }

    suspend fun install(context: Context, name: String): Pair<Boolean, String> {
        val (code, output) = AppShell.exec(context, "export DEBIAN_FRONTEND=noninteractive && pkg install -y $name 2>&1", timeout = 180)
        return (code == 0) to output
    }

    suspend fun uninstall(context: Context, name: String): Pair<Boolean, String> {
        val (code, output) = AppShell.exec(context, "export DEBIAN_FRONTEND=noninteractive && pkg uninstall -y $name 2>&1", timeout = 60)
        return (code == 0) to output
    }

    suspend fun update(context: Context): Pair<Boolean, String> {
        val (code, output) = AppShell.exec(context, "export DEBIAN_FRONTEND=noninteractive && pkg update 2>&1", timeout = 180)
        return (code == 0) to output
    }

    suspend fun upgradeAll(context: Context): Pair<Boolean, String> {
        val (code, output) = AppShell.exec(context, "export DEBIAN_FRONTEND=noninteractive && pkg upgrade -y 2>&1", timeout = 300)
        return (code == 0) to output
    }

    suspend fun hasLocks(context: Context): Boolean {
        val (_, out) = AppShell.exec(context, "pgrep -f 'pkg|apt|dpkg' 2>/dev/null")
        return out.trim().isNotEmpty()
    }

    suspend fun forceRemoveLocks(context: Context): String {
        val prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH
        val locks = listOf(
            "$prefix/var/lib/apt/lists/lock",
            "$prefix/var/lib/dpkg/lock-frontend",
            "$prefix/var/lib/dpkg/lock",
            "$prefix/var/cache/apt/archives/lock",
            "$prefix/cache/apt/archives/lock"
        )
        val cmd = "rm -f ${locks.joinToString(" ")} 2>&1"
        val (_, out) = AppShell.exec(context, cmd)
        return out
    }

    private suspend fun getInstalledNames(context: Context): Set<String> {
        return getInstalled(context).map { it.name }.toSet()
    }
}

@Composable
fun PackageManagerScreen(
    navBarBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
    onBackPressed: () -> Unit = {}
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior()
    val isDark = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()

        var showProgressDialog by remember { mutableStateOf(false) }
    var progressTitle by remember { mutableStateOf("") }
    var progressLog by remember { mutableStateOf("") }
    var progressSuccess by remember { mutableStateOf<Boolean?>(null) }
            
var selectedTab by remember { mutableStateOf(0) }
    var installedList by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var availableList by remember { mutableStateOf<List<PackageInfo>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var searchExpanded by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var loadingAvailable by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf<PackageInfo?>(null) }

    LaunchedEffect(Unit) {
        isLoading = true
        installedList = PkgRepo.getInstalled(context)
        isLoading = false
    }



    LaunchedEffect(searchQuery, selectedTab) {
        if (searchQuery.isBlank()) {
            if (selectedTab == 1) {
                loadingAvailable = true
                availableList = PkgRepo.getAvailableAll(context)
                loadingAvailable = false
            }
        } else {
            loadingAvailable = true
            delay(300)
            availableList = PkgRepo.searchAvailable(context, searchQuery)
            loadingAvailable = false
        }
    }

    if (showDetail != null) {
        BackHandler { showDetail = null }
        PackageDetailScreen(
            pkg = showDetail!!,
            navBarBottomPadding = navBarBottomPadding,
            onBack = { showDetail = null },
            onChanged = { success ->
                scope.launch {
                    installedList = PkgRepo.getInstalled(context)
                    if (searchQuery.isNotBlank()) {
                        availableList = PkgRepo.searchAvailable(context, searchQuery)
                    }
                }
                if (success) {
                    Toast.makeText(context, "操作成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "操作失败，请检查日志", Toast.LENGTH_SHORT).show()
                }
                showDetail = null
            }
        )
        return
    }

    BackHandler {
        when {
            searchQuery.isNotBlank() -> searchQuery = ""
            else -> onBackPressed()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "软件包管理",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable {
                                if (searchQuery.isNotBlank()) searchQuery = ""
                                else onBackPressed()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            progressTitle = "正在刷新软件源"
                            progressLog = ""
                            progressSuccess = null
                            showProgressDialog = true
                            scope.launch {
                                val (ok, log) = PkgRepo.update(context)
                                progressLog = log
                                progressSuccess = ok
                                if (ok) {
                                    installedList = PkgRepo.getInstalled(context)
                                    if (searchQuery.isBlank()) {
                                        availableList = PkgRepo.getAvailableAll(context)
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_refresh),
                            contentDescription = "刷新软件源",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            progressTitle = "正在升级所有包"
                            progressLog = ""
                            progressSuccess = null
                            showProgressDialog = true
                            scope.launch {
                                val (ok, log) = PkgRepo.upgradeAll(context)
                                progressLog = log
                                progressSuccess = ok
                                if (ok) {
                                    installedList = PkgRepo.getInstalled(context)
                                }
                            }
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_download),
                            contentDescription = "升级所有包",
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SearchBar(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                inputField = {
                    InputField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        onSearch = { },
                        expanded = true,
                        onExpandedChange = { },
                        label = "搜索软件包"
                    )
                },
                expanded = true,
                onExpandedChange = { }
            ) { }

            if (searchQuery.isBlank()) {
                TabRowWithContour(
                    tabs = listOf("已安装 (${installedList.size})", "未安装"),
                    selectedTabIndex = selectedTab,
                    onTabSelected = { selectedTab = it },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (isLoading || loadingAvailable) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color(0xFF2563EB)
                    )
                } else {
                    val list = if (searchQuery.isNotBlank()) {
                        val q = searchQuery.lowercase()
                        val installedMatch = installedList.filter { it.name.lowercase().contains(q) }
                        val availableMatch = availableList.filter { it.name.lowercase().contains(q) }
                        (installedMatch + availableMatch).distinctBy { it.name }
                    } else if (selectedTab == 0) installedList else availableList
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp,
                            top = 4.dp, bottom = navBarBottomPadding + 16.dp
                        )
                    ) {
                        if (list.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 60.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (searchQuery.isNotBlank()) "未找到匹配的软件包"
                                               else if (selectedTab == 0) "暂无已安装的包"
                                               else "暂无未安装的包",
                                        color = if (isDark) Color.White.copy(alpha = 0.5f)
                                                else Color.Black.copy(alpha = 0.5f),
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        } else {
                            items(list) { pkg ->
                                PackageCard(
                                    pkg = pkg,
                                    onClick = { showDetail = pkg }
                                )
                            }
                        }
                    }
                }
            }

            OverlayDialog(
                show = showProgressDialog,
                title = progressTitle.ifBlank { "正在处理" },
                summary = "",
                onDismissRequest = { if (progressSuccess != null) showProgressDialog = false },
                content = {
                    val logScrollState = rememberScrollState()
                    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        if (progressSuccess == null) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = MiuixTheme.colorScheme.primary,
                                    strokeWidth = 3.dp
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        if (progressSuccess != null) {
                            Text(
                                text = if (progressSuccess == true) "操作成功" else "操作失败",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (progressSuccess == true) MiuixTheme.colorScheme.primary else Color(0xFFDC2626)
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                        if (progressLog.isNotBlank()) {
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
                        if (progressSuccess != null) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                TextButton(
                                    text = "关闭",
                                    onClick = { showProgressDialog = false },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun PackageCard(
    pkg: PackageInfo,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val textColor = if (isDark) Color.White else Color.Black
    val subColor = if (isDark) Color.White.copy(alpha = 0.6f) else Color.Black.copy(alpha = 0.6f)
    val accentColor = Color(0xFF2563EB)
    val grayColor = Color(0xFF6B7280)

    MiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pkg.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = pkg.version.ifBlank { "未知版本" },
                    fontSize = 13.sp,
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Box(
                modifier = Modifier
                    .background(
                        color = if (pkg.isInstalled) accentColor.copy(alpha = 0.12f)
                               else grayColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (pkg.isInstalled) "已安装" else "可安装",
                    fontSize = 12.sp,
                    color = if (pkg.isInstalled) accentColor else grayColor
                )
            }
        }
    }
}
