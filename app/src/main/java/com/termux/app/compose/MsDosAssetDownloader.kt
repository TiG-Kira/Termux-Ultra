package com.termux.app.compose

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * MS-DOS 4.00 系统盘的动态下载器。
 *
 * 资产在常驻 tag `msdos-v1` 的 prerelease Release 里，不是 App 版本 Release。
 * 远端 .sha256 与本地记账比对决定是否需要重下；本地缓存完整时允许离线启动。
 * 校验文件按官方域名优先、relay 兜底，所有响应的最终 host 都受白名单约束。
 */
object MsDosAssetDownloader {
    private const val TAG = "MsDosAssetDownloader"
    private const val REPO = "TiG-Kira/Termux-Ultra"

    const val RELEASE_TAG = "msdos-v1"
    const val DOWNLOAD_PAGE_URL = "https://github.com/$REPO/releases/tag/$RELEASE_TAG"
    const val TERMUX_DIR = "$TERMUX_HOME_ABS/msdos"
    const val DISK_PATH = "$TERMUX_DIR/msdos4.img"

    private const val ASSET = "msdos4.img.gz"
    private const val CHECKSUM_ASSET = "$ASSET.sha256"
    private const val STAMP_FILE = ".asset.sha256"
    private const val MAX_DOWNLOAD_BYTES = 64L * 1024 * 1024
    private const val MAX_EXTRACTED_BYTES = 128L * 1024 * 1024
    private const val MIN_DISK_BYTES = 8L * 1024 * 1024

    // 国内加速源前置：中国大陆访问 github.com 直连常超时，优先走镜像再回落官方。
    private val MIRROR_DIRS = listOf(
        "https://ghfast.top/https://github.com/$REPO/releases/download/$RELEASE_TAG/",
        "https://ghproxy.net/https://github.com/$REPO/releases/download/$RELEASE_TAG/",
        "https://mirror.ghproxy.com/https://github.com/$REPO/releases/download/$RELEASE_TAG/",
        "https://ghproxy.com/https://github.com/$REPO/releases/download/$RELEASE_TAG/",
        "https://github.com/$REPO/releases/download/$RELEASE_TAG/"
    )

    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "ghfast.top",
        "ghproxy.net",
        "mirror.ghproxy.com",
        "ghproxy.com"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    enum class Phase { CHECKING, DOWNLOADING, EXTRACTING, READY, FAILED }

    /** 记账格式 "<gz sha256>:<raw size>"，同时校验落盘镜像的精确长度。 */
    private fun readStamp(stamp: File): Pair<String, Long>? {
        val parts = stamp.takeIf { it.exists() }?.readText()?.trim()?.split(':') ?: return null
        val hash = parts.getOrNull(0)?.takeIf { it.length == 64 } ?: return null
        val size = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return hash to size
    }

    fun isDiskReady(): Boolean {
        val disk = File(DISK_PATH)
        val recorded = readStamp(File(TERMUX_DIR, STAMP_FILE)) ?: return false
        return disk.exists() && disk.length() >= MIN_DISK_BYTES && disk.length() == recorded.second
    }

    /**
     * 仅判断本地是否已有可用镜像（文件存在且达到最小体积）。
     * 不依赖远程校验记账，用于在后台检查/下载进行中也能解锁启动，
     * 避免"有本地镜像却被资产检查阻塞"。落盘采用原子 rename，运行中的会话不会读到半成品。
     */
    fun hasUsableDisk(): Boolean {
        val disk = File(DISK_PATH)
        return disk.exists() && disk.length() >= MIN_DISK_BYTES
    }

    /**
     * 确保磁盘镜像就位。内部切到 IO。
     * @param force 忽略本地记账强制重下
     * @param onState 阶段 / 进度(0..1，-1 表示未知) / 提示文案
     */
    suspend fun ensureDisk(
        force: Boolean = false,
        onState: (Phase, Float, String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        onState(Phase.CHECKING, -1f, "")
        val dir = File(TERMUX_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            onState(Phase.FAILED, -1f, "无法创建 $TERMUX_DIR")
            return@withContext false
        }

        val disk = File(dir, "msdos4.img")
        val stamp = File(dir, STAMP_FILE)
        val local = readStamp(stamp)
        val localReady = local != null && disk.exists() &&
            disk.length() >= MIN_DISK_BYTES && disk.length() == local.second

        val expected = fetchChecksum()
        if (expected == null) {
            // 离线但本地镜像完整，照常放行。
            if (!force && localReady) {
                onState(Phase.READY, 1f, "")
                return@withContext true
            }
            onState(Phase.FAILED, -1f, "无法获取校验文件，请检查网络或手动下载")
            return@withContext false
        }
        if (!force && localReady && local?.first?.equals(expected, ignoreCase = true) == true) {
            onState(Phase.READY, 1f, "")
            return@withContext true
        }

        var gz: ByteArray? = null
        var lastError: String? = null
        for (mirror in MIRROR_DIRS) {
            try {
                onState(Phase.DOWNLOADING, 0f, "")
                gz = fetch(mirror + ASSET) { done, total ->
                    val progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else -1f
                    onState(Phase.DOWNLOADING, progress, "")
                }
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "mirror failed: $mirror (${e.message})")
            }
        }
        val bytes = gz ?: run {
            onState(Phase.FAILED, -1f, lastError ?: "全部镜像下载失败")
            return@withContext false
        }

        if (!sha256Hex(bytes).equals(expected, ignoreCase = true)) {
            onState(Phase.FAILED, -1f, "SHA-256 校验不通过")
            return@withContext false
        }

        onState(Phase.EXTRACTING, -1f, "")
        val tmp = File(dir, "msdos4.img.tmp")
        try {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        written += read
                        if (written > MAX_EXTRACTED_BYTES) throw IOException("解压体积超出上限")
                        out.write(buf, 0, read)
                    }
                }
            }
            if (tmp.length() < MIN_DISK_BYTES) throw IOException("解压后镜像不完整")
            if (disk.exists() && !disk.delete()) throw IOException("无法替换旧镜像")
            if (!tmp.renameTo(disk)) throw IOException("镜像落盘失败")
            stamp.writeText("$expected:${disk.length()}")
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "extract failed", e)
            onState(Phase.FAILED, -1f, e.message ?: "解压失败")
            return@withContext false
        }

        Log.i(TAG, "MS-DOS disk ready at $DISK_PATH (${disk.length()} bytes)")
        onState(Phase.READY, 1f, "")
        true
    }

    /** 按整行锚定哈希，避免把 relay 的 HTML 错误页里的 hex 当成校验值；官方域名优先。 */
    private suspend fun fetchChecksum(): String? {
        val pattern = Regex("""^\s*([0-9a-fA-F]{64})\s*$""", RegexOption.MULTILINE)
        for (mirror in MIRROR_DIRS) {
            try {
                val found = pattern.find(String(fetch(mirror + CHECKSUM_ASSET)))?.groupValues?.get(1)
                if (found != null) return found
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "checksum mirror failed: $mirror (${e.message})")
            }
        }
        return null
    }

    private suspend fun fetch(url: String, onBytes: ((Long, Long) -> Unit)? = null): ByteArray {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val host = response.request.url.host
            if (host !in ALLOWED_HOSTS) throw IOException("untrusted redirect host: $host")
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            val total = body.contentLength()
            val out = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    if (!currentCoroutineContext().isActive) throw CancellationException()
                    done += read
                    if (done > MAX_DOWNLOAD_BYTES) throw IOException("下载体积超出上限")
                    out.write(buf, 0, read)
                    onBytes?.invoke(done, total)
                }
            }
            return out.toByteArray()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { b -> "%02x".format(Locale.ROOT, b.toInt() and 0xFF) }
}
