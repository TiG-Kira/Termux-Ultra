package com.termux.app.compose.terminal.view

import com.termux.app.compose.terminal.color.SparsePalette
import com.termux.app.compose.terminal.color.TerminalColorScheme
import com.termux.app.compose.terminal.text.TextStyle

/**
 * 终端渲染侧的合成颜色查询对象。
 *
 * 将[主题基底][TerminalColorScheme]与 OSC 稀疏覆盖板([SparsePalette])合成：
 * 被 OSC 覆盖过的槽位取其覆盖值，否则回退到主题基底。渲染时按此查询取色，
 * 使 shell 的动态改色仅作为一层覆盖盖在主题之上，复位只清空覆盖板。
 */
internal class TerminalPaletteResolver(
    private val colorScheme: TerminalColorScheme,
    private val palette: SparsePalette
) {
    /** 取指定索引的最终展示色。 */
    fun color(index: Int): Int =
        if (palette.isOverridden(index)) palette.value(index) else colorScheme.color(index)

    /** 最终默认前景色。 */
    val foreground: Int get() = color(TextStyle.COLOR_INDEX_FOREGROUND)

    /** 最终默认背景色。 */
    val background: Int get() = color(TextStyle.COLOR_INDEX_BACKGROUND)

    /** 最终默认光标色。 */
    val cursor: Int get() = color(TextStyle.COLOR_INDEX_CURSOR)
}
