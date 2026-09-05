package com.termux.app.compose.terminal.color

import com.termux.app.compose.terminal.text.TextStyle

/**
 * 终端颜色的稀疏覆盖板。
 *
 * 仅记录 shell 通过 OSC 序列动态改变过的颜色槽位，未覆盖的槽位回退到
 * [TerminalColorScheme] 主题基底。这是与主题解耦的关键：主题基底保持不变，
 * OSC 的改动是一层薄的差异，复位只需清空本板。
 */
internal class SparsePalette {
    private val values = IntArray(TextStyle.NUM_INDEXED_COLORS)
    private val overridden = BooleanArray(TextStyle.NUM_INDEXED_COLORS)

    /** 指定索引是否被 OSC 覆盖过。 */
    fun isOverridden(index: Int): Boolean = overridden[index]

    /** 取指定索引的覆盖值（仅当 [isOverridden] 为 true 时有效）。 */
    fun value(index: Int): Int = values[index]

    /** OSC 改色：写入覆盖值并标记。 */
    fun set(index: Int, color: Int) {
        values[index] = color
        overridden[index] = true
    }

    /** 复位单个索引的覆盖，恢复回退到主题基底。 */
    fun reset(index: Int) {
        overridden[index] = false
        values[index] = 0
    }

    /** 清空整块覆盖板，全部槽位回退到主题基底。 */
    fun resetAll() {
        overridden.fill(false)
        values.fill(0)
    }
}
