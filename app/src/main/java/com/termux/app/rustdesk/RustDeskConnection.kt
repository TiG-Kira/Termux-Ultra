package com.termux.app.rustdesk

/**
 * 一条 RustDesk 连接记录。
 *
 * [server]/[key] 留空、[forceRelay] 未开时回落到 [RustDeskSettings] 的全局默认值：
 * 设置页填一次即可覆盖大部分场景，单条记录只在需要时覆盖。
 */
data class RustDeskConnection(
    val id: String,
    val name: String,
    val remoteId: String,
    val password: String = "",
    val server: String = "",
    val key: String = "",
    val forceRelay: Boolean = false
) {
    /** 列表副标题：ID 本身是主信息，自建服务器时补上 host，避免同名 ID 分不清。 */
    val summary: String
        get() = if (server.isBlank()) remoteId else "$remoteId@$server"
}
