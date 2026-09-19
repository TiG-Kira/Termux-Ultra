package com.termux.app.compose

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * QuickCommand / QuickCommandStore 核心逻辑的纯 JVM 单元测试。
 *
 * 不依赖 Android framework，直接用 JUnit + kotlinx-serialization-json。
 * QuickCommandStore 本身只是 SharedPreferences 的薄封装，
 * 这里测试的是它依赖的 JSON 编解码和纯函数 buildQuickCommandText。
 */
class QuickCommandTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ==================== buildQuickCommandText ====================

    @Test
    fun buildQuickCommandText_autoExecute_true_appendsCarriageReturn() {
        val cmd = QuickCommand("ls", "ls -la", autoExecute = true)
        assertEquals("ls -la\r", buildQuickCommandText(cmd))
    }

    @Test
    fun buildQuickCommandText_autoExecute_false_pasteOnly() {
        val cmd = QuickCommand("vim", "vim /etc/hosts", autoExecute = false)
        assertEquals("vim /etc/hosts", buildQuickCommandText(cmd))
    }

    @Test
    fun buildQuickCommandText_emptyCommand_autoExecute_true() {
        val cmd = QuickCommand("empty", "", autoExecute = true)
        assertEquals("\r", buildQuickCommandText(cmd))
    }

    @Test
    fun buildQuickCommandText_commandWithExistingNewline_autoExecute_true() {
        val cmd = QuickCommand("weird", "ls\n", autoExecute = true)
        // shell 看到连续多个 \r 是空回车，无害
        assertEquals("ls\n\r", buildQuickCommandText(cmd))
    }

    // ==================== 序列化 roundtrip ====================

    @Test
    fun roundtrip_singleCommand() {
        val original = QuickCommand("status", "git status", true)
        val encoded = json.encodeToString(listOf(original))
        val decoded = json.decodeFromString<List<QuickCommand>>(encoded)
        assertEquals(listOf(original), decoded)
    }

    @Test
    fun roundtrip_multipleCommands_preservesOrder() {
        val originals = listOf(
            QuickCommand("ls", "ls -la", true),
            QuickCommand("bg-daemon", "systemctl --user start my-daemon", false),
            QuickCommand("empty-cmd", "", true)
        )
        val encoded = json.encodeToString(originals)
        val decoded = json.decodeFromString<List<QuickCommand>>(encoded)
        assertEquals(3, decoded.size)
        assertEquals(originals, decoded)
        assertFalse(decoded[1].autoExecute)
    }

    @Test
    fun roundtrip_emptyList() {
        val encoded = json.encodeToString(emptyList<QuickCommand>())
        val decoded = json.decodeFromString<List<QuickCommand>>(encoded)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun defaultAutoExecute_true_whenFieldOmitted() {
        // 老版本 JSON 里没有 autoExecute 字段，默认应为 true
        val raw = """[{"label":"legacy","command":"pwd"}]"""
        val list = json.decodeFromString<List<QuickCommand>>(raw)
        assertEquals(1, list.size)
        assertTrue(list[0].autoExecute)
    }

    @Test
    fun unknownJsonFields_ignored_gracefully() {
        val raw = """[{"label":"x","command":"ls","autoExecute":true,"weirdNewField":42}]"""
        val list = json.decodeFromString<List<QuickCommand>>(raw)
        assertEquals(1, list.size)
        assertEquals("x", list[0].label)
        assertEquals("ls", list[0].command)
        assertTrue(list[0].autoExecute)
    }

    @Test
    fun corruptedJson_decodedAsEmpty() {
        // JSON 损坏时 store.getAll() 会兜底返回空列表，
        // 直接 decode 会抛异常——store 层 catch 了这个。
        assertThrows(Exception::class.java) {
            json.decodeFromString<List<QuickCommand>>("{not valid json}")
        }
    }

    @Test
    fun quickCommand_dataClass_equality() {
        val a = QuickCommand("a", "ls", true)
        val b = QuickCommand("a", "ls", true)
        val c = QuickCommand("a", "ls", false)
        assertEquals(a, b)
        assertNotEquals(a, c)
    }
}
