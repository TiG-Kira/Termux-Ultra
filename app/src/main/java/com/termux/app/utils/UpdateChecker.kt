package com.termux.app.utils

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UpdateChecker {
    private const val GITHUB_RELEASE_URL = "https://api.github.com/repos/TiG-Kira/Termux-Ultra/releases/latest"
    private const val GITHUB_RELEASES_LIST_URL = "https://api.github.com/repos/TiG-Kira/Termux-Ultra/releases?per_page=50"
    private const val GITHUB_RELEASE_PAGE_URL = "https://github.com/TiG-Kira/Termux-Ultra/releases"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    data class UpdateResult(
        val hasUpdate: Boolean,
        val latestVersion: String,
        val currentVersion: String,
        val releaseUrl: String,
        val apkDownloadUrl: String,
        val releaseNotes: String,
        val isPreRelease: Boolean = false
    )

    enum class ReleaseStatus {
        NORMAL,
        PRERELEASE,
        NOT_FOUND
    }

    suspend fun checkForUpdates(currentVersion: String, includePreRelease: Boolean = false): UpdateResult {
        return if (includePreRelease) {
            checkForUpdatesWithPreRelease(currentVersion)
        } else {
            checkForUpdatesStable(currentVersion)
        }
    }

    private suspend fun checkForUpdatesStable(currentVersion: String): UpdateResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(GITHUB_RELEASE_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext buildNoUpdateResult(currentVersion)
                    }

                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val latestVersion = json.optString("tag_name", currentVersion)
                        .removePrefix("v")
                        .removePrefix("V")
                    val releaseNotes = json.optString("body", "")

                    val assets = json.optJSONArray("assets")
                    var apkUrl = ""
                    if (assets != null && assets.length() > 0) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", "")
                                break
                            }
                        }
                    }

                    val hasUpdate = compareVersions(latestVersion, currentVersion) > 0

                    UpdateResult(
                        hasUpdate = hasUpdate,
                        latestVersion = latestVersion,
                        currentVersion = currentVersion,
                        releaseUrl = GITHUB_RELEASE_PAGE_URL,
                        apkDownloadUrl = apkUrl,
                        releaseNotes = releaseNotes
                    )
                }
            } catch (e: Exception) {
                buildNoUpdateResult(currentVersion)
            }
        }
    }

    private suspend fun checkForUpdatesWithPreRelease(currentVersion: String): UpdateResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(GITHUB_RELEASES_LIST_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext buildNoUpdateResult(currentVersion)
                    }

                    val body = response.body?.string() ?: ""
                    val releases = JSONArray(body)

                    var bestVersion = currentVersion
                    var bestReleaseNotes = ""
                    var bestIsPreRelease = false
                    var bestReleaseUrl = GITHUB_RELEASE_PAGE_URL
                    var bestApkUrl = ""

                    for (i in 0 until releases.length()) {
                        val release = releases.getJSONObject(i)
                        val isDraft = release.optBoolean("draft", false)
                        if (isDraft) continue

                        val tagName = release.optString("tag_name", "")
                            .removePrefix("v")
                            .removePrefix("V")

                        val isPreRelease = release.optBoolean("prerelease", false)

                        if (compareVersions(tagName, bestVersion) > 0 ||
                            (compareVersions(tagName, bestVersion) == 0 && isPreRelease && !bestIsPreRelease)) {

                            val releaseUrl = release.optString("html_url", "")
                                .ifEmpty { GITHUB_RELEASE_PAGE_URL }

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

                            bestVersion = tagName
                            bestReleaseNotes = release.optString("body", "")
                            bestIsPreRelease = isPreRelease
                            bestReleaseUrl = releaseUrl
                            bestApkUrl = apkUrl
                        }
                    }

                    val hasUpdate = compareVersions(bestVersion, currentVersion) > 0

                    UpdateResult(
                        hasUpdate = hasUpdate,
                        latestVersion = bestVersion,
                        currentVersion = currentVersion,
                        releaseUrl = bestReleaseUrl,
                        apkDownloadUrl = bestApkUrl,
                        releaseNotes = bestReleaseNotes,
                        isPreRelease = bestIsPreRelease
                    )
                }
            } catch (e: Exception) {
                buildNoUpdateResult(currentVersion)
            }
        }
    }

    private fun buildNoUpdateResult(currentVersion: String): UpdateResult {
        return UpdateResult(
            hasUpdate = false,
            latestVersion = currentVersion,
            currentVersion = currentVersion,
            releaseUrl = GITHUB_RELEASE_PAGE_URL,
            apkDownloadUrl = "",
            releaseNotes = ""
        )
    }

    suspend fun getReleaseStatus(currentVersion: String): ReleaseStatus? {
        return withContext(Dispatchers.IO) {
            try {
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
                            .removePrefix("v")
                            .removePrefix("V")
                        if (tagName == currentVersion) {
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
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLength = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLength) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 > p2) return 1
            if (p1 < p2) return -1
        }
        return 0
    }
}