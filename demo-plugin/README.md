# Demo Plugin for Termux Ultra

这是 Termux Ultra 插件系统的官方示例插件。

## 功能

- 🔌 **资源卡片**：在资源页添加演示卡片，一键执行命令
- 🤖 **技能卡片**：向 Termux Agent 添加 2 个新技能
- 🌐 **H5 主页**：内置 WebView 交互界面，展示 JS Bridge API
- 📝 **System Prompt**：向 Agent 追加插件相关指令

## 权限需求

- `H5_WEBVIEW` — 显示 H5 主页
- `TERMUX_SESSION_ACCESS` — 执行终端命令
- `FILE_SYSTEM_READ` — 读取文件
- `INTERNET_ACCESS` — 网络访问

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
| `getPluginInfo()` | 获取插件信息 |
| `getConfig()` | 读取插件配置 |
| `setConfig(key, value)` | 保存配置项 |
| `exec(command)` | 执行终端命令 |
| `readFile(path)` | 读取插件文件 |
| `openUrl(url)` | 打开外部链接 |
| `toast(message)` | 显示 Toast |
| `getDeviceInfo()` | 获取设备信息 |
| `finishPage()` | 关闭插件页面 |
