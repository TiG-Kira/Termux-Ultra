package com.termux.app.github

/**
 * GitHub OAuth 与仓库交互的常量配置。
 *
 * 本项目使用 **PKCE（Authorization Code + PKCE, S256）** 完成浏览器授权回调，
 * 因此【不需要也不应写入 Client Secret】——OAuth App 的 Client ID 本身是公开信息，
 * 而 Client Secret 一旦随 APK 发布即可被任何人提取。
 */
internal object GitHubConfig {
    /** OAuth App Client ID（公开信息，非机密） */
    const val CLIENT_ID = "Ov23li5nucDUhazywwXD"

    /** 本地回环回调地址，需与 OAuth App 中登记的 Callback URL 一致 */
    const val REDIRECT_URI = "http://127.0.0.1:6241/callback"
    const val CALLBACK_PORT = 6241
    const val CALLBACK_PATH = "/callback"

    /** 授权端点 */
    const val AUTHORIZE_URL = "https://github.com/login/oauth/authorize"
    /** Token 交换端点（三种 grant_type 共用） */
    const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    /** Device Flow 设备码申请端点 */
    const val DEVICE_CODE_URL = "https://github.com/login/device/code"

    /** REST API 根地址 */
    const val API_BASE = "https://api.github.com"

    /** 目标仓库：Issues 的读写对象 */
    const val REPO_OWNER = "TiG-Kira"
    const val REPO_NAME = "Termux-Ultra"

    /**
     * 申请的权限范围：
     * - `read:user`：读取登录用户昵称与头像
     * - `public_repo`：在公开仓库创建 / 读写 Issue
     */
    const val SCOPES = "read:user public_repo"
}

/** 登录后的 GitHub 用户摘要信息 */
data class GitHubUser(
    val login: String,
    val name: String?,
    val avatarUrl: String?,
    val id: Long
)

/** 持久化的登录会话 */
data class GitHubSession(
    val token: String,
    val tokenType: String,
    val scope: String,
    val user: GitHubUser,
    val loginAtMillis: Long
)

/** Issue 的展示状态，区分「已解决关闭」与「未解决关闭」 */
enum class IssueStateKind {
    /** 打开中 */
    OPEN,
    /** 已关闭且标记为已完成 */
    CLOSED_RESOLVED,
    /** 已关闭但未完成（未计划处理 / 无标记） */
    CLOSED_UNRESOLVED
}

/** Issue 列表项 */
data class GitHubIssue(
    val number: Int,
    val title: String,
    val body: String?,
    val authorLogin: String,
    val kind: IssueStateKind,
    val commentCount: Int,
    val createdAt: String,
    val htmlUrl: String
)

/** Device Flow 下发给用户的一次性设备码 */
data class GitHubDeviceAuth(
    val userCode: String,
    val verificationUri: String
)

/** 已登录用户摘要 + Issue 浏览所需的最小 API 封装结果 */
internal data class GitHubIssuePage(
    val issues: List<GitHubIssue>
)

/** Issue 回复 */
data class GitHubComment(
    val authorLogin: String,
    val body: String,
    val createdAt: String
)

/** Issue 详情 = 主楼 + 回复列表 */
data class GitHubIssueDetail(
    val issue: GitHubIssue,
    val comments: List<GitHubComment>
)
