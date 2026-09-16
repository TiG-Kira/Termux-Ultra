# Demo Plugin for Termux Ultra

这是 Termux Ultra 插件系统的官方示例插件。

## 功能

- 🌐 **多页面 H5 界面**：主页 + 关于页 + 设置页，展示页面导航
- 🔌 **资源卡片**：在资源页添加演示卡片，一键执行命令
- 🤖 **技能卡片**：向 Termux Agent 添加 2 个新技能
- 📝 **System Prompt**：向 Agent 追加插件相关指令

## 权限需求

- `H5_WEBVIEW` — 显示 H5 主页
- `TERMUX_SESSION_ACCESS` — 执行终端命令
- `FILE_SYSTEM_READ` — 读取文件
- `INTERNET_ACCESS` — 网络访问

## 插件包目录结构

```
demo-plugin/
├── manifest.json          # 插件清单（必需）
├── web/                   # H5 页面目录（推荐）
│   ├── index.html         # 主页（h5Home.entry 指定）
│   ├── about.html         # 关于页（pages[0].entry 指定）
│   └── settings.html      # 设置页（pages[1].entry 指定）
└── README.md              # 说明文档（可选）
```

## H5 文件位置规范

### 基本规则

1. **所有 H5 文件必须打包在插件根目录下**，路径相对于插件根目录
2. `manifest.json` 中的 `entry` 字段使用相对路径，如 `web/index.html`
3. 插件安装时会校验所有 `entry` 指向的文件是否存在，缺失则报错

### manifest.json 中的 H5 配置

```json
{
  "entryPoints": {
    "h5Home": {
      "enabled": true,
      "entry": "web/index.html",
      "title": "主页标题"
    },
    "pages": [
      {
        "id": "page_id",
        "title": "页面显示名",
        "type": "h5",
        "entry": "web/about.html"
      }
    ]
  }
}
```

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `h5Home.enabled` | boolean | 是 | 是否启用 H5 主页 |
| `h5Home.entry` | string | 是 | 主页入口文件路径（相对于插件根目录） |
| `h5Home.title` | string | 否 | 主页显示名称（留空则使用插件名称） |
| `pages[].id` | string | 是 | 子页面唯一标识 |
| `pages[].title` | string | 是 | 子页面显示名称 |
| `pages[].type` | string | 是 | 页面类型，H5 页面固定为 `"h5"` |
| `pages[].entry` | string | 是 | 子页面入口文件路径（相对于插件根目录） |

### 路径规范

- ✅ 正确：`web/index.html`、`web/about.html`、`pages/home/main.html`
- ❌ 错误：`/web/index.html`（绝对路径）、`C:\path\to\file.html`（Windows 路径）
- ❌ 错误：`file:///sdcard/...`（外部路径，不允许访问插件包外的文件）

### 多页面导航

H5 页面之间可以通过相对路径导航：

```html
<a href="about.html">关于</a>
<a href="settings.html">设置</a>
```

WebView 会以当前 HTML 文件所在目录为基准解析相对路径。

## 打包为 .tup

将所有文件打包为 ZIP 格式，重命名为 `.tup`：

```bash
# 在 demo-plugin 目录下
zip -r ../demo-plugin.tup .
```

## 安装

1. 打开 Termux Ultra → 资源页 → 插件中心
2. 点击「安装插件」
3. 选择 `demo-plugin.tup` 文件
4. 确认权限并启用

## JS Bridge API

插件 H5 页面可通过 `window.TermuxUltra` 对象访问以下 API：

| API | 说明 |
|-----|------|
| `getPluginInfo()` | 获取插件信息（返回 JSON 字符串） |
| `getConfig()` | 读取插件配置（返回 JSON 字符串） |
| `setConfig(key, value)` | 保存配置项 |
| `exec(command)` | 执行终端命令（返回 JSON 字符串） |
| `readFile(path)` | 读取插件包内文件内容 |
| `openUrl(url)` | 在外部浏览器打开链接 |
| `toast(message)` | 显示 Toast 提示 |
| `getDeviceInfo()` | 获取设备信息（返回 JSON 字符串） |
| `finishPage()` | 关闭当前插件页面 |

## 开发调试

1. 修改 H5 文件后，重新打包 `.tup` 并卸载重装插件
2. 使用 Chrome DevTools 远程调试 WebView（需在 App 中启用 WebView 调试）
3. 通过 `bridge.toast()` 在 H5 页面显示调试信息
