package com.termux.app.compose.terminal.view

import com.termux.app.compose.terminal.engine.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 会话事件订阅绑定器。
 *
 * 拥有会话相关的协程订阅（屏幕刷新事件流、OSC 剪贴板写入流），
 * 会话切换时负责取消旧订阅并建立新订阅，
 * 使 TerminalView 不再手工管理订阅 Job 的生命周期。
 */
internal class SessionBinder(
    private val scope: CoroutineScope,
    private val onScreenUpdated: () -> Unit,
    private val onCopiedText: (String) -> Unit
) {

    private var screenRefreshJob: Job? = null
    private var clipboardCollectJob: Job? = null

    /** 绑定会话：取消旧订阅；会话非空时建立新的事件流订阅。 */
    fun bind(session: TerminalSession?) {
        screenRefreshJob?.cancel()
        screenRefreshJob = null
        clipboardCollectJob?.cancel()
        clipboardCollectJob = null

        if (session != null) {
            screenRefreshJob = session.uiEvent
                .conflate()
                .onEach { onScreenUpdated() }
                .launchIn(scope)
            clipboardCollectJob = session.copiedText
                .onEach(onCopiedText)
                .launchIn(scope)
        }
    }
}
