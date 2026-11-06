package com.termux.app.rustdesk

import android.content.Context

/**
 * RustDesk 的全局默认值与开关，与 VNC 的 `vnc_enabled` 一起放在 `app_settings` 里。
 *
 * 开关由设置页写入、[com.termux.app.MainActivity] 启动时读取（与 VNC 一致，切换后重启主页）；
 * 其余项在发起连接时才读，改完即生效，不需要重启应用。
 */
object RustDeskSettings {

    private const val PREFS_NAME = "app_settings"
    private const val KEY_ENABLED = "rustdesk_enabled"
    private const val KEY_SERVER = "rustdesk_default_server"
    private const val KEY_PUBLIC_KEY = "rustdesk_public_key"
    private const val KEY_FORCE_RELAY = "rustdesk_force_relay"
    private const val KEY_REQUEST_APPROVAL = "rustdesk_request_approval"
    private const val KEY_SAVE_PASSWORD = "rustdesk_save_password"
    private const val KEY_ASK_PASSWORD = "rustdesk_ask_password"

    /** 发起连接时一次性读出的默认值，避免交接路径上反复读 SharedPreferences。 */
    data class Defaults(
        val server: String,
        val publicKey: String,
        val forceRelay: Boolean,
        val requestApproval: Boolean,
        val savePassword: Boolean,
        val askPassword: Boolean
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun defaults(context: Context): Defaults = prefs(context).let { p ->
        Defaults(
            server = p.getString(KEY_SERVER, "").orEmpty(),
            publicKey = p.getString(KEY_PUBLIC_KEY, "").orEmpty(),
            forceRelay = p.getBoolean(KEY_FORCE_RELAY, false),
            requestApproval = p.getBoolean(KEY_REQUEST_APPROVAL, false),
            savePassword = p.getBoolean(KEY_SAVE_PASSWORD, true),
            askPassword = p.getBoolean(KEY_ASK_PASSWORD, true)
        )
    }

    fun server(context: Context): String = prefs(context).getString(KEY_SERVER, "").orEmpty()

    fun setServer(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SERVER, value).apply()
    }

    fun publicKey(context: Context): String = prefs(context).getString(KEY_PUBLIC_KEY, "").orEmpty()

    fun setPublicKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PUBLIC_KEY, value).apply()
    }

    fun forceRelay(context: Context): Boolean = prefs(context).getBoolean(KEY_FORCE_RELAY, false)

    fun setForceRelay(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_FORCE_RELAY, value).apply()
    }

    fun requestApproval(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REQUEST_APPROVAL, false)

    fun setRequestApproval(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REQUEST_APPROVAL, value).apply()
    }

    fun savePassword(context: Context): Boolean = prefs(context).getBoolean(KEY_SAVE_PASSWORD, true)

    fun setSavePassword(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SAVE_PASSWORD, value).apply()
    }

    fun askPassword(context: Context): Boolean = prefs(context).getBoolean(KEY_ASK_PASSWORD, true)

    fun setAskPassword(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ASK_PASSWORD, value).apply()
    }
}
