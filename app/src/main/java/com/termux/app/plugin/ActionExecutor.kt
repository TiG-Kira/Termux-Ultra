package com.termux.app.plugin

import android.content.Context
import android.util.Log

object ActionExecutor {

    private const val TAG = "ActionExecutor"
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
        // 内部兜底：按钮/列表项/开关的点击最终都会走到这里。
        // 宿主动作（startActivity / shell / 页面跳转）任何一步抛异常都会从点击回调里冒泡成崩溃，
        // 因此统一在此吞掉并降级返回 false，保证点击不会让页面崩溃。
        return runCatching {
            when {
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
                    PluginManager.openUrl(context, pluginId, actionStr)
                    true
                }
                else -> false
            }
        }.onFailure { e ->
            Log.e(TAG, "执行动作失败: $actionStr", e)
        }.getOrDefault(false)
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
