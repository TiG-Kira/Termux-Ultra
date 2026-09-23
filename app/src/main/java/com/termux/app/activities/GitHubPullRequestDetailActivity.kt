package com.termux.app.activities

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.termux.R
import com.termux.app.compose.BackButton
import com.termux.app.compose.CommentCard
import com.termux.app.compose.KiTerminalTheme
import com.termux.app.compose.MarkdownContent
import com.termux.app.compose.NavigationHelper
import com.termux.app.compose.PullRequestStateBadge
import com.termux.app.github.GitHubApi
import com.termux.app.github.GitHubIssue
import com.termux.app.github.GitHubPullRequestDetail
import com.termux.app.github.GitHubSessionStore
import com.termux.app.github.MergeableState
import com.termux.app.github.MergeMethod
import com.termux.app.github.RepoPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** PR 详情页：正文（Markdown）+ 回复列表 + 管理员合并/关闭操作 */
class GitHubPullRequestDetailActivity : ComponentActivity() {
    companion object {
        const val EXTRA_PR_NUMBER = "pr_number"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val prNumber = intent.getIntExtra(EXTRA_PR_NUMBER, 0)
        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navDispatcherOwner) {
                KiTerminalTheme {
                    val context = this@GitHubPullRequestDetailActivity
                    val scrollBehavior = MiuixScrollBehavior()
                    val density = LocalDensity.current
                    val systemNavBarsHeight = with(density) {
                        androidx.compose.foundation.layout.WindowInsets.navigationBars.getBottom(density).toDp()
                    }
                    val session = remember { GitHubSessionStore.load(context) }
                    val api = remember { session?.let { GitHubApi(it.token) } }

                    var detail by remember { mutableStateOf<GitHubPullRequestDetail?>(null) }
                    var loading by remember { mutableStateOf(true) }
                    var error by remember { mutableStateOf<String?>(null) }
                    var perm by remember { mutableStateOf<RepoPermission?>(null) }
                    val scope = rememberCoroutineScope()

                    var commentDraft by remember { mutableStateOf("") }
                    var submittingComment by remember { mutableStateOf(false) }

                    var showConfirmMerge by remember { mutableStateOf<MergeMethod?>(null) }
                    var showConfirmClose by remember { mutableStateOf(false) }
                    var busy by remember { mutableStateOf(false) }

                    fun loadAll() {
                        val token = session?.token ?: return
                        loading = true; error = null
                        scope.launch(Dispatchers.IO) {
                            val a = GitHubApi(token)
                            val prResult = runCatching { a.fetchPullRequestDetail(prNumber) }
                            val permResult = runCatching { a.fetchRepoPermission(session.user.login) }
                            withContext(Dispatchers.Main) {
                                prResult.onSuccess { detail = it }.onFailure { error = it.message ?: it.javaClass.simpleName }
                                permResult.onSuccess { perm = it }
                                loading = false
                            }
                        }
                    }

                    fun sendComment() {
                        val text = commentDraft.trim()
                        if (text.isBlank()) { Toast.makeText(context, R.string.github_comment_empty_warn, Toast.LENGTH_SHORT).show(); return }
                        val a = api ?: return
                        submittingComment = true
                        scope.launch(Dispatchers.IO) {
                            runCatching { a.createIssueComment(prNumber, text) }
                                .onSuccess {
                                    withContext(Dispatchers.Main) {
                                        commentDraft = ""; submittingComment = false
                                        Toast.makeText(context, R.string.github_comment_submitted, Toast.LENGTH_SHORT).show()
                                        loadAll()
                                    }
                                }
                                .onFailure { e ->
                                    withContext(Dispatchers.Main) {
                                        submittingComment = false
                                        Toast.makeText(context, context.getString(R.string.github_action_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                                    }
                                }
                        }
                    }

                    fun doMerge(method: MergeMethod) {
                        val a = api ?: return
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            runCatching { a.mergePullRequest(prNumber, method) }
                                .onSuccess {
                                    withContext(Dispatchers.Main) { busy = false; showConfirmMerge = null; Toast.makeText(context, R.string.github_action_success_pr_merged, Toast.LENGTH_SHORT).show(); loadAll() }
                                }
                                .onFailure { e ->
                                    withContext(Dispatchers.Main) { busy = false; Toast.makeText(context, context.getString(R.string.github_action_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
                                }
                        }
                    }

                    fun doClosePr() {
                        val a = api ?: return
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            runCatching { a.closePullRequest(prNumber) }
                                .onSuccess { withContext(Dispatchers.Main) { busy = false; showConfirmClose = false; Toast.makeText(context, R.string.github_action_success_pr_closed, Toast.LENGTH_SHORT).show(); loadAll() } }
                                .onFailure { e -> withContext(Dispatchers.Main) { busy = false; Toast.makeText(context, context.getString(R.string.github_action_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() } }
                        }
                    }

                    fun doReopenPr() {
                        val a = api ?: return
                        busy = true
                        scope.launch(Dispatchers.IO) {
                            runCatching { a.reopenPullRequest(prNumber) }
                                .onSuccess { withContext(Dispatchers.Main) { busy = false; Toast.makeText(context, R.string.github_action_success_pr_reopened, Toast.LENGTH_SHORT).show(); loadAll() } }
                                .onFailure { e -> withContext(Dispatchers.Main) { busy = false; Toast.makeText(context, context.getString(R.string.github_action_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() } }
                        }
                    }

                    LaunchedEffect(Unit) { loadAll() }

                    Scaffold(
                        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                        topBar = { TopAppBar(title = stringResource(R.string.github_pr_detail_title, prNumber), scrollBehavior = scrollBehavior, navigationIcon = { BackButton { finish() } }) }
                    ) { padding ->
                        when {
                            session == null -> LoginRequired(padding)
                            loading -> Loading(padding)
                            error != null -> LoadFailed(padding, error!!) { loadAll() }
                            detail != null -> {
                                val d = detail!!; val canManage = perm?.canManage == true
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().padding(padding).nestedScroll(scrollBehavior.nestedScrollConnection),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = systemNavBarsHeight + 26.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    item {
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(16.dp)) {
                                                Text(d.pr.title, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface))
                                                Spacer(Modifier.height(10.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    PullRequestStateBadge(d.pr.state, d.pr.merged)
                                                    if (d.pr.isOpen) {
                                                        Spacer(Modifier.width(8.dp))
                                                        when (d.pr.mergeable) {
                                                            MergeableState.MERGEABLE -> Text(text = stringResource(R.string.github_pr_mergeable), fontSize = 12.sp, color = Color(0xFF1E7E34), fontWeight = FontWeight.Bold)
                                                            MergeableState.CONFLICTING -> Text(text = stringResource(R.string.github_pr_conflicting), fontSize = 12.sp, color = Color(0xFF9A6700), fontWeight = FontWeight.Bold)
                                                            else -> Unit
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.height(8.dp))
                                                Text(stringResource(R.string.github_issue_author, d.pr.authorLogin), style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                                                Spacer(Modifier.height(2.dp))
                                                Text(stringResource(R.string.github_issue_created_at, d.pr.createdAt.take(10)), style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                                            }
                                        }
                                    }
                                    item {
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(16.dp)) {
                                                MarkdownContent(text = d.pr.body ?: stringResource(R.string.github_issue_no_body), bodyFontSizeSp = 13)
                                            }
                                        }
                                    }

                                    if (canManage && !d.pr.merged) {
                                        item { SmallTitle(text = stringResource(R.string.github_actions_section)) }
                                        item {
                                            Card(Modifier.fillMaxWidth()) {
                                                Column(Modifier.padding(16.dp)) {
                                                    when {
                                                        d.pr.isOpen -> {
                                                            Button(onClick = { showConfirmMerge = MergeMethod.MERGE }, enabled = !busy && d.pr.mergeable != MergeableState.CONFLICTING, modifier = Modifier.fillMaxWidth()) { Text(text = stringResource(R.string.github_merge_button), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                                                            Spacer(Modifier.height(8.dp))
                                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                                Button(onClick = { showConfirmMerge = MergeMethod.SQUASH }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(text = stringResource(R.string.github_squash_button), color = Color.White, fontSize = 13.sp) }
                                                                Button(onClick = { showConfirmMerge = MergeMethod.REBASE }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(text = stringResource(R.string.github_rebase_button), color = Color.White, fontSize = 13.sp) }
                                                            }
                                                            Spacer(Modifier.height(12.dp))
                                                            TextButton(text = stringResource(R.string.github_close_pr_button), onClick = { showConfirmClose = true }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                                        }
                                                        d.pr.isClosed -> {
                                                            TextButton(text = stringResource(R.string.github_reopen_pr_button), onClick = { doReopenPr() }, enabled = !busy, modifier = Modifier.fillMaxWidth())
                                                        }
                                                    }
                                                    if (busy) { Spacer(Modifier.height(8.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                                                }
                                            }
                                        }
                                    }

                                    item { SmallTitle(text = stringResource(R.string.github_issue_comments_section)) }
                                    if (d.comments.isEmpty()) {
                                        item {
                                            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), Alignment.Center) {
                                                Text(stringResource(R.string.github_issue_no_comments), style = TextStyle(fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary))
                                            }
                                        }
                                    } else {
                                        items(d.comments, key = { it.authorLogin + it.createdAt }) { comment -> CommentCard(comment) }
                                    }

                                    item {
                                        Card(Modifier.fillMaxWidth()) {
                                            Column(Modifier.padding(16.dp)) {
                                                TextField(value = commentDraft, onValueChange = { commentDraft = it }, modifier = Modifier.fillMaxWidth().height(120.dp), singleLine = false, label = stringResource(R.string.github_comment_placeholder))
                                                Spacer(Modifier.height(8.dp))
                                                Button(onClick = { sendComment() }, enabled = !submittingComment, modifier = Modifier.fillMaxWidth()) {
                                                    Text(text = if (submittingComment) stringResource(R.string.github_comment_submitting) else stringResource(R.string.github_comment_submit), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    val confirmMethod = showConfirmMerge
                    if (confirmMethod != null) {
                        OverlayDialog(
                            show = true, onDismissRequest = { showConfirmMerge = null },
                            title = stringResource(R.string.github_action_confirm_merge_title),
                            summary = stringResource(R.string.github_action_confirm_merge_summary, confirmMethod.display)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(text = stringResource(R.string.cancel), onClick = { showConfirmMerge = null })
                                Spacer(Modifier.width(12.dp))
                                TextButton(text = stringResource(R.string.github_confirm), onClick = { doMerge(confirmMethod) })
                            }
                        }
                    }
                    if (showConfirmClose) {
                        OverlayDialog(
                            show = true, onDismissRequest = { showConfirmClose = false },
                            title = stringResource(R.string.github_action_confirm_close_pr_title),
                            summary = stringResource(R.string.github_action_confirm_close_pr_summary)
                        ) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(text = stringResource(R.string.cancel), onClick = { showConfirmClose = false })
                                Spacer(Modifier.width(12.dp))
                                TextButton(text = stringResource(R.string.github_close_pr_button), onClick = { doClosePr() })
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable private fun LoginRequired(padding: PaddingValues) { Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.github_login_required), style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)) } }
    @Composable private fun Loading(padding: PaddingValues) { Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(stringResource(R.string.github_loading), style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)) } }
    @Composable private fun LoadFailed(padding: PaddingValues, err: String, onRetry: () -> Unit) { Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text(stringResource(R.string.github_load_failed, err), style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary), textAlign = TextAlign.Center); Spacer(Modifier.height(12.dp)); TextButton(text = stringResource(R.string.github_retry), onClick = onRetry) } }
}
