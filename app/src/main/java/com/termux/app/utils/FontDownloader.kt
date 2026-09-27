package com.termux.app.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 终端可选字体的按需下载器。
 *
 * 大部分字体不内置于 APK（只为省体积），用户选中时才从远端取回并存到
 * [Context.getFilesDir]/fonts/，后续与内置字体的读取路径一致。
 */
object FontDownloader {
    private const val TAG = "FontDownloader"
    private const val REMOTE_DIR = "app/src/main/assets/fonts/"

    // 多源回退：GitHub 直连在部分地区长期不可达，jsDelivr 的 fastly 节点与镜像站
    // 能覆盖国内网络环境。逐个尝试，任一源拿到合法字体文件即停止。
    private val MIRROR_BASES = listOf(
        "https://raw.githubusercontent.com/termux/termux-styling/master/",
        "https://fastly.jsdelivr.net/gh/termux/termux-styling@master/",
        "https://ghproxy.net/https://raw.githubusercontent.com/termux/termux-styling/master/",
        "https://ghfast.top/https://raw.githubusercontent.com/termux/termux-styling/master/"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun fontDir(context: Context): File =
        File(context.filesDir, "fonts").apply { mkdirs() }

    fun downloadedFile(context: Context, fileName: String): File =
        File(fontDir(context), fileName)

    suspend fun download(context: Context, fileName: String): Result<File> =
        withContext(Dispatchers.IO) {
            val target = downloadedFile(context, fileName)
            // 同一字体被并发下载时各写独立临时文件，避免交叉写入把内容写坏。
            val tmp = File(target.parentFile, "$fileName.${System.nanoTime()}.part")
            var lastError: Exception? = null
            try {
                for (base in MIRROR_BASES) {
                    val result = runCatching { fetchToFile(base + REMOTE_DIR + fileName, tmp) }
                    if (result.isSuccess && isFontFile(tmp)) {
                        if (tmp.renameTo(target)) {
                            return@withContext Result.success(target)
                        }
                        lastError = Exception("rename failed: $fileName")
                    } else {
                        lastError = result.exceptionOrNull() as? Exception
                            ?: Exception("invalid font content: $fileName")
                    }
                    tmp.delete()
                }
                Result.failure(lastError ?: Exception("all font mirrors failed: $fileName"))
            } finally {
                tmp.delete()
            }
        }

    private fun fetchToFile(url: String, out: File) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            val body = response.body ?: throw Exception("empty body for $url")
            body.byteStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    // 镜像不可用时常返回 HTML 错误页且带 200 状态码，靠魔数区分，避免把错误页当字体落盘。
    private fun isFontFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        val header = ByteArray(4)
        file.inputStream().use { input ->
            if (input.read(header) != 4) return false
        }
        return header.contentEquals(byteArrayOf(0x00, 0x01, 0x00, 0x00)) ||
            header.contentEquals(byteArrayOf(0x74, 0x72, 0x75, 0x65)) ||
            header.contentEquals(byteArrayOf(0x74, 0x74, 0x63, 0x66)) ||
            header.contentEquals(byteArrayOf(0x4F, 0x54, 0x54, 0x4F))
    }
}
