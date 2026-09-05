package com.termux.app.compose.terminal.engine.buffer

/**
 * 将列(Col)和行(Row)打包进单个 64 位 Long 中。
 * 零对象分配，规避 Pair<Int, Int> 和 IntArray 带来的 GC 压力。
 */
@JvmInline
internal value class CursorCoord(val packed: Long) {
    val col: Int get() = (packed ushr 32).toInt()
    val row: Int get() = packed.toInt()

    companion object {
        fun pack(col: Int, row: Int): CursorCoord {
            // 将 col 存入高 32 位，row 存入低 32 位（按位与防止负数扩展污染高位）
            return CursorCoord((col.toLong() shl 32) or (row.toLong() and 0xFFFFFFFFL))
        }
    }
}
