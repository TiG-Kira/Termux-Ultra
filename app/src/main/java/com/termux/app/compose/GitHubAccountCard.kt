package com.termux.app.compose

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.termux.R
import com.termux.app.activities.GitHubAccountActivity
import com.termux.app.github.GitHubDeviceAuth
import com.termux.app.github.GitHubLoginRunner
import com.termux.app.github.GitHubApi
import com.termux.app.github.GitHubSession
import com.termux.app.github.GitHubSessionStore
import com.termux.app.github.RepoRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置页顶部的 GitHub 账户卡片（独立 Card + ArrowPreference）。
 *
 * - 未登录：抽象人头像 + 「使用 GitHub 登录」；点击弹窗展示设备码，由用户自行授权；
 * - 已登录：GitHub 头像 + 昵称，点击直接进入账户详情页。
 */
@Composable
fun GitHubAccountCard() {
    val context = LocalContext.current
    // 每次重组都从存储读一次并不划算，但登录/注销都会回到本页，
    // 因此用 Lifecycle 的 ON_RESUME 作为唯一的刷新时机，保证状态与存储一致。
    var session by remember { mutableStateOf(GitHubSessionStore.load(context)) }
    var showLoginSheet by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf(context.getString(R.string.github_login_preparing)) }
    var deviceAuth by remember { mutableStateOf<GitHubDeviceAuth?>(null) }
    var runner by remember { mutableStateOf<GitHubLoginRunner?>(null) }

    DisposableEffect(Unit) {
        onDispose { runner?.cancel() }
    }

    // 从账户页返回（含注销）或任何回到设置页的时机，同步一次登录态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                session = GitHubSessionStore.load(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 从账户页返回（含注销）时刷新登录态
    val accountLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        session = GitHubSessionStore.load(context)
    }

    fun beginLogin() {
        deviceAuth = null
        statusText = context.getString(R.string.github_login_preparing)
        showLoginSheet = true
        val login = GitHubLoginRunner(context)
        runner = login
        login.start(
            onStatus = { statusText = it },
            onDeviceAuth = {
                deviceAuth = it
                statusText = context.getString(R.string.github_login_waiting_device)
            },
            onSuccess = { result ->
                session = result
                runner = null
                showLoginSheet = false
            },
            onFailure = { message ->
                runner = null
                showLoginSheet = false
                Toast.makeText(
                    context,
                    context.getString(R.string.github_login_failed, message),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        ArrowPreference(
            title = session?.user?.login ?: stringResource(R.string.github_login_title),
            summary = if (session == null) {
                stringResource(R.string.github_login_summary)
            } else {
                stringResource(R.string.github_login_summary_signed_in)
            },
            onClick = {
                if (session == null) beginLogin()
                else accountLauncher.launch(Intent(context, GitHubAccountActivity::class.java))
            },
            startAction = { AvatarWithAdminBadge(session?.user?.avatarUrl, session) }
        )
    }

    OverlayDialog(
        show = showLoginSheet,
        onDismissRequest = {
            showLoginSheet = false
            runner?.cancel()
        },
        title = stringResource(R.string.github_login_dialog_title),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                deviceAuth?.takeIf { it.userCode.isNotBlank() }?.let { auth ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.github_login_device_hint, auth.userCode),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.github_login_device_tip),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    TextButton(
                        text = stringResource(R.string.github_login_open_device_page),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { openDevicePage(context, auth.verificationUri) }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        showLoginSheet = false
                        runner?.cancel()
                    }
                )
            }
        }
    )
}

private fun openDevicePage(context: Context, uri: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        )
    }
}

/**
 * [ArrowPreference] 的 startAction：圆形头像 + 头像正下方的管理员 Badge。
 *
 * 管理员 tag 放在头像底部（而不是标题右侧），避免挤压标题空间；
 * 非管理员时只渲染头像，宽度与头像一致，不与其它入口的图标错位。
 */
@Composable
fun AvatarWithAdminBadge(avatarUrl: String?, session: GitHubSession?) {
    Column(
        modifier = Modifier.widthIn(min = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LoginAvatar(avatarUrl)
        if (session != null) {
            Spacer(modifier = Modifier.height(3.dp))
            RepoAdminBadge(session)
        }
    }
}

@Composable
private fun LoginAvatar(avatarUrl: String?) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (avatarUrl.isNullOrBlank()) {
            Icon(
                painter = painterResource(R.drawable.ic_person),
                contentDescription = stringResource(R.string.github_login_title),
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onSurface
            )
        } else {
            AsyncImage(
                model = avatarUrl,
                contentDescription = stringResource(R.string.github_account_title),
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            )
        }
    }
}

/**
 * 总览页 TopAppBar 左侧的用户登录状态入口。
 *
 * 逻辑与设置页 [GitHubAccountCard] 完全一致：
 * - 未登录 / 取不到头像 → 抽象人头像，点击弹出 GitHub 设备码登录；
 * - 已登录 → 显示 GitHub 头像，点击进入账户详情页。
 */
@Composable
fun GitHubLoginStatusIcon(onNavigateToAccount: () -> Unit) {
    val context = LocalContext.current
    // 每次重组都从存储读一次并不划算，但登录/注销都会回到本页，
    // 因此用 Lifecycle 的 ON_RESUME 作为刷新时机，保证状态与存储一致。
    var session by remember { mutableStateOf(GitHubSessionStore.load(context)) }
    var showLoginSheet by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf(context.getString(R.string.github_login_preparing)) }
    var deviceAuth by remember { mutableStateOf<GitHubDeviceAuth?>(null) }
    var runner by remember { mutableStateOf<GitHubLoginRunner?>(null) }

    DisposableEffect(Unit) {
        onDispose { runner?.cancel() }
    }

    // 从账户页返回（含注销）或任何回到本页的时机，同步一次登录态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                session = GitHubSessionStore.load(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 从账户页返回（含注销）时刷新登录态
    val accountLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        session = GitHubSessionStore.load(context)
    }

    fun beginLogin() {
        deviceAuth = null
        statusText = context.getString(R.string.github_login_preparing)
        showLoginSheet = true
        val login = GitHubLoginRunner(context)
        runner = login
        login.start(
            onStatus = { statusText = it },
            onDeviceAuth = {
                deviceAuth = it
                statusText = context.getString(R.string.github_login_waiting_device)
            },
            onSuccess = { result ->
                session = result
                runner = null
                showLoginSheet = false
            },
            onFailure = { message ->
                runner = null
                showLoginSheet = false
                Toast.makeText(
                    context,
                    context.getString(R.string.github_login_failed, message),
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable {
                if (session == null) beginLogin() else onNavigateToAccount()
            }
            .padding(2.dp),
        contentAlignment = Alignment.Center
    ) {
        LoginAvatar(session?.user?.avatarUrl)
    }

    OverlayDialog(
        show = showLoginSheet,
        onDismissRequest = {
            showLoginSheet = false
            runner?.cancel()
        },
        title = stringResource(R.string.github_login_dialog_title),
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                deviceAuth?.takeIf { it.userCode.isNotBlank() }?.let { auth ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.github_login_device_hint, auth.userCode),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.github_login_device_tip),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    TextButton(
                        text = stringResource(R.string.github_login_open_device_page),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { openDevicePage(context, auth.verificationUri) }
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        showLoginSheet = false
                        runner?.cancel()
                    }
                )
            }
        }
    )
}

// ============================================================================
// 仓库管理员 Badge（供 GitHubAccountCard / GitHubAccountActivity 复用）
// ============================================================================

/**
 * 仓库角色检测结果缓存（进程级）。
 *
 * [RepoAdminBadge] 用在设置页的 GitHub 卡片和账户页的用户卡片里，两处都位于 LazyColumn ——
 * 条目滑出屏幕即被销毁、滑回来又重新组合，组合内的 `remember` 随之丢失，
 * 于是每滑一次就多打一次 GitHub 权限接口。
 *
 * 这里把「检测结果」从组合里搬出来，按 **Activity 实例 + 登录用户名** 缓存成一条 StateFlow：
 * - 同一个 Activity 内滑动 / 反复重组 → 复用同一条 flow，不再请求；
 * - 重新进入该 Activity（新实例）→ key 变了，才有一次新的检测；
 * - 换了登录用户 → login 变了，同样重新检测。
 *
 * 请求在缓存自己的协程域里跑，**不随组合销毁而取消**：
 * 否则「请求还在飞时把卡片滑出屏幕」会让这次请求白跑、滑回来又要重来。
 * 同一 key 的并发触发会被去重，只打一次接口。
 */
object RepoRoleCache {
    private val lock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val states = mutableMapOf<String, MutableStateFlow<RepoRole?>>()
    private val requested = mutableSetOf<String>()

    /**
     * 取某个「Activity 实例 + 用户」的结果流（不存在则创建）。
     * 用 identityHashCode 而非 Activity 引用做 key —— 避免缓存持有 Activity 导致泄漏。
     */
    fun roleFlow(owner: Any, login: String): StateFlow<RepoRole?> = flowOf(owner, login)

    /** 异步落地一次检测（同 key 幂等）；结果写入 flow，订阅方自动收到 */
    fun refresh(owner: Any, login: String, token: String) {
        val isNew = synchronized(lock) { requested.add(keyOf(owner, login)) }
        if (!isNew) return
        ioScope.launch {
            // 失败也写入 null，避免失败场景下随滑动无限重试
            val role = runCatching { GitHubApi(token).fetchRepoPermission(login) }
                .getOrNull()
                ?.myRole
            flowOf(owner, login).value = role
        }
    }

    /** 注销时清空，避免下一个账号沿用上一个账号的管理员判定 */
    fun invalidate() = synchronized(lock) {
        states.clear()
        requested.clear()
    }

    private fun keyOf(owner: Any, login: String) = "${System.identityHashCode(owner)}:$login"

    private fun flowOf(owner: Any, login: String): MutableStateFlow<RepoRole?> = synchronized(lock) {
        states.getOrPut(keyOf(owner, login)) { MutableStateFlow(null) }
    }
}

/**
 * 异步检测当前登录用户在目标仓库的角色，若为管理员则显示 Badge。
 * 未登录或查询失败时不显示 Badge，也不抛异常。
 *
 * 检测结果由 [RepoRoleCache] 持久化：同一 Activity 内反复重组 / 滑动不会重复请求。
 */
@Composable
fun RepoAdminBadge(session: GitHubSession?) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val login = session?.user?.login
    val token = session?.token

    // 组合滑出再滑回来时 LaunchedEffect 会重跑，但 refresh() 对同一 key 幂等，不会重复打接口
    val flow = remember(lifecycleOwner, login) {
        if (login == null) MutableStateFlow<RepoRole?>(null) else RepoRoleCache.roleFlow(lifecycleOwner, login)
    }
    val role by flow.collectAsState()

    LaunchedEffect(lifecycleOwner, login, token) {
        if (login != null && token != null) RepoRoleCache.refresh(lifecycleOwner, login, token)
    }

    val r = role
    if (r == null || !r.isAdminLike()) return

    // 尺寸刻意做小：这个 Badge 要塞在 40dp 头像的正下方，宽度过大会把标题挤掉
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF1A56DB))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(R.string.github_admin_badge),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}
