package com.termux.app.utils

import android.os.Build
import android.os.Process
import android.util.Log
import kotlin.jvm.Throws
import kotlin.jvm.JvmStatic
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * bootstrap 运行环境的首启动在线下载器。
 *
 * APK 不再烘焙 libtermux-bootstrap.so，改为首启动按当前 ABI 从远端拉取对应架构的
 * bootstrap zip，校验（SHA-256 + zip 魔数 PK\x03\x04）后交给 TermuxInstaller 解压。
 *
 * 镜像源排序原则：Release 资产（GitHub CDN）优先，其次「服务端代抓 GitHub」的 relay，
 * GitHub 直连垫底 —— 这样没有 GitHub 直连能力的用户会先命中 relay，而不依赖直连。
 */
object BootstrapDownloader {
    private const val TAG = "BootstrapDownloader"
    private const val REPO = "TiG-Kira/Termux-Ultra"

    // 同一 tag 对应一份 Release 资产与一份源码树 zip；bootstrap 内容变更时升版本（bootstrap-v2 …）。
    private const val REF = "bootstrap-v1"

    // zip 在源码树里的目录；Release 资产是平铺的，没有这一层，故按源分别拼进目录 URL。
    private const val REMOTE_DIR = "app/bootstrap/"

    // arch -> 期望 SHA-256（与 app/bootstrap/*.zip 一致；改 zip 必须同步此处）。
    private val EXPECTED_SHA256 = mapOf(
        "aarch64" to "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3",
        "arm"     to "a38f4d3b2f735f83be2bf54eff463e86dc32a3e2f9f861c1557c4378d249c018",
        "i686"    to "f5bc0b025b9f3b420b5fcaeefc064f888f5f22a0d6fd7090f4aac0c33eb3555b",
        "x86_64"  to "b7fd0f2e3a4de534be3144f9f91acc768630fc463eaf134ab2e64c545e834f7a"
    )

    // 每项必须是「以 / 结尾的完整目录 URL」，取用时只拼文件名 —— 目录段写在这里就不会漏。
    // 已实测剔除：jsDelivr 等公共 CDN 对约 30MB 单文件返回 403；
    // mirror.ghproxy.com、gh.api.99988866.xyz 连接直接失败。
    private val MIRROR_DIRS = listOf(
        "https://github.com/$REPO/releases/download/$REF/",
        "https://ghproxy.net/https://github.com/$REPO/releases/download/$REF/",
        "https://ghfast.top/https://github.com/$REPO/releases/download/$REF/",
        "https://ghproxy.net/https://raw.githubusercontent.com/$REPO/$REF/$REMOTE_DIR",
        "https://ghfast.top/https://raw.githubusercontent.com/$REPO/$REF/$REMOTE_DIR",
        "https://raw.githubusercontent.com/$REPO/$REF/$REMOTE_DIR"
    )

    // 连接超时收紧，让不可达的镜像源快速跳过、尽快尝试下一个；读超时放宽以容纳 ~30MB 下载。
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 当前进程应使用的 bootstrap arch 名（arm64-v8a -> aarch64 …）。
     * 按进程实际位数过滤：单架构包装到位数不匹配的设备时（如 32 位包跑在 arm64 设备上），
     * 只有与进程同位数架构的二进制可执行，取错会让 bootstrap 整体跑不起来。
     */
    @JvmStatic
    fun getArchForAbi(): String {
        val is64Bit = Process.is64Bit()
        for (abi in Build.SUPPORTED_ABIS) {
            if ((abi == "arm64-v8a" || abi == "x86_64") != is64Bit) continue
            when (abi) {
                "arm64-v8a" -> return "aarch64"
                "armeabi-v7a" -> return "arm"
                "x86" -> return "i686"
                "x86_64" -> return "x86_64"
            }
        }
        Log.w(TAG, "No matching ABI (64bit=$is64Bit, supported=${Build.SUPPORTED_ABIS.contentToString()}), using fallback")
        return if (is64Bit) "aarch64" else "arm"
    }

    /**
     * 阻塞式下载并校验 bootstrap zip，返回字节数组。
     * 必须在后台线程调用（TermuxInstaller 的 bootstrap 线程里）。
     * 全部镜像失败时抛出 IllegalStateException。
     */
    @JvmStatic
    @Throws(Exception::class)
    fun getBootstrapZip(arch: String): ByteArray {
        val expected = EXPECTED_SHA256[arch]
            ?: throw IllegalStateException("No expected SHA-256 for arch: $arch")
        var lastError: Exception? = null
        for (dir in MIRROR_DIRS) {
            val url = dir + "bootstrap-$arch.zip"
            try {
                val bytes = fetchBytes(url)
                if (isValidBootstrap(bytes, expected)) {
                    Log.i(TAG, "bootstrap ($arch) downloaded and verified from $url")
                    return bytes
                }
                val mismatch = "checksum/magic mismatch for $arch from $url"
                lastError = Exception(mismatch)
                Log.w(TAG, mismatch)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "mirror failed: $url (${e.message})")
            }
        }
        throw IllegalStateException("All bootstrap mirrors failed for $arch: ${lastError?.message}")
    }

    private fun fetchBytes(url: String): ByteArray {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code} for $url")
            return response.body?.bytes()
                ?: throw Exception("empty body for $url")
        }
    }

    private fun isValidBootstrap(bytes: ByteArray, expectedSha256: String): Boolean {
        if (bytes.size < 4) return false
        // zip 魔数：50 4B 03 04 (PK\x03\x04)。
        if (bytes[0] != 0x50.toByte() || bytes[1] != 0x4B.toByte()
            || bytes[2] != 0x03.toByte() || bytes[3] != 0x04.toByte()) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val actual = digest.joinToString("") { b -> "%02x".format(Locale.ROOT, b.toInt() and 0xFF) }
        return actual.equals(expectedSha256, ignoreCase = true)
    }
}
