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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            .clickable {
                if (session == null) beginLogin()
                else accountLauncher.launch(Intent(context, GitHubAccountActivity::class.java))
            }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LoginAvatar(session?.user?.avatarUrl)
            Column(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = session?.user?.login ?: stringResource(R.string.github_login_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuixTheme.colorScheme.onSurface
                    )
                    if (session != null) {
                        Spacer(Modifier.width(6.dp))
                        RepoAdminBadge(session)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (session == null) {
                        stringResource(R.string.github_login_summary)
                    } else {
                        stringResource(R.string.github_login_summary_signed_in)
                    },
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
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
 * 异步检测当前登录用户在目标仓库的角色，若为管理员则显示 Badge。
 * 未登录或查询失败时静默返回 null，不会抛出异常。
 */
@Composable
fun RepoAdminBadge(session: com.termux.app.github.GitHubSession?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var role by remember { mutableStateOf<com.termux.app.github.RepoRole?>(null) }
    var checkedLogin by remember { mutableStateOf<String?>(null) }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(session?.user?.login, lifecycleOwner) {
        if (session == null) { role = null; checkedLogin = null; return@DisposableEffect onDispose { } }
        if (checkedLogin == session.user.login) return@DisposableEffect onDispose { }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        scope.launch {
            runCatching {
                com.termux.app.github.GitHubApi(session.token).fetchRepoPermission(session.user.login)
            }.onSuccess { perm ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    role = perm.myRole
                    checkedLogin = session.user.login
                }
            }.onFailure {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    checkedLogin = session.user.login // 仅避免重复请求
                }
            }
        }
        onDispose { scope.cancel() }
    }

    val r = role
    if (r == null || !r.isAdminLike()) return

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1A56DB))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = stringResource(R.string.github_admin_badge),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}
