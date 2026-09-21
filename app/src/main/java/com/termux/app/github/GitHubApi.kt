package com.termux.app.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * GitHub REST v3 的最小封装：只覆盖本功能需要的「读用户 / 读 Issue / 读回复 / 建 Issue」。
 */
class GitHubApi(private val token: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun fetchUser(): GitHubUser = withContext(Dispatchers.IO) {
        val json = getJson(GitHubConfig.API_BASE + "/user")
        GitHubUser(
            login = json.optString("login"),
            name = json.optString("name", "").ifEmpty { null },
            avatarUrl = json.optString("avatar_url", "").ifEmpty { null },
            id = json.optLong("id", 0L)
        )
    }

    /**
     * 拉取仓库 Issue 列表。
     * @param creator 非空时只取该用户创建的 Issue
     */
    suspend fun fetchIssues(creator: String? = null): List<GitHubIssue> = withContext(Dispatchers.IO) {
        var url = GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/issues" +
            "?state=all&sort=created&direction=desc&per_page=100"
        if (!creator.isNullOrEmpty()) url += "&creator=$creator"
        val array = JSONArray(executeForString(authorizedGet(url)))
        val result = ArrayList<GitHubIssue>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            // /issues 会把 Pull Request 一并返回，这里过滤掉
            if (!obj.isNull("pull_request")) continue
            result.add(
                GitHubIssue(
                    number = obj.optInt("number", 0),
                    title = obj.optString("title", ""),
                    body = obj.optString("body", "").ifEmpty { null },
                    authorLogin = obj.optJSONObject("user")?.optString("login").orEmpty(),
                    kind = kindOf(obj.optString("state", "open"), obj.optString("state_reason", "").ifEmpty { null }),
                    commentCount = obj.optInt("comments", 0),
                    createdAt = obj.optString("created_at", ""),
                    htmlUrl = obj.optString("html_url", "")
                )
            )
        }
        result
    }

    /** 拉取单个 Issue（含正文与状态） */
    suspend fun fetchIssue(number: Int): GitHubIssue = withContext(Dispatchers.IO) {
        val obj = JSONObject(
            executeForString(
                authorizedGet(GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/issues/$number")
            )
        )
        GitHubIssue(
            number = obj.optInt("number", number),
            title = obj.optString("title", ""),
            body = obj.optString("body", "").ifEmpty { null },
            authorLogin = obj.optJSONObject("user")?.optString("login").orEmpty(),
            kind = kindOf(obj.optString("state", "open"), obj.optString("state_reason", "").ifEmpty { null }),
            commentCount = obj.optInt("comments", 0),
            createdAt = obj.optString("created_at", ""),
            htmlUrl = obj.optString("html_url", "")
        )
    }

    /** 拉取 Issue 详情：主楼 + 回复列表 */
    suspend fun fetchIssueDetail(number: Int): GitHubIssueDetail = withContext(Dispatchers.IO) {
        GitHubIssueDetail(fetchIssue(number), fetchComments(number))
    }

    suspend fun fetchComments(issueNumber: Int): List<GitHubComment> = withContext(Dispatchers.IO) {
        val url = GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}" +
            "/issues/$issueNumber/comments?per_page=100"
        val array = JSONArray(executeForString(authorizedGet(url)))
        ArrayList<GitHubComment>(array.length()).apply {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                add(
                    GitHubComment(
                        authorLogin = obj.optJSONObject("user")?.optString("login").orEmpty(),
                        body = obj.optString("body", ""),
                        createdAt = obj.optString("created_at", "")
                    )
                )
            }
        }
    }

    /** 以当前登录身份创建 Issue，返回新 Issue 编号 */
    suspend fun createIssue(title: String, body: String): Int = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("title", title)
            put("body", body)
        }.toString()
        val request = Request.Builder()
            .url(GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/issues")
            .headers(baseHeaders())
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        JSONObject(executeForString(request)).optInt("number", 0)
    }

    private fun getJson(url: String): JSONObject = JSONObject(executeForString(authorizedGet(url)))

    private fun authorizedGet(url: String): Request = Request.Builder()
        .url(url)
        .headers(baseHeaders())
        .get()
        .build()

    private fun executeForString(request: Request): String {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "HTTP ${response.code}"
                error(msg)
            }
            return text
        }
    }

    private fun baseHeaders(): Headers = Headers.Builder()
        .add("Authorization", "Bearer $token")
        .add("Accept", "application/vnd.github+json")
        .add("X-GitHub-Api-Version", "2022-11-28")
        .add("User-Agent", USER_AGENT)
        .build()

    private fun kindOf(state: String, stateReason: String?): IssueStateKind = when {
        state.equals("open", true) -> IssueStateKind.OPEN
        stateReason.equals("completed", true) -> IssueStateKind.CLOSED_RESOLVED
        else -> IssueStateKind.CLOSED_UNRESOLVED
    }

    companion object {
        private const val USER_AGENT = "Termux-Ultra-Android"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
