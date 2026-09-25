<img width="5643" height="2790" alt="AATUltra" src="https://github.com/user-attachments/assets/d9205d38-6acf-4e9e-8b95-28969322bad1" />

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](./LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%2010.0%2B-green.svg)]()
[![Based on Termux v0.119.0](https://img.shields.io/badge/Base-Termux%20v0.119.0-orange.svg)](https://github.com/termux/termux-app/releases/tag/v0.119.0-beta.3)
[![v2.1.0.R5](https://img.shields.io/badge/v2.1.0.R5-stable-brightgreen.svg)](https://github.com/TiG-Kira/Termux-Ultra/releases)

[![Build status](https://github.com/TiG-Kira/Termux-Ultra/actions/workflows/ci.yml/badge.svg)](https://github.com/TiG-Kira/Termux-Ultra/actions)
[![Docs](https://img.shields.io/badge/Docs-GitHub%20Pages-blue.svg)](https://tig-kira.github.io/Termux-Ultra/)

## 分支说明

| 分支 | 基底 | 版本 | 状态 |
|------|------|------|------|
| **`main`** 🎯 | 上游 Termux `v0.119.0-beta.3` | 2.x.x.R5/R6 | **正式版主线**，承接功能开发、Bug 修复、架构优化 |
| `release/r1-r4` | 上游 Termux `v0.118.3` | 1.8.0.R4 | 📦 v1.8.x 历史快照，**停止功能更新**，仅做紧急阻断 Bug 修复 |
| `archived/corebump/2.x` | 上游 Termux `v0.119.0-beta.3` | 2.0.0.R5 | 📂 归档快照，保留 2.x 内部开发步骤 |

> ✅ **2.1.0.R5 正式版已于 2026-09-19 发布**，基于上游 Termux v0.119.0，包含 Nova 引擎、玻璃导航栏、插件系统、AI 助手等核心功能。
> 由于上游长期未更新 Release，内部测试上游 Beta 稳定，将直接作为 0.119.0 基底。



**Termux Ultra** 是一款基于 [Termux](https://github.com/termux/termux-app) 二次开发的 Android 终端模拟器与 Linux 环境应用。它在保留 Termux 原生终端能力的基础上，集成了 VNC 远程桌面、SSH 连接管理、文件管理器、Linux 容器（proot）、QEMU 虚拟机、一键资源部署、AI 助手、插件系统等增强功能，并将 5 款 Termux 插件（API、Boot、Styling、Tasker、Widget）内置为可开关的集成工具，无需额外安装。UI 采用 Jetpack Compose + Miuix 设计语言打造。

> 本仓库为应用本体（用户界面、终端模拟及扩展功能）。应用内可安装的软件包请参见 [termux/termux-packages](https://github.com/termux/termux-packages)。

***

## 最近更新

### Termux Ultra 2.1.0.R5 — 🎉 正式版发布

> 📅 **2026-09-19 正式发布**

- **Nova 引擎 (LibTerminal) 3.0.0**：全新终端核心引擎，性能与兼容性大幅提升，支持在经典引擎与新星引擎间一键切换
- **玻璃/柔光/浮动导航栏**：HyperOS 原生视觉风格复刻，指示器支持拖动切页，玻璃效果亮色模式更白、暗色模式更暗
- **插件系统 v2.0.0**：完整的第三方插件支持，包括 Compose JSON DSL 原生页面、宿主 Action 桥、持久化会话、权限管理
- **AI Termux 助手**：内置 AI 助手，支持多模型配置（OpenAI/本地大模型/内置 LLama）、技能系统、深度思考展示、训练本地模型
- **HyperOS 主题适配**：TopAppBar 和悬浮底栏 100% 复刻 HyperOS 原生样式与动画，系统版本自动检测
- **UI/UX 全面优化**：Miuix 风格设置页、统一 TabRow、页面过渡动画、预测式返回、横滑手势切页
- **VNC/SSH/文件管理**：远程管理页面统一搜索 UI，VNC 基于 AVNC + libvncserver，SSH 基于 connectbot
- **资源页一键部署**：Ubuntu/Debian 容器、QEMU 虚拟机、朱雀面板、Python 环境等一键安装脚本
- **LiveUpdate 实时通知**：下载进度分段显示、Agent 思考状态、包管理通知优化

### Termux Ultra 2.0.0.R5 — 🚀 重大上游基底升级

> 📅 **2026-09-19 合入 main**，以大 commit 方式将 2.x 分支完整内容覆盖主线，v1.8.x 历史全部保留。

- **上游 Termux 核心从 v0.118.3 升级至 v0.119.0-beta.3**：终端模拟器、TermuxService、通知系统、原生库全部同步上游最新代码
- **LiveUpdate 实时通知修复**：v0.119 基底引入了新的 `POST_PROMOTED_NOTIFICATIONS` API（Android 15+），适配旧版 API 兼容层（sdk_int < 36 回退普通通知），解决之前 2.x 分支通知不弹的问题
- **Notification 渠道与权限全面适配**：`AndroidManifest.xml` 补齐 `POST_PROMOTED_NOTIFICATIONS` 权限声明，`TermuxService` 中通知构建改用新 API
- **Kotlin 三件套 patch 升级**：compose 2.3.10→2.3.21、serialization 2.3.10→2.3.21、ksp 2.3.10→2.3.12，为后续升 2.4.x 大版本铺路
- **CI Workflow 简化**：GitHub Actions 切换到 main 的 `ci.yml`（`ubuntu-26.04` + `checkout@v7` + `setup-java@v4`），移除了原来复杂的 matrix 构建
- **Dependabot 重新配置**：新增 gradle 生态版本跟踪，配置 ignore 避免 Kotlin 三件套单独升大版本导致不一致
- **分支架构重组**：`main` 承接 2.0 beta 主线，`release/r1-r4` 保存 v1.8.0 历史快照（停止功能更新），`archived/corebump/2.x` 归档 2.x 内部开发步骤

### VorteX Guard Engine (v1.7.0)

> ⚠️ **原增强防护模块已全面改版升级为 VorteX Guard Engine (VGE)**，Shell hook 架构重写、检测更精准、零终端干扰

- **VorteX Guard Engine 内核重构**：Shell hook 架构全面重写，彻底解决长时间运行后终端无提示符、输入无回显、oh-my-bash 主题崩坏等问题
- **TCP 通信隔离**：所有服务端通信放入子 Shell 执行，父进程零 fd 改动，杜绝 PTY termios 被意外修改
- **DEGUB trap 安全化**：extdebug 不再全局常开，仅在 DENY 跳过命令时瞬时开启，PROMPT_COMMAND 前兜底关闭
- **函数覆盖替代 trap**：su/sudo/dd/mkfs 等高危单词命令改为函数覆盖拦截，零干扰 bash 内部行为
- **初始化宽限期**：OMB/OMZ 初始化脚本在安全模块加载期间自动放行，避免框架初始化被拦截
- **PTY termios 修复**：JNI 层显式设置 ECHO|ICANON|ISIG，解决 Android toybox stty 在某些设备上不生效的问题
- **OMB/OMZ 适配**：自动检测 oh-my-bash / oh-my-zsh 并通过其原生 preexec/precmd 接口注册，零 DEBUG trap 干扰
- **高危命令二次确认**：增强模式支持 OFF（关闭）、WARN_ONLY（仅提示）、AUTO_BLOCK（自动拦截）、WARN_VERIFY（警告并弹窗验证）四种模式
- **设置实时生效**：增强模式切换后 hook 与 SecuritySocketServer 立即重启，无需重启应用
- **脚本检测覆盖**：bash/sh/zsh/ksh/dash/fish 脚本执行 + ./script.sh 直接执行 + rm -rf / / chmod 777 / 等危险参数组合
- **UTF-8 BOM 修复**：解决 Windows 写入 shell 脚本时自动添加 BOM 导致 bash source 报错的问题

### 插件系统（v2.0.0 升级）
- **Compose JSON DSL 页面**：`pages[].type = "compose"` 声明由宿主 `ComposeRenderer` 原生渲染的 UI 页面（无需 WebView），支持 column/row/text/card/listItem/switch/button/slider/divider/lazyColumn 等节点
- **宿主 Action 桥（HostActionRegistry）**：资源卡片 `action.hostActionId`、Compose `onClick: "action:xxx"`、H5 `hostAction()` 三处统一入口，可调用 `open_vnc_settings`/`open_termux_styling`/`open_termux_tasker`/`open_termux_widget`/`open_plugin_center`/`open_system_settings` 等宿主原生页面
- **页面导航 API**：H5 内 `bridge.navigate(pageId)` 跳转到同插件任意 compose/h5 子页面
- **统一 action 字符串协议**：`ActionExecutor` 支持 `shell:`/`action:`/`nav:`/直接 URL 四种前缀，Compose 节点 `onClick`/`onChange` 字段直接使用，`{value}` 占位符替换开关当前值
- **插件持久化会话（PluginPersistentSession）**：宿主侧 Kotlin API 提供常驻 shell，可增量读写 transcript、发送 Ctrl+C/EOF、查询 cwd/pid/exitCode；自动注册到 `TermuxService`，退出时由 `PluginPersistentSessionRegistry` 清理
- **minHostVersion 默认升至 2.0.0**：旧插件需显式声明 `minHostVersion: "1.2.0"` 才能在低版本宿主上安装

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
- **插件格式**：ZIP 打包（`.tup` 后缀），安装时校验 manifest 与所有 entry 文件存在性
- **插件能力**：
  - 增加资源页卡片入口（SHELL_COMMAND / OPEN_URL / HOST_ACTION / CUSTOM 四种 action）
  - 增加/修改设置项
  - 提供自定义 Agent Skill
  - 提供 H5 多页面界面（主页 + 子页面）
  - 提供 **Compose JSON DSL 原生页面**（`pages[].type = "compose"`，无需 WebView）
  - 屏蔽/禁用系统功能
  - 修改 Agent System Prompt（APPEND/MODIFY/OVERWRITE）
  - 跨应用联动
- **宿主 Action 桥**：资源卡片、Compose `onClick`、H5 `hostAction()` 三处统一调用宿主原生入口（VNC 设置、Styling、Tasker、Widget、插件中心、系统设置等）
- **页面导航 API**：H5 内 `navigate(pageId)` 跳转到同插件任意 compose/h5 子页面
- **统一 action 协议**：`shell:` / `action:` / `nav:` / 直接 URL 四种前缀，支持 `{value}` 占位符
- **持久化会话**：宿主侧 `PluginPersistentSession` 提供常驻 shell，可增量读写、发送 Ctrl+C/EOF
- **权限系统**：ROOT 执行、会话访问、文件读写、跨应用联动、H5 WebView、网络访问、Agent 修改
- **管理界面**：插件安装、启用/禁用、配置查看、卸载，OVERWRITE 模式二次确认
- **H5 Bridge**：`window.TermuxUltra` 对象，含 exec/getConfig/setConfig/readFile/hostAction/navigate 等 API

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
发行渠道如下：

| <img src="https://avatars.githubusercontent.com/in/15368?s=64&v=4" width = "30" height = "30" alt="LOGO"/> | [GitHub CI](https://github.com/TiG-Kira/Termux-Ultra/actions/workflows/ci.yml) | CI 自动构建 (测试版)，每次 commit 自动构建，适合尝鲜与测试 PR，需登录 GitHub 账号下载 Artifacts。 |
|------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|---------------|

| <img src="https://avatars.githubusercontent.com/in/15368?s=64&v=4" width = "30" height = "30" alt="LOGO"/> | [GitHub Releases](https://github.com/TiG-Kira/Termux-Ultra/releases) | 正式版 (稳定版) ，发布页 `Assets` 下提供各架构 APK。|
|------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|-----------|

- Debug 版本仅输出 universal APK（`termux-ultra_debug_universal.apk`），安装包 + bootstrap 约 `~180MB`。
- Release 版本输出各架构独立 APK，使用架构包约 `~120MB`。
- GitHub 来源的 APK 均为 `debuggable`，彼此兼容，但与其他来源不兼容。

### 关于 Google Play 商店（已弃用）

原版 Termux 及其插件因 [Android 10 问题](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10) 已在 Play Store 停止更新，最后版本为 `v0.101`。
>**强烈建议不再从 Play Store 安装 Termux 系应用**，请迁移至 GitHub 或 F-Droid 来源。

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
│   │   │   │   ├── PluginManager.kt              # 插件管理器核心（启停/权限/会话）
│   │   │   │   ├── PluginManifest.kt             # 插件清单数据模型
│   │   │   │   ├── PluginTypes.kt                # 插件类型 / 权限 / 风险等级枚举
│   │   │   │   ├── PluginLoader.kt               # 插件 ZIP 加载/校验/状态持久化
│   │   │   │   ├── PluginSecurity.kt             # 插件安全校验与权限文案
│   │   │   │   ├── ActionExecutor.kt             # 统一 action 字符串执行器（shell:/action:/nav:/URL）
│   │   │   │   ├── HostActionRegistry.kt         # 宿主原生入口注册表（open_vnc_settings 等）
│   │   │   │   ├── ComposeUiNode.kt              # Compose JSON DSL 数据模型与解析器
│   │   │   │   ├── ComposeRenderer.kt           # Compose JSON 节点渲染器
│   │   │   │   ├── PluginComposeActivity.kt     # Compose 插件页面宿主 Activity
│   │   │   │   ├── PluginWebViewActivity.kt     # H5 插件页面宿主 Activity + JS Bridge
│   │   │   │   ├── PluginCenterActivity.kt      # 插件中心列表 / 安装 / 卸载 UI
│   │   │   │   ├── PluginPersistentSession.kt        # 插件持久化 shell 会话
│   │   │   │   └── PluginPersistentSessionRegistry.kt # 会话 handle → sessionId 反查表
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

Termux Ultra v2.0.0 插件系统允许第三方开发者扩展应用功能。插件采用 **ZIP 格式**（`.tup` 后缀）打包，可在 资源页 → 插件中心 安装。

相比 v1.2.0，v2.0.0 新增能力：

- **Compose JSON DSL 页面**：`pages[].type = "compose"` 声明由宿主 `ComposeRenderer` 原生渲染的 UI（无需 WebView）
- **宿主 Action 桥（HostActionRegistry）**：资源卡片 `action.hostActionId`、Compose `onClick: "action:xxx"`、H5 `hostAction()` 三处统一调用宿主原生入口
- **页面导航 API**：H5 内 `navigate(pageId)` 跳转到同插件任意 compose/h5 子页面
- **统一 action 字符串协议**：`ActionExecutor` 支持 `shell:`/`action:`/`nav:`/直接 URL 四种前缀，Compose 节点 `onClick`/`onChange` 直接使用
- **插件持久化会话**：宿主侧 `PluginPersistentSession` 提供常驻 shell，可增量读写、发送 Ctrl+C/EOF（host-side Kotlin API，非 JS Bridge）
- **minHostVersion 默认升至 2.0.0**

### 快速开始

1. 创建插件目录结构
2. 编写 `manifest.json`
3. 添加功能代码（H5/Compose/Skill/资源卡片）
4. 打包为 ZIP（重命名 `.tup`）
5. 在插件中心安装测试

### 插件目录结构

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

> **页面文件位置规范**：所有页面文件（HTML/CSS/JS/图片/Compose JSON）必须打包在插件根目录下，`manifest.json` 中 `entry` 字段使用相对于插件根目录的路径（如 `web/index.html`、`compose/home.json`）。插件安装时会校验所有 `entry` 指向的文件是否存在，缺失则报错。

### manifest.json 字段总览

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
    "resourceCards": [...],
    "settingItems": [...],
    "agentSkills": [...],
    "h5Home": {...},
    "pages": [...]
  },
  "systemPrompt": {...}
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
| `entryPoints` | object | 否 | `null` | 入口点配置（资源卡片/设置项/Skill/H5 主页/子页面） |
| `systemPrompt` | object | 否 | `null` | System Prompt 修改策略 |

### 权限声明

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

### 1. 资源卡片（resourceCards）

在 manifest.json 的 `entryPoints.resourceCards` 中声明插件资源卡片，点击卡片会执行 `action`。支持四种 action 类型：

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

| action.type | 必填字段 | 说明 |
|-------------|----------|------|
| `SHELL_COMMAND` | `command` | 在 Termux shell 中执行命令（需 `TERMUX_SESSION_ACCESS` 或 `ROOT_EXECUTE`） |
| `OPEN_URL` | `url` | 在外部浏览器打开链接 |
| `HOST_ACTION` | `hostActionId` | 调用宿主 `HostActionRegistry` 注册的原生入口（见 [宿主内置 Action 列表](#7-宿主内置-action-列表)） |
| `CUSTOM` | — | 自定义类型，由宿主扩展处理 |

> `id` 在同插件内必须唯一，对外暴露的最终卡片 ID 为 `{pluginId}.{cardId}`。

### 2. 自定义 Agent Skill（agentSkills）

在 manifest.json 的 `entryPoints.agentSkills` 中声明自定义 Skill，AI 助手会将这些 Skill 注入到 System Prompt 的技能卡片中：

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
| `riskLevel` | string | `"NONE"` | 风险等级：`NONE`/`LOW`/`MEDIUM`/`HIGH`/`CRITICAL` |
| `cardFormat` | object | `null` | 自定义卡片渲染格式（仅当插件修改了 Prompt 卡片逻辑时才需提供） |

> `cardFormat` 为可选字段。若插件未修改 System Prompt 的卡片格式逻辑，系统使用默认渲染。

### 3. System Prompt 扩展（systemPrompt）

在 manifest.json 的 `systemPrompt` 中声明 System Prompt 修改策略，AI 助手会据此调整核心 Prompt：

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
| `mode` | string | `"APPEND"` | 修改模式：`APPEND`/`MODIFY`/`OVERWRITE` |
| `content` | string | — | 内联文本，直接写入 System Prompt（不支持文件路径引用） |
| `cardFormat` | object | `null` | 自定义卡片格式（可选） |

修改模式说明：
- `APPEND`：追加内容到核心 Prompt 末尾（低风险，启用时直接生效）
- `MODIFY`：替换指定段落（中风险）
- `OVERWRITE`：完全覆盖核心规则（**极高风险**，启用时系统弹出二次确认警告对话框 `PluginOverwriteDialog`，用户确认后才生效）

> **注意**：`content` 字段为内联文本内容，直接写入 System Prompt，不支持文件路径引用。OVERWRITE 模式下，插件的 System Prompt 将完全替换原有的 System Prompt，设置中的自定义 System Prompt 入口会变为"还原 System Prompt"。

### 4. H5 多页面界面（h5Home + pages）

插件支持**多页面 H5 界面**，通过 `manifest.json` 的 `entryPoints` 配置。`pages[].type` 既可 `"h5"`（WebView 加载 HTML）也可 `"compose"`（见 [5. Compose JSON DSL 页面](#5-compose-json-dsl-页面v200-新增)）。

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
      },
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

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `h5Home.enabled` | boolean | 是 | 是否启用 H5 主页 |
| `h5Home.entry` | string | 是 | 主页入口文件路径（相对于插件根目录，默认 `web/index.html`） |
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

> WebView 以当前 HTML 文件所在目录为基准解析相对路径；外部 `http://`/`https://` 链接会被拦截到系统浏览器。

#### JS Bridge API

H5 页面通过 `window.TermuxUltra` 对象访问原生能力。完整 API 列表（含 v2.0.0 新增）：

| API | 返回 | 说明 | 版本 |
|-----|------|------|------|
| `getPluginInfo()` | JSON 字符串 | 插件元信息（id/name/version/enabled/permissions） | v1.2.0 |
| `getConfig()` | JSON 字符串 | 插件配置 Map | v1.2.0 |
| `setConfig(key, value)` | boolean | 保存配置项到 SharedPreferences | v1.2.0 |
| `exec(command)` | JSON 字符串 | 执行终端命令 `{success, output\|error}` | v1.2.0 |
| `readFile(path)` | JSON 字符串 | 读取插件包内文件 `{success, content\|error}` | v1.2.0 |
| `openUrl(url)` | void | 在外部浏览器打开链接 | v1.2.0 |
| `toast(message)` | void | 显示 Toast 提示 | v1.2.0 |
| `getDeviceInfo()` | JSON 字符串 | 设备信息（model/brand/androidVersion/sdkVersion/termuxVersion/rootAvailable） | v1.2.0 |
| `finishPage()` | void | 关闭当前插件页面 | v1.2.0 |
| `hostAction(actionId)` | JSON 字符串 | 调用宿主 HostAction 注册的原生入口 | v2.0.0 新增 |
| `navigate(pageId)` | JSON 字符串 | 跳转到同插件内另一页面（compose/h5 均可） | v2.0.0 新增 |

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

> `hostAction` / `navigate` 返回 `{"success": true/false}`，宿主未注册该 actionId 或找不到 pageId 时返回 `false`。

### 5. Compose JSON DSL 页面（v2.0.0 新增）

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

#### 支持的 Compose 节点类型

| type | 关键 props | 说明 |
|------|------------|------|
| `column` | `padding` | 垂直布局，子节点用 `children` |
| `row` | — | 水平布局 |
| `text` | `text`、`fontSize`、`fontWeight`（"Bold"） | 文本 |
| `card` | `title` | 卡片容器，子节点用 `children` |
| `listItem` | `title`、`subtitle`、`onClick` | 列表项，点击触发 action |
| `switch` | `stateKey`、`label`、`onChange` | 开关，值自动持久化到插件配置 |
| `button` | `text`、`onClick` | 按钮 |
| `slider` | `stateKey` | 滑块，值存入 stateStore |
| `spacer` | `height` | 间距（dp） |
| `divider` | — | 水平分割线 |
| `lazyColumn` | `itemsSource`、`itemTemplate` | 动态列表，支持 shell 数据源 |

> `lazyColumn.itemsSource` 当前仅支持 `{ "type": "shell", "command": "..." }`，shell 输出会优先按 JSON 数组解析，失败则按空白分隔的行解析（字段映射为 `serial`/`status`/`raw`）。`itemTemplate` 的 props 支持 `{key}` 占位符。

#### action 字符串协议（onClick / onChange）

Compose 节点的 `onClick`/`onChange` 字段使用 `ActionExecutor` 统一协议：

| 前缀 | 示例 | 说明 |
|------|------|------|
| `shell:` | `shell:uname -a` | 在插件 shell 中执行命令（需 `TERMUX_SESSION_ACCESS` 或 `ROOT_EXECUTE`） |
| `action:` | `action:open_vnc_settings` | 调用宿主 HostAction（见 [7. 宿主内置 Action 列表](#7-宿主内置-action-列表)） |
| `nav:` | `nav:page_about` | 跳转到同插件其他页面（按 `pages[].id` 匹配，compose/h5 均可） |
| `http://`/`https://` | `https://example.com` | 在外部浏览器打开 |
| `{value}` 占位 | `shell:echo {value}` | `switch.onChange` 中替换为当前布尔值（`true`/`false`） |

> 未匹配任何前缀的字符串会被忽略（返回 `false`）。

### 6. 宿主 Action 桥（HostActionRegistry）

`HostActionRegistry` 是宿主侧 Kotlin 单例，维护 `actionId → handler` 映射，供三处统一调用：

1. **资源卡片**：`action.type = "HOST_ACTION"` + `action.hostActionId`
2. **Compose 节点**：`onClick: "action:xxx"`
3. **H5 JS Bridge**：`bridge.hostAction("xxx")`

宿主启动时在 `HostActionRegistry.registerDefaults()` 注册所有内置入口；插件作者**只能引用已注册的 actionId，无法自行注册新的**（自定义宿主扩展需修改宿主源码）。

调用示例对照表：

| 调用位置 | 代码 |
|----------|------|
| manifest.json 资源卡片 | `{"action": {"type":"HOST_ACTION","hostActionId":"open_vnc_settings"}}` |
| Compose 节点 onClick | `"onClick": "action:open_vnc_settings"` |
| H5 JS Bridge | `bridge.hostAction("open_vnc_settings")` |

### 7. 宿主内置 Action 列表

`HostActionRegistry.registerDefaults()` 在宿主启动时注册以下 `hostActionId`：

| hostActionId | 说明 |
|--------------|------|
| `open_vnc_settings` | 打开 AVNC 设置页 |
| `open_termux_styling` | 打开 Termux:Styling（配色/字体） |
| `open_termux_tasker` | 打开 Termux:Tasker |
| `open_termux_widget` | 打开 Termux:Widget |
| `open_plugin_center` | 打开插件中心 |
| `open_system_settings` | 打开 Termux Ultra 系统设置 |

> 宿主可在 `HostActionRegistry.registerDefaults()` 中追加更多原生入口（每加一个原生页面只需一行 `register(...)`）。

### 8. 插件持久化会话（host-side Kotlin API）

`PluginPersistentSession` 是宿主侧提供给 Kotlin 代码（如自定义 Agent Skill handler、Compose 渲染扩展、原生 Module）使用的常驻 shell 会话，**不通过 JS Bridge 暴露**。

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

#### PluginPersistentSession API 概览

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

> 会话会自动注册到 `TermuxService.mTermuxSessions`，终端页面也能管理；会话退出时 `PluginPersistentSessionRegistry` 通过 `TerminalSession.mHandle` 反查 sessionId 并清理 `PluginManager` 注册表。同插件可访问性由 `PluginPersistentSession.pluginId` 校验，跨插件访问会记 warn 并返回 null。

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
