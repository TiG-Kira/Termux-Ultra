package com.termux.app.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.termux.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

object ApkDownloader {
    private const val TAG = "ApkDownloader"
    private const val UPDATE_DIR = "updates"

    // 更新包只能来自 GitHub Release，避免被重定向到任意镜像或 CDN。
    // 注意：GitHub Release 资产会经 302 跳转到官方 CDN release-assets.githubusercontent.com，
    // OkHttp 跟随重定向后 response.request.url 为最终地址，该 host 必须纳入白名单，
    // 否则合法下载会被误判为不受信任来源而失败。
    private val ALLOWED_DOWNLOAD_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "raw.githubusercontent.com"
    )

    private val SHA256_PATTERN = Regex("([0-9a-fA-F]{64})")

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun hasInstallPermission(context: Context): Boolean {
        return context.packageManager.canRequestPackageInstalls()
    }

    fun requestInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.request_install_permission_failed), e)
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        url: String,
        versionName: String,
        onProgress: ((Int, Long, Long) -> Unit)? = null
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (!isAllowedDownloadUrl(url)) {
                    Log.w(TAG, "Rejected update download from untrusted source")
                    return@withContext Result.failure<Unit>(
                        Exception(context.getString(R.string.update_url_not_allowed))
                    )
                }

                val apkFile = File(getUpdateDir(context), getApkFileName(context, versionName))
                if (apkFile.exists()) {
                    apkFile.delete()
                }

                try {
                    downloadToFile(context, url, apkFile, onProgress)
                } catch (e: Exception) {
                    apkFile.delete()
                    throw e
                }

                verifyApkFile(context, apkFile, url).onFailure { e ->
                    apkFile.delete()
                    Log.w(TAG, "Discarding downloaded APK: ${e.message}")
                    return@withContext Result.failure<Unit>(e)
                }

                withContext(Dispatchers.Main) {
                    if (hasInstallPermission(context)) {
                        installApk(context, apkFile)
                    } else {
                        requestInstallPermission(context)
                    }
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, context.getString(R.string.download_failed), e)
                Result.failure(e)
            }
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, context.getString(R.string.install_failed), e)
            throw e
        }
    }

    /**
     * 更新包的完整性与来源校验，安装前必经此关；不通过的产物不得流向安装器。
     * @param checksumUrl 更新包地址本身，用于尝试拉取同名的 `.sha256`；Release 未附带时跳过摘要比对，由签名校验兜底
     */
    fun verifyApkFile(context: Context, apkFile: File, checksumUrl: String? = null): Result<Unit> {
        if (!apkFile.exists() || apkFile.length() == 0L) {
            return Result.failure(Exception(context.getString(R.string.apk_download_failed)))
        }
        if (!hasZipMagic(apkFile)) {
            return Result.failure(Exception(context.getString(R.string.apk_verify_failed_format)))
        }

        if (checksumUrl != null) {
            val expected = fetchPublishedSha256(checksumUrl)
            if (expected != null && !expected.equals(sha256Of(apkFile), ignoreCase = true)) {
                return Result.failure(Exception(context.getString(R.string.apk_verify_failed_checksum)))
            }
        }

        val apkInfo = getPackageArchiveInfo(context, apkFile)
            ?: return Result.failure(Exception(context.getString(R.string.apk_verify_failed_format)))

        if (apkInfo.packageName != context.packageName) {
            return Result.failure(Exception(context.getString(R.string.apk_verify_failed_package)))
        }

        val currentInfo = getPackageInfoCompat(
            context.packageManager,
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES
        )
        if (currentInfo != null && apkInfo.longVersionCode < currentInfo.longVersionCode) {
            return Result.failure(Exception(context.getString(R.string.apk_verify_failed_downgrade)))
        }

        // 调试包与 Release 资产的证书不同源，无法互为基准；正式包必须与自身同一签名。
        if (!isDebuggable(context)) {
            val installed = signerFingerprints(currentInfo)
            val downloaded = signerFingerprints(apkInfo)
            val trusted = !downloaded.isNullOrEmpty() && installed != null &&
                downloaded.all { it in installed }
            if (!trusted) {
                return Result.failure(Exception(context.getString(R.string.apk_verify_failed_signature)))
            }
        }

        return Result.success(Unit)
    }

    fun getDownloadedApkFile(context: Context, versionName: String): File {
        return File(getUpdateDir(context), getApkFileName(context, versionName))
    }

    fun constructDownloadUrl(version: String, context: Context): String {
        val abi = getDeviceAbi()
        val buildType = getBuildType(context)
        val apkFileName = if (buildType == "release") {
            "app-${abi}-release.apk"
        } else {
            "app-${abi}-debug.apk"
        }
        return "https://github.com/TiG-Kira/Termux-Ultra/releases/download/$version/$apkFileName"
    }

    // 内部私有目录：外部应用不可读写，且落在 FileProvider 的 files-path 覆盖范围内。
    private fun getUpdateDir(context: Context): File {
        val dir = File(context.filesDir, UPDATE_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun downloadToFile(
        context: Context,
        url: String,
        apkFile: File,
        onProgress: ((Int, Long, Long) -> Unit)?
    ) {
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception(context.getString(R.string.download_failed_with_code, response.code))
            }
            // 跟随重定向后可能已经离开受信域名，按最终地址再判一次
            if (!isAllowedDownloadUrl(response.request.url.toString())) {
                throw Exception(context.getString(R.string.update_url_not_allowed))
            }

            val body = response.body ?: throw Exception(context.getString(R.string.response_body_empty))
            val contentLength = body.contentLength()

            body.byteStream().use { input ->
                apkFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesDownloaded: Long = 0
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead
                        if (contentLength > 0 && onProgress != null) {
                            val progress = ((bytesDownloaded * 100) / contentLength).toInt()
                            onProgress(progress, bytesDownloaded, contentLength)
                        }
                    }
                    output.flush()
                }
            }
        }
    }

    private fun isAllowedDownloadUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" && uri.host in ALLOWED_DOWNLOAD_HOSTS
    }

    private fun fetchPublishedSha256(checksumUrl: String): String? {
        return try {
            Request.Builder().url("$checksumUrl.sha256").build().let { request ->
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return null
                    val text = response.body?.string().orEmpty()
                    SHA256_PATTERN.find(text)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "No usable checksum published alongside the update asset")
            null
        }
    }

    private fun hasZipMagic(file: File): Boolean {
        return try {
            FileInputStream(file).use { input ->
                val header = ByteArray(4)
                input.read(header) == 4 &&
                    header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().toHex()
    }

    private fun getPackageArchiveInfo(context: Context, apkFile: File): PackageInfo? {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apkFile.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        }
    }

    private fun getPackageInfoCompat(pm: PackageManager, packageName: String, flags: Int): PackageInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, flags)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun signerFingerprints(info: PackageInfo?): List<String>? {
        if (info == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val certs = info.signingInfo?.signingCertificateHistory ?: return null
        if (certs.isEmpty()) return null
        return certs.map { cert -> MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).toHex() }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { b -> "%02x".format(Locale.ROOT, b.toInt() and 0xFF) }

    private fun getApkFileName(context: Context, versionName: String): String {
        val suffix = if (getBuildType(context) == "release") "release" else "debug"
        return "Termux-Ultra_${getDeviceAbi()}-${suffix}_$versionName.apk"
    }

    private fun getBuildType(context: Context): String {
        return if (isDebuggable(context)) "debug" else "release"
    }

    private fun isDebuggable(context: Context): Boolean {
        return (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private fun getDeviceAbi(): String {
        return try {
            val abis = android.os.Build.SUPPORTED_ABIS
            if (abis.isNotEmpty()) {
                when (abis[0]) {
                    "arm64-v8a" -> "arm64-v8a"
                    "armeabi-v7a" -> "armeabi-v7a"
                    "x86_64" -> "x86_64"
                    "x86" -> "x86"
                    else -> "universal"
                }
            } else {
                "universal"
            }
        } catch (e: Exception) {
            "universal"
        }
    }
}
