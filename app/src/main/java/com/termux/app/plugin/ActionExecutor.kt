package com.termux.app.plugin

import android.content.Context

object ActionExecutor {
    /**
     * actionStr 格式：
     *   "shell:adb devices"           → 执行 shell 命令
     *   "action:open_vnc_settings"    → 调宿主原生能力
     *   "nav:adb_remote"              → 跳同一插件内另一个 compose/h5 页面
     *   "https://..."                 → 外链（走系统浏览器）
     */
    fun execute(
        context: Context,
        pluginId: String,
        actionStr: String,
        payload: Map<String, Any?> = emptyMap()
    ): Boolean {
        return when {
            actionStr.startsWith("shell:") -> {
                val cmd = actionStr.removePrefix("shell:")
                PluginManager.executeShellCommand(context, pluginId, cmd)
                true
            }
            actionStr.startsWith("action:") -> {
                val id = actionStr.removePrefix("action:")
                HostActionRegistry.execute(context, id, pluginId, payload)
            }
            actionStr.startsWith("nav:") -> {
                val pageId = actionStr.removePrefix("nav:")
                navigateToPluginPage(context, pluginId, pageId)
                true
            }
            actionStr.startsWith("http://") || actionStr.startsWith("https://") -> {
                PluginManager.openUrl(context, actionStr)
                true
            }
            else -> false
        }
    }

    private fun navigateToPluginPage(context: Context, pluginId: String, pageId: String) {
        val manifest = PluginLoader.loadPluginManifest(context, pluginId) ?: return
        val page = manifest.entryPoints?.pages?.find { it.id == pageId } ?: return
        when (page.type) {
            "compose" -> PluginComposeActivity.start(context, pluginId, page.entry ?: "", page.title)
            else -> PluginWebViewActivity.start(context, pluginId, page.entry ?: "", page.title)
        }
    }
}
