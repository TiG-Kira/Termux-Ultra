package com.termux.app.compose

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 一条快捷指令。
 *
 * @param label 用户给指令起的名字（列表显示用）。
 * @param command 实际写入终端的命令文本（不含末尾换行）。
 * @param autoExecute 选择后是否自动追加 `\r` 执行；若为 false 则仅粘贴文本。
 */
@Serializable
data class QuickCommand(
    val label: String,
    val command: String,
    val autoExecute: Boolean = true
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
        val list = getAll().toMutableList()
        list.add(command)
        saveAll(list)
        return list
    }

    fun remove(label: String): List<QuickCommand> {
        val list = getAll().filter { it.label != label }
        saveAll(list)
        return list
    }

    fun update(oldLabel: String, newItem: QuickCommand): List<QuickCommand> {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.label == oldLabel }
        if (idx >= 0) list[idx] = newItem else list.add(newItem)
        saveAll(list)
        return list
    }

    fun clear() {
        prefs.edit().remove(KEY_COMMANDS).apply()
    }
}
