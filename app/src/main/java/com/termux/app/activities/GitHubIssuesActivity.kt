package com.termux.app.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 仓库全部话题（Issue）列表页。点击任意条目进入详情。
 */
class GitHubIssuesActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navDispatcherOwner) {
                KiTerminalTheme {
                    val context = this@GitHubIssuesActivity
                    val scrollBehavior = MiuixScrollBehavior()
                    val density = LocalDensity.current
                    val systemNavBarsHeight = with(density) {
                        WindowInsets.navigationBars.getBottom(density).toDp()
                    }
                    val session = remember { GitHubSessionStore.load(context) }

                    var issues by remember { mutableStateOf<List<GitHubIssue>>(emptyList()) }
                    var loading by remember { mutableStateOf(true) }
                    var error by remember { mutableStateOf<String?>(null) }
                    val scope = rememberCoroutineScope()

                    fun load() {
                        val token = session?.token ?: return
                        loading = true
                        error = null
                        scope.launch(Dispatchers.IO) {
                            runCatching { GitHubApi(token).fetchIssues() }
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

                    LaunchedEffect(Unit) { load() }

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
                                title = stringResource(R.string.github_topics_title),
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
                        IssueFeed(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            issues = issues,
                            loading = loading,
                            error = error,
                            onRetry = { load() },
                            onIssueClick = { openDetail(it.number) },
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
