---
title: 插件构建文档
lang: zh
ref: plugins
description: Termux Ultra 插件开发完整指南 — manifest.json 全字段、权限声明、资源卡片、Agent Skill、System Prompt、H5 多页面与 JS Bridge、Compose JSON DSL、宿主 Action 桥、持久化会话、打包与调试。
---

## 1. 概述

Termux Ultra v2.0.0 插件系统允许第三方开发者扩展应用功能。插件采用 **ZIP 格式**（`.tup` 后缀）打包，可在 **资源页 → 插件中心** 安装。

相比 v1.2.0，v2.0.0 新增能力：

- **Compose JSON DSL 页面**：`pages[].type = "compose"` 声明由宿主 `ComposeRenderer` 原生渲染的 UI（无需 WebView）
- **宿主 Action 桥（HostActionRegistry）**：资源卡片 `action.hostActionId`、Compose `onClick: "action:xxx"`、H5 `hostAction()` 三处统一调用宿主原生入口
- **页面导航 API**：H5 内 `navigate(pageId)` 跳转到同插件任意 compose/h5 子页面
- **统一 action 字符串协议**：`ActionExecutor` 支持 `shell:` / `action:` / `nav:` / 直接 URL 四种前缀
- **插件持久化会话**：宿主侧 `PluginPersistentSession` 提供常驻 shell，可增量读写、发送 Ctrl+C / EOF（host-side Kotlin API，非 JS Bridge）
- **`minHostVersion` 默认升至 `2.0.0`**

## 2. 快速开始

1. 创建插件目录结构
2. 编写 `manifest.json`
3. 添加功能代码（H5 / Compose / Skill / 资源卡片）
4. 打包为 ZIP（重命名为 `.tup`）
5. 在插件中心安装测试

## 3. 插件目录结构

```
my-plugin/
├── manifest.json          # 必须：插件清单
├── icon.png               # 建议：192x192 PNG 图标
├── web/                   # 可选：H5 页面目录（所有 H5 文件放在此）
│   ├── index.html         # 主页（h5Home.entry 指定）
│   ├── about.html         # H5 子页面（pages[].entry 指定）
│   └── settings.html
├── compose/               # 可选：Compose JSON DSL 页面
│   ├── home.json          # Compose 子页面（pages[].entry 指定，type="compose"）
│   └── adb_list.json
└── skills/                # 可选：自定义 Skill（JSON 定义）
    └── my_skill.json
```

> **页面文件位置规范**：所有页面文件（HTML / CSS / JS / 图片 / Compose JSON）必须打包在插件根目录下，`manifest.json` 中 `entry` 字段使用**相对于插件根目录**的路径（如 `web/index.html`、`compose/home.json`）。插件安装时会校验所有 `entry` 指向的文件是否存在，缺失则报错。

## 4. manifest.json 字段总览

```json
{
  "id": "com.example.myplugin",
  "name": "我的插件",
  "version": "1.0.0",
  "minHostVersion": "2.0.0",
  "description": "插件功能简介",
  "author": "开发者名",
  "icon": "icon.png",
  "permissions": ["H5_WEBVIEW", "TERMUX_SESSION_ACCESS", "FILE_SYSTEM_READ"],
  "entryPoints": {
    "resourceCards": [],
    "settingItems": [],
    "agentSkills": [],
    "h5Home": {},
    "pages": []
  },
  "systemPrompt": {}
}
```

| 字段 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `id` | string | 是 | — | 插件唯一 ID，反向域名格式 `^[a-zA-Z][a-zA-Z0-9_.]*$` |
| `name` | string | 是 | — | 插件显示名称 |
| `version` | string | 是 | — | 语义化版本 |
| `minHostVersion` | string | 否 | `2.0.0` | 最低宿主版本，低版本宿主拒绝安装 |
| `description` | string | 否 | `""` | 简介 |
| `author` | string | 否 | `""` | 作者 |
| `icon` | string | 否 | `null` | 图标相对路径 |
| `permissions` | string[] | 否 | `[]` | 权限列表 |
| `entryPoints` | object | 否 | `null` | 入口点配置（资源卡片 / 设置项 / Skill / H5 主页 / 子页面） |
| `systemPrompt` | object | 否 | `null` | System Prompt 修改策略 |

## 5. 权限声明

插件可声明所需权限，系统会在使用时请求用户授权：

| 权限 | 说明 | 风险等级 |
|------|------|----------|
| `TERMUX_SESSION_ACCESS` | 读取和写入终端会话 / 打开持久化会话 | 中 |
| `ROOT_EXECUTE` | 通过 ROOT 权限执行命令 | 高 |
| `FILE_SYSTEM_READ` | 读取文件系统 | 中 |
| `FILE_SYSTEM_WRITE` | 写入文件系统 | 高 |
| `AGENT_MODIFY` | 修改 Agent 行为和 System Prompt | 高 |
| `H5_WEBVIEW` | 加载 H5 主页 | 低 |
| `CROSS_APP_BRIDGE` | 跨应用消息联动 | 中 |
| `INTERNET_ACCESS` | 网络访问 | 低 |

## 6. 资源卡片（resourceCards）

在 `entryPoints.resourceCards` 中声明插件资源卡片，点击卡片执行 `action`。支持四种 action 类型：

```json
{
  "entryPoints": {
    "resourceCards": [
      {
        "id": "my_shell",
        "title": "执行命令",
        "description": "运行 pkg install",
        "action": { "type": "SHELL_COMMAND", "command": "pkg install git -y" }
      },
      {
        "id": "my_url",
        "title": "打开链接",
        "description": "跳转外部浏览器",
        "action": { "type": "OPEN_URL", "url": "https://example.com" }
      },
      {
        "id": "my_host",
        "title": "打开 VNC 设置",
        "description": "调用宿主原生入口（v2.0.0 新增）",
        "action": { "type": "HOST_ACTION", "hostActionId": "open_vnc_settings" }
      },
      {
        "id": "my_custom",
        "title": "自定义",
        "description": "由宿主扩展处理",
        "action": { "type": "CUSTOM" }
      }
    ]
  }
}
```

| `action.type` | 必填字段 | 说明 |
|---------------|----------|------|
| `SHELL_COMMAND` | `command` | 在 Termux shell 中执行命令（需 `TERMUX_SESSION_ACCESS` 或 `ROOT_EXECUTE`） |
| `OPEN_URL` | `url` | 在外部浏览器打开链接 |
| `HOST_ACTION` | `hostActionId` | 调用宿主 `HostActionRegistry` 注册的原生入口（见第 10 节） |
| `CUSTOM` | — | 自定义类型，由宿主扩展处理 |

> `id` 在同插件内必须唯一，对外暴露的最终卡片 ID 为 `{pluginId}.{cardId}`。

## 7. 自定义 Agent Skill（agentSkills）

在 `entryPoints.agentSkills` 中声明，AI 助手会将这些 Skill 注入 System Prompt 的技能卡片：

```json
{
  "entryPoints": {
    "agentSkills": [
      {
        "id": "demo_check_env",
        "name": "检查环境",
        "description": "检查 Termux 运行环境是否正常",
        "category": "系统",
        "handler": "echo 'Checking environment...' && which bash && which pkg && echo 'OK'",
        "requiresClick": false,
        "hasOutput": true,
        "riskLevel": "NONE"
      },
      {
        "id": "demo_root_op",
        "name": "ROOT 操作",
        "description": "高风险操作示例",
        "category": "高危",
        "handler": "su -c 'mount | grep system'",
        "requiresClick": true,
        "hasOutput": true,
        "riskLevel": "HIGH"
      }
    ]
  }
}
```

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `id` | string | — | Skill 唯一 ID，对外暴露为 `{pluginId}.{skillId}` |
| `name` | string | — | 技能卡片显示名 |
| `description` | string | — | 技能描述，AI 会据此判断何时调用 |
| `category` | string | — | 技能分类 |
| `handler` | string | — | 处理逻辑，当前为 shell 命令 |
| `requiresClick` | boolean | `true` | 是否需要用户点击确认才执行 |
| `hasOutput` | boolean | `false` | 是否有输出回显 |
| `riskLevel` | string | `"NONE"` | 风险等级：`NONE` / `LOW` / `MEDIUM` / `HIGH` / `CRITICAL` |
| `cardFormat` | object | `null` | 自定义卡片渲染格式（仅当插件修改了 Prompt 卡片逻辑时才需提供） |

> `cardFormat` 为可选字段。若插件未修改 System Prompt 的卡片格式逻辑，系统使用默认渲染。

## 8. System Prompt 扩展（systemPrompt）

```json
{
  "systemPrompt": {
    "mode": "APPEND",
    "content": "## 插件「我的插件」附加指令\n你可以使用以下额外功能：\n- 使用技能 demo_check_env 检查环境\n- 使用技能 demo_hello 打招呼\n- 资源页的演示卡片可快速执行命令",
    "cardFormat": null
  }
}
```

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `mode` | string | `"APPEND"` | 修改模式：`APPEND` / `MODIFY` / `OVERWRITE` |
| `content` | string | — | 内联文本，直接写入 System Prompt（**不支持文件路径引用**） |
| `cardFormat` | object | `null` | 自定义卡片格式（可选） |

修改模式说明：

- `APPEND`：追加内容到核心 Prompt 末尾（低风险，启用时直接生效）
- `MODIFY`：替换指定段落（中风险）
- `OVERWRITE`：完全覆盖核心规则（**极高风险**，启用时弹出二次确认警告对话框 `PluginOverwriteDialog`，用户确认后才生效）

> **注意**：`content` 为内联文本，不支持文件路径引用。`OVERWRITE` 模式下，插件的 System Prompt 将完全替换原有 System Prompt，设置中的自定义 System Prompt 入口会变为"还原 System Prompt"。

## 9. H5 多页面界面（h5Home + pages）

`pages[].type` 可为 `"h5"`（WebView 加载 HTML）或 `"compose"`（原生渲染，见第 11 节）。

```json
{
  "entryPoints": {
    "h5Home": {
      "enabled": true,
      "entry": "web/index.html",
      "title": "插件主页标题"
    },
    "pages": [
      { "id": "page_about", "title": "关于", "type": "h5", "entry": "web/about.html" },
      { "id": "page_settings", "title": "设置", "type": "h5", "entry": "web/settings.html" },
      { "id": "page_compose_home", "title": "原生页面", "type": "compose", "entry": "compose/home.json" }
    ]
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `h5Home.enabled` | boolean | 是 | 是否启用 H5 主页 |
| `h5Home.entry` | string | 是 | 主页入口文件路径（相对插件根目录，默认 `web/index.html`） |
| `h5Home.title` | string | 否 | 主页显示名称（留空则用插件名） |
| `pages[].id` | string | 是 | 子页面唯一标识 |
| `pages[].title` | string | 是 | 子页面显示名称 |
| `pages[].icon` | string | 否 | 子页面图标相对路径 |
| `pages[].type` | string | 是 | `h5`（WebView）或 `compose`（原生渲染） |
| `pages[].entry` | string | 是* | 入口文件路径（compose 类型必填） |

**H5 页面间导航**：在 WebView 中通过相对路径跳转（同目录下）：

```html
<a href="about.html">关于</a>
<a href="settings.html">设置</a>
```

> WebView 以当前 HTML 文件所在目录为基准解析相对路径；外部 `http://` / `https://` 链接会被拦截到系统浏览器。

### 9.1 JS Bridge API

H5 页面通过 `window.TermuxUltra` 对象访问原生能力：

| API | 返回 | 说明 | 版本 |
|-----|------|------|------|
| `getPluginInfo()` | JSON 字符串 | 插件元信息（id / name / version / enabled / permissions） | v1.2.0 |
| `getConfig()` | JSON 字符串 | 插件配置 Map | v1.2.0 |
| `setConfig(key, value)` | boolean | 保存配置项到 SharedPreferences | v1.2.0 |
| `exec(command)` | JSON 字符串 | 执行终端命令 `{success, output\|error}` | v1.2.0 |
| `readFile(path)` | JSON 字符串 | 读取插件包内文件 `{success, content\|error}` | v1.2.0 |
| `openUrl(url)` | void | 在外部浏览器打开链接 | v1.2.0 |
| `toast(message)` | void | 显示 Toast 提示 | v1.2.0 |
| `getDeviceInfo()` | JSON 字符串 | 设备信息（model / brand / androidVersion / sdkVersion / termuxVersion / rootAvailable） | v1.2.0 |
| `finishPage()` | void | 关闭当前插件页面 | v1.2.0 |
| `hostAction(actionId)` | JSON 字符串 | 调用宿主 HostAction 注册的原生入口 | v2.0.0 新增 |
| `navigate(pageId)` | JSON 字符串 | 跳转到同插件内另一页面（compose / h5 均可） | v2.0.0 新增 |

**示例**：

```javascript
var bridge = window.TermuxUltra;

// 1. 基础调用
var info = JSON.parse(bridge.getPluginInfo());
var result = JSON.parse(bridge.exec('echo Hello from ' + info.name));
bridge.toast('命令执行完成');

// 2. 配置持久化
bridge.setConfig('last_open', new Date().toISOString());
var cfg = JSON.parse(bridge.getConfig());
bridge.toast('上次打开: ' + cfg.last_open);

// 3. 调用宿主原生入口（v2.0.0 新增）
bridge.hostAction('open_vnc_settings');     // 跳到 AVNC 设置页
bridge.hostAction('open_plugin_center');    // 跳到插件中心
bridge.hostAction('open_system_settings');  // 跳到 Termux Ultra 系统设置

// 4. 跳转到同插件的其他页面（v2.0.0 新增）
bridge.navigate('page_about');        // 跳到 pages[].id = "page_about" 的 H5 页面
bridge.navigate('page_compose_home'); // 即使目标是 compose 页面也行
```

> `hostAction` / `navigate` 返回 `{"success": true/false}`，宿主未注册该 `actionId` 或找不到 `pageId` 时返回 `false`。

## 10. 宿主 Action 桥（HostActionRegistry）

`HostActionRegistry` 是宿主侧 Kotlin 单例，维护 `actionId → handler` 映射，供三处统一调用：

1. **资源卡片**：`action.type = "HOST_ACTION"` + `action.hostActionId`
2. **Compose 节点**：`onClick: "action:xxx"`
3. **H5 JS Bridge**：`bridge.hostAction("xxx")`

宿主启动时在 `HostActionRegistry.registerDefaults()` 注册所有内置入口；**插件作者只能引用已注册的 `actionId`，无法自行注册新的**（自定义宿主扩展需修改宿主源码）。

| 调用位置 | 代码 |
|----------|------|
| manifest.json 资源卡片 | `{"action": {"type":"HOST_ACTION","hostActionId":"open_vnc_settings"}}` |
| Compose 节点 onClick | `"onClick": "action:open_vnc_settings"` |
| H5 JS Bridge | `bridge.hostAction("open_vnc_settings")` |

### 10.1 宿主内置 Action 列表

| `hostActionId` | 说明 |
|----------------|------|
| `open_vnc_settings` | 打开 AVNC 设置页 |
| `open_termux_styling` | 打开 Termux:Styling（配色 / 字体） |
| `open_termux_tasker` | 打开 Termux:Tasker |
| `open_termux_widget` | 打开 Termux:Widget |
| `open_plugin_center` | 打开插件中心 |
| `open_system_settings` | 打开 Termux Ultra 系统设置 |

> 宿主可在 `HostActionRegistry.registerDefaults()` 中追加更多原生入口（每加一个原生页面只需一行 `register(...)`）。

## 11. Compose JSON DSL 页面

插件可不写 HTML，直接用 JSON 描述一个由宿主 `ComposeRenderer` 原生渲染的页面。在 `pages[]` 中将 `type` 设为 `"compose"`，`entry` 指向一个 JSON 文件：

```json
{
  "entryPoints": {
    "pages": [
      {
        "id": "page_compose_home",
        "title": "原生页面",
        "type": "compose",
        "entry": "compose/home.json"
      }
    ]
  }
}
```

`compose/home.json` 是一个 `ComposeUiNode` 树，结构为 `{ "type", "props", "children" }`：

```json
{
  "type": "column",
  "props": { "padding": 12 },
  "children": [
    {
      "type": "card",
      "props": { "title": "设备状态" },
      "children": [
        {
          "type": "listItem",
          "props": {
            "title": "打开 VNC 设置",
            "subtitle": "跳转到 AVNC 设置页",
            "onClick": "action:open_vnc_settings"
          }
        },
        {
          "type": "listItem",
          "props": {
            "title": "查看 uname",
            "subtitle": "执行 shell 命令",
            "onClick": "shell:uname -a"
          }
        },
        {
          "type": "listItem",
          "props": {
            "title": "关于页",
            "subtitle": "跳转到 H5 关于页",
            "onClick": "nav:page_about"
          }
        }
      ]
    },
    {
      "type": "switch",
      "props": {
        "stateKey": "auto_refresh",
        "label": "自动刷新",
        "onChange": "shell:echo auto_refresh={value} >> ~/.termux/plugin.cfg"
      }
    },
    {
      "type": "button",
      "props": {
        "text": "打开官网",
        "onClick": "https://example.com"
      }
    },
    {
      "type": "lazyColumn",
      "props": {
        "itemsSource": { "type": "shell", "command": "adb devices" },
        "itemTemplate": {
          "type": "listItem",
          "props": {
            "title": "{serial}",
            "subtitle": "{status}",
            "onClick": "shell:adb -s {serial} shell echo hi"
          }
        }
      }
    }
  ]
}
```

### 11.1 支持的 Compose 节点类型

| type | 关键 props | 说明 |
|------|------------|------|
| `column` | `padding` | 垂直布局，子节点用 `children` |
| `row` | — | 水平布局 |
| `text` | `text`、`fontSize`、`fontWeight`（`"Bold"`） | 文本 |
| `card` | `title` | 卡片容器，子节点用 `children` |
| `listItem` | `title`、`subtitle`、`onClick` | 列表项，点击触发 action |
| `switch` | `stateKey`、`label`、`onChange` | 开关，值自动持久化到插件配置 |
| `button` | `text`、`onClick` | 按钮 |
| `slider` | `stateKey` | 滑块，值存入 stateStore |
| `spacer` | `height` | 间距（dp） |
| `divider` | — | 水平分割线 |
| `lazyColumn` | `itemsSource`、`itemTemplate` | 动态列表，支持 shell 数据源 |

> `lazyColumn.itemsSource` 当前仅支持 `{ "type": "shell", "command": "..." }`，shell 输出会优先按 JSON 数组解析，失败则按空白分隔的行解析（字段映射为 `serial` / `status` / `raw`）。`itemTemplate` 的 props 支持 `{key}` 占位符。

### 11.2 action 字符串协议（onClick / onChange）

| 前缀 | 示例 | 说明 |
|------|------|------|
| `shell:` | `shell:uname -a` | 在插件 shell 中执行命令（需 `TERMUX_SESSION_ACCESS` 或 `ROOT_EXECUTE`） |
| `action:` | `action:open_vnc_settings` | 调用宿主 HostAction（见第 10.1 节） |
| `nav:` | `nav:page_about` | 跳转到同插件其他页面（按 `pages[].id` 匹配，compose / h5 均可） |
| `http://` / `https://` | `https://example.com` | 在外部浏览器打开 |
| `{value}` 占位 | `shell:echo {value}` | `switch.onChange` 中替换为当前布尔值（`true` / `false`） |

> 未匹配任何前缀的字符串会被忽略（返回 `false`）。

## 12. 插件持久化会话（host-side Kotlin API）

`PluginPersistentSession` 是宿主侧提供给 Kotlin 代码（如自定义 Agent Skill handler、Compose 渲染扩展、原生 Module）使用的**常驻 shell 会话**，**不通过 JS Bridge 暴露**。

与 `PluginManager.executeShellCommand()`（每次新建进程）不同，持久化会话保持存活，直到插件显式关闭或 `TermuxService` 被销毁。

```kotlin
// 1. 打开会话（需要 TERMUX_SESSION_ACCESS 权限）
val session = PluginManager.openPersistentSession(
    context,
    pluginId = "com.example.myplugin",
    sessionName = "My Plugin Session"
) ?: return  // 权限不足或创建失败返回 null

// 2. 写入命令并读取增量输出
session.writeln("ls -la /data")
Thread.sleep(200)
val output = session.readNew()  // 读取自上次以来的新增 transcript

// 3. 控制信号
session.interrupt()  // Ctrl+C，中断前台程序
session.sendEof()    // Ctrl+D

// 4. 状态查询
val running = session.isRunning
val cwd = session.cwd           // 当前工作目录（可能为 null）
val pid = session.pid           // 进程 PID（0=未启动, >0=运行中, -1=已结束）
val exit = session.exitCode     // 退出码（仅已结束时有效）

// 5. 关闭会话（幂等，可重复调用）
PluginManager.closePersistentSession(session.sessionId, "com.example.myplugin")
```

### 12.1 API 概览

| 成员 | 说明 |
|------|------|
| `sessionId` | 会话唯一 ID（`${pluginId}::${uuid 前 8 位}`） |
| `pluginId` | 所属插件 ID |
| `sessionName` | 会话名（用于终端页面显示） |
| `isRunning` | 是否存活 |
| `cwd` | shell 当前工作目录（可能为 null） |
| `pid` | 进程 PID |
| `exitCode` | 退出码 |
| `write(data)` | 写入原始字节（stdin） |
| `writeln(line)` | 写入一行文本（自动追加换行） |
| `executeCommand(cmd)` | 等价于 `writeln` |
| `readNew(mark=true)` | 增量读取新输出（默认推进游标，`mark=false` 偷看不动） |
| `readAll()` | 读取全部 transcript（不更新游标） |
| `resetReadCursor()` | 重置游标到末尾，忽略历史输出 |
| `interrupt()` | 发送 Ctrl+C（0x03） |
| `sendEof()` | 发送 EOF（0x04） |
| `close()` | 结束会话（SIGKILL，幂等） |

> 会话会自动注册到 `TermuxService.mTermuxSessions`，终端页面也能管理；会话退出时 `PluginPersistentSessionRegistry` 通过 `TerminalSession.mHandle` 反查 `sessionId` 并清理 `PluginManager` 注册表。同插件可访问性由 `PluginPersistentSession.pluginId` 校验，跨插件访问会记 warn 并返回 null。

## 13. 打包与安装

1. 将插件文件按结构组织
2. 压缩为 ZIP 文件，重命名为 `.tup`
3. 将 `.tup` 文件推送到设备
4. 打开 `Termux Ultra → 资源页 → 插件中心 → 从文件安装`

```bash
# 打包命令示例
cd my-plugin
zip -r ../my-plugin.tup .
adb push ../my-plugin.tup /sdcard/Download/
```

## 14. 调试技巧

- 查看插件加载日志：`设置 → 调试 → 日志级别` 设为 `Verbose`
- H5 主页调试：使用 Chrome DevTools 远程调试 WebView
- 权限测试：在插件管理页撤销权限后重新调用 API 测试授权流程

## 15. 示例插件

项目 `demo-plugin/` 目录包含一个完整的示例插件（版本 v1.1.0），展示了：

- 多页面 H5 界面（主页 + 关于页 + 设置页），通过 `h5Home` + `pages` 数组配置
- JS Bridge API 完整使用示例（`window.TermuxUltra` 对象）
- 资源卡片定义（`SHELL_COMMAND` 类型）
- Agent Skill 定义（自定义 handler）
- System Prompt 追加（`APPEND` 模式）
- 插件配置持久化（`setConfig` / `getConfig`）
- 打包为 `.tup` 的完整流程

目录内容：`manifest.json`、`README.md`、`web/index.html`、`web/about.html`、`web/settings.html`。
