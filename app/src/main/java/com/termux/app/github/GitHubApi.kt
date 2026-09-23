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
 * GitHub REST v3 封装：Issue 读写、PR 读写/合并、评论，以及仓库权限检测。
 *
 * 所有 suspend 函数都在 Dispatchers.IO 执行 HTTP 调用，调用方再切回 Main。
 */
class GitHubApi(private val token: String) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    // =========================================================================
    // 用户
    // =========================================================================

    suspend fun fetchUser(): GitHubUser = withContext(Dispatchers.IO) {
        val json = getJson(GitHubConfig.API_BASE + "/user")
        GitHubUser(
            login = json.optString("login"),
            name = json.optString("name", "").ifEmpty { null },
            avatarUrl = json.optString("avatar_url", "").ifEmpty { null },
            id = json.optLong("id", 0L)
        )
    }

    // =========================================================================
    // 仓库权限
    // =========================================================================

    /**
     * 查询当前登录用户在目标仓库的角色。
     * - 先通过 `/repos/{owner}/{repo}` 读取 owner login，判断是否 owner
     * - 再通过 `/repos/{owner}/{repo}/collaborators/{username}/permission` 读取 role
     * - 若未成为 collaborator（403/404），回退到 owner 判定，否则返回 UNKNOWN
     */
    suspend fun fetchRepoPermission(username: String): RepoPermission = withContext(Dispatchers.IO) {
        val repoUrl = GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}"
        val repoJson = getJson(repoUrl)
        val repoOwner = repoJson.optJSONObject("owner")?.optString("login").orEmpty()
        val isOwner = repoOwner.equals(username, true)

        val role: RepoRole = runCatching {
            val permUrl = GitHubConfig.API_BASE +
                "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/collaborators/${username}/permission"
            val permJson = getJson(permUrl)
            val raw = permJson.optString("permission", "").lowercase()
            roleFromApi(raw)
        }.getOrElse {
            // 403/404 → 没有协作角色。但 owner 依然被视为 ADMIN
            if (isOwner) RepoRole.ADMIN else RepoRole.UNKNOWN
        }

        RepoPermission(
            repoOwner = repoOwner,
            myRole = if (isOwner) RepoRole.ADMIN else role,
            isOwner = isOwner
        )
    }

    private fun roleFromApi(raw: String): RepoRole = when (raw) {
        "admin" -> RepoRole.ADMIN
        "maintain" -> RepoRole.MAINTAIN
        "write" -> RepoRole.WRITE
        "triage" -> RepoRole.TRIAGE
        "read" -> RepoRole.READ
        else -> RepoRole.UNKNOWN
    }

    // =========================================================================
    // Issue（已有接口 + 扩展）
    // =========================================================================

    suspend fun fetchIssues(creator: String? = null): List<GitHubIssue> = withContext(Dispatchers.IO) {
        var url = GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/issues" +
            "?state=all&sort=created&direction=desc&per_page=100"
        if (!creator.isNullOrEmpty()) url += "&creator=$creator"
        val array = JSONArray(executeForString(authorizedGet(url)))
        val result = ArrayList<GitHubIssue>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            if (!obj.isNull("pull_request")) continue
            result.add(parseIssue(obj))
        }
        result
    }

    suspend fun fetchIssue(number: Int): GitHubIssue = withContext(Dispatchers.IO) {
        val obj = JSONObject(
            executeForString(
                authorizedGet(GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/issues/$number")
            )
        )
        parseIssue(obj)
    }

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
                add(parseComment(obj))
            }
        }
    }

    /** 发送一条评论到 Issue/PR（GitHub 共用 issues/:number/comments） */
    suspend fun createIssueComment(number: Int, body: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("body", body).toString()
        val request = Request.Builder()
            .url(GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/issues/$number/comments")
            .headers(baseHeaders())
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        executeForString(request)
    }

    /** 关闭 Issue，可选 state_reason（completed | not_planned | reopened） */
    suspend fun closeIssue(number: Int, reason: String? = null) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("state", "closed").apply {
            if (!reason.isNullOrBlank()) put("state_reason", reason)
        }.toString()
        patch(GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/issues/$number", payload)
    }

    /** 重新打开 Issue */
    suspend fun reopenIssue(number: Int) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("state", "open").toString()
        patch(GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/issues/$number", payload)
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

    // =========================================================================
    // Pull Request
    // =========================================================================

    /**
     * 列出仓库的 PR（state=all，合并的 + 关闭的 + 打开的）。
     * per_page=100 足够当前仓库规模。
     */
    suspend fun fetchPullRequests(state: String = "all"): List<GitHubPullRequest> = withContext(Dispatchers.IO) {
        val url = GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}" +
            "/pulls?state=$state&sort=created&direction=desc&per_page=100"
        val array = JSONArray(executeForString(authorizedGet(url)))
        ArrayList<GitHubPullRequest>(array.length()).apply {
            for (i in 0 until array.length()) {
                add(parsePullRequest(array.optJSONObject(i) ?: continue))
            }
        }
    }

    suspend fun fetchPullRequest(number: Int): GitHubPullRequest = withContext(Dispatchers.IO) {
        val url = GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/pulls/$number"
        parsePullRequest(getJson(url))
    }

    suspend fun fetchPullRequestDetail(number: Int): GitHubPullRequestDetail = withContext(Dispatchers.IO) {
        GitHubPullRequestDetail(fetchPullRequest(number), fetchComments(number))
    }

    /**
     * 合并 PR。GitHub 默认 commit_title / commit_message 自动生成，此处留空让服务端处理。
     */
    suspend fun mergePullRequest(number: Int, method: MergeMethod): JSONObject = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("merge_method", method.apiValue)
            // 默认让 GitHub 自动生成 commit 标题
        }.toString()
        val url = GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/pulls/$number/merge"
        put(url, payload)
    }

    /** 关闭 PR（非合并） */
    suspend fun closePullRequest(number: Int) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("state", "closed").toString()
        patch(GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/pulls/$number", payload)
    }

    /** 重新打开 PR */
    suspend fun reopenPullRequest(number: Int) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("state", "open").toString()
        patch(GitHubConfig.API_BASE + "/repos/${GitHubConfig.REPO_OWNER}/${GitHubConfig.REPO_NAME}/pulls/$number", payload)
    }

    // =========================================================================
    // 解析辅助
    // =========================================================================

    private fun parseIssue(obj: JSONObject): GitHubIssue = GitHubIssue(
        number = obj.optInt("number", 0),
        title = obj.optString("title", ""),
        body = obj.optString("body", "").ifEmpty { null },
        authorLogin = obj.optJSONObject("user")?.optString("login").orEmpty(),
        kind = kindOf(obj.optString("state", "open"), obj.optString("state_reason", "").ifEmpty { null }),
        commentCount = obj.optInt("comments", 0),
        createdAt = obj.optString("created_at", ""),
        htmlUrl = obj.optString("html_url", "")
    )

    private fun parseComment(obj: JSONObject): GitHubComment = GitHubComment(
        authorLogin = obj.optJSONObject("user")?.optString("login").orEmpty(),
        body = obj.optString("body", ""),
        createdAt = obj.optString("created_at", "")
    )

    private fun parsePullRequest(obj: JSONObject): GitHubPullRequest = GitHubPullRequest(
        number = obj.optInt("number", 0),
        title = obj.optString("title", ""),
        body = obj.optString("body", "").ifEmpty { null },
        authorLogin = obj.optJSONObject("user")?.optString("login").orEmpty(),
        state = obj.optString("state", ""),
        merged = obj.optBoolean("merged", false),
        commentsCount = obj.optInt("comments", 0),
        createdAt = obj.optString("created_at", ""),
        updatedAt = obj.optString("updated_at", ""),
        htmlUrl = obj.optString("html_url", ""),
        mergeable = MergeableState.fromApi(obj.optString("mergeable").ifEmpty { null })
    )

    private fun kindOf(state: String, stateReason: String?): IssueStateKind = when {
        state.equals("open", true) -> IssueStateKind.OPEN
        stateReason.equals("completed", true) -> IssueStateKind.CLOSED_RESOLVED
        else -> IssueStateKind.CLOSED_UNRESOLVED
    }

    // =========================================================================
    // HTTP 基础
    // =========================================================================

    private fun getJson(url: String): JSONObject = JSONObject(executeForString(authorizedGet(url)))

    private fun authorizedGet(url: String): Request = Request.Builder()
        .url(url)
        .headers(baseHeaders())
        .get()
        .build()

    private fun patch(url: String, jsonBody: String) {
        val request = Request.Builder()
            .url(url)
            .headers(baseHeaders())
            .method("PATCH", jsonBody.toRequestBody(JSON_MEDIA))
            .build()
        executeForString(request)
    }

    private fun put(url: String, jsonBody: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .headers(baseHeaders())
            .put(jsonBody.toRequestBody(JSON_MEDIA))
            .build()
        return JSONObject(executeForString(request))
    }

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

    companion object {
        private const val USER_AGENT = "Termux-Ultra-Android"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
