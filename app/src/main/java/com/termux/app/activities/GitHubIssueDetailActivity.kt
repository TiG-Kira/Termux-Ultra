package com.termux.app.activities

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
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
import com.termux.app.github.GitHubComment
import com.termux.app.github.GitHubIssueDetail
import com.termux.app.github.GitHubSessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Issue 详情页：主楼正文（GFM 渲染）+ 状态 + 回复列表。
 */
class GitHubIssueDetailActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ISSUE_NUMBER = "issue_number"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val issueNumber = intent.getIntExtra(EXTRA_ISSUE_NUMBER, 0)
        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navDispatcherOwner) {
                KiTerminalTheme {
                    val context = this@GitHubIssueDetailActivity
                    val scrollBehavior = MiuixScrollBehavior()
                    val density = LocalDensity.current
                    val systemNavBarsHeight = with(density) {
                        WindowInsets.navigationBars.getBottom(density).toDp()
                    }
                    val session = remember { GitHubSessionStore.load(context) }

                    var detail by remember { mutableStateOf<GitHubIssueDetail?>(null) }
                    var loading by remember { mutableStateOf(true) }
                    var error by remember { mutableStateOf<String?>(null) }
                    val scope = rememberCoroutineScope()

                    fun load() {
                        val token = session?.token ?: return
                        loading = true
                        error = null
                        scope.launch(Dispatchers.IO) {
                            runCatching { GitHubApi(token).fetchIssueDetail(issueNumber) }
                                .onSuccess { d ->
                                    withContext(Dispatchers.Main) { detail = d; loading = false }
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

                    Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            TopAppBar(
                                title = stringResource(R.string.github_issue_detail_title, issueNumber),
                                scrollBehavior = scrollBehavior,
                                navigationIcon = { BackButton { finish() } }
                            )
                        }
                    ) { padding ->
                        when {
                            session == null -> {
                                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                                    Text(
                                        stringResource(R.string.github_login_required),
                                        style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    )
                                }
                            }
                            loading -> {
                                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                                    Text(
                                        stringResource(R.string.github_loading),
                                        style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                    )
                                }
                            }
                            error != null -> {
                                Column(
                                    Modifier.fillMaxSize().padding(padding),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        stringResource(R.string.github_load_failed, error ?: ""),
                                        style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    TextButton(text = stringResource(R.string.github_retry), onClick = { load() })
                                }
                            }
                            detail != null -> {
                                val d = detail!!
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
                                    // 标题 + 状态 + 作者
                                    item {
                                        Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                                            Column(Modifier.padding(16.dp)) {
                                                Text(
                                                    d.issue.title,
                                                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                                )
                                                Spacer(Modifier.height(10.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    IssueStateBadge(d.issue.kind)
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        stringResource(R.string.github_issue_author, d.issue.authorLogin),
                                                        style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        stringResource(R.string.github_issue_created_at, d.issue.createdAt.take(10)),
                                                        style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 正文
                                    item {
                                        Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                                            Column(Modifier.padding(16.dp)) {
                                                MarkdownContent(
                                                    text = d.issue.body ?: stringResource(R.string.github_issue_no_body),
                                                    bodyFontSizeSp = 13
                                                )
                                            }
                                        }
                                    }

                                    // 回复
                                    item { SmallTitle(text = stringResource(R.string.github_issue_comments_section)) }

                                    if (d.comments.isEmpty()) {
                                        item {
                                            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), Alignment.Center) {
                                                Text(
                                                    stringResource(R.string.github_issue_no_comments),
                                                    style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                                )
                                            }
                                        }
                                    } else {
                                        items(d.comments, key = { it.authorLogin + it.createdAt }) { comment ->
                                            CommentCard(comment)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentCard(comment: GitHubComment) {
    Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.authorLogin,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    comment.createdAt.take(10),
                    style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                )
            }
            Spacer(Modifier.height(8.dp))
            MarkdownContent(
                text = comment.body.ifBlank { stringResource(R.string.github_issue_no_body) },
                bodyFontSizeSp = 13
            )
        }
    }
}
