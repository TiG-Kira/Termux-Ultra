package com.termux.app.vortex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * VorteX 沙箱的跨进程告警事件通道。
 *
 * 设计沿用 `RiskConfirmManager` 的 Snackbar 事件范式：**单例持有 SharedFlow，
 * 由UI 层（[VorteXSandboxNoticeHost]）订阅并转成 Snackbar**。
 *
 * 为什么需要它：引导脚本运行在 libterminal 的独立进程里，而 Snackbar 挂在
 * Compose UI 上，两边无法直接通信。脚本把事件写进哨兵文件，宿主侧轮询到后
 * 通过本通道投递到 UI。
 */
object VorteXSandboxNotice {

    /**
     * 单次告警限流：同一条消息在该秒数内不重复投递。
     *
     * 必要性——用户在控制台敲 `cd /sdcard` 时，proot 会拦截**每一次**路径解析，
     * 一次 `ls /sdcard/*` 就可能产生几十上百次访问。若不去重，
     * Snackbar 会连珠炮般刷屏，反而看不到真正重要的提示。
     */
    private const val DEDUP_WINDOW_MS = 5000L

    private val _events = MutableSharedFlow<Notice>(
        replay = 0,
        extraBufferCapacity = 16,
    )

    val events: SharedFlow<Notice> = _events.asSharedFlow()

    data class Notice(
        val message: String,
        /** Android 的 Snackbar 时长语义（`android.view.Snackbar.LENGTH_*`）。 */
        val duration: Int = android.view.Snackbar.LENGTH_LONG,
    )

    /** 上次投递时间，key = 消息文本。 */
    private val lastPosted = HashMap<String, Long>()

    /**
     * 投递一条告警。非阻塞——事件缓冲区满时直接丢弃，
     * 因为告警属于「尽力而为」的提示，绝不能因此拖慢命令执行。
     */
    @JvmStatic
    fun post(message: String, duration: Int = android.view.Snackbar.LENGTH_LONG) {
        val now = System.currentTimeMillis()
        synchronized(lastPosted) {
            val last = lastPosted[message] ?: 0L
            //顺带清理过期条目，避免 map 无限增长。
            if (lastPosted.size > 64) {
                lastPosted.entries.removeAll { now - it.value > DEDUP_WINDOW_MS * 4 }
            }
            if (now - last < DEDUP_WINDOW_MS) return
            lastPosted[message] = now
        }
        _events.tryEmit(Notice(message, duration))
    }
}

/**
 * VorteX 沙箱告警的 Snackbar 宿主：订阅 [VorteXSandboxNotice.events] 并转成 Snackbar。
 *
 * 挂在终端控制台页（与 [RiskConfirmDialogHost] 同位置），只在沙箱会话期间轮询——
 * 非沙箱会话没有这个需求，也避免无谓的文件系统检查。
 *
 * @param enabled 是否启用轮询（通常传「当前会话是否为沙箱会话」）
 * @param snackbarHostState Miuix Snackbar 宿主；为 null 时回退到 Android 原生 Snackbar
 */
@Composable
fun VorteXSandboxNoticeHost(
    enabled: Boolean,
    snackbarHostState: top.yukonga.miuix.kmp.basic.SnackbarHostState? = null,
    pollIntervalMs: Long = 1000L,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        VorteXSandboxNotice.events.collect { notice ->
            if (snackbarHostState != null) {
                scope.launch {
                    snackbarHostState.showSnackbar(
                        message = notice.message,
                        duration = top.yukonga.miuix.kmp.basic.SnackbarDuration.Long,
                    )
                }
            } else {
                android.widget.Snackbar.make(
                    context.findViewById(android.R.id.content),
                    notice.message,
                    notice.duration,
                ).show()
            }
        }
    }

    // 轮询哨兵文件：脚本在 libterminal 进程里跑，只能以文件作为跨进程信使。
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (enabled) {
            VorteXSandbox.consumeStorageNotice(context)
            delay(pollIntervalMs)
        }
    }
}