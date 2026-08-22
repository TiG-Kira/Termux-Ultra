package com.termux.app.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 版本号数据类，支持解析 `R?x.y.z.RB` 格式（如 `0.9.0.RB` 或 `R0.9.0.RB`）。
 * RB = ReBuild 分支缩写。
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<AppVersion> {

    companion object {
        private val VERSION_REGEX = Regex("""^(R|r)?(\d+)\.(\d+)\.(\d+)\.RB$""")
        private val PLAIN_REGEX = Regex("""^v?(R|r)?(\d+)\.(\d+)\.(\d+)$""")

        /**
         * 解析版本号字符串，支持 `0.9.0.RB`、`R0.9.0.RB` 和纯数字格式 `0.9.0`。
         * @return AppVersion 或 null（格式不匹配）
         */
        fun parse(versionName: String): AppVersion? {
            val versionMatch = VERSION_REGEX.find(versionName)
            if (versionMatch != null) {
                return AppVersion(
                    major = versionMatch.groupValues[2].toInt(),
                    minor = versionMatch.groupValues[3].toInt(),
                    patch = versionMatch.groupValues[4].toInt()
                )
            }
            val plainMatch = PLAIN_REGEX.find(versionName)
            if (plainMatch != null) {
                return AppVersion(
                    major = plainMatch.groupValues[2].toInt(),
                    minor = plainMatch.groupValues[3].toInt(),
                    patch = plainMatch.groupValues[4].toInt()
                )
            }
            return null
        }
    }

    override fun compareTo(other: AppVersion): Int {
        return compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    }

    fun toVersionName(): String = "$major.$minor.$patch.RB"

    fun toDisplayString(): String = "$major.$minor.$patch.RB"
}

/**
 * 更新检查结果。
 */
sealed class UpdateResult {
    data class UpdateAvailable(
        val latestVersion: AppVersion,
        val latestVersionName: String,
        val currentVersion: AppVersion,
        val currentVersionName: String,
        val releaseUrl: String,
        val apkDownloadUrl: String,
        val releaseNotes: String,
        val isBeta: Boolean
    ) : UpdateResult()

    data class UpToDate(
        val currentVersion: AppVersion,
        val currentVersionName: String
    ) : UpdateResult()

    data object CheckFailed : UpdateResult()
}

object UpdateChecker {
    private const val GITHUB_REPO = "tig-kira/termux-ultra"
    private const val GITHUB_RELEASES_LIST_URL = "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=50"
    private const val GITHUB_RELEASE_PAGE_URL = "https://github.com/$GITHUB_REPO/releases"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    enum class ReleaseStatus {
        NORMAL,
        PRERELEASE,
        NOT_FOUND
    }

    /**
     * 检查更新。
     * @param currentVersionName 当前版本号字符串（如 "1.0.0.RB" 或 "R1.0.0.RB"）
     * @param betaEnabled 是否包含 Beta/Pre-release 版本
     * @return UpdateResult
     */
    suspend fun checkForUpdates(
        currentVersionName: String,
        betaEnabled: Boolean = false
    ): UpdateResult {
        val currentVersion = AppVersion.parse(currentVersionName)
            ?: return UpdateResult.CheckFailed

        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(GITHUB_RELEASES_LIST_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext UpdateResult.CheckFailed
                    }

                    val body = response.body?.string() ?: ""
                    val releases = JSONArray(body)

                    var currentTagMatched = false
                    var bestRelease: JSONObject? = null
                    var bestVersion: AppVersion? = null
                    var bestVersionName = ""
                    var bestIsPreRelease = false

                    for (i in 0 until releases.length()) {
                        val release = releases.getJSONObject(i)
                        val isDraft = release.optBoolean("draft", false)
                        if (isDraft) continue

                        val tagName = release.optString("tag_name", "")
                        val tagVersion = AppVersion.parse(tagName)

                        if (tagVersion == null) continue

                        if (tagVersion == currentVersion) {
                            currentTagMatched = true
                        }

                        val isPreRelease = release.optBoolean("prerelease", false)
                        if (!betaEnabled && isPreRelease) continue

                        if (bestVersion == null || tagVersion > bestVersion) {
                            bestVersion = tagVersion
                            bestVersionName = tagName
                            bestRelease = release
                            bestIsPreRelease = isPreRelease
                        }
                    }

                    if (!currentTagMatched) {
                        return@withContext UpdateResult.UpToDate(
                            currentVersion = currentVersion,
                            currentVersionName = currentVersionName
                        )
                    }

                    if (bestVersion == null || bestVersion <= currentVersion) {
                        return@withContext UpdateResult.UpToDate(
                            currentVersion = currentVersion,
                            currentVersionName = currentVersionName
                        )
                    }

                    val release = bestRelease!!
                    val releaseUrl = release.optString("html_url", "")
                        .ifEmpty { GITHUB_RELEASE_PAGE_URL }
                    val releaseNotes = release.optString("body", "")

                    val assets = release.optJSONArray("assets")
                    var apkUrl = ""
                    if (assets != null && assets.length() > 0) {
                        for (j in 0 until assets.length()) {
                            val asset = assets.getJSONObject(j)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    UpdateResult.UpdateAvailable(
                        latestVersion = bestVersion,
                        latestVersionName = bestVersionName,
                        currentVersion = currentVersion,
                        currentVersionName = currentVersionName,
                        releaseUrl = releaseUrl,
                        apkDownloadUrl = apkUrl,
                        releaseNotes = releaseNotes,
                        isBeta = bestIsPreRelease
                    )
                }
            }
        } catch (e: Exception) {
            UpdateResult.CheckFailed
        }
    }

    /**
     * 获取当前版本的 Release 状态（NORMAL / PRERELEASE / NOT_FOUND）。
     */
    suspend fun getReleaseStatus(currentVersionName: String): ReleaseStatus? {
        val currentVersion = AppVersion.parse(currentVersionName)
            ?: return null

        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(GITHUB_RELEASES_LIST_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext null
                    }

                    val body = response.body?.string() ?: ""
                    val releases = JSONArray(body)

                    for (i in 0 until releases.length()) {
                        val release = releases.getJSONObject(i)
                        val tagName = release.optString("tag_name", "")
                        val tagVersion = AppVersion.parse(tagName)

                        if (tagVersion != null && tagVersion == currentVersion) {
                            val isPrerelease = release.optBoolean("prerelease", false)
                            val isDraft = release.optBoolean("draft", false)
                            if (isDraft) {
                                return@withContext ReleaseStatus.NOT_FOUND
                            }
                            return@withContext if (isPrerelease) {
                                ReleaseStatus.PRERELEASE
                            } else {
                                ReleaseStatus.NORMAL
                            }
                        }
                    }

                    ReleaseStatus.NOT_FOUND
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 构建 APK 下载 URL（根据版本名）。
     * 格式: https://github.com/tig-kira/termux-ultra/releases/download/{versionName}/termux-ultra_{versionName}_universal.apk
     */
    fun constructDownloadUrl(versionName: String): String {
        return "$GITHUB_RELEASE_PAGE_URL/download/$versionName/termux-ultra_${versionName}_universal.apk"
    }
}