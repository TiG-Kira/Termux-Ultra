# 插件系统扩展：宿主能力注册 + 配置化 Compose DSL

> 本文档记录远程会话中讨论的插件系统扩展方案，供本地会话按阶段实施。
> 核心原则：**插件 ZIP 内不含任何 dex/so，全靠 manifest 声明，宿主管理器内部调用。**

---

## 一、目标

1. **插件可调用宿主原生能力**：插件在 manifest 里声明 `hostActionId`，宿主查表执行对应的原生逻辑（打开 Activity、跳页面等）。
2. **配置化 Compose 页面**：插件提供一份 JSON 配置（描述 UI 树），宿主用一个通用 `PluginComposeActivity` 递归渲染，插件开发者无需写 Kotlin/Compose。
3. **H5 页面也能触发宿主能力**：扩展 JS Bridge，让现有 H5 插件也能调用 `hostAction` 和页面导航。

---

## 二、整体架构

```
插件 ZIP
├── manifest.json          ← 声明入口（H5 / Compose / 资源卡片 / 技能）
├── pages/*.json           ← Compose DSL 配置（阶段 2 新增）
├── web/*.html             ← H5 页面（现有）
└── skills/*.json          ← 技能定义（现有）

宿主
├── HostActionRegistry     ← 宿主预注册所有原生能力，插件只能消费
├── ActionExecutor         ← 统一路由：shell:xxx / action:xxx / nav:xxx
├── PluginWebViewActivity  ← H5 宿主（现有，阶段 3 扩展 Bridge）
└── PluginComposeActivity  ← Compose DSL 宿主（阶段 2 新增）
    └── ComposeRenderer    ← 递归渲染 UiNode → Compose
```

---

## 三、分阶段任务清单

### 阶段 1：宿主能力注册表 + Action 执行引擎（基础设施）

**目标**：插件声明 `action.hostActionId` 时，宿主能找到 handler 并执行。

| 产物 | 类型 | 路径 |
|------|------|------|
| `HostActionRegistry.kt` | 新文件 | `app/src/main/java/com/termux/app/plugin/` |
| `ActionExecutor.kt` | 新文件 | `app/src/main/java/com/termux/app/plugin/` |
| `PluginTypes.kt` | 修改 | `ActionType` 枚举加 `HOST_ACTION`；`PluginAction` 加 `hostActionId` |
| `PluginManifest.kt` | 修改 | `PluginActionRef` 加 `hostActionId` 字段 |
| `PluginManager.kt` | 修改 | 加 `executeAction(context, pluginId, actionStr)` 入口 |

#### HostActionRegistry 设计

```kotlin
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

    // 在宿主 Application 或主 Activity 启动时调用
    fun registerDefaults(context: Context) {
        register("open_vnc_settings") { ctx, _, _ ->
            ctx.startActivity(Intent(ctx, com.gaurav.avnc.ui.prefs.PrefsActivity::class.java))
        }
        register("open_termux_styling") { ctx, _, _ ->
            ctx.startActivity(Intent().apply {
                component = ComponentName(ctx.packageName, "com.termux.app.activities.TermuxStylingActivity")
            })
        }
        register("open_termux_tasker") { ctx, _, _ ->
            ctx.startActivity(Intent().apply {
                component = ComponentName(ctx.packageName, "com.termux.app.activities.TermuxTaskerActivity")
            })
        }
        register("open_termux_widget") { ctx, _, _ ->
            ctx.startActivity(Intent().apply {
                component = ComponentName(ctx.packageName, "com.termux.app.activities.TermuxWidgetActivity")
            })
        }
        register("open_plugin_center") { ctx, _, _ ->
            ctx.startActivity(Intent(ctx, PluginCenterActivity::class.java))
        }
        // 后续每加一个原生入口，在这里加一行即可
    }
}
```

#### ActionExecutor 设计

```kotlin
object ActionExecutor {
    /**
     * actionStr 格式：
     *   "shell:adb devices"           → 执行 shell 命令
     *   "action:open_vnc_settings"    → 调宿主原生能力
     *   "action:open_termux_styling"  → 调宿主原生能力
     *   "nav:adb_remote"              → 跳同一插件内另一个 compose/h5 页面
     *   "https://..."                 → 外链（走系统浏览器）
     */
    fun execute(context: Context, pluginId: String, actionStr: String, payload: Map<String, Any?> = emptyMap()): Boolean {
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
```

#### PluginTypes.kt 修改

```kotlin
enum class ActionType {
    SHELL_COMMAND,
    OPEN_URL,
    CUSTOM,
    HOST_ACTION           // ← 新增
}

data class PluginAction(
    val type: ActionType,
    val command: String? = null,
    val url: String? = null,
    val hostActionId: String? = null   // ← 新增
)
```

#### PluginManifest.kt 修改

```kotlin
data class PluginActionRef(
    val type: String,
    val command: String? = null,
    val url: String? = null,
    val hostActionId: String? = null   // ← 新增
)
```

#### PluginManager.kt 修改

在 `getPluginResourceCards` 里把 `hostActionId` 带进 `PluginAction`；
新增：
```kotlin
fun executeAction(context: Context, pluginId: String, actionStr: String, payload: Map<String, Any?> = emptyMap()): Boolean {
    return ActionExecutor.execute(context, pluginId, actionStr, payload)
}
```

---

### 阶段 2：配置化 Compose DSL 渲染引擎

**目标**：新增 `PluginComposeActivity`，读插件 ZIP 里的 `pages/xxx.json`，递归渲染成 Compose UI。

| 产物 | 类型 | 路径 |
|------|------|------|
| `ComposeUiNode.kt` | 新文件 | `app/src/main/java/com/termux/app/plugin/` |
| `ComposeRenderer.kt` | 新文件 | `app/src/main/java/com/termux/app/plugin/` |
| `PluginComposeActivity.kt` | 新文件 | `app/src/main/java/com/termux/app/plugin/` |
| `PluginManifest.kt` | 修改 | `PluginPageRef.type` 支持 `"compose"` |
| `PluginLoader.kt` | 修改 | `validatePluginContents` 加 compose 配置文件校验 |

#### ComposeUiNode.kt（数据模型）

```kotlin
data class ComposeUiNode(
    val type: String,
    val props: Map<String, Any?> = emptyMap(),
    val children: List<ComposeUiNode>? = null
)

// 用 Gson 反序列化
object ComposeUiNodeParser {
    private val gson = Gson()
    fun parse(json: String): ComposeUiNode? = runCatching {
        gson.fromJson(json, ComposeUiNode::class.java)
    }.getOrNull()
}
```

#### Compose DSL 配置文件示例（`pages/adb_home.json`）

```json
{
  "type": "column",
  "props": { "padding": 16 },
  "children": [
    {
      "type": "text",
      "props": { "text": "ADB 工具箱", "fontSize": 24, "fontWeight": "Bold" }
    },
    {
      "type": "card",
      "props": { "title": "设备列表" },
      "children": [
        {
          "type": "listItem",
          "props": {
            "title": "Pixel 7",
            "subtitle": "connected",
            "onClick": "shell:adb -s emulator-5554 shell"
          }
        }
      ]
    },
    {
      "type": "switch",
      "props": {
        "label": "自动检测新设备",
        "stateKey": "auto_detect",
        "onChange": "shell:settings put global auto_detect {value}"
      }
    },
    {
      "type": "button",
      "props": {
        "text": "立即扫描",
        "onClick": "action:scan_devices"
      }
    },
    {
      "type": "lazyColumn",
      "props": {
        "itemsSource": { "type": "shell", "command": "adb devices -l" },
        "itemTemplate": {
          "type": "listItem",
          "props": {
            "title": "{serial}",
            "subtitle": "{status}"
          }
        }
      }
    }
  ]
}
```

#### ComposeRenderer.kt（递归渲染）

```kotlin
@Composable
fun ComposeRenderer.RenderNode(
    node: ComposeUiNode,
    pluginId: String,
    context: Context,
    stateStore: MutableMap<String, Any?>
) {
    when (node.type) {
        "column" -> Column(Modifier.padding((node.props["padding"] as? Number)?.toInt()?.dp ?: 0.dp)) {
            node.children?.forEach { RenderNode(it, pluginId, context, stateStore) }
        }
        "row" -> Row { node.children?.forEach { RenderNode(it, pluginId, context, stateStore) } }
        "text" -> {
            val text = (node.props["text"] as? String).orEmpty()
            val fontSize = (node.props["fontSize"] as? Number)?.toFloat() ?: 14f
            Text(text = text, fontSize = fontSize.sp)
        }
        "card" -> Card { node.children?.forEach { RenderNode(it, pluginId, context, stateStore) } }
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
            var checked by remember(stateKey) {
                mutableStateOf(stateStore[stateKey] as? Boolean ?: false)
            }
            Switch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    stateStore[stateKey] = it
                    // 持久化到插件配置
                    val config = PluginManager.getPluginConfig(context, pluginId).toMutableMap()
                    config[stateKey] = it
                    PluginManager.savePluginConfig(context, pluginId, config)
                    // 执行 onChange
                    (node.props["onChange"] as? String)?.let { action ->
                        ActionExecutor.execute(context, pluginId, action.replace("{value}", it.toString()))
                    }
                }
            )
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
            Slider(value = value, onValueChange = {
                value = it
                stateStore[stateKey] = it
            })
        }
        "spacer" -> Spacer(Modifier.height((node.props["height"] as? Number)?.toFloat()?.dp ?: 8.dp))
        "divider" -> HorizontalDivider()
        "lazyColumn" -> RenderLazyColumn(node, pluginId, context, stateStore)
        else -> Text("Unknown component: ${node.type}", color = Color.Red)
    }
}
```

#### lazyColumn 动态数据渲染

```kotlin
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
                // 尝试 JSON 解析，失败则按行解析
                items = parseShellOutput(result.getOrDefault(""))
            }
        }
    }

    LazyColumn {
        items(items) { item ->
            itemTemplate?.let { RenderNode(it.copy(
                props = it.props.mapValues { (k, v) ->
                    if (v is String && v.startsWith("{") && v.endsWith("}")) {
                        item[v.trim('{', '}')] ?: v
                    } else v
                }
            ), pluginId, context, stateStore) }
        }
    }
}
```

#### PluginComposeActivity.kt（宿主 Activity）

```kotlin
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
        val pluginId = intent.getStringExtra(EXTRA_PLUGIN_ID) ?: run { finish(); return }
        val entryPath = intent.getStringExtra(EXTRA_ENTRY_PATH) ?: "pages/index.json"
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "插件页面"

        setContent {
            KiTerminalTheme {
                val context = LocalContext.current
                val plugin = PluginManager.getPluginById(context, pluginId)
                if (plugin == null || plugin.state != PluginState.ENABLED) {
                    finish(); return@setContent
                }

                val json = PluginManager.getPluginFileContent(context, pluginId, entryPath)
                val rootNode = json?.let { ComposeUiNodeParser.parse(it) }
                val stateStore = remember { PluginManager.getPluginConfig(context, pluginId).toMutableMap() }

                Scaffold(topBar = { TopAppBar(title = title) }) { padding ->
                    Box(Modifier.padding(padding)) {
                        if (rootNode != null) {
                            ComposeRenderer.RenderNode(rootNode, pluginId, context, stateStore)
                        } else {
                            Text("页面配置解析失败")
                        }
                    }
                }
            }
        }
    }
}
```

#### PluginManifest.kt 修改

```kotlin
data class PluginPageRef(
    val id: String,
    val title: String,
    val icon: String? = null,
    val type: String = "h5",    // "h5" 或 "compose"
    val entry: String? = null
)
```

#### PluginLoader.kt 修改

在 `validatePluginContents` 里追加 compose 页面的入口文件校验：

```kotlin
manifest.entryPoints?.pages?.forEach { page ->
    if (page.type == "compose" && !page.entry.isNullOrBlank()) {
        val entryFile = File(pluginDir, page.entry)
        if (!entryFile.exists()) {
            throw IllegalStateException("插件 Compose 页面配置不存在: ${page.entry}")
        }
    }
}
```

---

### 阶段 3：JS Bridge 扩展（H5 页面也能触发宿主能力）

| 产物 | 类型 | 路径 |
|------|------|------|
| `PluginWebViewActivity.kt` | 修改 | `PluginJsBridge` 加两个方法 |

```kotlin
@JavascriptInterface
fun hostAction(actionId: String): String {
    val ok = ActionExecutor.execute(this@PluginWebViewActivity, pluginId, "action:$actionId")
    return gson.toJson(mapOf("success" to ok))
}

@JavascriptInterface
fun navigate(pageId: String): String {
    val ok = ActionExecutor.execute(this@PluginWebViewActivity, pluginId, "nav:$pageId")
    return gson.toJson(mapOf("success" to ok))
}
```

H5 里这样用：
```javascript
TermuxUltra.hostAction("open_vnc_settings")
TermuxUltra.navigate("adb_remote")
```

---

### 阶段 4：PluginCenterActivity 适配 + 资源卡片点击路由

| 产物 | 类型 | 路径 |
|------|------|------|
| `PluginCenterActivity.kt` | 修改 | 详情页识别 `type: compose`，调 `PluginComposeActivity.start()` |
| 资源卡片点击处 | 修改 | 点击事件统一走 `ActionExecutor.execute()` |

在 `PluginContentDialog` 里，遍历 `pages` 时根据 type 选择启动哪个 Activity：

```kotlin
pages.forEach { page ->
    Button(onClick = {
        if (page.type == "compose") {
            PluginComposeActivity.start(context, activePlugin.id, page.entry ?: "", page.title)
        } else {
            PluginWebViewActivity.start(context, activePlugin.id, page.entry ?: "", page.title)
        }
    }) { Text(page.title) }
}
```

资源卡片点击处（`ResourcesScreen` 等）把硬编码的 shell/url 判断改成：

```kotlin
val action = card.action
when (action.type) {
    ActionType.HOST_ACTION -> action.hostActionId?.let {
        ActionExecutor.execute(context, pluginId, "action:$it")
    }
    ActionType.SHELL_COMMAND -> action.command?.let {
        ActionExecutor.execute(context, pluginId, "shell:$it")
    }
    ActionType.OPEN_URL -> action.url?.let {
        ActionExecutor.execute(context, pluginId, it)
    }
    else -> {}
}
```

---

### 阶段 5：宿主能力注册表初始填充

在 `HostActionRegistry.registerDefaults()` 里把项目已有的 Activity 入口逐一注册。
每次宿主新增一个可暴露的原生功能，只需在此加一行，插件侧无需改动。

---

## 四、manifest.json 规范（完整）

```json
{
  "id": "com.example.adbtoolkit",
  "name": "ADB 工具箱",
  "version": "1.0.0",
  "minHostVersion": "2.0.0",
  "permissions": ["TERMUX_SESSION_ACCESS", "H5_WEBVIEW"],
  "entryPoints": {
    "h5Home": { "enabled": true, "entry": "web/index.html", "title": "主页" },
    "pages": [
      { "id": "home",   "title": "ADB 主页",  "type": "compose", "entry": "pages/home.json" },
      { "id": "remote", "title": "远程ADB",   "type": "h5",      "entry": "web/remote.html" }
    ],
    "resourceCards": [
      {
        "id": "check",
        "title": "ADB 设备检测",
        "description": "检测设备列表",
        "action": { "type": "SHELL_COMMAND", "command": "adb devices" }
      },
      {
        "id": "vnc",
        "title": "打开 VNC 设置",
        "description": "跳转到宿主 VNC 设置页",
        "action": { "type": "HOST_ACTION", "hostActionId": "open_vnc_settings" }
      }
    ]
  }
}
```

---

## 五、Action 字符串格式规范

| 前缀 | 含义 | 示例 |
|------|------|------|
| `shell:` | 执行 shell 命令 | `shell:adb devices` |
| `action:` | 调宿主原生能力 | `action:open_vnc_settings` |
| `nav:` | 跳插件内页面 | `nav:adb_remote` |
| `http://` / `https://` | 外链浏览器 | `https://example.com` |

事件中的 `{value}` 会被替换为组件的新值（如 switch 的 true/false，slider 的数值）。

---

## 六、明确不做的事

- ❌ 插件 ZIP 内不含 dex/so，不做动态代码加载
- ❌ 不支持远程 URL 作为 compose 数据源
- ❌ 不让插件自定义主题/颜色（强制走宿主 MiuixTheme）
- ❌ 不做复杂 DSL 数据绑定引擎（动态页面建议用 H5）
- ❌ 不做插件间通信

---

## 七、分支清理命令

如果需要删除本次会话创建的分支：

```bash
# 先切回 main
git checkout main

# 删除 agent 分支
git branch -D trae/agent-37Zw1z

# 如要丢弃工作区自动生成的缓存改动
git checkout -- .trae-html-share-packages/
```
