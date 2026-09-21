package com.termux.app.activities

import android.content.Context
import android.os.Bundle
import android.widget.Toast
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
import com.termux.app.github.GitHubLogcat
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
 * 反馈页：填写标题与问题描述，提交时自动附加最近应用日志，
 * 并以登录用户的身份在仓库创建 Issue。
 */
class GitHubFeedbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val navDispatcher = NavigationHelper.createDispatcher()
            val navDispatcherOwner = NavigationHelper.createOwner(navDispatcher)
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navDispatcherOwner) {
                KiTerminalTheme {
                    val context = this@GitHubFeedbackActivity
                    val scrollBehavior = MiuixScrollBehavior()
                    val density = LocalDensity.current
                    val systemNavBarsHeight = with(density) {
                        WindowInsets.navigationBars.getBottom(density).toDp()
                    }
                    val session = remember { GitHubSessionStore.load(context) }

                    var title by remember { mutableStateOf("") }
                    var body by remember { mutableStateOf("") }
                    var submitting by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    fun submit() {
                        val t = title.trim()
                        val b = body.trim()
                        if (t.isBlank()) {
                            Toast.makeText(context, R.string.github_feedback_title_required, Toast.LENGTH_SHORT).show()
                            return
                        }
                        if (b.isBlank()) {
                            Toast.makeText(context, R.string.github_feedback_body_required, Toast.LENGTH_SHORT).show()
                            return
                        }
                        val token = session?.token
                        if (token == null) {
                            Toast.makeText(context, R.string.github_login_required, Toast.LENGTH_SHORT).show()
                            finish()
                            return
                        }
                        submitting = true
                        scope.launch(Dispatchers.IO) {
                            val logs = GitHubLogcat.recent()
                            val heading = context.getString(R.string.github_feedback_logs_heading)
                            val finalBody = buildString {
                                append(b)
                                append("\n\n---\n\n### $heading\n\n")
                                append("```log\n")
                                append(logs)
                                append("\n```")
                            }
                            runCatching { GitHubApi(token).createIssue(t, finalBody) }
                                .onSuccess { number ->
                                    withContext(Dispatchers.Main) {
                                        submitting = false
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.github_feedback_success, number),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        finish()
                                    }
                                }
                                .onFailure { e ->
                                    withContext(Dispatchers.Main) {
                                        submitting = false
                                        Toast.makeText(
                                            context,
                                            context.getString(
                                                R.string.github_feedback_failed,
                                                e.message ?: e.javaClass.simpleName
                                            ),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                        }
                    }

                    Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        topBar = {
                            TopAppBar(
                                title = stringResource(R.string.github_feedback_page_title),
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
                            item {
                                Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(
                                            stringResource(R.string.github_feedback_title_label),
                                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        TextField(
                                            value = title,
                                            onValueChange = { title = it },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            item {
                                Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(
                                            stringResource(R.string.github_feedback_body_label),
                                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onSurface)
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        TextField(
                                            value = body,
                                            onValueChange = { body = it },
                                            modifier = Modifier.fillMaxWidth().height(150.dp),
                                            singleLine = false
                                        )
                                    }
                                }
                            }

                            item {
                                Text(
                                    stringResource(R.string.github_feedback_note),
                                    style = TextStyle(fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                )
                            }

                            item {
                                Button(
                                    onClick = { submit() },
                                    enabled = !submitting,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColorsPrimary()
                                ) {
                                    Text(
                                        if (submitting) stringResource(R.string.github_feedback_submitting)
                                        else stringResource(R.string.github_feedback_submit),
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
