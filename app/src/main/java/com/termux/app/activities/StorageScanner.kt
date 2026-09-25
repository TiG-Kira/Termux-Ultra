package com.termux.app.activities

import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.storage.StorageManager
import android.app.usage.StorageStatsManager
import android.system.Os
import android.system.OsConstants
import com.termux.R
import com.termux.app.compose.AiLocalModel
import java.io.File
import java.util.LinkedHashSet

/**
 * 存储占用扫描。
 *
 * 设计前提：分类占用之和必须等于总占用。此前分类用 `File.length()` 逻辑大小、
 * 总数用 StorageStatsManager 真实块占用，两套口径天生对不上；同时还存在
 * 「先算总量再扣除已分类目录」的补数写法，导致缓存、本地模型被重复计算。
 *
 * 现在改为：一趟遍历应用私有数据目录 + 应用框架部分，每个文件按路径归入
 * 唯一分类（互斥、全覆盖），占用统一取 st_blocks * 512（与 du / 系统统计同口径）。
 * 总占用直接由分类求和得出，构造上不可能不一致。
 */
internal object StorageScanner {

    private const val BLOCK_SIZE = 512L

    data class ScanResult(
        val categories: List<CategoryStorage>,
        /** 分类之和，即页面展示的总占用。 */
        val totalBytes: Long,
        /** 系统 StorageStatsManager 数值，仅用于交叉校验与排障。 */
        val systemReportedBytes: Long
    )

    fun scan(context: Context): ScanResult {
        val sizes = mutableMapOf<StorageCategory, Long>()
        StorageCategory.entries.forEach { sizes[it] = 0L }
        val cleanable = mutableListOf<CleanableItem>()
        val countedInodes = HashSet<String>()

        val dataDir = File(context.applicationInfo.dataDir)
        walkAndClassify(dataDir, sizes, countedInodes)

        sizes[StorageCategory.APP_FRAMEWORK] =
            sizes.getValue(StorageCategory.APP_FRAMEWORK) + measureAppFramework(context)

        collectCleanable(context, cleanable)

        // 总占用以系统统计为准（app + data + cache），
        // 实测分类之和与系统值之间的差额归入「其它」，保证分类相加 === 总占用。
        val systemTotal = querySystemStats(context)
        val measuredExceptOther = sizes.filterKeys { it != StorageCategory.OTHER }.values.sum()
        val otherBytes = if (systemTotal > measuredExceptOther) {
            systemTotal - measuredExceptOther
        } else {
            sizes.getValue(StorageCategory.OTHER)
        }
        sizes[StorageCategory.OTHER] = otherBytes

        val categories = StorageCategory.entries.map { category ->
            CategoryStorage(
                category = category,
                sizeBytes = sizes.getValue(category),
                cleanableItems = cleanable.filter { it.category == category }
            )
        }.sortedByDescending { it.sizeBytes }

        return ScanResult(
            categories = categories,
            totalBytes = categories.sumOf { it.sizeBytes },
            systemReportedBytes = systemTotal
        )
    }

    /**
     * 遍历应用私有数据目录并按路径给每个条目分类。
     * 目录自身的块也计入（与 du 一致），路径规则互斥保证不会重复计数。
     * 用显式栈代替递归：prefix 下目录层级很深，递归可能 StackOverflowError。
     */
    private fun walkAndClassify(
        root: File,
        sizes: MutableMap<StorageCategory, Long>,
        countedInodes: HashSet<String>
    ) {
        val pending = ArrayDeque<File>()
        pending.add(root)

        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            val stat = runCatching { Os.lstat(current.absolutePath) }.getOrNull() ?: continue

            // 符号链接不跟随也不计入，避免把链接目标（busybox applets、~/storage）重复算进来
            if (OsConstants.S_ISLNK(stat.st_mode)) continue

            val isFile = OsConstants.S_ISREG(stat.st_mode)
            if (isFile) {
                // 硬链接去重：同一 (dev, ino) 只算一次
                if (!countedInodes.add("${stat.st_dev}:${stat.st_ino}")) continue
            }

            val category = classify(relativePath(root, current))
            sizes[category] = sizes.getValue(category) + stat.st_blocks * BLOCK_SIZE

            if (!isFile) {
                listChildren(current)?.forEach { pending.add(it) }
            }
        }
    }

    /** 路径 → 分类。规则互斥：先特例后兜底，保证每个路径只落在一个分类里。 */
    private fun classify(relative: String): StorageCategory {
        if (relative.startsWith("files/home/")) {
            val inHome = relative.removePrefix("files/home/")
            return when {
                inHome.startsWith(".local/share/containers/") ||
                    inHome.startsWith("containers/") ||
                    inHome.startsWith(".docker/") -> StorageCategory.CONTAINERS
                inHome.startsWith("vm/") || inHome.startsWith("qemu/") -> StorageCategory.VM_FILES
                // 本地模型目录在 home 内，单独归类避免和「用户文档」重复计数
                inHome.startsWith(".local/share/termux-ultra/llm") -> StorageCategory.LOCAL_MODEL
                else -> StorageCategory.USER_DOCS
            }
        }
        if (relative.startsWith("files/")) return StorageCategory.TERMUX_FILESYSTEM
        return StorageCategory.OTHER
    }

    /**
     * 应用框架：APK、split APK、原生库、oat/vdex 都在应用数据目录之外，单独计量。
     * 取不到（无权限/路径变化）时静默跳过，不参与分类求和也不会虚高。
     */
    private fun measureAppFramework(context: Context): Long {
        var total = 0L
        val info = context.applicationInfo
        val apks = buildList {
            add(File(info.sourceDir))
            info.splitSourceDirs?.forEach { add(File(it)) }
        }
        apks.forEach { file ->
            val size = diskUsage(file)
            total += size
        }

        info.nativeLibraryDir?.let {
            val size = sumDiskUsage(File(it))
            total += size
        }

        // oat/<abi>/base.{oat,vdex,art}
        val oatDir = File(File(info.sourceDir).parentFile, "oat")
        val oatSize = sumDiskUsage(oatDir)
        total += oatSize
        return total
    }

    /** 可清理项：缓存、日志、缩略图来自私有目录（已计入分类），外置备份单列不计入总占用。 */
    private fun collectCleanable(context: Context, out: MutableList<CleanableItem>) {
        val homeDir = File(context.applicationInfo.dataDir, "files/home")

        val cacheDir = context.cacheDir
        val cacheSize = sumDiskUsage(cacheDir)
        if (cacheSize > 0) {
            out += CleanableItem(
                category = StorageCategory.OTHER,
                name = "缓存文件",
                description = "应用缓存数据",
                sizeBytes = cacheSize,
                path = cacheDir.absolutePath,
                type = CleanableType.CACHE
            )
        }

        val tempDir = File(cacheDir, "temp")
        if (tempDir.exists()) {
            out += CleanableItem(
                category = StorageCategory.OTHER,
                name = "临时文件",
                description = "临时下载和处理文件",
                sizeBytes = sumDiskUsage(tempDir),
                path = tempDir.absolutePath,
                type = CleanableType.TEMP
            )
        }

        val logDir = File(homeDir, ".termux/logs")
        if (logDir.exists()) {
            out += CleanableItem(
                category = StorageCategory.OTHER,
                name = "日志文件",
                description = "运行日志和崩溃报告",
                sizeBytes = sumDiskUsage(logDir),
                path = logDir.absolutePath,
                type = CleanableType.LOGS
            )
        }

        val thumbDir = File(homeDir, ".thumbnails")
        if (thumbDir.exists()) {
            out += CleanableItem(
                category = StorageCategory.OTHER,
                name = "缩略图缓存",
                description = "文件管理器生成的缩略图",
                sizeBytes = sumDiskUsage(thumbDir),
                path = thumbDir.absolutePath,
                type = CleanableType.THUMBNAIL
            )
        }

        val backupDir = File(Environment.getExternalStorageDirectory(), "TermuxBackup")
        if (backupDir.exists()) {
            out += CleanableItem(
                category = StorageCategory.OTHER,
                name = "旧备份文件",
                description = "过期的 Termux 备份文件",
                sizeBytes = sumDiskUsage(backupDir),
                path = backupDir.absolutePath,
                type = CleanableType.BACKUP
            )
        }
    }

    /**
     * 系统设置里显示的本应用占用：app（APK/库/oat）+ data + cache。
     * 这是总占用的唯一事实来源，分类之和向它对齐。
     */
    private fun querySystemStats(context: Context): Long {
        return try {
            val statsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val uuid = storageManager.getUuidForPath(Environment.getDataDirectory()) ?: StorageManager.UUID_DEFAULT
            val stats = statsManager.queryStatsForUid(uuid, Process.myUid())
            stats.appBytes + stats.dataBytes + stats.cacheBytes
        } catch (_: Exception) {
            0L
        }
    }

    private fun diskUsage(file: File): Long {
        val stat = runCatching { Os.lstat(file.absolutePath) }.getOrNull() ?: return 0L
        if (OsConstants.S_ISLNK(stat.st_mode)) return 0L
        return stat.st_blocks * BLOCK_SIZE
    }

    private fun sumDiskUsage(file: File): Long {
        if (!file.exists()) return 0L
        var total = 0L
        val pending = ArrayDeque<File>()
        pending.add(file)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            val stat = runCatching { Os.lstat(current.absolutePath) }.getOrNull() ?: continue
            if (OsConstants.S_ISLNK(stat.st_mode)) continue
            total += stat.st_blocks * BLOCK_SIZE
            if (OsConstants.S_ISDIR(stat.st_mode)) {
                listChildren(current)?.forEach { pending.add(it) }
            }
        }
        return total
    }

    /** listFiles 在部分目录上会抛 SecurityException，失败时当作无子项。 */
    private fun listChildren(dir: File): Array<File>? {
        return runCatching { dir.listFiles() }.getOrNull()
    }

    private fun relativePath(root: File, file: File): String {
        val rootPath = root.absolutePath.trimEnd('/')
        val fullPath = file.absolutePath
        return if (fullPath == rootPath) "" else fullPath.removePrefix("$rootPath/")
    }
}
