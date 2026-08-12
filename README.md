# Termux Ultra

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](./LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%207.0%2B-green.svg)]()
[![Based on Termux](https://img.shields.io/badge/Based%20on-Termux%20v0.118.x-orange.svg)](https://github.com/termux/termux-app)

[![Build status](https://github.com/TiG-Kira/Termux-Ultra/workflows/Build/badge.svg)](https://github.com/TiG-Kira/Termux-Ultra/actions)

**Termux Ultra** 是一款基于 [Termux](https://github.com/termux/termux-app) 二次开发的 Android 终端模拟器与 Linux 环境应用。它在保留 Termux 原生终端能力的基础上，集成了 VNC 远程桌面、SSH 连接管理、文件管理器、Linux 容器（proot）、QEMU 虚拟机、一键资源部署等增强功能，并将 5 款 Termux 插件（API、Boot、Styling、Tasker、Widget）内置为可开关的集成工具，无需额外安装。UI 采用 Jetpack Compose + Miuix 设计语言打造。

> 本仓库为应用本体（用户界面、终端模拟及扩展功能）。应用内可安装的软件包请参见 [termux/termux-packages](https://github.com/termux/termux-packages)。

***

## 最近更新

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

### 交互与动画
- 首页横滑手势切换页面（终端 → 文件 → 远程 → 资源）
- 页面切换叠加动画与左右切换动画，支持预测式返回
- 卡片圆角与点击反馈裁剪统一
- 底部导航避让与边距修正，防止误触

## 应用与插件

Termux Ultra 将以下 5 款 Termux 插件的源码集成到主应用中（位于 `vendor/termux-addons/`），作为可开关的内置工具，无需额外安装独立 APK：

- [Termux:API](https://github.com/termux/termux-api) — 已集成
- [Termux:Boot](https://github.com/termux/termux-boot) — 已集成
- [Termux:Styling](https://github.com/termux/termux-styling) — 已集成（使用合并包名 `com.termux`）
- [Termux:Tasker](https://github.com/termux/termux-tasker) — 已集成
- [Termux:Widget](https://github.com/termux/termux-widget) — 已集成

> 集成工具默认关闭，在 `设置` → `集成工具` 中按需开启。若设备已安装对应的官方独立 APK，开关将自动禁用以避免冲突。

以下插件尚未集成，仍需作为独立应用安装（需使用相同签名来源）：

- [Termux:Float](https://github.com/termux/termux-float) — 悬浮终端窗口

## 系统要求

- Android `>= 7.0`（API 24）
- targetSdk `28`，compileSdk `37`
- 支持架构：`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`

## 安装

Termux Ultra 与原版 Termux 及其所有插件共享 `sharedUserId`（`com.termux`），因此设备上安装的本应用与所有插件 APK **必须使用同一签名来源**，否则将无法协同工作，安装时也会出现 `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`、`signatures do not match` 等错误。

- 请勿混用来源（例如 F-Droid 装一个、GitHub 装另一个）。
- 如需更换来源，请先**卸载所有已安装的 Termux 及其插件 APK**，再从同一新来源全部安装。卸载前建议参考 [Backing up Termux](https://wiki.termux.com/wiki/Backing_up_Termux) 备份数据。

> “bootstrap” 指 `termux-app` 自带的用于启动最小 shell 环境的最小包集合，其 zip 由 [termux/termux-packages releases](https://github.com/termux/termux-packages/releases) 构建发布。

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
│   │   │   ├── container_run.sh          # proot 容器启动脚本
│   │   │   ├── install_linux_container.sh # Linux 容器安装（Ubuntu/Debian）
│   │   │   ├── install_qemu.sh           # 容器内 QEMU 安装
│   │   │   ├── install_lightpanel.sh     # 朱雀面板安装脚本
│   │   │   ├── qemu_termux_setup.sh      # Termux 内 QEMU 配置
│   │   │   ├── gen_seed_iso.sh           # Seed ISO 生成
│   │   │   ├── run_in_container.sh       # 容器内脚本执行入口
│   │   │   ├── minecraft_server_wrapper.sh
│   │   │   ├── seed.iso                  # 预生成 Seed ISO
│   │   │   └── resolv.conf               # DNS 配置
│   │   ├── cpp/                # CMake 原生构建（termux-bootstrap）
│   │   ├── cpp_avnc/           # AVNC 原生 VNC 客户端
│   │   ├── java/com/termux/    # 应用 Kotlin/Java 源码
│   │   │   ├── app/            # 核心逻辑（TermuxActivity、TermuxService 等）
│   │   │   ├── app/compose/    # Jetpack Compose UI（主页、文件、远程、资源、设置、AI 助手等）
│   │   │   │   ├── AiTermuxActivity.kt   # AI 助手界面
│   │   │   │   ├── AiTermuxEngine.kt     # AI 引擎与技能执行器
│   │   │   │   └── AiTermuxModels.kt     # AI 数据模型与配置
│   │   │   ├── app/vnc/        # VNC 连接管理
│   │   │   ├── app/ssh/        # SSH 连接管理
│   │   │   ├── app/remote/     # 远程管理综合页
│   │   │   ├── app/ftp/        # 内置 FTP 服务器
│   │   │   └── app/activities/ # 第三方资源中心、实用工具中心、AI 助手等
│   │   ├── jniLibs/            # 预编译 .so 库
│   │   └── res/                # 资源（布局、drawable、strings、xml 偏好）
│   ├── extern/                 # 第三方原生库源码
│   │   ├── libjpeg-turbo/      # JPEG 编解码
│   │   ├── libvncserver/       # VNC 服务端库
│   │   └── wolfssl/            # TLS/SSL 库
│   └── CMakeLists.txt          # 原生构建配置
├── vendor/termux-addons/       # 集成的 Termux 插件源码
│   ├── termux-api/             # Termux:API
│   ├── termux-boot/            # Termux:Boot
│   ├── termux-styling/         # Termux:Styling
│   ├── termux-tasker/          # Termux:Tasker
│   └── termux-widget/          # Termux:Widget
├── terminal-emulator/          # 终端模拟器模块
├── terminal-view/              # 终端视图模块
├── termux-shared/              # 共享常量与工具库
├── art/                        # 图标与宣传图脚本
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
| 构建 | Gradle、CMake 3.22.1、NDK 22.1.7171670 |
| 集成插件 | termux-api、termux-boot、termux-styling、termux-tasker、termux-widget |
| 包名 | `com.termux`（sharedUserId） |

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
- 提交信息遵循 [Conventional Commits](https://www.conventionalcommits.org) 规范（如 `Added: 新增功能`、`Fixed: 修复问题`、`Changed!: 破坏性变更`），冒号后需有空格。
- `versionName` 遵循 [语义化版本 2.0.0](https://semver.org/spec/v2.0.0.html)，格式 `major.minor.patch(-prerelease)(+buildmetadata)`，如 `v0.1.0`。

### Fork 注意事项

- 修改包名需重新编译对应 `$PREFIX` 的 bootstrap zip，参见 [Building Packages](https://github.com/termux/termux-packages/wiki/Building-packages)。
- 集成插件（`vendor/termux-addons/`）的组件在 `AndroidManifest.xml` 中默认以 `android:enabled="false"` 声明，运行时通过 `IntegratedTools.applyComponentState()` 动态启停，Fork 时需保持此模式。
- Termux:Styling 使用合并包名 `com.termux` 而非原始 `com.termux.styling`，修改包名时需同步更新 `IntegratedTools.kt` 中的组件映射。
- 部分插件尚未完全迁移到 `termux-shared` 的 `TermuxConstants`，仍存在硬编码 `com.termux`，需手动 patch。

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

***

# 附录：原 Termux README

> 以下为上游项目 [termux/termux-app](https://github.com/termux/termux-app) 的原始 README 内容，保留以供参考。

# Termux application

[![Build status](https://github.com/termux/termux-app/workflows/Build/badge.svg)](https://github.com/termux/termux-app/actions)
[![Testing status](https://github.com/termux/termux-app/workflows/Unit%20tests/badge.svg)](https://github.com/termux/termux-app/actions)
[![Join the chat at https://gitter.im/termux/termux](https://badges.gitter.im/termux/termux.svg)](https://gitter.im/termux/termux)
[![Join the Termux discord server](https://img.shields.io/discord/641256914684084234.svg?label=&logo=discord&logoColor=ffffff&color=5865F2)](https://discord.gg/HXpF69X)
[![Termux library releases at Jitpack](https://jitpack.io/v/termux/termux-app.svg)](https://jitpack.io/#termux/termux-app)


[Termux](https://termux.com) is an Android terminal application and Linux environment.

Note that this repository is for the app itself (the user interface and the terminal emulation). For the packages installable inside the app, see [termux/termux-packages](https://github.com/termux/termux-packages).

Quick how-to about Termux package management is available at [Package Management](https://github.com/termux/termux-packages/wiki/Package-Management). It also has info on how to fix **`repository is under maintenance or down`** errors when running `apt` or `pkg` commands.

***

**@termux is looking for Termux Application maintainers for implementing new features, fixing bugs and reviewing pull requests since the current one (@fornwall) is inactive.**

Issue https://github.com/termux/termux-app/issues/1072 needs extra attention.

***

### Contents
- [Termux App and Plugins](#Termux-App-and-Plugins)
- [Installation](#Installation)
- [Uninstallation](#Uninstallation)
- [Important Links](#Important-Links)
- [Debugging](#Debugging)
- [For Maintainers and Contributors](#For-Maintainers-and-Contributors)
- [Forking](#Forking)
##



## Termux App and Plugins

The core [Termux](https://github.com/termux/termux-app) app comes with the following optional plugin apps.

- [Termux:API](https://github.com/termux/termux-api)
- [Termux:Boot](https://github.com/termux/termux-boot)
- [Termux:Float](https://github.com/termux/termux-float)
- [Termux:Styling](https://github.com/termux/termux-styling)
- [Termux:Tasker](https://github.com/termux/termux-tasker)
- [Termux:Widget](https://github.com/termux/termux-widget)
##



## Installation

Latest version is `v0.118.1`.

Termux can be obtained through various sources listed below for **only** Android `>= 7`. Support was dropped for Android `5` and `6` on [2020-01-01](https://www.reddit.com/r/termux/comments/dnzdbs/end_of_android56_support_on_20200101/) at `v0.83`, old builds are available on [archive.org](https://archive.org/details/termux-repositories-legacy).

The APK files of different sources are signed with different signature keys. The `Termux` app and all its plugins use the same [`sharedUserId`](https://developer.android.com/guide/topics/manifest/manifest-element) `com.termux` and so all their APKs installed on a device must have been signed with the same signature key to work together and so they must all be installed from the same source. Do not attempt to mix them together, i.e do not try to install an app or plugin from `F-Droid` and another one from a different source like `Github`. Android Package Manager will also normally not allow installation of APKs with different signatures and you will get errors on installation like `App not installed`, `Failed to install due to an unknown error`, `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`, `signatures do not match previously installed version`, etc. This restriction can be bypassed with root or with custom roms.

If you wish to install from a different source, then you must **uninstall any and all existing Termux or its plugin app APKs** from your device first, then install all new APKs from the same new source. Check [Uninstallation](#Uninstallation) section for details. You may also want to consider [Backing up Termux](https://wiki.termux.com/wiki/Backing_up_Termux) before the uninstallation so that you can restore it after re-installing from Termux different source.

In the following paragraphs, *"bootstrap"* refers to the minimal packages that are shipped with the `termux-app` itself to start a working shell environment. Its zips are built and released [here](https://github.com/termux/termux-packages/releases).

### F-Droid

Termux application can be obtained from `F-Droid` from [here](https://f-droid.org/en/packages/com.termux/).

You **do not** need to download the `F-Droid` app (via the `Download F-Droid` link) to install Termux. You can download the Termux APK directly from the site by clicking the `Download APK` link at the bottom of each version section.

It usually takes a few days (or even a week or more) for updates to be available on `F-Droid` once an update has been released on `Github`. The `F-Droid` releases are built and published by `F-Droid` once they [detect](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/com.termux.yml) a new `Github` release. The Termux maintainers **do not** have any control over the building and publishing of the Termux apps on `F-Droid`. Moreover, the Termux maintainers also do not have access to the APK signing keys of `F-Droid` releases, so we cannot release an APK ourselves on `Github` that would be compatible with `F-Droid` releases.

The `F-Droid` app often may not notify you of updates and you will manually have to do a pull down swipe action in the `Updates` tab of the app for it to check updates. Make sure battery optimizations are disabled for the app, check https://dontkillmyapp.com/ for details on how to do that.

Only a universal APK is released, which will work on all supported architectures. The APK and bootstrap installation size will be `~180MB`. `F-Droid` does [not support](https://github.com/termux/termux-app/pull/1904) architecture specific APKs.

### Github

Termux application can be obtained on `Github` either from [`Github Releases`](https://github.com/termux/termux-app/releases) for version `>= 0.118.0` or from [`Github Build`](https://github.com/termux/termux-app/actions/workflows/debug_build.yml) action workflows.

The APKs for `Github Releases` will be listed under `Assets` drop-down of a release. These are automatically attached when a new version is released.

The APKs for `Github Build` action workflows will be listed under `Artifacts` section of a workflow run. These are created for each commit/push done to the repository and can be used by users who don't want to wait for releases and want to try out the latest features immediately or want to test their pull requests. Note that for action workflows, you need to be [**logged into a `Github` account**](https://github.com/login) for the `Artifacts` links to be enabled/clickable. If you are using the [`Github` app](https://github.com/mobile), then make sure to open workflow link in a browser like Chrome or Firefox that has your Github account logged in since the in-app browser may not be logged in. 

The APKs for both of these are [`debuggable`](https://developer.android.com/studio/debug) and are compatible with each other but they are not compatible with other sources.

Both universal and architecture specific APKs are released. The APK and bootstrap installation size will be `~180MB` if using universal and `~120MB` if using architecture specific. Check [here](https://github.com/termux/termux-app/issues/2153) for details.

### Google Play Store **(Deprecated)**

**Termux and its plugins are no longer updated on [Google Play Store](https://play.google.com/store/apps/details?id=com.termux) due to [android 10 issues](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10) and have been deprecated.** The last version released for Android `>= 7` was `v0.101`. **It is highly recommended to not install Termux apps from Play Store any more.**

There are plans for **unpublishing** the Termux app and all its plugins on Play Store soon so that new users cannot install it and for **disabling** the Termux apps with updates so that existing users **cannot continue using outdated versions**. You are encouraged to move to `F-Droid` or `Github` builds as soon as possible.

You **will not need to buy plugins again** if you bought them on Play Store. All plugins are free on `F-Droid` and  `Github`.

You can backup all your data under `$HOME/` and `$PREFIX/` before changing installation source, and then restore it afterwards, by following instructions at [Backing up Termux](https://wiki.termux.com/wiki/Backing_up_Termux) before the uninstallation.

There is currently no work being done to solve android `10` issues and *working* updates will not be resumed on Google Play Store any time soon. We will continue targeting sdk `28` for now. So there is not much point in staying on Play Store builds and waiting for updates to be resumed. If for some reason you don't want to move to `F-Droid` or `Github` sources for now, then at least check [Package Management](https://github.com/termux/termux-packages/wiki/Package-Management) to **change your mirror**, otherwise, you will get **`repository is under maintenance or down`** errors when running `apt` or `pkg` commands. After that, it is also **highly advisable** to run `pkg upgrade` command to update all packages to the latest available versions, or at least update `termux-tools` package with `pkg install termux-tools` command. 

Note that by upgrading old packages to latest versions, like that of `python` may break your setups/scripts since they may not be compatible anymore. Moreover, you will not be able to downgrade the package versions since termux repos only keep the latest version and you will have to manually rebuild the old versions of the packages if required as per https://github.com/termux/termux-packages/wiki/Building-packages.

If you plan on staying on Play Store sources in future as well, then you may want to **disable automatic updates in Play Store** for Termux apps, since if and when updates to disable Termux apps are released, then **you will not be able to downgrade** and **will be forced** to move since apps won't work anymore. Only a way to backup `termux-app` data may be provided. The `termux-tools` [version `>= 0.135`](https://github.com/termux/termux-packages/pull/7493) will also show a banner at the top of the terminal saying `You are likely using a very old version of Termux, probably installed from the Google Play Store.`, you can remove it by running `rm -f /data/data/com.termux/files/usr/etc/motd-playstore` and restarting the app.

#### Why Disable?

<details>
<summary></summary>

- They should be disabled because deprecated things get removed and are not supported after some time, its the standard practice. It has been many months now since deprecation was announced and updates have not been released on Play Store since after `29 September 2020`.

- The new versions have lots of **new features and fixes** which you can mostly check out in the Changelog of [`Github Releases`](https://github.com/termux/termux-app/releases) that you may be missing out. Extra detail is usually provided in [commit messages](https://github.com/termux/termux-app/commits/master).

- Users on old versions are quite often reporting issues in multiple repositories and support forums that were **fixed months ago**, which we then have to deal with. The maintainers of @termux work in their free time, majorly for free, to work on development and provide support and having to re-re-deal with old issues takes away the already limited time from current work and is not possible to continue doing. Play Store page of `termux-app` has been filled with bad reviews of *"broken app"*, even though its clearly mentioned on the page that app is not being updated, yet users don't read and still install and report issues.

- Asking people to pay for plugins when the `termux-app` at installation time is broken due to repository issues and has bugs is unethical.

- Old versions don't have proper logging/debugging and crash report support. Reporting bugs without logs or detailed info is not helpful in solving them.

- It's also easier for us to solve package related issues and provide custom functionality with app updates, which can't be done if users continue using old versions. For example, the [bintray shudown](https://github.com/termux/termux-packages/wiki/Package-Management) causing package install/update failures for new Play Store users is/was not an issue for F-Droid users since it is being shipped with updated bootstrap and repo info, hence no reported issues from new F-Droid users.
</details>

##



## Uninstallation

Uninstallation may be required if a user doesn't want Termux installed in their device anymore or is switching to a different [install source](#Installation). You may also want to consider [Backing up Termux](https://wiki.termux.com/wiki/Backing_up_Termux) before the uninstallation.

To uninstall Termux completely, you must uninstall **any and all existing Termux or its plugin app APKs** listed in [Termux App and Plugins](#Termux-App-and-Plugins).

Go to `Android Settings` -> `Applications` and then look for those apps. You can also use the search feature if it’s available on your device and search `termux` in the applications list.

Even if you think you have not installed any of the plugins, it's strongly suggested to go through the application list in Android settings and double-check.
##



## Important Links

### Community
All community links are available [here](https://wiki.termux.com/wiki/Community).

The main ones are the following.

- [Termux Reddit community](https://reddit.com/r/termux)
- [Termux Matrix Channel](https://matrix.to/#termux_termux:gitter.im)
- [Termux Dev Matrix Channel](https://matrix.to/#termux_dev:gitter.im)
- [Termux Twitter](https://twitter.com/termux/)
- [Termux Reports Email](mailto:support@termux.dev)

### Wikis

- [Termux Wiki](https://wiki.termux.com/wiki/)
- [Termux App Wiki](https://github.com/termux/termux-app/wiki)
- [Termux Packages Wiki](https://github.com/termux/termux-packages/wiki)

### Miscellaneous
- [FAQ](https://wiki.termux.com/wiki/FAQ)
- [Termux File System Layout](https://github.com/termux/termux-packages/wiki/Termux-file-system-layout)
- [Differences From Linux](https://wiki.termux.com/wiki/Differences_from_Linux)
- [Package Management](https://wiki.termux.com/wiki/Package_Management)
- [Remote Access](https://wiki.termux.com/wiki/Remote_Access)
- [Backing up Termux](https://wiki.termux.com/wiki/Backing_up_Termux)
- [Terminal Settings](https://wiki.termux.com/wiki/Terminal_Settings)
- [Touch Keyboard](https://wiki.termux.com/wiki/Touch_Keyboard)
- [Android Storage and Sharing Data with Other Apps](https://wiki.termux.com/wiki/Internal_and_external_storage)
- [Android APIs](https://wiki.termux.com/wiki/Termux:API)
- [Moved Termux Packages Hosting From Bintray to IPFS](https://github.com/termux/termux-packages/issues/6348)
- [Running Commands in Termux From Other Apps via `RUN_COMMAND` intent](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)
- [Termux and Android 10](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10)


### Terminal

<details>
<summary></summary>

### Terminal resources

- [XTerm control sequences](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html)
- [vt100.net](https://vt100.net/)
- [Terminal codes (ANSI and terminfo equivalents)](https://wiki.bash-hackers.org/scripting/terminalcodes)

### Terminal emulators

- VTE (libvte): Terminal emulator widget for GTK+, mainly used in gnome-terminal. [Source](https://github.com/GNOME/vte), [Open Issues](https://bugzilla.gnome.org/buglist.cgi?quicksearch=product%3A%22vte%22+), and [All (including closed) issues](https://bugzilla.gnome.org/buglist.cgi?bug_status=RESOLVED&bug_status=VERIFIED&chfield=resolution&chfieldfrom=-2000d&chfieldvalue=FIXED&product=vte&resolution=FIXED).

- iTerm 2: OS X terminal application. [Source](https://github.com/gnachman/iTerm2), [Issues](https://gitlab.com/gnachman/iterm2/issues) and [Documentation](https://iterm2.com/documentation.html) (which includes [iTerm2 proprietary escape codes](https://iterm2.com/documentation-escape-codes.html)).

- Konsole: KDE terminal application. [Source](https://projects.kde.org/projects/kde/applications/konsole/repository), in particular [tests](https://projects.kde.org/projects/kde/applications/konsole/repository/revisions/master/show/tests), [Bugs](https://bugs.kde.org/buglist.cgi?bug_severity=critical&bug_severity=grave&bug_severity=major&bug_severity=crash&bug_severity=normal&bug_severity=minor&bug_status=UNCONFIRMED&bug_status=NEW&bug_status=ASSIGNED&bug_status=REOPENED&product=konsole) and [Wishes](https://bugs.kde.org/buglist.cgi?bug_severity=wishlist&bug_status=UNCONFIRMED&bug_status=NEW&bug_status=ASSIGNED&bug_status=REOPENED&product=konsole).

- hterm: JavaScript terminal implementation from Chromium. [Source](https://github.com/chromium/hterm), including [tests](https://github.com/chromium/hterm/blob/master/js/hterm_vt_tests.js), and [Google group](https://groups.google.com/a/chromium.org/forum/#!forum/chromium-hterm).

- xterm: The grandfather of terminal emulators. [Source](https://invisible-island.net/datafiles/release/xterm.tar.gz).

- Connectbot: Android SSH client. [Source](https://github.com/connectbot/connectbot)

- Android Terminal Emulator: Android terminal app which Termux terminal handling is based on. Inactive. [Source](https://github.com/jackpal/Android-Terminal-Emulator).
</details>

##



### Debugging

You can help debug problems of the `Termux` app and its plugins by setting appropriate `logcat` `Log Level` in `Termux` app settings -> `<APP_NAME>` -> `Debugging` -> `Log Level` (Requires `Termux` app version `>= 0.118.0`). The `Log Level` defaults to `Normal` and log level `Verbose` currently logs additional information. Its best to revert log level to `Normal` after you have finished debugging since private data may otherwise be passed to `logcat` during normal operation and moreover, additional logging increases execution time.

The plugin apps **do not execute the commands themselves** but send execution intents to `Termux` app, which has its own log level which can be set in `Termux` app settings -> `Termux` -> `Debugging` -> `Log Level`. So you must set log level for both `Termux` and the respective plugin app settings to get all the info.

Once log levels have been set, you can run the `logcat` command in `Termux` app terminal to view the logs in realtime (`Ctrl+c` to stop) or use `logcat -d > logcat.txt` to take a dump of the log. You can also view the logs from a PC over `ADB`. For more information, check official android `logcat` guide [here](https://developer.android.com/studio/command-line/logcat).

Moreover, users can generate termux files `stat` info and `logcat` dump automatically too with terminal's long hold options menu `More` -> `Report Issue` option and selecting `YES` in the prompt shown to add debug info. This can be helpful for reporting and debugging other issues. If the report generated is too large, then `Save To File` option in context menu (3 dots on top right) of `ReportActivity` can be used and the file viewed/shared instead.

Users must post complete report (optionally without sensitive info) when reporting issues. Issues opened with **(partial) screenshots of error reports** instead of text will likely be automatically closed/deleted.

##### Log Levels

- `Off` - Log nothing.
- `Normal` - Start logging error, warn and info messages and stacktraces.
- `Debug` - Start logging debug messages.
- `Verbose` - Start logging verbose messages.
##



## For Maintainers and Contributors

The [termux-shared](termux-shared) library was added in [`v0.109`](https://github.com/termux/termux-app/releases/tag/v0.109). It defines shared constants and utils of the Termux app and its plugins. It was created to allow for the removal of all hardcoded paths in the Termux app. Some of the termux plugins are using this as well and rest will in future. If you are contributing code that is using a constant or a util that may be shared, then define it in `termux-shared` library if it currently doesn't exist and reference it from there. Update the relevant changelogs as well. Pull requests using hardcoded values **will/should not** be accepted. Termux app and plugin specific classes must be added under `com.termux.shared.termux` package and general classes outside it. The [`termux-shared` `LICENSE`](termux-shared/LICENSE.md) must also be checked and updated if necessary when contributing code. The licenses of any external library or code must be honoured.

The main Termux constants are defined by [`TermuxConstants`](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java) class. It also contains information on how to fork Termux or build it with your own package name. Changing the package name will require building the bootstrap zip packages and other packages with the new `$PREFIX`, check [Building Packages](https://github.com/termux/termux-packages/wiki/Building-packages) for more info.

Check [Termux Libraries](https://github.com/termux/termux-app/wiki/Termux-Libraries) for how to import termux libraries in plugin apps and [Forking and Local Development](https://github.com/termux/termux-app/wiki/Termux-Libraries#forking-and-local-development) for how to update termux libraries for plugins.

Commit messages **must** use [Conventional Commits](https://www.conventionalcommits.org) specs so that chagelogs can automatically be generated by the [`create-conventional-changelog`](https://github.com/termux/create-conventional-changelog) script, check its repo for further details on the spec. Use the following `types` as `Added: Add foo`, `Added|Fixed: Add foo and fix bar`, `Changed!: Change baz as a breaking change`, etc. You can optionally add a scope as well, like `Fixed(terminal): Some bug`. The space after `:` is necessary.

- **Added** for new features.
- **Changed** for changes in existing functionality.
- **Deprecated** for soon-to-be removed features.
- **Removed** for now removed features.
- **Fixed** for any bug fixes.
- **Security** in case of vulnerabilities.
- **Docs** for updating documentation.

Changelogs for releases are generated based on [Keep a Changelog](https://github.com/olivierlacan/keep-a-changelog) specs.

The `versionName` in `build.gradle` files of Termux and its plugin apps must follow the [semantic version `2.0.0` spec](https://semver.org/spec/v2.0.0.html) in the format `major.minor.patch(-prerelease)(+buildmetadata)`. When bumping `versionName` in `build.gradle` files and when creating a tag for new releases on github, make sure to include the patch number as well, like `v0.1.0` instead of just `v0.1`. The `build.gradle` files and `attach_debug_apks_to_release` workflow validates the version as well and the build/attachment will fail if `versionName` does not follow the spec.
##



## Forking

- Check [`TermuxConstants`](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java) javadocs for instructions on what changes to make in the app to change package name.
- You also need to recompile bootstrap zip for the new package name. Check [here](https://github.com/termux/termux-app/issues/1983) and [here](https://github.com/termux/termux-app/issues/2081#issuecomment-865280111) for experimental work on it.
- Currently, not all plugins use `TermuxConstants` from `termux-shared` library and have hardcoded `com.termux` values and will need to be manually patched.
- If forking termux plugins, check [Forking and Local Development](https://github.com/termux/termux-app/wiki/Termux-Libraries#forking-and-local-development) for info on how to use termux libraries for plugins.
