package com.termux.app.plugin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.termux.app.compose.KiTerminalTheme
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

class PluginComposeActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_PLUGIN_ID = "plugin_id"
        private const val EXTRA_ENTRY_PATH = "entry_path"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, pluginId: String, entryPath: String, title: String? = null) {
            val intent = Intent(context, PluginComposeActivity::class.java).apply {
                putExtra(EXTRA_PLUGIN_ID, pluginId)
                putExtra(EXTRA_ENTRY_PATH, entryPath)
                putExtra(EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: run { finish(); return }
        val entryPath = intent.getStringExtra(EXTRA_ENTRY_PATH) ?: "pages/index.json"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "插件页面"

        setContent {
            KiTerminalTheme {
                val context = LocalContext.current
                val plugin = PluginManager.getPluginById(context, pluginId)

                if (plugin == null || plugin.state != PluginState.ENABLED) {
                    finish()
                    return@setContent
                }

                val json = PluginManager.getPluginFileContent(context, pluginId, entryPath)
                val rootNode = json?.let { ComposeUiNodeParser.parse(it) }
                val stateStore = remember {
                    PluginManager.getPluginConfig(context, pluginId).toMutableMap()
                }

                Scaffold(topBar = { TopAppBar(title = title) }) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        if (rootNode != null) {
                            ComposeRenderer.RenderNode(rootNode, pluginId, context, stateStore)
                        } else {
                            Text("页面配置解析失败: $entryPath")
                        }
                    }
                }
            }
        }
    }
}
