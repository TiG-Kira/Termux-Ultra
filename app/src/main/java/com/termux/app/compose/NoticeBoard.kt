package com.termux.app.compose

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.app.activities.NoticeBoardActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 公告板内容状态。 */
sealed interface NoticeBoardState {
    data object Loading : NoticeBoardState
    data class Ready(val content: String) : NoticeBoardState
    data object Failed : NoticeBoardState
}

/**
 * 仓库根目录 NOTICE.md 公告板内容仓库。
 *
 * 从 GitHub raw 拉取全文，进程级缓存 10 分钟：总览页卡片与 GitHub 账户页预览卡共用
 * 同一条 [state]，进页面时 [refreshIfNeeded] 按需刷新，公告页 [refresh] 强制拉最新。
 */
object NoticeBoardRepository {
    private const val NOTICE_RAW_URL =
        "https://raw.githubusercontent.com/TiG-Kira/Termux-Ultra/main/NOTICE.md"
    private const val CACHE_TTL_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<NoticeBoardState>(NoticeBoardState.Loading)
    val state: StateFlow<NoticeBoardState> = _state.asStateFlow()

    @Volatile private var fetchedAt = 0L
    @Volatile private var inFlight = false

    /** 进页面时调用：缓存未过期则直接复用，否则后台刷新一次。 */
    fun refreshIfNeeded() {
        if (inFlight) return
        val current = _state.value
        if (current is NoticeBoardState.Ready &&
            System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS
        ) return
        inFlight = true
        scope.launch { fetchInternal() }
    }

    /** 强制重新拉取（公告页进入 / 重试）。 */
    fun refresh() {
        if (inFlight) return
        inFlight = true
        scope.launch { fetchInternal() }
    }

    private suspend fun fetchInternal() {
        try {
            val request = okhttp3.Request.Builder().url(NOTICE_RAW_URL).build()
            client.newCall(request).execute().use { response ->
                val body = if (response.isSuccessful) response.body?.string().orEmpty() else ""
                if (body.isNotBlank()) {
                    _state.value = NoticeBoardState.Ready(body)
                    fetchedAt = System.currentTimeMillis()
                    return
                }
            }
            // 拉取失败时保留已有的缓存内容，只在确实无内容时才置为 Failed
            if (_state.value !is NoticeBoardState.Ready) _state.value = NoticeBoardState.Failed
        } catch (_: Exception) {
            if (_state.value !is NoticeBoardState.Ready) _state.value = NoticeBoardState.Failed
        } finally {
            inFlight = false
        }
    }

    /** 取公告的纯文本简述：剥掉 Markdown 标记、压缩空白，供卡片预览。 */
    fun brief(markdown: String, maxChars: Int = 90): String {
        val plain = markdown
            .replace(Regex("```[\\s\\S]*?```"), " ")
            .replace(Regex("!\\[.*?]\\(.*?\\)"), " ")
            .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("(?m)^#{1,6}\\s*"), "")
            .replace(Regex("[*_`~>]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (plain.length <= maxChars) plain else plain.take(maxChars) + "…"
    }
}

/**
 * 公告板预览卡（GitHub 账户页用户卡片下方）。
 *
 * 显示 NOTICE.md 的简略内容，点击进入公告页看全文。
 */
@Composable
fun NoticeBoardPreviewCard() {
    val context = LocalContext.current
    val state by NoticeBoardRepository.state.collectAsState()
    LaunchedEffect(Unit) { NoticeBoardRepository.refreshIfNeeded() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                context.startActivity(Intent(context, NoticeBoardActivity::class.java))
            }
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.notice_board_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(6.dp))
            val cachedBrief = remember(state) {
                (state as? NoticeBoardState.Ready)?.let { NoticeBoardRepository.brief(it.content) }
            }
            val summaryText = cachedBrief ?: when (state) {
                NoticeBoardState.Loading -> stringResource(R.string.notice_board_loading)
                else -> stringResource(R.string.notice_board_empty)
            }
            Text(
                text = summaryText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.notice_board_hint),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.primary
            )
        }
    }
}
