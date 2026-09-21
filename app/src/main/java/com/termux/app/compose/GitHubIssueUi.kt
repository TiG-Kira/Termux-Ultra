package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.res.painterResource
import com.termux.R
import com.termux.app.github.GitHubIssue
import com.termux.app.github.IssueStateKind
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 三态状态徽章：Open / 已解决 Close / 未解决 Close */
@Composable
fun IssueStateBadge(kind: IssueStateKind) {
    val (bg, fg, textRes) = when (kind) {
        IssueStateKind.OPEN ->
            Triple(Color(0xFFE6F4EA), Color(0xFF1E7E34), R.string.github_issue_state_open)
        IssueStateKind.CLOSED_RESOLVED ->
            Triple(Color(0xFFE8F0FE), Color(0xFF1A56DB), R.string.github_issue_state_closed_resolved)
        IssueStateKind.CLOSED_UNRESOLVED ->
            Triple(Color(0xFFF1F1F1), Color(0xFF7A7A7A), R.string.github_issue_state_closed_unresolved)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(textRes),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = fg
        )
    }
}

/** 单个 Issue 卡片：标题 + 状态 + 作者 + 回复数 + 创建时间 */
@Composable
fun IssueCard(issue: GitHubIssue, onClick: () -> Unit) {
    val onSurface = MiuixTheme.colorScheme.onSurface
    val muted = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "#${issue.number}  ${issue.title}",
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = onSurface)
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IssueStateBadge(issue.kind)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.github_issue_author, issue.authorLogin),
                    style = TextStyle(fontSize = 12.sp, color = muted)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.github_issue_replies, issue.commentCount),
                    style = TextStyle(fontSize = 12.sp, color = muted)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.github_issue_created_at, issue.createdAt.take(10)),
                style = TextStyle(fontSize = 12.sp, color = muted)
            )
        }
    }
}

/**
 * Issue 列表的统一布局：处理 加载中 / 失败 / 空 / 正常 四种状态。
 * 由调用方将其放在 Scaffold 的 content padding 内。
 */
@Composable
fun IssueFeed(
    modifier: Modifier,
    issues: List<GitHubIssue>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onIssueClick: (GitHubIssue) -> Unit,
    contentPadding: PaddingValues
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (loading) {
            item { CenterNote(stringResource(R.string.github_loading)) }
            return@LazyColumn
        }
        if (error != null) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.github_load_failed, error),
                        style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(text = stringResource(R.string.github_retry), onClick = onRetry)
                }
            }
            return@LazyColumn
        }
        if (issues.isEmpty()) {
            item { CenterNote(stringResource(R.string.github_issue_empty)) }
            return@LazyColumn
        }
        items(issues, key = { it.number }) { IssueCard(issue = it, onClick = { onIssueClick(it) }) }
    }
}

@Composable
private fun CenterNote(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary),
            textAlign = TextAlign.Center
        )
    }
}

/** 通用返回按钮（圆形点击区 + 返回箭头） */
@Composable
fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = MiuixIcons.Back,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface
        )
    }
}

/** 列表项左侧的方形图标容器（40dp surfaceVariant 圆角底 + 着色图标） */
@Composable
fun LeadIcon(iconRes: Int) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MiuixTheme.colorScheme.onSurface
        )
    }
}
