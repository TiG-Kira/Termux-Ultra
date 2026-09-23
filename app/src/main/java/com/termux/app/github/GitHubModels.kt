package com.termux.app.github

/**
 * GitHub OAuth 与仓库交互的常量配置。
 *
 * 本项目登录使用 **Device Flow（设备码）**：应用申请设备码并展示，
 * 用户在浏览器输入设备码完成授权，应用轮询换取令牌。
 * 因此【不需要也不应写入 Client Secret】——Client ID 本身是公开信息，
 * 而 Client Secret 一旦随 APK 发布即可被任何人提取。
 */
internal object GitHubConfig {
    /** OAuth App Client ID（公开信息，非机密） */
    const val CLIENT_ID = "Ov23li5nucDUhazywwXD"

    /** Token 交换端点（Device Flow 轮询用） */
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
     * - `public_repo`：在公开仓库创建 / 读写 Issue、评论、合并 PR
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

// ============================================================================
// Pull Request & Repo Permission 相关数据类
// ============================================================================

/**
 * 仓库协作者角色（来自 GitHub `/collaborators/{username}/permission` 的 `permission` 字段）。
 * 按权限从高到低：admin > maintain > write > triage > read。
 */
enum class RepoRole(val label: String) {
    ADMIN("管理员"),
    MAINTAIN("协作者"),
    WRITE("开发者"),
    TRIAGE("访客"),
    READ("只读"),
    UNKNOWN("未知");

    /** 是否属于「管理员组」——仓库 owner / maintainer 拥有合并与关闭 PR 权限 */
    fun isAdminLike(): Boolean = this == ADMIN || this == MAINTAIN
}

/**
 * 仓库权限快照——由 API 拉取并缓存，用于卡片标记、PR/Issue 管理按钮判定。
 * 同时包含 repo.owner.login 便于快速比对。
 */
data class RepoPermission(
    val repoOwner: String,
    val myRole: RepoRole,
    val isOwner: Boolean
) {
    /** 是否具备关闭 Issue / 合并 PR 的管理员权限 */
    val canManage: Boolean get() = myRole.isAdminLike()
}

/** PR 合并方式——映射 GitHub API `merge_method` 参数 */
enum class MergeMethod(val apiValue: String, val display: String) {
    MERGE("merge", "合并"),
    SQUASH("squash", "压缩并合并"),
    REBASE("rebase", "变基并合并");

    companion object {
        fun fromApi(v: String?): MergeMethod = entries.firstOrNull { it.apiValue.equals(v, true) } ?: MERGE
    }
}

/** PR 合并状态（来自 /pulls/{number} 的 mergeable 字段） */
enum class MergeableState {
    MERGEABLE,
    CONFLICTING,
    UNKNOWN;

    companion object {
        fun fromApi(v: String?): MergeableState = when {
            v == null -> UNKNOWN
            v.equals("true", true) -> MERGEABLE
            v.equals("false", true) -> CONFLICTING
            else -> UNKNOWN
        }
    }
}

/** Pull Request 列表项（与 IssueCard 平行的轻量模型） */
data class GitHubPullRequest(
    val number: Int,
    val title: String,
    val body: String?,
    val authorLogin: String,
    val state: String,           // "open" | "closed" | "merged"
    val merged: Boolean,
    val commentsCount: Int,
    val createdAt: String,
    val updatedAt: String,
    val htmlUrl: String,
    val mergeable: MergeableState
) {
    val isOpen: Boolean get() = state.equals("open", true)
    val isClosed: Boolean get() = state.equals("closed", true)
    val isMerged: Boolean get() = merged
}

/** PR 详情（列表模型 + 回复列表 + 基本 review 状态） */
data class GitHubPullRequestDetail(
    val pr: GitHubPullRequest,
    val comments: List<GitHubComment>
)
