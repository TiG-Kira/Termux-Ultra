package com.termux.app.compose

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource

/**
 * TextEditorScreen 语法高亮引擎。
 *
 * 基于文件扩展名识别语言（支持 Bash/Zsh/Python/Kotlin/Java/C/C++/Rust/Go/JS/TS/JSON/YAML/Markdown），
 * 用正则把文本切分成 token（关键字/字符串/注释/数字/符号），
 * 返回 List<HighlightToken(start, end, type)> 供 BasicTextField 叠加 SpanStyle 绘制。
 */
object SyntaxHighlighter {

    data class Token(val start: Int, val end: Int, val type: Type)
    enum class Type { KEYWORD, STRING, COMMENT, NUMBER, FUNCTION, OPERATOR, BRACE, DEFAULT }

    private val KEYWORDS_SHELL = setOf(
        "if","then","else","elif","fi","for","in","do","done","while","until","case","esac",
        "function","return","break","continue","exit","echo","export","source","true","false"
    )
    private val KEYWORDS_PYTHON = setOf(
        "def","class","import","from","as","if","elif","else","for","while","break","continue",
        "return","pass","lambda","try","except","finally","raise","with","yield","global","nonlocal",
        "in","is","not","and","or","None","True","False","async","await","self"
    )
    private val KEYWORDS_KOTLIN = setOf(
        "fun","val","var","class","interface","object","import","package","if","else","for","while",
        "do","break","continue","return","when","try","catch","finally","throw","typeof","is","in",
        "as","this","super","null","true","false","lambda","data","sealed","abstract","open",
        "override","companion","const","lateinit","by","get","set","init"
    )
    private val KEYWORDS_JAVA = setOf(
        "class","interface","enum","import","package","public","private","protected","static","final",
        "abstract","void","int","long","short","byte","char","float","double","boolean","new","return",
        "if","else","for","while","do","break","continue","switch","case","default","try","catch",
        "finally","throw","throws","this","super","null","true","false","instanceof","synchronized"
    )
    private val KEYWORDS_CLIKE = setOf(
        "include","using","namespace","typedef","struct","enum","union","const","static","volatile",
        "extern","inline","signed","unsigned","sizeof","int","long","short","char","float","double",
        "void","return","if","else","for","while","do","break","continue","switch","case","default",
        "try","catch","throw","new","delete","this","null","true","false","class","struct","template",
        "typename","virtual","override","public","private","protected","#define","#include","#ifdef","#ifndef",
        "fn","let","mut","pub","use","mod","impl","trait","where","move","ref","box","dyn","async","await"
    )

    fun detectLanguage(file: File?): String {
        val name = file?.name?.lowercase() ?: return "plain"
        return when {
            name.endsWith(".sh") || name.endsWith(".bash") || name.endsWith(".zsh") -> "bash"
            name.endsWith(".py") -> "python"
            name.endsWith(".kt") || name.endsWith(".kts") -> "kotlin"
            name.endsWith(".java") -> "java"
            name.endsWith(".c") || name.endsWith(".h") -> "c"
            name.endsWith(".cpp") || name.endsWith(".cc") || name.endsWith(".hpp") || name.endsWith(".cxx") -> "cpp"
            name.endsWith(".rs") -> "rust"
            name.endsWith(".go") -> "go"
            name.endsWith(".js") || name.endsWith(".jsx") -> "javascript"
            name.endsWith(".ts") || name.endsWith(".tsx") -> "typescript"
            name.endsWith(".json") -> "json"
            name.endsWith(".yaml") || name.endsWith(".yml") -> "yaml"
            name.endsWith(".md") || name.endsWith(".markdown") -> "markdown"
            name.endsWith(".xml") -> "xml"
            name.endsWith(".gradle") || name.endsWith(".gradle.kts") -> "gradle"
            else -> "plain"
        }
    }

    fun highlight(text: String, language: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val kw = when (language) {
            "bash" -> KEYWORDS_SHELL
            "python" -> KEYWORDS_PYTHON
            "kotlin" -> KEYWORDS_KOTLIN
            "java" -> KEYWORDS_JAVA
            "c","cpp","rust","go","javascript","typescript","json","gradle" -> KEYWORDS_CLIKE
            else -> emptySet()
        }
        tokens.addAll(splitTokens(text, kw))
        tokens.sortBy { it.start }
        return tokens
    }

    private fun splitTokens(text: String, keywords: Set<String>): List<Token> {
        val result = mutableListOf<Token>()
        val len = text.length
        var i = 0
        while (i < len) {
            val c = text[i]
            when {
                // 注释 // 或 #
                (c == '/' && i+1 < len && text[i+1] == '/') || (c == '#') -> {
                    var j = i
                    while (j < len && text[j] != '\n') j++
                    result.add(Token(i, j, Type.COMMENT))
                    i = j
                }
                // 注释 /* */
                c == '/' && i+1 < len && text[i+1] == '*' -> {
                    var j = i + 2
                    while (j < len-1 && !(text[j] == '*' && text[j+1] == '/')) j++
                    j = minOf(j + 2, len)
                    result.add(Token(i, j, Type.COMMENT))
                    i = j
                }
                // 字符串 "..." 或 '...'
                c == '"' || c == '\'' || c == '`' -> {
                    val quote = c
                    var j = i + 1
                    while (j < len && text[j] != quote) {
                        if (text[j] == '\\') j++
                        j++
                    }
                    j = minOf(j + 1, len)
                    result.add(Token(i, j, Type.STRING))
                    i = j
                }
                // 数字
                c.isDigit() -> {
                    var j = i
                    while (j < len && (text[j].isDigit() || text[j] == '.' || text[j] == '_' || text[j] in "xXbBeE")) j++
                    result.add(Token(i, j, Type.NUMBER))
                    i = j
                }
                // 标识符（可能是关键字/函数）
                c.isLetter() || c == '_' || c == '@' -> {
                    var j = i
                    while (j < len && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                    val word = text.substring(i, j)
                    val type = when {
                        word in keywords -> Type.KEYWORD
                        j < len && text[j] == '(' -> Type.FUNCTION
                        else -> Type.DEFAULT
                    }
                    result.add(Token(i, j, type))
                    i = j
                }
                else -> {
                    result.add(Token(i, i+1, Type.DEFAULT))
                    i++
                }
            }
        }
        return result
    }

    fun fileInfo(file: File?): String {
        if (file == null) return "新建文件"
        val ext = file.extension.ifBlank { "(无扩展名)" }
        val size = file.length()
        val sizeStr = when {
            size < 1024 -> "$size B"
            size < 1024*1024 -> "${size/1024} KB"
            else -> "${size/1024/1024} MB"
        }
        val date = try {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
        } catch (_: Exception) { "" }
        return "$ext · $sizeStr · $date"
    }

    fun permissions(file: File?): String {
        if (file == null) return ""
        val sb = StringBuilder()
        sb.append(if (file.canRead()) 'r' else '-')
        sb.append(if (file.canWrite()) 'w' else '-')
        sb.append(if (file.canExecute()) 'x' else '-')
        return sb.toString()
    }
}
