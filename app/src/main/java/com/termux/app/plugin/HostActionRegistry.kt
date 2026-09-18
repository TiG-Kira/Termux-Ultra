package com.termux.app.plugin

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import java.util.concurrent.ConcurrentHashMap

object HostActionRegistry {
    private val actions = ConcurrentHashMap<String, (Context, pluginId: String, payload: Map<String, Any?>) -> Unit>()

    fun register(id: String, handler: (Context, String, Map<String, Any?>) -> Unit) {
        actions[id] = handler
    }

    fun execute(context: Context, id: String, pluginId: String, payload: Map<String, Any?> = emptyMap()): Boolean {
        val handler = actions[id] ?: return false
        handler(context, pluginId, payload)
        return true
    }

    /** 在宿主 Application 或主 Activity 启动时调用一次 */
    fun registerDefaults(context: Context) {
        register("open_vnc_settings") { ctx, _, _ ->
            ctx.startActivity(Intent(ctx, com.gaurav.avnc.ui.prefs.PrefsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        register("open_termux_styling") { ctx, _, _ ->
            ctx.startActivity(Intent().apply {
                component = ComponentName(ctx.packageName, "com.termux.app.activities.TermuxStylingActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        register("open_termux_tasker") { ctx, _, _ ->
            ctx.startActivity(Intent().apply {
                component = ComponentName(ctx.packageName, "com.termux.app.activities.TermuxTaskerActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        register("open_termux_widget") { ctx, _, _ ->
            ctx.startActivity(Intent().apply {
                component = ComponentName(ctx.packageName, "com.termux.app.activities.TermuxWidgetActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        register("open_plugin_center") { ctx, _, _ ->
            ctx.startActivity(Intent(ctx, PluginCenterActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        register("open_system_settings") { ctx, _, _ ->
            ctx.startActivity(Intent(ctx, com.termux.app.activities.SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
        // 后续每加一个原生入口，在这里加一行即可
    }
}
