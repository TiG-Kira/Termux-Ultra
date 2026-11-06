package com.termux.app.rustdesk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 连接记录的本地存储：JSON 数组落在独立 SharedPreferences 里，与 VNC / SSH 连接同款做法。
 *
 * 用 org.json 逐字段读写而不是 Gson 反射：字段缺失只会退化成默认值，不会在数据类里塞 null。
 */
class RustDeskConnectionManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getConnections(): List<RustDeskConnection> {
        val raw = prefs.getString(KEY_CONNECTIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::toConnection)
            }
        }.getOrDefault(emptyList())
    }

    fun saveConnection(connection: RustDeskConnection) {
        val connections = getConnections().toMutableList()
        val index = connections.indexOfFirst { it.id == connection.id }
        if (index >= 0) connections[index] = connection else connections.add(connection)
        saveAll(connections)
    }

    fun deleteConnection(id: String) {
        saveAll(getConnections().filterNot { it.id == id })
    }

    fun clearAll() {
        prefs.edit().remove(KEY_CONNECTIONS).apply()
    }

    /** 清掉所有记录里保存的密码，连接本身保留；返回受影响条数。 */
    fun clearPasswords(): Int {
        val connections = getConnections()
        val affected = connections.count { it.password.isNotEmpty() }
        saveAll(connections.map { it.copy(password = "") })
        return affected
    }

    fun count(): Int = getConnections().size

    private fun saveAll(connections: List<RustDeskConnection>) {
        val array = JSONArray()
        connections.forEach { connection ->
            array.put(JSONObject().apply {
                put("id", connection.id)
                put("name", connection.name)
                put("remoteId", connection.remoteId)
                put("password", connection.password)
                put("server", connection.server)
                put("key", connection.key)
                put("forceRelay", connection.forceRelay)
            })
        }
        prefs.edit().putString(KEY_CONNECTIONS, array.toString()).apply()
    }

    private fun toConnection(json: JSONObject): RustDeskConnection? {
        val id = json.optString("id")
        val remoteId = json.optString("remoteId")
        // 没有 ID 或没有远端 RustDesk ID 的记录无法交接，直接丢弃
        if (id.isEmpty() || remoteId.isEmpty()) return null
        return RustDeskConnection(
            id = id,
            name = json.optString("name").ifEmpty { remoteId },
            remoteId = remoteId,
            password = json.optString("password"),
            server = json.optString("server"),
            key = json.optString("key"),
            forceRelay = json.optBoolean("forceRelay")
        )
    }

    private companion object {
        const val PREFS_NAME = "rustdesk_connections"
        const val KEY_CONNECTIONS = "connections"
    }
}
