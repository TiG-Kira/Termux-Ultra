package com.termux.app.plugin

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object ComposeRenderer {

    @Composable
    fun RenderNode(
        node: ComposeUiNode,
        pluginId: String,
        context: Context,
        stateStore: MutableMap<String, Any?>
    ) {
        when (node.type) {
            "column" -> Column(
                Modifier.padding((node.props["padding"] as? Number)?.toInt()?.dp ?: 0.dp)
            ) {
                node.children?.forEach { RenderNode(it, pluginId, context, stateStore) }
            }

            "row" -> Row {
                node.children?.forEach { RenderNode(it, pluginId, context, stateStore) }
            }

            "text" -> {
                val text = (node.props["text"] as? String).orEmpty()
                val fontSize = (node.props["fontSize"] as? Number)?.toFloat() ?: 14f
                val bold = (node.props["fontWeight"] as? String) == "Bold"
                Text(
                    text = text,
                    fontSize = fontSize.sp,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal
                )
            }

            "card" -> Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    (node.props["title"] as? String)?.let { Text(it, fontWeight = FontWeight.Bold) }
                    node.children?.forEach { RenderNode(it, pluginId, context, stateStore) }
                }
            }

            "listItem" -> {
                val title = (node.props["title"] as? String).orEmpty()
                val subtitle = node.props["subtitle"] as? String
                val onClick = node.props["onClick"] as? String
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = subtitle?.let { { Text(it) } },
                    modifier = if (onClick != null) Modifier.clickable {
                        ActionExecutor.execute(context, pluginId, onClick)
                    } else Modifier
                )
            }

            "switch" -> {
                val stateKey = node.props["stateKey"] as? String ?: return
                val label = (node.props["label"] as? String).orEmpty()
                var checked by remember(stateKey) {
                    mutableStateOf(stateStore[stateKey] as? Boolean ?: false)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, modifier = Modifier.weight(1f))
                    Switch(
                        checked = checked,
                        onCheckedChange = {
                            checked = it
                            stateStore[stateKey] = it
                            val config = PluginManager.getPluginConfig(context, pluginId).toMutableMap()
                            config[stateKey] = it
                            PluginManager.savePluginConfig(context, pluginId, config)
                            (node.props["onChange"] as? String)?.let { action ->
                                ActionExecutor.execute(context, pluginId, action.replace("{value}", it.toString()))
                            }
                        }
                    )
                }
            }

            "button" -> {
                val text = (node.props["text"] as? String).orEmpty()
                val onClick = node.props["onClick"] as? String
                Button(onClick = {
                    onClick?.let { ActionExecutor.execute(context, pluginId, it) }
                }) { Text(text) }
            }

            "slider" -> {
                val stateKey = node.props["stateKey"] as? String ?: return
                var value by remember(stateKey) {
                    mutableStateOf((stateStore[stateKey] as? Float) ?: 0f)
                }
                Slider(
                    value = value,
                    onValueChange = {
                        value = it
                        stateStore[stateKey] = it
                    }
                )
            }

            "spacer" -> Spacer(
                Modifier.height((node.props["height"] as? Number)?.toFloat()?.dp ?: 8.dp)
            )

            "divider" -> HorizontalDivider()

            "lazyColumn" -> RenderLazyColumn(node, pluginId, context, stateStore)

            else -> Text("Unknown component: ${node.type}", color = Color.Red)
        }
    }

    @Composable
    private fun RenderLazyColumn(
        node: ComposeUiNode,
        pluginId: String,
        context: Context,
        stateStore: MutableMap<String, Any?>
    ) {
        val source = node.props["itemsSource"] as? Map<*, *>
        val itemTemplate = node.props["itemTemplate"] as? ComposeUiNode ?: return

        var items by remember { mutableStateOf<List<Map<String, Any?>>>(emptyList()) }

        LaunchedEffect(Unit) {
            if (source?.get("type") == "shell") {
                val cmd = source["command"] as? String ?: return@LaunchedEffect
                val result = PluginManager.executeShellCommand(context, pluginId, cmd)
                if (result.isSuccess) {
                    items = parseShellOutput(result.getOrDefault(""))
                }
            }
        }

        LazyColumn {
            items(items) { item ->
                val resolved = resolveTemplate(itemTemplate, item)
                RenderNode(resolved, pluginId, context, stateStore)
            }
        }
    }

    /** 将 itemTemplate 里的 {key} 替换为实际数据 */
    private fun resolveTemplate(node: ComposeUiNode, data: Map<String, Any?>): ComposeUiNode {
        val resolvedProps = node.props.mapValues { (_, v) ->
            if (v is String && v.startsWith("{") && v.endsWith("}")) {
                data[v.trim('{', '}')] ?: v
            } else v
        }
        return node.copy(props = resolvedProps)
    }

    /** 解析 shell 输出，优先 JSON，失败则按行 */
    private fun parseShellOutput(raw: String): List<Map<String, Any?>> {
        runCatching {
            val type = object : TypeToken<List<Map<String, Any?>>>() {}.type
            return Gson().fromJson(raw, type)
        }
        return raw.lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.trim().split(Regex("\\s+"))
            mapOf(
                "serial" to parts.firstOrNull().orEmpty(),
                "status" to parts.getOrNull(1).orEmpty(),
                "raw" to line
            )
        }
    }
}
