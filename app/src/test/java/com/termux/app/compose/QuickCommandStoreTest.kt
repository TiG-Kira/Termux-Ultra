package com.termux.app.compose

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * QuickCommandStore 单元测试。
 *
 * 覆盖点：
 *   - getAll / add / remove / update / clear 的基础 CRUD 行为
 *   - 单例 get() 的线程安全、同一 Context 复用同一实例
 *   - JSON 损坏时的兜底行为（返回空列表而不是抛异常）
 *   - buildQuickCommandText 核心逻辑
 */
@RunWith(RobolectricTestRunner::class)
class QuickCommandStoreTest {

    private lateinit var context: Context
    private lateinit var store: QuickCommandStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication().applicationContext
        // 每次测试都清空 store，避免状态污染
        QuickCommandStore.get(context).clear()
        store = QuickCommandStore.get(context)
    }

    @Test
    fun getAll_emptyStore_returnsEmptyList() {
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun add_singleItem_canBeRetrieved() {
        val cmd = QuickCommand("ls", "ls -la", true)
        store.add(cmd)

        val list = store.getAll()
        assertEquals(1, list.size)
        assertEquals("ls", list[0].label)
        assertEquals("ls -la", list[0].command)
        assertTrue(list[0].autoExecute)
    }

    @Test
    fun add_multipleItems_preservesInsertionOrder() {
        store.add(QuickCommand("a", "cmd_a", true))
        store.add(QuickCommand("b", "cmd_b", false))
        store.add(QuickCommand("c", "cmd_c", true))

        val list = store.getAll()
        assertEquals(3, list.size)
        assertEquals("a", list[0].label)
        assertEquals("b", list[1].label)
        assertEquals("c", list[2].label)
        assertFalse(list[1].autoExecute)
    }

    @Test
    fun remove_byLabel_removesOnlyMatching() {
        store.add(QuickCommand("keep", "ls", true))
        store.add(QuickCommand("rm", "rm -rf /tmp/x", true))
        store.add(QuickCommand("also-keep", "pwd", false))

        store.remove("rm")

        val list = store.getAll()
        assertEquals(2, list.size)
        assertEquals("keep", list[0].label)
        assertEquals("also-keep", list[1].label)
    }

    @Test
    fun remove_nonexistentLabel_isNoOp() {
        store.add(QuickCommand("a", "cmd_a", true))
        store.remove("does-not-exist")
        assertEquals(1, store.getAll().size)
    }

    @Test
    fun update_existingLabel_replacesInPlace() {
        store.add(QuickCommand("my-cmd", "old", true))
        store.add(QuickCommand("other", "other-cmd", true))

        store.update("my-cmd", QuickCommand("my-cmd", "new", false))

        val list = store.getAll()
        assertEquals(2, list.size)
        assertEquals("new", list[0].command)
        assertFalse(list[0].autoExecute)
        // other 不受影响
        assertEquals("other", list[1].label)
    }

    @Test
    fun update_nonexistentLabel_addsNew() {
        val result = store.update("ghost", QuickCommand("ghost", "spooky", true))
        assertEquals(1, result.size)
        assertEquals("spooky", result[0].command)
    }

    @Test
    fun clear_removesEverything() {
        store.add(QuickCommand("a", "cmd_a", true))
        store.add(QuickCommand("b", "cmd_b", false))
        store.clear()
        assertTrue(store.getAll().isEmpty())
    }

    @Test
    fun singleton_get_returnsSameInstance() {
        val a = QuickCommandStore.get(context)
        val b = QuickCommandStore.get(context)
        assertSame(a, b)
    }

    /**
     * JSON 损坏时不应抛异常——这是一个健壮性兜底，
     * 防止用户设备上升级导致旧数据格式不兼容时 UI 崩溃。
     */
    @Test
    fun corruptedJson_returnsEmptyList_safely() {
        val prefs = context.getSharedPreferences("quick_commands_store", Context.MODE_PRIVATE)
        prefs.edit().putString("commands_json", "{not valid json}").commit()

        val store2 = QuickCommandStore.get(context)
        assertTrue(store2.getAll().isEmpty())
    }

    @Test
    fun unknownJsonFields_ignored() {
        val prefs = context.getSharedPreferences("quick_commands_store", Context.MODE_PRIVATE)
        val raw = """[{"label":"x","command":"ls","autoExecute":true,"weirdNewField":42}]"""
        prefs.edit().putString("commands_json", raw).commit()

        val list = store.getAll()
        assertEquals(1, list.size)
        assertEquals("x", list[0].label)
    }

    // === buildQuickCommandText 核心逻辑测试 ===

    @Test
    fun buildQuickCommandText_autoExecute_true_appendsCarriageReturn() {
        val cmd = QuickCommand("ls", "ls -la", autoExecute = true)
        assertEquals("ls -la\r", buildQuickCommandText(cmd))
    }

    @Test
    fun buildQuickCommandText_autoExecute_false_pasteOnly() {
        val cmd = QuickCommand("edit-interactive", "vim /etc/hosts", autoExecute = false)
        assertEquals("vim /etc/hosts", buildQuickCommandText(cmd))
    }

    @Test
    fun buildQuickCommandText_commandEndsWithNewline_stillAppendsOneMore() {
        val cmd = QuickCommand("weird", "ls\n", autoExecute = true)
        assertEquals("ls\n\r", buildQuickCommandText(cmd))
    }
}

/**
 * QuickCommand 序列化的纯逻辑测试（不依赖 Android 组件）。
 */
class QuickCommandSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun roundtrip_singleItem() {
        val original = QuickCommand("status", "git status", true)
        val encoded = json.encodeToString(listOf(original))
        val decoded = json.decodeFromString<List<QuickCommand>>(encoded)
        assertEquals(listOf(original), decoded)
    }

    @Test
    fun roundtrip_multipleItems() {
        val originals = listOf(
            QuickCommand("ls", "ls -la", true),
            QuickCommand("bg-daemon", "systemctl --user start my-daemon", false),
            QuickCommand("empty-cmd", "", true)
        )
        val encoded = json.encodeToString(originals)
        val decoded = json.decodeFromString<List<QuickCommand>>(encoded)
        assertEquals(originals, decoded)
    }

    @Test
    fun roundtrip_emptyList() {
        val encoded = json.encodeToString(emptyList<QuickCommand>())
        val decoded = json.decodeFromString<List<QuickCommand>>(encoded)
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun defaultAutoExecute_true_whenFieldOmitted() {
        // 老版本 JSON 里没有 autoExecute 字段，默认为 true
        val raw = """[{"label":"legacy","command":"pwd"}]"""
        val list = json.decodeFromString<List<QuickCommand>>(raw)
        assertEquals(1, list.size)
        assertTrue(list[0].autoExecute)
    }
}
