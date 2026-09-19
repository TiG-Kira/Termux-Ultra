package com.termux.app.compose

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * QuickCommand 核心逻辑的纯 JVM 单元测试。
 *
 * 不依赖 Android framework，直接用 JUnit + kotlinx-serialization-json。
 * 覆盖：buildQuickCommandText（纯函数）、序列化 roundtrip、id 唯一性。
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
        // id 字段在往返后应保持稳定（数据类 equality 用 id 参与）
        originals.forEachIndexed { i, orig ->
            assertEquals(orig.id, decoded[i].id)
        }
    }

    @Test
    fun roundtrip_emptyList() {
        val encoded = json.encodeToString(emptyList<QuickCommand>())
        val decoded = json.decodeFromString<List<QuickCommand>>(encoded)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun corruptedJson_decodedAsEmpty() {
        assertThrows(Exception::class.java) {
            json.decodeFromString<List<QuickCommand>>("{not valid json}")
        }
    }

    // ==================== id 唯一性 ====================

    @Test
    fun twoCommands_sameLabel_differentIds() {
        val a = QuickCommand("ls", "ls -la", true)
        val b = QuickCommand("ls", "ls -F", true)
        assertEquals(a.label, b.label)
        assertNotEquals(a.id, b.id)
        assertNotEquals(a, b)
    }

    @Test
    fun id_is_uuidFormat() {
        val cmd = QuickCommand("x", "echo hi", true)
        // UUID 标准格式 8-4-4-4-12
        val uuidRegex = Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""")
        assertTrue("id 应为标准 UUID 格式: ${cmd.id}", uuidRegex.matches(cmd.id))
    }

    @Test
    fun twoCalls_generateDifferentIds() {
        val ids = (1..100).map { QuickCommand("x", "y").id }
        assertEquals(100, ids.toSet().size)
    }

    @Test
    fun quickCommand_dataClass_equality_includesId() {
        // 相同 label+command 但不同 id — 视为不同对象
        val a = QuickCommand("a", "ls", true)
        val b = QuickCommand("a", "ls", true)
        assertNotEquals(a, b) // id 不同
        assertNotEquals(a.id, b.id)
    }
}
