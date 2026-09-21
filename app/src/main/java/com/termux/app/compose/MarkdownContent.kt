package com.termux.app.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * GitHub Flavored Markdown (GFM) 渲染器。
 *
 * 用于「版本更新日志 / 版本更新弹窗」等需要展示 Release Notes 的位置，覆盖 GitHub 常用语法：
 * - 标题 `#` ~ `######`
 * - 强调 `**粗体**`、`*斜体*`、`***粗斜体***`、`~~删除线~~`
 * - 行内代码 `` `code` `` 与围栏代码块 ```lang ... ```
 * - 引用块 `>`（支持嵌套与块内其他语法）
 * - 无序 / 有序列表（按缩进嵌套）
 * - 任务列表 `- [ ]` / `- [x]`
 * - 链接 `[text](url)`、图片 `![alt](url)`、自动链接 `<url>`
 * - 分隔线 `---` / `***` / `___`
 * - 表格 `| a | b |`（含对齐行 `:---:`）
 * - 反斜杠转义
 *
 * 只依赖 Compose Foundation，不引入 Material / Material3 组件，避免与 Miuix 混用。
 */

private const val ALIGN_NONE = -1
private const val ALIGN_LEFT = 0
private const val ALIGN_CENTER = 1
private const val ALIGN_RIGHT = 2

// ---------------------------------------------------------------------------
// 语法树
// ---------------------------------------------------------------------------

private sealed interface MdInline {
    data class Plain(val text: String) : MdInline
    data class Strong(val children: List<MdInline>) : MdInline
    data class Emphasis(val children: List<MdInline>) : MdInline
    data class Strike(val children: List<MdInline>) : MdInline
    data class Code(val text: String) : MdInline
    data class Link(val children: List<MdInline>, val url: String) : MdInline
    data object LineBreak : MdInline
}

private sealed interface MdBlock {
    data class Heading(val level: Int, val inline: List<MdInline>) : MdBlock
    data class Paragraph(val inline: List<MdInline>) : MdBlock
    data class Fence(val code: String, val language: String) : MdBlock
    data class Quote(val children: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val items: List<MdListItem>) : MdBlock
    data class Table(
        val header: List<List<MdInline>>,
        val align: List<Int>,
        val rows: List<List<List<MdInline>>>
    ) : MdBlock
    data object Divider : MdBlock
}

private data class MdListItem(
    val depth: Int,
    val marker: String,
    val checked: Boolean?,
    val inline: List<MdInline>
)

// ---------------------------------------------------------------------------
// 块级解析
// ---------------------------------------------------------------------------

private val RE_FENCE = Regex("^(`{3,}|~{3,})\\s*([A-Za-z0-9+#_.-]*)\\s*$")
private val RE_HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val RE_HR = Regex("^(?:-{3,}|\\*{3,}|_{3,})$")
private val RE_LIST_ITEM = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)$")
private val RE_TABLE_DELIM = Regex("^\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?$")
private val RE_TASK = Regex("^\\[([ xX])\\]\\s+")

private fun parseMarkdownBlocks(source: String): List<MdBlock> {
    val normalized = source
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace("\t", "    ")
    val lines = normalized.split('\n')
    return parseBlocks(lines, 0, lines.size)
}

private fun parseBlocks(lines: List<String>, start: Int, end: Int): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    var i = start
    while (i < end) {
        val trimmed = lines[i].trim()
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // 围栏代码块
        val fence = RE_FENCE.matchEntire(trimmed)
        if (fence != null) {
            val closing = fence.groupValues[1]
            val language = fence.groupValues[2]
            i++
            val code = StringBuilder()
            while (i < end) {
                val line = lines[i]
                if (line.trimStart().startsWith(closing)) {
                    i++
                    break
                }
                code.append(line).append('\n')
                i++
            }
            blocks.add(MdBlock.Fence(code.toString().trimEnd('\n'), language))
            continue
        }

        // 标题
        val heading = RE_HEADING.matchEntire(trimmed)
        if (heading != null) {
            val level = heading.groupValues[1].length.coerceAtMost(6)
            val text = heading.groupValues[2].trim().trimEnd('#').trim()
            blocks.add(MdBlock.Heading(level, parseInline(text)))
            i++
            continue
        }

        // 分隔线
        if (RE_HR.matches(trimmed)) {
            blocks.add(MdBlock.Divider)
            i++
            continue
        }

        // 引用块
        if (trimmed.startsWith(">")) {
            val inner = mutableListOf<String>()
            while (i < end) {
                val line = lines[i].trimStart()
                if (!line.startsWith(">")) break
                var rest = line.substring(1)
                if (rest.startsWith(" ")) rest = rest.substring(1)
                inner.add(rest)
                i++
            }
            blocks.add(MdBlock.Quote(parseBlocks(inner, 0, inner.size)))
            continue
        }

        // 表格（当前行含 | 且下一行是对齐行）
        if (trimmed.contains('|') && i + 1 < end && RE_TABLE_DELIM.matches(lines[i + 1].trim())) {
            val header = splitTableRow(trimmed)
            val align = splitTableRow(lines[i + 1].trim()).map { cell ->
                val c = cell.trim()
                when {
                    c.startsWith(":") && c.endsWith(":") -> ALIGN_CENTER
                    c.endsWith(":") -> ALIGN_RIGHT
                    c.startsWith(":") -> ALIGN_LEFT
                    else -> ALIGN_NONE
                }
            }
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < end) {
                val row = lines[i].trim()
                if (row.isEmpty() || !row.contains('|')) break
                rows.add(splitTableRow(row))
                i++
            }
            blocks.add(
                MdBlock.Table(
                    header = header.map { parseInline(it.trim()) },
                    align = align,
                    rows = rows.map { row -> row.map { parseInline(it.trim()) } }
                )
            )
            continue
        }

        // 列表
        val firstItem = RE_LIST_ITEM.find(lines[i])
        if (firstItem != null) {
            val listOrdered = firstItem.groupValues[2][0].isDigit()
            val items = mutableListOf<MdListItem>()
            val orderedCounters = HashMap<Int, Int>()
            while (i < end) {
                val line = lines[i]
                if (line.isBlank()) {
                    // 空行：仅当后面仍有列表项时才继续（宽松列表）
                    if (i + 1 < end && RE_LIST_ITEM.find(lines[i + 1]) != null) {
                        i++
                        continue
                    }
                    break
                }
                val match = RE_LIST_ITEM.find(line)
                if (match == null) {
                    // 缩进续行：并入上一个列表项
                    if (items.isEmpty()) break
                    val last = items.last()
                    items[items.lastIndex] = last.copy(
                        inline = mergeInline(last.inline, parseInline(line.trim()))
                    )
                    i++
                    continue
                }
                val indent = match.groupValues[1].length
                val depth = indent / 2
                val token = match.groupValues[2]
                val ordered = token[0].isDigit()
                var content = match.groupValues[3]
                var checked: Boolean? = null
                val task = RE_TASK.find(content)
                if (task != null) {
                    checked = task.groupValues[1].equals("x", ignoreCase = true)
                    content = content.substring(task.range.last + 1).trimStart()
                }
                val marker = if (ordered) {
                    val start = token.trimEnd('.', ')').toIntOrNull() ?: 1
                    val number = orderedCounters[depth] ?: start
                    orderedCounters[depth] = number + 1
                    "$number."
                } else {
                    when (depth % 3) {
                        0 -> "\u2022"
                        1 -> "\u25E6"
                        else -> "\u25AA"
                    }
                }
                items.add(
                    MdListItem(
                        depth = depth,
                        marker = marker,
                        checked = checked,
                        inline = parseInline(content)
                    )
                )
                i++
            }
            blocks.add(MdBlock.ListBlock(ordered = listOrdered, items = items))
            continue
        }

        // 段落
        val paragraph = StringBuilder()
        while (i < end) {
            val line = lines[i]
            val t = line.trim()
            if (t.isEmpty() || isBlockStart(t, lines, i, end)) break
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(t)
            i++
        }
        blocks.add(MdBlock.Paragraph(parseInline(paragraph.toString())))
    }
    return blocks
}

private fun isBlockStart(trimmed: String, lines: List<String>, index: Int, end: Int): Boolean {
    if (RE_HEADING.matchEntire(trimmed) != null) return true
    if (RE_FENCE.matchEntire(trimmed) != null) return true
    if (RE_HR.matches(trimmed)) return true
    if (trimmed.startsWith(">")) return true
    if (RE_LIST_ITEM.find(trimmed) != null) return true
    if (trimmed.contains('|') && index + 1 < end && RE_TABLE_DELIM.matches(lines[index + 1].trim())) return true
    return false
}

private fun splitTableRow(row: String): List<String> {
    var text = row.trim()
    if (text.startsWith("|")) text = text.substring(1)
    if (text.endsWith("|") && text.length > 1) text = text.substring(0, text.length - 1)
    if (text.isEmpty()) return emptyList()
    return text.split("|").map { it.trim() }
}

private fun mergeInline(a: List<MdInline>, b: List<MdInline>): List<MdInline> {
    val merged = a.toMutableList()
    merged.add(MdInline.Plain(" "))
    merged.addAll(b)
    return merged
}

// ---------------------------------------------------------------------------
// 行内解析
// ---------------------------------------------------------------------------

private val ESCAPABLE = setOf(
    '\\', '`', '*', '_', '{', '}', '[', ']', '(', ')', '#', '+', '-', '.',
    '!', '>', '~', '|', '$', ':', '"', '\'', '/', '=', '&', '<'
)

private val RE_INLINE_CODE = Regex("(`+)([\\s\\S]*?)\\1")
private val RE_IMAGE_LINK = Regex("!\\[([^\\]]*)\\]\\(\\s*<?([^\\s>]*)>?(?:\\s+\"[^\"]*\")?\\s*\\)")
private val RE_LINK = Regex("\\[([^\\]]*)\\]\\(\\s*<?([^\\s>]*)>?(?:\\s+\"[^\"]*\")?\\s*\\)")
private val RE_AUTOLINK = Regex("<((?:https?|ftp|mailto)://[^>\\s]+|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})>")
private val RE_LINE_BREAK_TAG = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val RE_STRONG_EM = Regex("\\*\\*\\*(?=\\S)([\\s\\S]*?\\S)\\*\\*\\*")
private val RE_STRONG_STAR = Regex("\\*\\*(?=\\S)([\\s\\S]*?\\S)\\*\\*")
private val RE_STRONG_UNDER = Regex("__(?=\\S)([\\s\\S]*?\\S)__")
private val RE_STRIKE = Regex("~~(?=\\S)([\\s\\S]*?\\S)~~")
private val RE_EM_STAR = Regex("\\*(?=[^\\s*])([\\s\\S]*?)\\*")
private val RE_EM_UNDER = Regex("_(?=[^\\s_])([\\s\\S]*?)_")
private val RE_BARE_URL = Regex("https?://[^\\s<>()\\[\\]]+")

private const val MAX_INLINE_DEPTH = 8

private fun parseInline(source: String, depth: Int = 0): List<MdInline> {
    if (source.isEmpty()) return emptyList()
    val nodes = mutableListOf<MdInline>()
    val plain = StringBuilder()
    var i = 0

    fun flush() {
        if (plain.isNotEmpty()) {
            nodes.add(MdInline.Plain(plain.toString()))
            plain.clear()
        }
    }

    fun childrenOf(raw: String): List<MdInline> =
        if (depth < MAX_INLINE_DEPTH) parseInline(raw, depth + 1) else listOf(MdInline.Plain(raw))

    while (i < source.length) {
        val ch = source[i]

        // 反斜杠转义
        if (ch == '\\' && i + 1 < source.length && source[i + 1] in ESCAPABLE) {
            plain.append(source[i + 1])
            i += 2
            continue
        }

        // 行内代码（优先匹配，避免内部符号被当作语法）
        if (ch == '`') {
            val m = RE_INLINE_CODE.matchAt(source, i)
            if (m != null) {
                flush()
                nodes.add(MdInline.Code(m.groupValues[2].trim(' ')))
                i = m.range.last + 1
                continue
            }
        }

        // 图片
        if (ch == '!' && i + 1 < source.length && source[i + 1] == '[') {
            val m = RE_IMAGE_LINK.matchAt(source, i)
            if (m != null) {
                flush()
                val alt = m.groupValues[1].ifBlank { "image" }
                nodes.add(MdInline.Link(listOf(MdInline.Plain(alt)), m.groupValues[2]))
                i = m.range.last + 1
                continue
            }
        }

        // 链接
        if (ch == '[') {
            val m = RE_LINK.matchAt(source, i)
            if (m != null) {
                flush()
                nodes.add(MdInline.Link(childrenOf(m.groupValues[1]), m.groupValues[2]))
                i = m.range.last + 1
                continue
            }
        }

        if (ch == '<') {
            // 自动链接 <https://...> / <mail@...>
            val auto = RE_AUTOLINK.matchAt(source, i)
            if (auto != null) {
                flush()
                val target = auto.groupValues[1]
                val url = if (target.contains("@") && !target.contains("://")) "mailto:$target" else target
                nodes.add(MdInline.Link(listOf(MdInline.Plain(target)), url))
                i = auto.range.last + 1
                continue
            }
            // <br> / <br/>
            val br = RE_LINE_BREAK_TAG.matchAt(source, i)
            if (br != null) {
                flush()
                nodes.add(MdInline.LineBreak)
                i = br.range.last + 1
                continue
            }
        }

        // 强调
        if (ch == '*' || ch == '_' || ch == '~') {
            if (ch == '*') {
                val triple = RE_STRONG_EM.matchAt(source, i)
                if (triple != null) {
                    flush()
                    nodes.add(MdInline.Strong(listOf(MdInline.Emphasis(childrenOf(triple.groupValues[1])))))
                    i = triple.range.last + 1
                    continue
                }
            }
            val strong = when (ch) {
                '*' -> RE_STRONG_STAR.matchAt(source, i)
                '_' -> RE_STRONG_UNDER.matchAt(source, i)
                else -> null
            }
            if (strong != null) {
                flush()
                nodes.add(MdInline.Strong(childrenOf(strong.groupValues[1])))
                i = strong.range.last + 1
                continue
            }
            if (ch == '~') {
                val strike = RE_STRIKE.matchAt(source, i)
                if (strike != null) {
                    flush()
                    nodes.add(MdInline.Strike(childrenOf(strike.groupValues[1])))
                    i = strike.range.last + 1
                    continue
                }
            } else {
                // GFM 规则：单词内部的 `_` 不构成强调（避免 snake_case 被斜体化）
                val prev = if (i > 0) source[i - 1] else ' '
                val allowUnder = ch != '_' || !prev.isLetterOrDigit()
                if (allowUnder) {
                    val em = if (ch == '*') RE_EM_STAR.matchAt(source, i) else RE_EM_UNDER.matchAt(source, i)
                    if (em != null) {
                        flush()
                        nodes.add(MdInline.Emphasis(childrenOf(em.groupValues[1])))
                        i = em.range.last + 1
                        continue
                    }
                }
            }
        }

        // 裸链接（GFM autolink）
        if (ch == 'h' || ch == 'H') {
            val m = RE_BARE_URL.matchAt(source, i)
            if (m != null) {
                flush()
                val url = m.groupValues[0]
                nodes.add(MdInline.Link(listOf(MdInline.Plain(url)), url))
                i = m.range.last + 1
                continue
            }
        }

        plain.append(ch)
        i++
    }
    flush()
    return nodes
}

// ---------------------------------------------------------------------------
// 渲染
// ---------------------------------------------------------------------------

private data class MdColors(
    val text: Color,
    val heading: Color,
    val muted: Color,
    val link: Color,
    val codeBackground: Color,
    val codeText: Color,
    val quoteBar: Color,
    val divider: Color,
    val tableHeader: Color
)

/**
 * 以 GitHub Flavored Markdown 方式渲染 [text]。
 */
@Composable
fun MarkdownContent(
    text: String,
    modifier: Modifier = Modifier,
    bodyFontSizeSp: Int = 13
) {
    val colors = MdColors(
        text = MiuixTheme.colorScheme.onSurface,
        heading = MiuixTheme.colorScheme.onSurface,
        muted = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        link = MiuixTheme.colorScheme.primary,
        codeBackground = MiuixTheme.colorScheme.surfaceVariant,
        codeText = MiuixTheme.colorScheme.onSurface,
        quoteBar = MiuixTheme.colorScheme.primary,
        divider = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.35f),
        tableHeader = MiuixTheme.colorScheme.surfaceVariant
    )
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(modifier = modifier) {
        blocks.forEach { block -> RenderBlock(block, colors, bodyFontSizeSp) }
    }
}

@Composable
private fun RenderBlock(block: MdBlock, colors: MdColors, bodySize: Int) {
    when (block) {
        is MdBlock.Heading -> {
            val size = when (block.level) {
                1 -> 20
                2 -> 18
                3 -> 16
                4 -> 15
                5 -> 14
                else -> 13
            }
            MarkdownRichText(
                inline = block.inline,
                colors = colors,
                style = TextStyle(
                    fontSize = size.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.heading
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (block.level <= 2) 12.dp else 8.dp, bottom = 4.dp)
            )
        }

        is MdBlock.Paragraph -> {
            MarkdownRichText(
                inline = block.inline,
                colors = colors,
                style = TextStyle(fontSize = bodySize.sp, color = colors.text),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
        }

        is MdBlock.Fence -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.codeBackground)
                    .padding(10.dp)
            ) {
                Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    BasicText(
                        text = block.code,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.codeText
                        ),
                        softWrap = false
                    )
                }
            }
        }

        is MdBlock.Quote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(colors.quoteBar)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                ) {
                    block.children.forEach { child -> RenderBlock(child, colors, bodySize) }
                }
            }
        }

        is MdBlock.ListBlock -> {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                block.items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (item.depth * 14).dp, top = 1.dp, bottom = 1.dp)
                    ) {
                        BasicText(
                            text = if (item.checked != null) {
                                if (item.checked) "\u2611" else "\u2610"
                            } else {
                                item.marker
                            },
                            style = TextStyle(fontSize = bodySize.sp, color = colors.muted),
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        MarkdownRichText(
                            inline = item.inline,
                            colors = colors,
                            style = TextStyle(fontSize = bodySize.sp, color = colors.text),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        is MdBlock.Table -> RenderTable(block, colors, bodySize)

        MdBlock.Divider -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .height(1.dp)
                    .background(colors.divider)
            )
        }
    }
}

@Composable
private fun RenderTable(block: MdBlock.Table, colors: MdColors, bodySize: Int) {
    val columnCount = block.header.size.coerceAtLeast(1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                .background(colors.tableHeader)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            block.header.forEachIndexed { index, cell ->
                MarkdownRichText(
                    inline = cell,
                    colors = colors,
                    style = TextStyle(
                        fontSize = bodySize.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.text
                    ),
                    textAlign = tableAlign(block.align.getOrElse(index) { ALIGN_NONE }),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        block.rows.forEach { row ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.divider)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                for (index in 0 until columnCount) {
                    MarkdownRichText(
                        inline = row.getOrElse(index) { emptyList() },
                        colors = colors,
                        style = TextStyle(fontSize = bodySize.sp, color = colors.text),
                        textAlign = tableAlign(block.align.getOrElse(index) { ALIGN_NONE }),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.divider)
        )
    }
}

private fun tableAlign(value: Int): TextAlign = when (value) {
    ALIGN_CENTER -> TextAlign.Center
    ALIGN_RIGHT -> TextAlign.Right
    else -> TextAlign.Start
}

@Composable
private fun MarkdownRichText(
    inline: List<MdInline>,
    colors: MdColors,
    style: TextStyle,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start
) {
    val annotated = remember(inline, colors, style, textAlign) { buildInlineString(inline, colors) }
    BasicText(
        text = annotated,
        style = style.copy(textAlign = textAlign),
        modifier = modifier
    )
}

private fun buildInlineString(nodes: List<MdInline>, colors: MdColors): AnnotatedString =
    buildAnnotatedString { appendInline(nodes, colors) }

private fun AnnotatedString.Builder.appendInline(nodes: List<MdInline>, colors: MdColors) {
    for (node in nodes) {
        when (node) {
            is MdInline.Plain -> append(node.text.replace('\n', ' '))
            is MdInline.Strong -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInline(node.children, colors)
                pop()
            }
            is MdInline.Emphasis -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendInline(node.children, colors)
                pop()
            }
            is MdInline.Strike -> {
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                appendInline(node.children, colors)
                pop()
            }
            is MdInline.Code -> {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = colors.codeBackground,
                        color = colors.codeText,
                        fontSize = 12.sp
                    )
                )
                append(node.text)
                pop()
            }
            is MdInline.Link -> {
                withLink(LinkAnnotation.Url(node.url)) {
                    pushStyle(
                        SpanStyle(
                            color = colors.link,
                            textDecoration = TextDecoration.Underline
                        )
                    )
                    appendInline(node.children, colors)
                    pop()
                }
            }
            MdInline.LineBreak -> append('\n')
        }
    }
}
