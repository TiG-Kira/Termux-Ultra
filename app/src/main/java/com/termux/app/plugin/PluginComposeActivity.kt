package com.termux.app.plugin

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.termux.app.compose.rememberGlassPageBackdrop
import com.termux.app.compose.KiTerminalTheme
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
        // 状态栏/导航栏透明，让 compose 页面背景透出，避免栏色与页面配色不一致（老问题）。
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: run { finish(); return }
        val entryPath = intent.getStringExtra(EXTRA_ENTRY_PATH) ?: "pages/index.json"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "插件页面"

        // 提前校验插件状态，避免在 Composable 里 return
        val plugin = PluginManager.getPluginById(this, pluginId)
        if (plugin == null || plugin.state != PluginState.ENABLED) {
            finish()
            return
        }

        setContent {
            KiTerminalTheme {
                val context = LocalContext.current
                val json = PluginManager.getPluginFileContent(context, pluginId, entryPath)
                val rootNode = json?.let { ComposeUiNodeParser.parse(it) }
                // 显式转为 MutableMap<String, Any?> 匹配 RenderNode 签名
                val stateStore = remember {
                    PluginManager.getPluginConfig(context, pluginId).toMutableMap<String, Any?>()
                }

                val glassPage = rememberGlassPageBackdrop()
                Scaffold(topBar = {
                    GlassTopAppBar(
                        title = title,
                        backdrop = glassPage.backdrop,
                        navigationIcon = {
                            GlassIconButton(onClick = { finish() }) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = "返回",
                                    tint = MiuixTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    )
                }) { padding ->
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
