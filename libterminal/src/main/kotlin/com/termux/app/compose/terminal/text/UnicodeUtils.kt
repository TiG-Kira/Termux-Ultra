package com.termux.app.compose.terminal.text

/**
 * 针对 CharArray 安全提取 Unicode 码点（零对象分配）。
 * 自动处理 UTF-16 代理项对 (Surrogate Pairs) 和数组越界安全。
 *
 * @param index 当前遍历的索引
 * @param limit 数组的有效读取边界
 * @param block 提取成功后的回调，包含完整码点 (codePoint) 以及字符占用的索引数 (charCount: 1或2)
 */
internal inline fun <R> CharArray.withCodePointAt(
    index: Int,
    limit: Int = this.size,
    block: (codePoint: Int, charCount: Int) -> R
): R {
    val c = this[index]
    return if (c.isHighSurrogate() && index + 1 < limit) {
        block(Character.toCodePoint(c, this[index + 1]), 2)
    } else {
        block(c.code, 1)
    }
}

/**
 * 针对 CharSequence (String 等) 安全提取 Unicode 码点。
 */
internal inline fun <R> CharSequence.withCodePointAt(
    index: Int,
    limit: Int = this.length,
    block: (codePoint: Int, charCount: Int) -> R
): R {
    val c = this[index]
    return if (c.isHighSurrogate() && index + 1 < limit) {
        block(Character.toCodePoint(c, this[index + 1]), 2)
    } else {
        block(c.code, 1)
    }
}

/** 快速获取当前字符占用的 Char 数量（1或2） */
internal inline  fun CharArray.charCountAtSafe(index: Int, limit: Int = this.size): Int =
    if (this[index].isHighSurrogate() && index + 1 < limit) 2 else 1

internal inline fun CharSequence.charCountAtSafe(index: Int, limit: Int = this.length): Int =
    if (this[index].isHighSurrogate() && index + 1 < limit) 2 else 1

/**
 * 按终端显示列宽（WcWidth）遍历字符数组。
 *
 * @param limit 遍历边界（不含）
 * @param action 回调，接收：字符起始索引(index)，当前列号(col)，码点(codePoint)，显示宽度(width)，字符在数组中的长度(charCount)
 * @return 是否遍历到了末尾
 */
internal inline fun CharArray.forEachColumn(
    startIndex: Int = 0,
    limit: Int = this.size,
    action: (index: Int, col: Int, codePoint: Int, width: Int, charCount: Int) -> Boolean
) {
    var i = startIndex
    var col = 0
    while (i < limit) {
        this.withCodePointAt(i, limit) { cp, count ->
            val w = WcWidth.width(cp)
            if (!action(i, col, cp, w, count)) return
            if (w > 0) col += w
            i += count
        }
    }
}

/** 针对字符串或 CharSequence 扩展相同的方法 */
internal inline fun CharSequence.forEachColumn(
    startIndex: Int = 0,
    limit: Int = this.length,
    action: (index: Int, col: Int, codePoint: Int, width: Int, charCount: Int) -> Boolean
) {
    var i = startIndex
    var col = 0
    while (i < limit) {
        this.withCodePointAt(i, limit) { cp, count ->
            val w = WcWidth.width(cp)
            if (!action(i, col, cp, w, count)) return
            if (w > 0) col += w
            i += count
        }
    }
}