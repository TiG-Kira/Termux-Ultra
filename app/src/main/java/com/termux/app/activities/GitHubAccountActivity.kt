package com.termux.app.activities

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.core.view.WindowCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.R
import com.termux.app.compose.*
import com.termux.app.compose.NavigationHelper
import com.termux.app.github.GitHubApi
import com.termux.app.github.GitHubIssue
import com.termux.app.github.GitHubSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 已登录用户的账户页：
 * 用户信息头 → 反馈问题 / 查看所有话题 两个入口 → 「我的反馈」该用户在本仓库的 Issue → 注销按钮。
 */
class GitHubAccountActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navDispatcherOwner) {
                KiTerminalTheme {
                    val context = this@GitHubAccountActivity
                    val scrollBehavior = MiuixScrollBehavior()
                    val density = LocalDensity.current
                    val systemNavBarsHeight = with(density) {
                        WindowInsets.navigationBars.getBottom(density).toDp()
                    }
                    val session = GitHubSessionStore.load(context)

                    if (session == null) {
                        Scaffold(
                            contentWindowInsets = WindowInsets(0, 0, 0, 0),
                            topBar = {
                                TopAppBar(
                                    title = stringResource(R.string.github_account_title),
                                    scrollBehavior = scrollBehavior,
                                    navigationIcon = { BackButton { finish() } }
                                )
                            }
                        ) { padding ->
                            Box(
                                Modifier.fillMaxSize().padding(padding),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.github_login_required),
                                    style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                )
                            }
                        }
                        return@KiTerminalTheme
                    }

                    var issues by remember { mutableStateOf<List<GitHubIssue>>(emptyList()) }
                    var loading by remember { mutableStateOf(true) }
                    var error by remember { mutableStateOf<String?>(null) }
                    var showLogout by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    fun loadMine() {
                        loading = true
                        error = null
                        scope.launch(Dispatchers.IO) {
                            runCatching { GitHubApi(session.token).fetchIssues(creator = session.user.login) }
                                .onSuccess { list ->
                                    withContext(Dispatchers.Main) { issues = list; loading = false }
                                }
                                .onFailure { e ->
                                    withContext(Dispatchers.Main) {
                                        error = e.message ?: e.javaClass.simpleName
                                        loading = false
                                    }
                                }
                        }
                    }

                    LaunchedEffect(Unit) { loadMine() }

                    fun openDetail(number: Int) {
                        context.startActivity(
                            Intent(context, GitHubIssueDetailActivity::class.java)
                                .putExtra(GitHubIssueDetailActivity.EXTRA_ISSUE_NUMBER, number)
                        )
                    }

                    Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            TopAppBar(
                                title = stringResource(R.string.github_account_title),
                                scrollBehavior = scrollBehavior,
                                navigationIcon = { BackButton { finish() } }
                            )
                        }
                    ) { padding ->
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            contentPadding = PaddingValues(
                                start = 16.dp, end = 16.dp, bottom = systemNavBarsHeight + 26.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // 用户信息头：点击用浏览器打开该用户的 GitHub 主页
                            item {
                                Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                                    ArrowPreference(
                                        title = session.user.name ?: session.user.login,
                                        summary = "@${session.user.login}",
                                        onClick = { openUserProfile(context, session.user.login) },
                                        startAction = { AvatarWithAdminBadge(session.user.avatarUrl, session) }
                                    )
                                }
                            }

                            // 反馈问题
                            item {
                                Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                                    ArrowPreference(
                                        title = stringResource(R.string.github_feedback_entry),
                                        summary = stringResource(R.string.github_feedback_entry_summary),
                                        onClick = {
                                            context.startActivity(Intent(context, GitHubFeedbackActivity::class.java))
                                        },
                                        startAction = { LeadIcon(R.drawable.ic_bug) }
                                    )
                                }
                            }

                            // 查看所有话题
                            item {
                                Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                                    ArrowPreference(
                                        title = stringResource(R.string.github_topics_entry),
                                        summary = stringResource(R.string.github_topics_entry_summary),
                                        onClick = {
                                            context.startActivity(Intent(context, GitHubIssuesActivity::class.java))
                                        },
                                        startAction = { LeadIcon(R.drawable.ic_info) }
                                    )
                                }
                            }

                            // 查看合并请求（PR）
                            item {
                                Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                                    ArrowPreference(
                                        title = stringResource(R.string.github_pr_entry),
                                        summary = stringResource(R.string.github_pr_entry_summary),
                                        onClick = {
                                            context.startActivity(Intent(context, GitHubPullRequestsActivity::class.java))
                                        },
                                        startAction = { LeadIcon(R.drawable.ic_code) }
                                    )
                                }
                            }

                            // 我的反馈
                            item { SmallTitle(text = stringResource(R.string.github_my_feedback_group)) }

                            if (loading) {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), Alignment.Center) {
                                        Text(
                                            stringResource(R.string.github_loading),
                                            style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        )
                                    }
                                }
                            } else if (error != null) {
                                item {
                                    Column(
                                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            stringResource(R.string.github_load_failed, error ?: ""),
                                            style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(Modifier.height(12.dp))
                                        TextButton(text = stringResource(R.string.github_retry), onClick = { loadMine() })
                                    }
                                }
                            } else if (issues.isEmpty()) {
                                item {
                                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), Alignment.Center) {
                                        Text(
                                            stringResource(R.string.github_issue_empty),
                                            style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        )
                                    }
                                }
                            } else {
                                items(issues, key = { it.number }) { issue ->
                                    IssueCard(issue = issue, onClick = { openDetail(issue.number) })
                                }
                            }

                            // 注销按钮
                            item {
                                Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                                    Box(
                                        Modifier.fillMaxWidth().clickable { showLogout = true }
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            stringResource(R.string.github_logout),
                                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.error)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 注销确认弹窗（放在 LazyColumn 之外，避免随列表复用被回收）
                    OverlayDialog(
                        show = showLogout,
                        onDismissRequest = { showLogout = false },
                        title = stringResource(R.string.github_logout_confirm_title),
                        summary = stringResource(R.string.github_logout_confirm_summary)
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(text = stringResource(R.string.cancel), onClick = { showLogout = false })
                            Spacer(Modifier.width(12.dp))
                            TextButton(
                                text = stringResource(R.string.github_logout),
                                onClick = {
                                    showLogout = false
                                    GitHubSessionStore.clear(context)
                                    RepoRoleCache.invalidate()
                                    // 关闭本页，让设置页在 ON_RESUME 时重新读取登录态
                                    finish()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "GitHubAccountActivity"

        /** 用浏览器打开指定用户的 GitHub 主页 */
        private fun openUserProfile(context: Context, login: String) {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/$login"))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                )
            }
        }
    }
}
