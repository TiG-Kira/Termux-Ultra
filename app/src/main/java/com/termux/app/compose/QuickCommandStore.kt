package com.termux.app.compose

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一条快捷指令。
 *
 * @param id 稳定唯一 ID（UUID 字符串），用于列表 key 和删除依据，
 *           与 label 解耦，避免同名指令互相覆盖。
 * @param label 用户给指令起的名字（列表显示用）。
 * @param command 实际写入终端的命令文本（不含末尾换行）。
 * @param autoExecute 选择后是否自动追加 `\r` 执行；若为 false 则仅粘贴文本。
 */
@Serializable
data class QuickCommand(
    val id: String,
    val label: String,
    val command: String,
    val autoExecute: Boolean = true
)

/** 构造 QuickCommand 的便捷函数，自动生成 UUID。 */
fun QuickCommand(
    label: String,
    command: String,
    autoExecute: Boolean = true
): QuickCommand = QuickCommand(
    id = java.util.UUID.randomUUID().toString(),
    label = label,
    command = command,
    autoExecute = autoExecute
)

/**
 * 使用 SharedPreferences 持久化的快捷指令仓库。
 *
 * 以 JSON 数组形式存储在单个 key 上，避免多 key 遍历，
 * 也方便一次原子读写整个列表。整个类是线程安全的——底层
 * SharedPreferences 的 apply/commit 本身是同步的。
 */
class QuickCommandStore private constructor(
    private val prefs: SharedPreferences,
    private val json: Json
) {

    companion object {
        private const val PREFS_NAME = "quick_commands_store"
        private const val KEY_COMMANDS = "commands_json"

        @Volatile
        private var instance: QuickCommandStore? = null

        fun get(context: Context): QuickCommandStore {
            instance?.let { return it }
            synchronized(this) {
                instance?.let { return it }
                val prefs = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                instance = QuickCommandStore(
                    prefs,
                    Json { ignoreUnknownKeys = true; isLenient = true }
                )
                return instance!!
            }
        }
    }

    /**
     * 读取所有指令。始终返回一个 List（可能为空），不会抛解析异常。
     */
    fun getAll(): List<QuickCommand> {
        val raw = prefs.getString(KEY_COMMANDS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<QuickCommand>>(raw)
        } catch (_: Exception) {
            // JSON 损坏时兜底返回空列表，避免因数据问题导致 UI 崩溃
            emptyList()
        }
    }

    /**
     * 原子替换整个列表。内部使用 apply() 异步写盘，不会阻塞调用线程。
     */
    fun saveAll(items: List<QuickCommand>) {
        val jsonStr = json.encodeToString(items)
        prefs.edit().putString(KEY_COMMANDS, jsonStr).apply()
    }

    fun add(command: QuickCommand): List<QuickCommand> {
        val list = synchronized(this) {
            val l = getAll().toMutableList()
            l.add(command)
            saveAll(l)
            l
        }
        return list
    }

    fun remove(label: String): List<QuickCommand> {
        val list = synchronized(this) {
            val l = getAll().filter { it.label != label }
            saveAll(l)
            l
        }
        return list
    }

    fun removeById(id: String): List<QuickCommand> {
        val list = synchronized(this) {
            val l = getAll().filter { it.id != id }
            saveAll(l)
            l
        }
        return list
    }

    fun update(oldLabel: String, newItem: QuickCommand): List<QuickCommand> {
        val list = synchronized(this) {
            val l = getAll().toMutableList()
            val idx = l.indexOfFirst { it.label == oldLabel }
            if (idx >= 0) l[idx] = newItem else l.add(newItem)
            saveAll(l)
            l
        }
        return list
    }

    fun updateById(id: String, newItem: QuickCommand): List<QuickCommand> {
        val list = synchronized(this) {
            val l = getAll().toMutableList()
            val idx = l.indexOfFirst { it.id == id }
            if (idx >= 0) l[idx] = newItem else l.add(newItem)
            saveAll(l)
            l
        }
        return list
    }

    fun clear() {
        prefs.edit().remove(KEY_COMMANDS).apply()
    }
}

/**
 * 从 [context] 向上遍历 ContextWrapper 链，查找指定类型的 Activity。
 *
 * Compose 的 LocalContext.current 在某些场景下（如 Provider、ComposeView）
 * 可能不是 Activity 本身，而是 ContextWrapper。之前直接
 * `context as? TermuxActivity` 会静默失败。
 */
fun <T> findActivityFromContext(context: Context, clazz: Class<T>): T? {
    var c: Context? = context
    while (c is ContextWrapper) {
        if (clazz.isInstance(c)) return clazz.cast(c)
        c = c.baseContext
    }
    return null
}
