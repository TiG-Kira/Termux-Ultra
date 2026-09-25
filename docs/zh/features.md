---
title: 功能讲解
lang: zh
ref: features
description: Termux Ultra 架构总览、终端引擎、Miuix UI、集成工具机制、插件系统能力矩阵与权限模型、AI 助手、VorteX Guard 安全引擎、VNC/SSH 实现与技术栈。
---

## 1. 架构总览

Termux Ultra 在保留 Termux 原生终端能力的基础上，叠加了一整套增强子系统：

| 子系统 | 位置 | 职责 |
|--------|------|------|
| 终端核心 | `libterminal` | 终端仿真、屏幕渲染 |
| 应用主体 | `app/src/main/java/com/termux/app/` | `TermuxActivity`、`TermuxService`、通知系统 |
| Compose UI | `app/.../app/compose/` | 主页、文件、远程、资源、设置、AI 助手 |
| 插件系统 | `app/.../app/plugin/` | 插件装载、权限、Action 桥、Compose 渲染 |
| VNC | `app/.../app/vnc/`、`app/src/main/cpp_avnc/` | AVNC 客户端与 libvncserver |
| SSH | `app/.../app/ssh/` | connectbot sshlib 连接管理 |
| FTP | `app/.../app/ftp/` | 内置 FTP 服务器 |
| 集成插件 | `vendor/termux-addons/` | API / Boot / Styling / Tasker / Widget 源码 |
| 共享库 | `termux-shared/` | 跨模块常量与工具（`TermuxConstants`） |

## 2. 终端引擎

**LibTerminal** 是终端核心引擎，负责终端仿真与屏幕渲染。当前版本 **3.1.1**（自 3.0.0 起迭代），在滚动、大批量输出、转义序列处理上表现优异。

## 3. UI：Jetpack Compose + Miuix

UI 全面采用 Jetpack Compose，设计语言为 **Miuix（HyperOS 风格）**：

- **TopAppBar 与悬浮底栏** 100% 复刻 HyperOS 原生样式与动画，并做系统版本自动检测
- **玻璃 / 柔光 / 浮动导航栏**：亮色模式更白、暗色模式更暗，指示器支持拖动切页
- **统一 TabRow** 与页面过渡动画、**预测式返回**、横滑手势切页
- **设置页** 使用 Miuix 的 ArrowPreference 套件，卡片圆角与点击反馈裁剪统一
- 深色 / 浅色自适应，多语言（中文 / 英文，100% 中文覆盖）

依赖版本：Jetpack Compose `1.8.3`、Material 3 `1.3.0`、Miuix KMP `0.9.4`（ui / icons / preference）。

## 4. 集成工具机制

5 款 Termux 插件的源码集成在 `vendor/termux-addons/`，作为**可开关的内置工具**，无需独立 APK。

实现要点：

- 所有集成组件的 Android 组件在 `AndroidManifest.xml` 中默认声明 `android:enabled="false"`。
- 运行时通过 `IntegratedTools` 单例统一启停：`setEnabled()` + `applyComponentState()`，底层调用 `PackageManager.setComponentEnabledSetting()`。
- `IntegratedTools.componentsFor()` 注册组件映射；**禁止绕过单例直接调用 PackageManager**。
- 开启前会检测设备是否已安装同名官方独立 APK，若冲突则自动禁用开关并提示。
- Termux:Styling 使用**合并包名 `com.termux`** 而非原始 `com.termux.styling`。

## 5. 插件系统

### 5.1 能力矩阵

| 能力 | 说明 |
|------|------|
| 资源页卡片 | 增加 `SHELL_COMMAND` / `OPEN_URL` / `HOST_ACTION` / `CUSTOM` 四种 action 的入口 |
| 设置项 | 增加 / 修改设置项 |
| Agent Skill | 为 AI 助手注入自定义技能 |
| H5 界面 | 多页面 WebView 界面（主页 + 子页面） |
| Compose 页面 | JSON DSL 描述的原生渲染页面，**无需 WebView** |
| 功能屏蔽 | 屏蔽 / 禁用系统功能、设置项、导航页面 |
| System Prompt | `APPEND` / `MODIFY` / `OVERWRITE` 三种修改策略 |
| 跨应用联动 | Broadcast、ContentProvider、Webhook |

### 5.2 权限模型

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

### 5.3 安全策略

- 安装时校验 `manifest.json` 与**所有 `entry` 指向文件的存在性**，缺失即报错。
- `minHostVersion` 默认为 `2.0.0`，低版本宿主拒绝安装。
- `OVERWRITE` 模式的 System Prompt 修改属**极高风险**，启用时弹出 `PluginOverwriteDialog` 二次确认。
- 管理界面可安装、启用/禁用、查看配置、卸载。

### 5.4 统一 Action 协议

`ActionExecutor` 支持四种前缀，`Compose` 节点的 `onClick` / `onChange` 直接使用：

| 前缀 | 示例 | 说明 |
|------|------|------|
| `shell:` | `shell:uname -a` | 在插件 shell 中执行命令 |
| `action:` | `action:open_vnc_settings` | 调用宿主 `HostActionRegistry` 原生入口 |
| `nav:` | `nav:page_about` | 跳转到同插件其他页面（按 `pages[].id`） |
| `http(s)://` | `https://example.com` | 在外部浏览器打开 |
| `{value}` | `shell:echo {value}` | `switch.onChange` 中替换为当前布尔值 |

### 5.5 持久化会话

`PluginPersistentSession` 提供常驻 shell（与每次新建进程的 `PluginManager.executeShellCommand()` 不同）：

- 增量读写 transcript（`readNew()` / `readAll()` / `resetReadCursor()`）
- 发送 Ctrl+C（`interrupt()`）与 EOF（`sendEof()`）
- 查询 `cwd` / `pid` / `exitCode` / `isRunning`
- 自动注册到 `TermuxService.mTermuxSessions`，终端页面也能管理
- 退出时由 `PluginPersistentSessionRegistry` 通过 `TerminalSession.mHandle` 反查清理
- 跨插件访问由 `pluginId` 校验拦截

## 6. AI 助手

- **自然语言交互**：对话式操作终端、文件系统、远程连接
- **技能系统**：新建/关闭会话、执行命令、文件读写、VNC/SSH 连接、QEMU 虚拟机管理
- **多模型**：兼容 OpenAI API 及自定义端点，可配置 `temperature` 等参数
- **安全机制**：危险操作检测（`rm -rf`、`dd`、fork bomb 等）与二次确认
- **上下文感知**：可获取会话信息、文件列表、执行结果等实时数据
- **深度思考展示**：模型支持时展示推理过程
- **插件扩展**：插件可添加 Skill、修改 System Prompt（见插件构建文档）

## 7. 安全增强：VorteX Guard Engine（VGE）

原增强防护模块已改版为 **VorteX Guard Engine**，Shell hook 架构重写，检测更精准、零终端干扰：

- **TCP 通信隔离**：所有服务端通信放入子 Shell 执行，父进程零 fd 改动，杜绝 PTY termios 被意外修改
- **DEBUG trap 安全化**：`extdebug` 不再全局常开，仅在 DENY 跳过命令时瞬时开启，`PROMPT_COMMAND` 前兜底关闭
- **函数覆盖替代 trap**：`su` / `sudo` / `dd` / `mkfs` 等高危单词命令改为函数覆盖拦截，零干扰 bash 内部行为
- **初始化宽限期**：OMB/OMZ 初始化脚本在安全模块加载期间自动放行
- **PTY termios 修复**：JNI 层显式设置 `ECHO|ICANON|ISIG`，解决 Android toybox `stty` 在某些设备上不生效的问题
- **OMB/OMZ 适配**：自动检测 oh-my-bash / oh-my-zsh 并通过其原生 `preexec`/`precmd` 接口注册
- **高危命令二次确认**：四种模式 `OFF`（关闭）/ `WARN_ONLY`（仅提示）/ `AUTO_BLOCK`（自动拦截）/ `WARN_VERIFY`（警告并弹窗验证）
- **设置实时生效**：增强模式切换后 hook 与 `SecuritySocketServer` 立即重启，无需重启应用
- **脚本检测覆盖**：`bash`/`sh`/`zsh`/`ksh`/`dash`/`fish` 脚本执行 + `./script.sh` 直接执行 + `rm -rf /`、`chmod 777` 等危险参数组合
- **UTF-8 BOM 修复**：解决 Windows 写入 shell 脚本自动添加 BOM 导致 `bash source` 报错的问题

## 8. VNC / SSH 实现

| 模块 | 依赖 | 说明 |
|------|------|------|
| VNC | AVNC、libvncserver、libjpeg-turbo、wolfSSL | 手势缩放、多输入模式、特殊按键、色彩格式配置、自动扫描本地端口 |
| SSH | connectbot sshlib `2.2.36` | 多连接配置管理、自动安装 `ssh`/`sshpass`、本地端口转发、主机密钥验证、多 IP 重试 |

原生构建目标（CMake）：`native-vnc`、`vncclient`、`turbojpeg-static`、`wolfssl`、`termux-bootstrap`。

## 9. LiveUpdate 实时通知

- 下载进度**分段显示**
- Agent 思考状态展示
- 包管理通知优化
- v0.119 基底引入 `POST_PROMOTED_NOTIFICATIONS`（Android 15+），已适配旧版 API 兼容层（`sdk_int < 36` 回退普通通知）

## 10. 技术栈

| 类别 | 技术 |
| --- | --- |
| 语言 | Kotlin、Java、C/C++ |
| UI | Jetpack Compose 1.8.3、Material 3 1.3.0、Miuix KMP 0.9.4（ui / icons / preference） |
| 架构组件 | AndroidX、Lifecycle 2.8.5、ViewModel、Navigation、Room 2.7.2、DataBinding |
| 终端 | libterminal |
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

## 11. 构建本项目

环境要求：JDK 21、Android SDK（compileSdk 37）、NDK `22.1.7171670`、CMake `3.22.1`。

> 为避免路径中的空格导致 NDK 编译问题，请通过**无空格的硬链接路径**访问项目（如 `D:\KiTerminal-UX`）。

```bash
# Debug 版本（仅输出 universal APK）
./gradlew assembleDebug

# Release 版本（输出各架构 APK）
./gradlew assembleRelease
```

产物：

- Debug：`app/build/outputs/apk/debug/termux-ultra_debug_universal.apk`
- Release：`app/build/outputs/apk/release/` 下各架构 APK

签名：项目内置 `ki-terminal-release.jks`（alias: `ki-terminal`），Debug 与 Release 均使用该签名。

> 构建时不要使用 `-q` 参数，以便观察构建进度。
