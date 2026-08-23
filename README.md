# Termux Ultra

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](./LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)]()
[![Based on Termux](https://img.shields.io/badge/Based%20on-Termux%20v0.118.x-orange.svg)](https://github.com/termux/termux-app)

[![Build status](https://github.com/TiG-Kira/Termux-Ultra/workflows/Build/badge.svg)](https://github.com/TiG-Kira/Termux-Ultra/actions)

**Termux Ultra** 是一款基于 [Termux](https://github.com/termux/termux-app) 二次开发的 Android 终端模拟器与 Linux 环境应用。它在保留 Termux 原生终端能力的基础上，集成了 VNC 远程桌面、SSH 连接管理、文件管理器、Linux 容器（proot）、QEMU 虚拟机、一键资源部署、AI 助手、插件系统等增强功能，并将 5 款 Termux 插件（API、Boot、Styling、Tasker、Widget）内置为可开关的集成工具，无需额外安装。UI 采用 Jetpack Compose + Miuix 设计语言打造。

> 本仓库为应用本体（用户界面、终端模拟及扩展功能）。应用内可安装的软件包请参见 [termux/termux-packages](https://github.com/termux/termux-packages)。

***

## 最近更新

### 插件系统（v1.2.0.RB）
- 全新插件系统，支持 ZIP/TUP 格式插件包安装
- 插件可扩展：资源卡片、设置项、Agent Skill、H5 多页面界面
- 插件可屏蔽：系统功能、设置项目、导航页面
- 插件权限管理：ROOT 执行、会话访问、文件读写、跨应用联动
- Agent 插件接口：System Prompt 追加/修改/覆盖、自定义 Skill
- 跨应用联动桥：Broadcast、ContentProvider、Webhook
- H5 多页面界面：WebView + JavaScript Bridge（`window.TermuxUltra`）
- 多 H5 入口：主页面（h5Home）+ 子页面（pages 数组），插件中心分入口展示

### AI 助手（v118.3.63）
- 内置 AI 助手，支持通过自然语言与终端交互
- 支持 OpenAI 兼容 API 及自定义端点配置
- 技能系统：新建/关闭会话、执行命令、文件操作、VNC/SSH 连接、QEMU 虚拟机管理
- 危险操作检测与二次确认机制
- 深度思考内容展示（模型支持时）

### 文件管理增强
- SFTP 服务器支持，局域网文件传输
- 多文件选择与批量操作
- 文件类型图标与详情面板优化

### 终端会话管理
- 会话状态实时刷新（400ms 轮询）
- 已结束会话信息保留与查看
- 搜索功能优化与欢迎卡片

***

## 目录

- [功能特性](#功能特性)
- [应用与插件](#应用与插件)
- [系统要求](#系统要求)
- [安装](#安装)
- [卸载](#卸载)
- [项目结构](#项目结构)
- [构建](#构建)
- [技术栈](#技术栈)
- [插件开发指南](#插件开发指南)
- [调试](#调试)
- [维护者与贡献者](#维护者与贡献者)
- [致谢](#致谢)
- [开源许可](#开源许可)

## 功能特性

### 终端
- 多会话管理：新建、重命名、关闭、切换会话
- 搜索框实时过滤（输入标题搜索，无结果显示"未找到"）
- 服务状态检测：持续监控终端运行状态，支持 Wake Lock 保活
- 内存监控与保护：内存超限时冻结会话，防止数据丢失
- 会话保活提示：Android 12+ 配合 tmux 实现后台持久化

### 集成工具管理
5 款 Termux 插件已内置到应用中，无需额外安装独立 APK，在 `设置` 中按需开关：
- **Termux:API** — 提供 Android 系统功能调用（传感器、通知、TTS 等）
- **Termux:Boot** — 开机自动执行 `~/.termux/boot/` 下的脚本
- **Termux:Styling** — 终端配色方案与字体管理（使用合并包名 `com.termux`）
- **Termux:Tasker** — Tasker 自动化集成
- **Termux:Widget** — 桌面快捷方式与小组件

> 工具默认关闭，开启时通过 `PackageManager.setComponentEnabledSetting()` 动态启用对应组件，关闭时禁用。若设备已安装官方独立 APK，开关将自动禁用并提示冲突。

### 文件管理
- 完整的文件 / 文件夹操作：新建、复制、剪切、粘贴、删除、重命名
- 多种打开方式：查看内容（cat）、编辑（vi）、执行（bash）、复制路径
- 深色模式适配的文件详情面板
- 内置 FTP 服务器：支持局域网文件传输
- 拉取刷新

### 远程管理
- **VNC 远程桌面**：基于 AVNC + libvncserver，支持手势缩放、多种输入模式、特殊按键、色彩格式配置，自动扫描本地 VNC 端口
- **SSH 连接管理**：基于 connectbot sshlib，可保存、编辑、删除多个连接配置，自动安装 `ssh`/`sshpass`
- **SSH 隧道**：支持本地端口转发、主机密钥验证、多 IP 重试
- 统一的搜索 UI 与卡片式管理

### Linux 容器与虚拟机
- **Linux 容器**：基于 proot 一键安装 Ubuntu（Noble/Jammy）或 Debian（Bookworm）环境，共享 Termux 主目录
- **QEMU 虚拟机**：支持在容器内或 Termux 内安装 QEMU，提供完整系统虚拟化
- **QEMU on VNC**：通过 QEMU 启动虚拟机并通过 VNC 显示桌面，支持自定义 VM 配置（CPU、内存、磁盘、ISO）
- **Seed ISO**：自动生成 seed ISO 用于虚拟机初始化配置

### 资源页（一键部署）
内置常用环境与服务的一键安装脚本，分为实用工具中心与第三方资源中心：
- Linux 容器安装（Ubuntu / Debian）
- QEMU 安装（容器内 / Termux 内）
- QEMU on VNC（虚拟机 + VNC 桌面）
- 朱雀面板（LightPanel）— 一键部署 Web 管理面板
- Python 环境部署
- tmux（保持容器与项目存活）
- 第三方资源中心：社区维护的扩展资源

### 插件系统（v2.0.0）
- **插件入口**：资源页 → 插件中心
- **插件格式**：ZIP 打包（`.tup` 后缀）
- **插件能力**：
  - 增加资源页卡片入口
  - 增加/修改设置项
  - 提供自定义 Agent Skill
  - 提供 H5 多页面界面（主页 + 子页面）
  - 屏蔽/禁用系统功能
  - 修改 Agent System Prompt（APPEND/MODIFY/OVERWRITE）
  - 跨应用联动
- **权限系统**：ROOT 执行、会话访问、文件读写等
- **管理界面**：插件安装、启用/禁用、配置、卸载
- **H5 Bridge**：`window.TermuxUltra` 对象，支持 exec/getConfig/setConfig/readFile 等 API

### 仪表盘与设置
- 网络信息卡片：实时刷新公网 IP 与所属国家
- 设备信息：机型、Android 版本、内核版本
- 备份 / 恢复 Termux 数据
- 集成工具开关面板（含独立 APK 冲突检测）
- 生物识别认证（指纹解锁）
- 多语言支持（中文 / 英文，100% 中文覆盖）
- 深色 / 浅色模式自适应
- Miuix 风格设置页（ArrowPreference 套件）

### AI 助手
- 自然语言交互：通过对话方式与终端、文件系统、远程连接等交互
- 技能系统：支持新建/关闭会话、执行命令、文件读写、VNC/SSH 连接、QEMU 虚拟机管理等
- 多模型支持：兼容 OpenAI API 及自定义端点，可配置 temperature 等参数
- 安全机制：危险操作检测（rm -rf、dd、fork bomb 等）与二次确认
- 上下文感知：可获取会话信息、文件列表、执行结果等实时数据
- 插件扩展：支持插件添加自定义 Skill、修改 System Prompt

### 交互与动画
- 首页横滑手势切换页面（终端 → 文件 → 远程 → 资源）
- 页面切换叠加动画与左右切换动画，支持预测式返回
- 卡片圆角与点击反馈裁剪统一
- 底部导航避让与边距修正，防止误触
- 玻璃/柔光/浮动导航栏效果

## 应用与插件

Termux Ultra 将以下 5 款 Termux 插件的源码集成到主应用中（位于 `vendor/termux-addons/`），作为可开关的内置工具，无需额外安装独立 APK：

- [Termux:API](https://github.com/termux/termux-api) — 已集成
- [Termux:Boot](https://github.com/termux/termux-boot) — 已集成
- [Termux:Styling](https://github.com/termux/termux-styling) — 已集成（使用合并包名 `com.termux`）
- [Termux:Tasker](https://github.com/termux/termux-tasker) — 已集成
- [Termux:Widget](https://github.com/termux/termux-widget) — 已集成

> 集成工具默认关闭，在 `设置` → `集成工具` 中按需开启。若设备已安装对应的官方独立 APK，开关将自动禁用以避免冲突。

Termux Ultra v2.0.0 起支持用户安装第三方插件（ZIP/TUP 格式），详见 [插件开发指南](#插件开发指南)。

## 系统要求

- Android `>= 8.0`（API 26）
- targetSdk `28`，compileSdk `37`
- 支持架构：`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`

## 安装

Termux Ultra 与原版 Termux 及其所有插件共享 `sharedUserId`（`com.termux`），因此设备上安装的本应用与所有插件 APK **必须使用同一签名来源**，否则将无法协同工作，安装时也会出现 `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`、`signatures do not match` 等错误。

- 请勿混用来源（例如 F-Droid 装一个、GitHub 装另一个）。
- 如需更换来源，请先**卸载所有已安装的 Termux 及其插件 APK**，再从同一新来源全部安装。卸载前建议参考 [Backing up Termux](https://wiki.termux.com/wiki/Backing_up_Termux) 备份数据。

> "bootstrap" 指 `termux-app` 自带的用于启动最小 shell 环境的最小包集合，其 zip 由 [termux/termux-packages releases](https://github.com/termux/termux-packages/releases) 构建发布。

### APK 来源

| 来源 | 说明 |
| --- | --- |
| GitHub Releases | 稳定版本，发布页 `Assets` 下提供各架构 APK |
| GitHub Build | 每次 commit 自动构建，适合尝鲜与测试 PR，需登录 GitHub 账号下载 Artifacts |

- Debug 版本仅输出 universal APK（`termux-ultra_debug_universal.apk`），安装包 + bootstrap 约 `~180MB`。
- Release 版本输出各架构独立 APK，使用架构包约 `~120MB`。
- GitHub 来源的 APK 均为 `debuggable`，彼此兼容，但与其他来源不兼容。

### 关于 Google Play 商店（已弃用）

原版 Termux 及其插件因 [Android 10 问题](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10) 已在 Play Store 停止更新，最后版本为 `v0.101`。**强烈建议不再从 Play Store 安装 Termux 系应用**，请迁移至 GitHub 或 F-Droid 来源。

## 卸载

如需彻底卸载，必须卸载设备上**所有** Termux 或其插件 APK（参见 [应用与插件](#应用与插件)）。

进入 `Android 设置` → `应用`，搜索 `termux`，逐个卸载。即便未安装过插件，也建议在应用列表中再次确认。

## 项目结构

```
Termux-Ultra/
├── app/                        # 主应用模块
│   ├── src/main/
│   │   ├── assets/             # 容器与部署脚本
│   │   ├── cpp/                # CMake 原生构建（termux-bootstrap）
│   │   ├── cpp_avnc/           # AVNC 原生 VNC 客户端
│   │   ├── java/com/termux/    # 应用 Kotlin/Java 源码
│   │   │   ├── app/            # 核心逻辑（TermuxActivity、TermuxService 等）
│   │   │   ├── app/compose/    # Jetpack Compose UI（主页、文件、远程、资源、设置、AI 助手等）
│   │   │   │   ├── AiTermuxActivity.kt   # AI 助手界面
│   │   │   │   ├── AiTermuxEngine.kt     # AI 引擎与技能执行器
│   │   │   │   └── AiTermuxModels.kt     # AI 数据模型与配置
│   │   │   ├── app/plugin/     # 插件系统（v2.0.0）
│   │   │   │   ├── PluginManager.kt      # 插件管理器核心
│   │   │   │   ├── PluginManifest.kt     # 插件清单数据模型
│   │   │   │   ├── PluginTypes.kt        # 插件类型与权限枚举
│   │   │   │   ├── PluginLoader.kt       # 插件 ZIP 加载器
│   │   │   │   ├── PluginSecurity.kt     # 插件安全校验
│   │   │   │   ├── AgentExtension.kt     # Agent 扩展接口
│   │   │   │   └── engine/               # 插件执行引擎
│   │   │   ├── app/vnc/        # VNC 连接管理
│   │   │   ├── app/ssh/        # SSH 连接管理
│   │   │   ├── app/remote/     # 远程管理综合页
│   │   │   ├── app/ftp/        # 内置 FTP 服务器
│   │   │   └── app/activities/ # 各子页面 Activity
│   │   ├── jniLibs/            # 预编译 .so 库
│   │   └── res/                # 资源（布局、drawable、strings、xml 偏好）
│   ├── extern/                 # 第三方原生库源码
│   └── CMakeLists.txt          # 原生构建配置
├── vendor/termux-addons/       # 集成的 Termux 插件源码
├── terminal-emulator/          # 终端模拟器模块
├── terminal-view/              # 终端视图模块
├── termux-shared/              # 共享常量与工具库
├── art/                        # 图标与宣传图脚本
├── demo-plugin/                # 示例插件（ZIP 打包示例）
├── build.gradle
├── settings.gradle
└── gradle.properties
```

## 构建

### 环境要求

- JDK 8
- Android SDK，compileSdk 37
- NDK `22.1.7171670`
- CMake `3.22.1`

### 构建命令

为避免路径中的空格导致 NDK 编译问题，请通过无空格的硬链接路径访问项目（如 `D:\KiTerminal-UX`）。

```bash
# Debug 版本（仅输出 universal APK）
./gradlew assembleDebug

# Release 版本（输出各架构 APK）
./gradlew assembleRelease
```

构建产物：
- Debug：`app/build/outputs/apk/debug/termux-ultra_debug_universal.apk`
- Release：`app/build/outputs/apk/release/` 下各架构 APK（`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`）

原生构建目标（CMake）：`native-vnc`、`vncclient`、`turbojpeg-static`、`wolfssl`、`termux-bootstrap`

> 构建时不要使用 `-q` 参数，以便观察构建进度。

### 签名

项目内置 `ki-terminal-release.jks` 签名配置（alias: `ki-terminal`），Debug 与 Release 均使用该签名。

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 语言 | Kotlin、Java、C/C++ |
| UI | Jetpack Compose 1.8.3、Material 3 1.3.0、Miuix KMP 0.9.3（ui / icons / preference） |
| 架构组件 | AndroidX、Lifecycle 2.8.5、ViewModel、Navigation、Room 2.7.2、DataBinding |
| 终端 | terminal-emulator、terminal-view |
| VNC | AVNC、libvncserver、libjpeg-turbo、wolfssl |
| SSH | connectbot sshlib 2.2.36 |
| 图片加载 | Coil Compose 2.7.0 |
| 生物识别 | AndroidX Biometric 1.2.0-alpha05 |
| 序列化 | Gson 2.10.1、kotlinx-serialization 1.9.0 |
| AI 助手 | OpenAI 兼容 API、自定义端点、技能系统 |
| 插件系统 | ZIP 打包、JSON 配置、WebView Bridge、Broadcast 桥接 |
| 构建 | Gradle、CMake 3.22.1、NDK 22.1.7171670 |
| 集成插件 | termux-api、termux-boot、termux-styling、termux-tasker、termux-widget |
| 包名 | `com.termux`（sharedUserId） |

## 插件开发指南

### 概述

Termux Ultra v1.2.0 引入了插件系统，允许第三方开发者扩展应用功能。插件采用 **ZIP 格式**（`.tup` 后缀）打包，可在资源页 → 插件中心安装。

### 快速开始

1. 创建插件目录结构
2. 编写 `manifest.json`
3. 添加功能代码
4. 打包为 ZIP
5. 安装测试

### 插件目录结构

```
my-plugin/
├── manifest.json          # 必须：插件清单
├── icon.png               # 建议：192x192 PNG 图标
├── web/                   # 可选：H5 页面目录（所有 H5 文件放在此）
│   ├── index.html         # 主页（h5Home.entry 指定）
│   ├── about.html         # 子页面（pages[].entry 指定）
│   └── settings.html      # 子页面
└── skills/                # 可选：自定义 Skill（JSON 定义）
    └── my_skill.json
```

> **H5 文件位置规范**：所有 H5 文件（HTML、CSS、JS、图片）必须打包在插件根目录下，`manifest.json` 中的 `entry` 字段使用相对于插件根目录的路径（如 `web/index.html`）。插件安装时会校验所有 `entry` 指向的文件是否存在，缺失则报错。

### manifest.json 必填字段

```json
{
  "id": "com.example.myplugin",
  "name": "我的插件",
  "version": "1.0.0",
  "minHostVersion": "1.2.0",
  "description": "插件功能简介",
  "author": "开发者名"
}
```

### 权限声明

插件可声明所需权限，系统会在使用时请求用户授权：

| 权限 | 说明 | 风险等级 |
|------|------|----------|
| `TERMUX_SESSION_ACCESS` | 读取和写入终端会话 | 中 |
| `ROOT_EXECUTE` | 通过 ROOT 权限执行命令 | 高 |
| `FILE_SYSTEM_READ` | 读取文件系统 | 中 |
| `FILE_SYSTEM_WRITE` | 写入文件系统 | 高 |
| `AGENT_MODIFY` | 修改 Agent 行为和 System Prompt | 高 |
| `H5_WEBVIEW` | 加载 H5 主页 | 低 |
| `CROSS_APP_BRIDGE` | 跨应用消息联动 | 中 |
| `INTERNET_ACCESS` | 网络访问 | 低 |

### 资源卡片扩展

在 manifest.json 的 `entryPoints.resourceCards` 中声明插件资源卡片：

```json
{
  "entryPoints": {
    "resourceCards": [
      {
        "id": "my_feature",
        "title": "我的功能",
        "description": "功能描述",
        "action": {
          "type": "shell_command",
          "command": "pkg install git -y"
        }
      }
    ]
  }
}
```

### 自定义 Agent Skill

在 manifest.json 的 `entryPoints.agentSkills` 中声明自定义 Skill：

```json
{
  "entryPoints": {
    "agentSkills": [
      {
        "id": "MY_SKILL",
        "name": "我的技能",
        "description": "技能描述",
        "category": "分类",
        "handler": "my_skill_handler",
        "requiresClick": true,
        "hasOutput": false,
        "riskLevel": "LOW"
      }
    ]
  }
}
```

**前提条件**：仅当插件修改了 Prompt 中的卡片格式逻辑时，才需要提供 `cardFormat` 定义。若未修改 Prompt，系统将使用默认卡片渲染逻辑。

### System Prompt 扩展

在 manifest.json 的 `systemPrompt` 中声明 System Prompt 修改策略：

```json
{
  "systemPrompt": {
    "mode": "APPEND",
    "content": "## 插件附加指令\n你可以使用以下额外功能：\n- 使用技能 A 检查环境\n- 使用技能 B 打招呼"
  }
}
```

修改模式说明：
- `APPEND`：追加内容到核心 Prompt 末尾（低风险）
- `MODIFY`：替换指定段落（中风险）
- `OVERWRITE`：完全覆盖核心规则（**极高风险**，系统会弹出二次确认警告）

> **注意**：`content` 字段为内联文本内容，直接写入 System Prompt，不支持文件路径引用。OVERWRITE 模式下，插件的 System Prompt 将完全替换原有的 System Prompt，设置中的自定义 System Prompt 入口会变为"还原 System Prompt"。

### H5 插件主页

插件支持**多页面 H5 界面**，通过 `manifest.json` 的 `entryPoints` 配置：

```json
{
  "entryPoints": {
    "h5Home": {
      "enabled": true,
      "entry": "web/index.html",
      "title": "插件主页标题"
    },
    "pages": [
      {
        "id": "page_about",
        "title": "关于",
        "type": "h5",
        "entry": "web/about.html"
      },
      {
        "id": "page_settings",
        "title": "设置",
        "type": "h5",
        "entry": "web/settings.html"
      }
    ]
  }
}
```

- `h5Home`：主入口页面，`title` 为插件中心显示名称
- `pages`：子页面数组，每个子页面需指定 `id`、`title`、`type`（固定为 `"h5"`）和 `entry`
- 所有 `entry` 路径相对于插件根目录，安装时自动校验文件存在性

**H5 页面间导航**：在 WebView 中通过相对路径跳转：

```html
<a href="about.html">关于</a>
<a href="settings.html">设置</a>
```

#### JS Bridge API

H5 页面通过 `window.TermuxUltra` 对象访问原生能力：

| API | 说明 |
|-----|------|
| `getPluginInfo()` | 获取插件元信息（返回 JSON 字符串） |
| `getConfig()` | 获取插件配置（返回 JSON 字符串） |
| `setConfig(key, value)` | 保存配置项 |
| `exec(command)` | 执行终端命令（返回 JSON 字符串） |
| `readFile(path)` | 读取插件包内文件内容 |
| `openUrl(url)` | 在外部浏览器打开链接 |
| `toast(message)` | 显示 Toast 提示 |
| `getDeviceInfo()` | 获取设备信息（返回 JSON 字符串） |
| `finishPage()` | 关闭当前插件页面 |

**示例**：

```javascript
var bridge = window.TermuxUltra;
var info = JSON.parse(bridge.getPluginInfo());
var result = JSON.parse(bridge.exec('echo Hello from ' + info.name));
bridge.toast('命令执行完成');
```

### 打包与安装

1. 将插件文件按结构组织
2. 压缩为 ZIP 文件，重命名为 `.tup`
3. 将 `.tup` 文件推送到设备
4. 打开 Termux Ultra → 资源页 → 插件中心 → 从文件安装

```bash
# 打包命令示例
cd my-plugin
zip -r ../my-plugin.tup .
adb push ../my-plugin.tup /sdcard/Download/
```

### 调试技巧

- 查看插件加载日志：设置 → 调试 → 日志级别设为 Verbose
- H5 主页调试：使用 Chrome DevTools 远程调试 WebView
- 权限测试：在插件管理页撤销权限后重新调用 API 测试授权流程

### 示例插件

项目 `demo-plugin/` 目录包含一个完整的示例插件（版本 v1.1.0），展示了：
- 多页面 H5 界面（主页 + 关于页 + 设置页），通过 `h5Home` + `pages` 数组配置
- JS Bridge API 完整使用示例（`window.TermuxUltra` 对象）
- 资源卡片定义（SHELL_COMMAND 类型）
- Agent Skill 定义（自定义 handler）
- System Prompt 追加（APPEND 模式）
- 插件配置持久化（setConfig / getConfig）
- 打包为 .tup 的完整流程

## 调试

可在应用 `设置` → `调试` 中配置 `logcat` 日志级别（需应用版本 `>= 0.118.0`）。日志级别默认为 `Normal`，`Verbose` 会记录额外信息。调试完成后请恢复 `Normal`，避免敏感数据写入 logcat 并降低性能。

查看日志：

```bash
# 终端内实时查看（Ctrl+c 停止）
logcat

# 导出日志快照
logcat -d > logcat.txt
```

也可通过长按终端菜单 `More` → `Report Issue` 自动生成 stat 信息与 logcat 快照，便于反馈问题。反馈时请附上完整报告（可去除敏感信息），仅截图的报告通常会被关闭。

### 日志级别

- `Off` — 不记录
- `Normal` — 记录 error / warn / info 及堆栈
- `Debug` — 记录 debug 信息
- `Verbose` — 记录 verbose 信息

## 维护者与贡献者

Termux Ultra 由 **Kira**（[@TiG-Kira](https://github.com/TiG-Kira)）开发维护，基于 Termux 原作者 @termux 的工作。

`termux-shared` 库定义了应用与插件共享的常量与工具类，主常量位于 [`TermuxConstants`](termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java)。提交代码时请遵循：

- 共享常量与工具请定义在 `termux-shared` 中，**禁止硬编码路径**，否则 PR 不予接受。
- 集成工具的启停统一通过 `IntegratedTools` 单例管理，禁止直接调用 `PackageManager.setComponentEnabledSetting()`，需通过 `IntegratedTools.setEnabled()` + `IntegratedTools.applyComponentState()` 完成。
- 集成工具的 Android 组件必须在 `AndroidManifest.xml` 中声明 `android:enabled="false"`，并在 `IntegratedTools.componentsFor()` 中注册。
- 插件系统的核心逻辑位于 `app/src/main/java/com/termux/app/plugin/`，新增插件功能请遵循现有接口定义。
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org) 规范（如 `Added: 新增功能`、`Fixed: 修复问题`、`Changed!: 破坏性变更`），冒号后需有空格。
- `versionName` 遵循 [语义化版本 2.0.0](https://semver.org/spec/v2.0.0.html)，格式 `major.minor.patch(-prerelease)(+buildmetadata)`，如 `v0.1.0`。

### Fork 注意事项

- 修改包名需重新编译对应 `$PREFIX` 的 bootstrap zip，参见 [Building Packages](https://github.com/termux/termux-packages/wiki/Building-packages)。
- 集成插件（`vendor/termux-addons/`）的组件在 `AndroidManifest.xml` 中默认以 `android:enabled="false"` 声明，运行时通过 `IntegratedTools.applyComponentState()` 动态启停，Fork 时需保持此模式。
- Termux:Styling 使用合并包名 `com.termux` 而非原始 `com.termux.styling`，修改包名时需同步更新 `IntegratedTools.kt` 中的组件映射。

## 致谢

- [Termux](https://github.com/termux/termux-app) — 终端模拟器与 Linux 环境基础
- [AVNC](https://github.com/gujjwal00/avnc) — Android VNC 客户端
- [libvncserver](https://github.com/LibVNC/libvncserver) — VNC 库
- [wolfSSL](https://github.com/wolfSSL/wolfssl) — 嵌入式 TLS 库
- [libjpeg-turbo](https://github.com/libjpeg-turbo/libjpeg-turbo) — JPEG 编解码
- [connectbot sshlib](https://github.com/connectbot/sshlib) — SSH 库
- [Miuix KMP](https://github.com/miuix-kotlin-multiplatform/miuix) — UI 设计组件
- [LightPanel](https://github.com/MyUI0/lightpanel) — 朱雀面板 Web 管理面板
- [Termux Add-ons](https://github.com/termux) — API、Boot、Styling、Tasker、Widget 插件源码

## 开源许可

本项目基于 [GNU Affero General Public License v3.0](./LICENSE) 开源。使用、修改与分发须遵守该协议条款，并保留原作者署名。

Termux 原项目版权归其原作者所有，本项目仅在其基础上进行二次开发。第三方原生库（libvncserver、wolfssl、libjpeg-turbo 等）请遵循各自许可证。