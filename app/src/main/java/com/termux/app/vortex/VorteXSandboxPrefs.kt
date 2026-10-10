package com.termux.app.vortex

import android.content.Context
import android.content.SharedPreferences

/**
 * VorteX 沙箱偏好存储（独立 SharedPreferences 文件，避免污染其它设置）。
 *
 * 持久化三类状态：
 * - 总开关 [KEY_ENABLED]
 * - 是否授权 Termux Agent 使用沙箱 [KEY_AGENT_AUTHORIZED]
 * - 各插件「使用沙箱运行」开关 [pluginKey]
 */
object VorteXSandboxPrefs {
    private const val PREF_NAME = "vortex_sandbox"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AGENT_AUTHORIZED = "agent_authorized"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isAgentAuthorized(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AGENT_AUTHORIZED, false)

    fun setAgentAuthorized(context: Context, authorized: Boolean) {
        prefs(context).edit().putBoolean(KEY_AGENT_AUTHORIZED, authorized).apply()
    }

    private fun pluginKey(pluginId: String) = "plugin_sandbox:$pluginId"

    fun isPluginSandboxEnabled(context: Context, pluginId: String): Boolean =
        prefs(context).getBoolean(pluginKey(pluginId), false)

    fun setPluginSandboxEnabled(context: Context, pluginId: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(pluginKey(pluginId), enabled).apply()
    }
}
