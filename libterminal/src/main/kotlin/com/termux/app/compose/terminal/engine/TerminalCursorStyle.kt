package com.termux.app.compose.terminal.engine

import androidx.annotation.Keep

/**
 * 终端光标样式。
 */
@Keep
enum class TerminalCursorStyle {
    BLOCK,
    UNDERLINE,
    BAR
}