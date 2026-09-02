package com.termux.app.activities

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.termux.R
import com.termux.app.compose.AiLocalModel
import com.termux.app.compose.LOCAL_MODELS
import com.termux.app.compose.KiTerminalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

class StorageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val navDispatcher = com.termux.app.compose.NavigationHelper.createDispatcher()
            val navDispatcherOwner = com.termux.app.compose.NavigationHelper.createOwner(navDispatcher)
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner provides navDispatcherOwner
            ) {
                KiTerminalTheme {
                    StorageScreen(onBack = { finish() })
                }
            }
        }
    }
}

enum class StorageCategory(val labelRes: Int, val descRes: Int, val iconRes: Int) {
    APP_FRAMEWORK(R.string.storage_category_app_framework, R.string.storage_category_app_framework_desc, R.drawable.ic_code),
    TERMUX_FILESYSTEM(R.string.storage_category_filesystem, R.string.storage_category_filesystem_desc, R.drawable.ic_folder),
    USER_DOCS(R.string.storage_category_user_docs, R.string.storage_category_user_docs_desc, R.drawable.ic_files),
    CONTAINERS(R.string.storage_category_containers, R.string.storage_category_containers_desc, R.drawable.ic_launch),
    VM_FILES(R.string.storage_category_vm, R.string.storage_category_vm_desc, R.drawable.ic_vnc),
    LOCAL_MODEL(R.string.storage_category_local_model, R.string.storage_category_local_model_desc, R.drawable.ic_computer),
    OTHER(R.string.storage_category_other, R.string.storage_category_other_desc, R.drawable.ic_tools)
}

data class CategoryStorage(
    val category: StorageCategory,
    val sizeBytes: Long,
    val cleanableItems: List<CleanableItem>
)

data class CleanableItem(
    val name: String,
    val description: String,
    val sizeBytes: Long,
    val path: String,
    val type: CleanableType
)

enum class CleanableType(val labelRes: Int) {
    CACHE(R.string.storage_clean_cache),
    TEMP(R.string.storage_clean_temp),
    LOGS(R.string.storage_clean_logs),
    BACKUP(R.string.storage_clean_backup),
    EMPTY_DIR(R.string.storage_clean_empty),
    THUMBNAIL(R.string.storage_clean_thumbnails)
}

private fun formatSize(context: android.content.Context, bytes: Long): String {
    return when {
        bytes < 1024 -> context.getString(R.string.storage_size_bytes, bytes.toFloat())
        bytes < 1024 * 1024 -> context.getString(R.string.storage_size_kb, bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> context.getString(R.string.storage_size_mb, bytes / (1024.0 * 1024.0))
        else -> context.getString(R.string.storage_size_gb, bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun scanTermuxStorage(context: android.content.Context): List<CategoryStorage> {
    val termuxDir = File("/data/data/${context.packageName}/files/home")
    val termuxFilesDir = File("/data/data/${context.packageName}/files")
    val externalDir = Environment.getExternalStorageDirectory()
    val appCacheDir = context.cacheDir

    val result = mutableMapOf<StorageCategory, Pair<Long, MutableList<CleanableItem>>>()
    StorageCategory.entries.forEach { cat -> result[cat] = Pair(0L, mutableListOf()) }

    fun addToCategory(category: StorageCategory, size: Long, item: CleanableItem? = null) {
        val current = result[category]!!
        result[category] = Pair(current.first + size, current.second).also {
            if (item != null) it.second.add(item)
        }
    }

    fun getDirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return try {
            if (dir.isFile) dir.length()
            else dir.walkTopDown().filter { it.isFile }.sumOf { file ->
                runCatching { file.length() }.getOrDefault(0L)
            }
        } catch (_: Exception) {
            0L
        }
    }

    // 应用框架: APK + native libs
    val apkFile = File(context.applicationInfo.sourceDir)
    val apkSize = runCatching { apkFile.length() }.getOrDefault(0L)
    addToCategory(StorageCategory.APP_FRAMEWORK, apkSize)

    val nativeLibDir = File(context.applicationInfo.nativeLibraryDir ?: "")
    addToCategory(StorageCategory.APP_FRAMEWORK, getDirSize(nativeLibDir))

    // Termux 应用容器: files 目录下除 home 目录外的所有内部文件（prefix、配置等）
    val termuxFilesSize = if (termuxFilesDir.exists() && termuxFilesDir.isDirectory) {
        runCatching {
            termuxFilesDir.listFiles()?.sumOf { file ->
                if (file.name == "home") 0L
                else getDirSize(file)
            } ?: 0L
        }.getOrDefault(0L)
    } else 0L
    addToCategory(StorageCategory.TERMUX_FILESYSTEM, termuxFilesSize)

    // 用户文档: 外部存储中的 termux 相关
    val termuxExternal = File(externalDir, "Termux")
    val termuxExternalSize = getDirSize(termuxExternal)
    addToCategory(StorageCategory.USER_DOCS, termuxExternalSize)

    val downloadDir = File(externalDir, "Download/Termux")
    addToCategory(StorageCategory.USER_DOCS, getDirSize(downloadDir))

    // 容器: 检查常见容器路径
    val containerPaths = listOf(
        File(termuxDir, ".local/share/containers"),
        File(termuxDir, "containers"),
        File(termuxDir, ".docker")
    )
    containerPaths.forEach { path ->
        if (path.exists()) {
            addToCategory(StorageCategory.CONTAINERS, getDirSize(path))
        }
    }

    // 虚拟机文件: 检查 QEMU/VM 相关路径
    val vmPaths = listOf(
        File(termuxDir, "vm"),
        File(termuxDir, "qemu"),
        File(externalDir, "Termux/VM")
    )
    vmPaths.forEach { path ->
        if (path.exists()) {
            addToCategory(StorageCategory.VM_FILES, getDirSize(path))
        }
    }

    // 本地大模型: 设备端 AI 模型占用
    addToCategory(StorageCategory.LOCAL_MODEL, AiLocalModel.getInstalledModelSize())

    // === 计算 home 目录下已归类子目录的大小，用于后续扣除 ===
    val alreadyClassifiedHomeSubDirs = listOfNotNull(
        File(termuxDir, ".local/share/containers").takeIf { it.exists() }?.let { getDirSize(it) },
        File(termuxDir, "containers").takeIf { it.exists() }?.let { getDirSize(it) },
        File(termuxDir, ".docker").takeIf { it.exists() }?.let { getDirSize(it) },
        File(termuxDir, "vm").takeIf { it.exists() }?.let { getDirSize(it) },
        File(termuxDir, "qemu").takeIf { it.exists() }?.let { getDirSize(it) },
        File(termuxDir, ".termux/logs").takeIf { it.exists() }?.let { getDirSize(it) },
        File(termuxDir, ".thumbnails").takeIf { it.exists() }?.let { getDirSize(it) }
    ).sum()

    // home 目录下的常规用户文件（总大小 - 已归类子目录）
    val homeTotalSize = getDirSize(termuxDir)
    val homeRegularSize = (homeTotalSize - alreadyClassifiedHomeSubDirs).coerceAtLeast(0L)
    if (homeRegularSize > 0) {
        addToCategory(StorageCategory.USER_DOCS, homeRegularSize)
    }

    // === Android 标准目录（shared_prefs、databases、codeCache）归入 OTHER ===
    val sharedPrefsDir = File("/data/data/${context.packageName}/shared_prefs")
    val sharedPrefsSize = getDirSize(sharedPrefsDir)
    if (sharedPrefsSize > 0) {
        addToCategory(StorageCategory.OTHER, sharedPrefsSize)
    }

    val databasesDir = File("/data/data/${context.packageName}/databases")
    val databasesSize = getDirSize(databasesDir)
    if (databasesSize > 0) {
        addToCategory(StorageCategory.OTHER, databasesSize)
    }

    val codeCacheDir = context.codeCacheDir
    val codeCacheSize = getDirSize(codeCacheDir)
    if (codeCacheSize > 0) {
        addToCategory(StorageCategory.OTHER, codeCacheSize)
    }

    // 外部缓存目录也计入
    context.externalCacheDir?.let { externalCache ->
        val externalCacheSize = getDirSize(externalCache)
        if (externalCacheSize > 0) {
            addToCategory(StorageCategory.OTHER, externalCacheSize)
        }
    }

    // 其它: 内部缓存
    val cacheSize = getDirSize(appCacheDir)
    addToCategory(StorageCategory.OTHER, cacheSize)
    if (appCacheDir.exists() && cacheSize > 0) {
        addToCategory(StorageCategory.OTHER, cacheSize, CleanableItem(
            name = context.getString(R.string.storage_clean_cache),
            description = context.getString(R.string.storage_clean_cache_desc),
            sizeBytes = cacheSize,
            path = appCacheDir.absolutePath,
            type = CleanableType.CACHE
        ))
    }

    // 临时文件
    val tempDir = File(appCacheDir, "temp")
    if (tempDir.exists()) {
        val tempSize = getDirSize(tempDir)
        addToCategory(StorageCategory.OTHER, tempSize, CleanableItem(
            name = context.getString(R.string.storage_clean_temp),
            description = context.getString(R.string.storage_clean_temp_desc),
            sizeBytes = tempSize,
            path = tempDir.absolutePath,
            type = CleanableType.TEMP
        ))
    }

    // 日志文件
    val logDir = File(termuxDir, ".termux/logs")
    if (logDir.exists()) {
        val logSize = getDirSize(logDir)
        addToCategory(StorageCategory.OTHER, logSize, CleanableItem(
            name = context.getString(R.string.storage_clean_logs),
            description = context.getString(R.string.storage_clean_logs_desc),
            sizeBytes = logSize,
            path = logDir.absolutePath,
            type = CleanableType.LOGS
        ))
    }

    // 旧备份文件
    val backupDir = File(externalDir, "TermuxBackup")
    if (backupDir.exists()) {
        val backupSize = getDirSize(backupDir)
        addToCategory(StorageCategory.OTHER, backupSize, CleanableItem(
            name = context.getString(R.string.storage_clean_backup),
            description = context.getString(R.string.storage_clean_backup_desc),
            sizeBytes = backupSize,
            path = backupDir.absolutePath,
            type = CleanableType.BACKUP
        ))
    }

    // 缩略图缓存
    val thumbDir = File(termuxDir, ".thumbnails")
    if (thumbDir.exists()) {
        val thumbSize = getDirSize(thumbDir)
        addToCategory(StorageCategory.OTHER, thumbSize, CleanableItem(
            name = context.getString(R.string.storage_clean_thumbnails),
            description = context.getString(R.string.storage_clean_thumbnails_desc),
            sizeBytes = thumbSize,
            path = thumbDir.absolutePath,
            type = CleanableType.THUMBNAIL
        ))
    }

    return result.map { (category, pair) ->
        CategoryStorage(
            category = category,
            sizeBytes = pair.first,
            cleanableItems = pair.second
        )
    }.sortedByDescending { it.sizeBytes }
}


/**
 * 获取与 Android 系统设置中一致的当前应用总占用（app + data，含缓存）。
 * 使用 StorageStatsManager 查询本 UID 的统计信息，避免文件大小累加导致的虚高。
 */
private fun getAccurateAppStorageBytes(context: Context): Long {
    return try {
        val storageStatsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val uuid = storageManager.getUuidForPath(Environment.getDataDirectory()) ?: StorageManager.UUID_DEFAULT
        val stats = storageStatsManager.queryStatsForUid(uuid, Process.myUid())
        stats.appBytes + stats.dataBytes
    } catch (_: Exception) {
        0L
    }
}

private fun cleanItem(item: CleanableItem): Boolean {
    return try {
        val file = File(item.path)
        if (!file.exists()) return false
        if (file.isDirectory) {
            file.deleteRecursively()
        } else {
            file.delete()
        }
        true
    } catch (_: Exception) {
        false
    }
}

@Composable
fun StorageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()
    val scope = rememberCoroutineScope()
    var isScanning by remember { mutableStateOf(true) }
    var categories by remember { mutableStateOf<List<CategoryStorage>>(emptyList()) }
    var accurateUsedBytes by remember { mutableStateOf(0L) }
    var showCleanConfirm by remember { mutableStateOf(false) }
    var showResult by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var selectedCleanablePaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showLocalModelDetail by remember { mutableStateOf(false) }

    suspend fun scan() {
        isScanning = true
        val (scannedCategories, accurateBytes) = withContext(Dispatchers.IO) {
            scanTermuxStorage(context) to getAccurateAppStorageBytes(context)
        }
        categories = scannedCategories
        accurateUsedBytes = accurateBytes
        isScanning = false
    }

    LaunchedEffect(Unit) {
        scan()
    }

    // 本地大模型详情页（替换当前页面）
    if (showLocalModelDetail) {
        LocalModelDetailScreen(
            onBack = {
                showLocalModelDetail = false
                scope.launch { scan() }
            },
            onDeleted = {
                showLocalModelDetail = false
                scope.launch { scan() }
            }
        )
        return
    }

    val totalStorageBytes = remember {
        val stat = StatFs(Environment.getDataDirectory().path)
        stat.blockCountLong * stat.blockSizeLong
    }

    val freeBytes by remember(categories) {
        mutableStateOf(
            StatFs(Environment.getDataDirectory().path).availableBytes
        )
    }

    val allCleanableItems = categories.flatMap { it.cleanableItems }
    val totalCleanableBytes = allCleanableItems.sumOf { it.sizeBytes }

    fun doClean() {
        showCleanConfirm = true
    }

    fun confirmClean() {
        showCleanConfirm = false
        val itemsToClean = allCleanableItems.filter { it.path in selectedCleanablePaths }
        val failedItems = mutableListOf<String>()
        itemsToClean.forEach { item ->
            if (!cleanItem(item)) {
                failedItems.add(item.name)
            }
        }
        scope.launch {
            val totalFreed = itemsToClean.sumOf { it.sizeBytes }
            selectedCleanablePaths = emptySet()
            categories = withContext(Dispatchers.IO) {
                scanTermuxStorage(context)
            }
            resultMessage = if (failedItems.isEmpty()) {
                context.getString(R.string.storage_clean_success, formatSize(context, totalFreed))
            } else {
                context.getString(R.string.storage_clean_failed, failedItems.joinToString(", "))
            }
            showResult = true
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = context.getString(R.string.storage_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = context.getString(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(bottom = 92.dp)
        ) {
            // 总占用卡片
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(
                                    text = context.getString(R.string.storage_total_usage),
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Text(
                                    text = if (isScanning) context.getString(R.string.storage_scanning)
                                    else formatSize(context, accurateUsedBytes),
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = context.getString(R.string.storage_total_space),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                                Text(
                                    text = formatSize(context, totalStorageBytes),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                                Text(
                                    text = context.getString(R.string.storage_free_space) + " " +
                                            formatSize(context, freeBytes.coerceAtLeast(0)),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        if (!isScanning && totalStorageBytes > 0) {
                            val usagePercent = (accurateUsedBytes.toDouble() / totalStorageBytes * 100).coerceIn(0.0, 100.0)
                            LinearProgressIndicator(
                                progress = (usagePercent / 100).toFloat(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = String.format("%.1f%%", usagePercent),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                            Text(
                                text = context.getString(R.string.storage_category_estimate_note),
                                fontSize = 11.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }

            // 分类详情
            item { SmallTitle(text = "分类占用") }

            if (isScanning) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = context.getString(R.string.storage_scanning),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
            } else {
                items(categories.filter { it.sizeBytes > 0 }) { catStorage ->
                    val isLocalModel = catStorage.category == StorageCategory.LOCAL_MODEL
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .then(
                                if (isLocalModel) Modifier.clickable { showLocalModelDetail = true }
                                else Modifier
                            )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(id = catStorage.category.iconRes),
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = context.getString(catStorage.category.labelRes),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = context.getString(catStorage.category.descRes),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = formatSize(context, catStorage.sizeBytes),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface
                                )
                                val totalUsage = categories.sumOf { it.sizeBytes }
                                if (totalUsage > 0) {
                                    val percent = (catStorage.sizeBytes.toDouble() / totalUsage * 100)
                                    Text(
                                        text = String.format("%.1f%%", percent),
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                        }
                    }
                }

                // 一键清理
                if (allCleanableItems.isNotEmpty()) {
                    item { SmallTitle(text = "可清理项目") }

                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = context.getString(
                                        R.string.storage_cleanable_found,
                                        formatSize(context, totalCleanableBytes)
                                    ),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.primary
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                val selectedCleanable = allCleanableItems.filter { it.path in selectedCleanablePaths }
                                CheckboxPreference(
                                    title = "全选",
                                    summary = "${allCleanableItems.size} 项可选 · ${formatSize(context, totalCleanableBytes)}",
                                    checked = selectedCleanable.isNotEmpty() && selectedCleanable.size == allCleanableItems.size,
                                    onCheckedChange = { all ->
                                        selectedCleanablePaths =
                                            if (all) allCleanableItems.map { it.path }.toSet() else emptySet()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )

                                allCleanableItems.forEachIndexed { index, item ->
                                    CheckboxPreference(
                                        title = item.name,
                                        summary = "${item.description} · ${formatSize(context, item.sizeBytes)}",
                                        checked = item.path in selectedCleanablePaths,
                                        onCheckedChange = { isChecked ->
                                            selectedCleanablePaths =
                                                if (isChecked) selectedCleanablePaths + item.path
                                                else selectedCleanablePaths - item.path
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (index < allCleanableItems.lastIndex) {
                                        HorizontalDivider(
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.25f)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = { doClean() },
                                    enabled = selectedCleanable.isNotEmpty(),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = context.getString(R.string.storage_one_click_clean),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = context.getString(R.string.storage_no_cleanable),
                                    fontSize = 14.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }
                    }
                }
            }
        }
        // 清理确认对话框
        val cleaningItems = allCleanableItems.filter { it.path in selectedCleanablePaths }
        OverlayDialog(
            title = context.getString(R.string.storage_clean_confirm_title),
            summary = context.getString(
                R.string.storage_clean_confirm_summary,
                cleaningItems.size,
                formatSize(context, cleaningItems.sumOf { it.sizeBytes })
            ),
            show = showCleanConfirm,
            onDismissRequest = { showCleanConfirm = false },
            content = {
                Column {
                    Button(
                        onClick = { confirmClean() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = context.getString(R.string.storage_clean_confirm),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        text = context.getString(R.string.storage_clean_cancel),
                        onClick = { showCleanConfirm = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )

        // 清理结果对话框
        OverlayDialog(
            title = "清理结果",
            summary = resultMessage,
            show = showResult,
            onDismissRequest = { showResult = false },
            content = {
                Button(
                    onClick = { showResult = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = context.getString(R.string.ok),
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        )
    }
}


    // 本地大模型详情页
    @androidx.compose.runtime.Composable
    private fun LocalModelDetailScreen(
        onBack: () -> Unit,
        onDeleted: () -> Unit
    ) {
        val context = LocalContext.current
        val scrollBehavior = MiuixScrollBehavior()
        val scope = rememberCoroutineScope()
        var refresh by remember { mutableStateOf(0) }
        var showDeleteConfirm by remember { mutableStateOf(false) }

        val selected = AiLocalModel.getSelectedModel()
        val installed = remember(refresh) { AiLocalModel.isLocalModelReady() }
        val size = remember(refresh) { AiLocalModel.getInstalledModelSize() }
        val path = remember(refresh) { AiLocalModel.modelDir().absolutePath }
        val downloadedAt = remember(refresh) { AiLocalModel.getDownloadedAt() }
        val dateText = remember(downloadedAt) {
            if (downloadedAt > 0) {
                try {
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(downloadedAt))
                } catch (e: Exception) { "" }
            } else ""
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = context.getString(R.string.storage_local_model_detail_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = context.getString(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
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
        ) {
            itemDetailCard(
                icon = R.drawable.ic_computer,
                title = context.getString(R.string.storage_local_model_name),
                summary = selected?.displayName ?: context.getString(R.string.storage_local_model_not_installed)
            )
            itemDetailCard(
                icon = R.drawable.ic_storage,
                title = context.getString(R.string.storage_local_model_occupancy),
                summary = if (installed) formatSize(context, size) else context.getString(R.string.storage_local_model_not_installed)
            )
            itemDetailCard(
                icon = R.drawable.ic_folder,
                title = context.getString(R.string.storage_local_model_location),
                summary = path
            )
            if (dateText.isNotBlank()) {
                itemDetailCard(
                    icon = R.drawable.ic_download,
                    title = context.getString(R.string.storage_local_model_downloaded_at),
                    summary = dateText
                )
            }

            Spacer(Modifier.height(24.dp))

            if (installed) {
                Button(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        color = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF5B0000) else Color(0xFFFFEBEE)
                    )
                ) {
                    Text(
                        text = context.getString(R.string.storage_local_model_delete),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD32F2F)
                    )
                }
            }
        }

        OverlayDialog(
            title = context.getString(R.string.storage_local_model_delete),
            summary = context.getString(R.string.storage_local_model_delete_confirm_summary),
            show = showDeleteConfirm,
            onDismissRequest = { showDeleteConfirm = false },
            content = {
                Column {
                    Button(
                        onClick = {
                            showDeleteConfirm = false
                            scope.launch {
                                withContext(Dispatchers.IO) { AiLocalModel.deleteModel() }
                                onDeleted()
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(
                            color = if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFF5B0000) else Color(0xFFFFEBEE)
                        )
                    ) {
                        Text(
                            text = context.getString(R.string.storage_local_model_delete),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD32F2F)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        text = context.getString(R.string.storage_clean_cancel),
                        onClick = { showDeleteConfirm = false },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        )
    }


}

@androidx.compose.runtime.Composable
private fun itemDetailCard(icon: Int, title: String, summary: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = icon),
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(2.dp))
                Text(summary, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onSurface)
            }
        }
    }
}
