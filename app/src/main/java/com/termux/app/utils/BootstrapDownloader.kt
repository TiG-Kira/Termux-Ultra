package com.termux.app.utils

import android.os.Build
import android.util.Log
import kotlin.jvm.Throws
import kotlin.jvm.JvmStatic
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * bootstrap 运行环境的首启动在线下载器。
 *
 * APK 不再烘焙 libtermux-bootstrap.so，改为首启动按当前 ABI 从远端拉取对应架构的
 * bootstrap zip，校验（SHA-256 + zip 魔数 PK\x03\x04）后交给 TermuxInstaller 解压。
 *
 * 镜像源排序原则：用「服务端代抓 GitHub」的 relay 代理放前面，GitHub 直连
 * （raw.githubusercontent.com）垫底 —— 这样没有 GitHub 直连能力的用户会先命中 relay，
 * 而不依赖直连。注意 jsDelivr 等公共 CDN 对单文件有体积上限（约 20–50MB），
 * 我们的 bootstrap zip 约 28MB 会被拒绝（HTTP 403），故不纳入 CDN，只用 relay 代理。
 * relay 在各自服务端抓取 GitHub 内容再回传，用户侧无需能直连 GitHub 即可下载。
 */
object BootstrapDownloader {
    private const val TAG = "BootstrapDownloader"

    // 指向含有 app/bootstrap/*.zip 的固定 tag；bootstrap 内容变更时升版本（bootstrap-v2 …）。
    private const val REF = "bootstrap-v1"

    // arch -> 期望 SHA-256（与 app/bootstrap/*.zip 一致；改 zip 必须同步此处）。
    private val EXPECTED_SHA256 = mapOf(
        "aarch64" to "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3",
        "arm"     to "a38f4d3b2f735f83be2bf54eff463e86dc32a3e2f9f861c1557c4378d249c018",
        "i686"    to "f5bc0b025b9f3b420b5fcaeefc064f888f5f22a0d6fd7090f4aac0c33eb3555b",
        "x86_64"  to "b7fd0f2e3a4de534be3144f9f91acc768630fc463eaf134ab2e64c545e834f7a"
    )

    // 顺序：GitHub 代抓 relay 在前，GitHub 直连垫底。relay 均服务端代抓 GitHub，
    // 故无 GitHub 直连的用户也能下载；jsDelivr 因单文件体积上限已排除。
    private val MIRROR_BASES = listOf(
        "https://ghproxy.net/https://raw.githubusercontent.com/TiG-Kira/Termux-Ultra/$REF/",
        "https://ghfast.top/https://raw.githubusercontent.com/TiG-Kira/Termux-Ultra/$REF/",
        "https://mirror.ghproxy.com/https://raw.githubusercontent.com/TiG-Kira/Termux-Ultra/$REF/",
        "https://gh.api.99988866.xyz/https://raw.githubusercontent.com/TiG-Kira/Termux-Ultra/$REF/",
        "https://raw.githubusercontent.com/TiG-Kira/Termux-Ultra/$REF/"
    )

    // 连接超时收紧，让不可达的镜像源快速跳过、尽快尝试下一个；读超时放宽以容纳 ~30MB 下载。
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 当前设备 ABI 映射到的 bootstrap arch 名（arm64-v8a -> aarch64 …）。 */
    @JvmStatic
    fun getArchForAbi(): String {
        for (abi in Build.SUPPORTED_ABIS) {
            when (abi) {
                "arm64-v8a" -> return "aarch64"
                "armeabi-v7a" -> return "arm"
                "x86" -> return "i686"
                "x86_64" -> return "x86_64"
            }
        }
        // 兜底：主流设备都是 64 位 arm。
        Log.w(TAG, "Unknown ABI list: ${Build.SUPPORTED_ABIS.contentToString()}, fallback to aarch64")
        return "aarch64"
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
        for (base in MIRROR_BASES) {
            try {
                val bytes = fetchBytes(base + "bootstrap-$arch.zip")
                if (isValidBootstrap(bytes, expected)) {
                    Log.i(TAG, "bootstrap ($arch) downloaded and verified from $base")
                    return bytes
                }
                lastError = Exception("checksum/magic mismatch for $arch from $base")
                Log.w(TAG, lastError.message)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "mirror failed: $base (${e.message})")
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
        val actual = digest.joinToString("") { b -> "%02x".format(b.toInt() and 0xFF) }
        return actual.equals(expectedSha256, ignoreCase = true)
    }
}
