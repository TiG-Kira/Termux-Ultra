package com.termux.app.compose

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * 最近编辑文件管理工具。
 * 使用 SharedPreferences 存储最近打开/保存的文件路径列表（最多 20 条）。
 */
object RecentFilesManager {

    private const val PREFS_NAME = "text_editor_recent"
    private const val KEY_RECENT_FILES = "recent_files"
    private const val MAX_RECENT = 20

    /** 一条最近文件记录 */
    data class RecentFile(
        val path: String,
        val lastModified: Long, // 时间戳（毫秒）
        val fileSize: Long
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 添加或更新一条记录（按文件路径去重，最新的排在最前面）。 */
    fun addRecent(context: Context, filePath: String) {
        val file = File(filePath)
        if (!file.exists()) return
        val record = RecentFile(
            path = file.absolutePath,
            lastModified = file.lastModified(),
            fileSize = file.length()
        )
        val list = getRecentList(context).toMutableList()
        list.removeAll { it.path == record.path }
        list.add(0, record)
        // 去重 + 限制数量
        val trimmed = list.distinctBy { it.path }.take(MAX_RECENT)
        saveList(context, trimmed)
    }

    /** 移除一条记录。 */
    fun removeRecent(context: Context, filePath: String) {
        val list = getRecentList(context).toMutableList()
        val removed = list.removeAll { it.path == filePath }
        if (removed) saveList(context, list)
    }

    /** 清空所有记录。 */
    fun clearRecent(context: Context) {
        prefs(context).edit().remove(KEY_RECENT_FILES).apply()
    }

    /** 获取最近文件列表，同时过滤掉已不存在的文件。 */
    fun getRecentList(context: Context): List<RecentFile> {
        val raw = prefs(context).getString(KEY_RECENT_FILES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val result = ArrayList<RecentFile>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val path = obj.getString("path")
                val file = File(path)
                if (file.exists()) {
                    result.add(
                        RecentFile(
                            path = path,
                            lastModified = obj.optLong("lastModified", file.lastModified()),
                            fileSize = obj.optLong("fileSize", file.length())
                        )
                    )
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveList(context: Context, list: List<RecentFile>) {
        val arr = JSONArray()
        list.forEach { item ->
            val obj = org.json.JSONObject()
            obj.put("path", item.path)
            obj.put("lastModified", item.lastModified)
            obj.put("fileSize", item.fileSize)
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY_RECENT_FILES, arr.toString()).apply()
    }
}
