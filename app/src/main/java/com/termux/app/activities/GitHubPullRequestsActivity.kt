package com.termux.app.activities

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.R
import com.termux.app.compose.BackButton
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.NavigationHelper
import com.termux.app.compose.PullRequestFeed
import com.termux.app.github.GitHubApi
import com.termux.app.github.GitHubPullRequest
import com.termux.app.github.GitHubSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 仓库合并请求（Pull Request）列表页。与 GitHubIssuesActivity 结构平行：
 * 加载中 / 失败 / 空 / 正常 —— 点击任意条目进入 PR 详情页。
 */
class GitHubPullRequestsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navDispatcherOwner) {
                KiTerminalTheme {
                    val context = this@GitHubPullRequestsActivity
                    val scrollBehavior = MiuixScrollBehavior()
                    val density = LocalDensity.current
                    val systemNavBarsHeight = with(density) {
                        androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(density).toDp()
                    }
                    val session = remember { GitHubSessionStore.load(context) }

                    var prs by remember { mutableStateOf<List<GitHubPullRequest>>(emptyList()) }
                    var loading by remember { mutableStateOf(true) }
                    var error by remember { mutableStateOf<String?>(null) }
                    val scope = rememberCoroutineScope()

                    fun load() {
                        val token = session?.token ?: return
                        loading = true
                        error = null
                        scope.launch(Dispatchers.IO) {
                            runCatching { GitHubApi(token).fetchPullRequests() }
                                .onSuccess { list ->
                                    withContext(Dispatchers.Main) { prs = list; loading = false }
                                }
                                .onFailure { e ->
                                    withContext(Dispatchers.Main) {
                                        error = e.message ?: e.javaClass.simpleName
                                        loading = false
                                    }
                                }
                        }
                    }

                    LaunchedEffect(Unit) { load() }

                    fun openDetail(number: Int) {
                        context.startActivity(
                            Intent(context, GitHubPullRequestDetailActivity::class.java)
                                .putExtra(GitHubPullRequestDetailActivity.EXTRA_PR_NUMBER, number)
                        )
                    }

                    Scaffold(
                        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                        topBar = {
                            TopAppBar(
                                title = stringResource(R.string.github_prs_title),
                                scrollBehavior = scrollBehavior,
                                navigationIcon = { BackButton { finish() } }
                            )
                        }
                    ) { padding ->
                        if (session == null) {
                            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                                Text(
                                    stringResource(R.string.github_login_required),
                                    style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                )
                            }
                            return@Scaffold
                        }
                        PullRequestFeed(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            prs = prs,
                            loading = loading,
                            error = error,
                            onRetry = { load() },
                            onPrClick = { openDetail(it.number) },
                            contentPadding = PaddingValues(
                                start = 16.dp, end = 16.dp, bottom = systemNavBarsHeight + 26.dp
                            )
                        )
                    }
                }
            }
        }
    }
}
