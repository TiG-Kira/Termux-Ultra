---
title: 使用手册
lang: zh
ref: manual
description: Termux Ultra 安装、首次启动、终端会话、文件管理、VNC/SSH、容器与虚拟机、设置备份、AI 助手、日志调试与常见问题。
---

## 1. 安装前必读：签名一致性

Termux Ultra 与原版 Termux 及其所有插件共享 `sharedUserId`（`com.termux`）。因此设备上安装的**本应用与所有插件 APK 必须使用同一签名来源**，否则无法协同工作，安装时会出现：

- `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`
- `signatures do not match`

规则：

- **不要混用来源**（例如 F-Droid 装一个、GitHub 装另一个）。
- 如需更换来源，请先**卸载所有已安装的 Termux 及其插件 APK**，再从同一新来源全部安装。
- 卸载前建议参考 [Backing up Termux](https://wiki.termux.com/wiki/Backing_up_Termux) 备份数据。

> "bootstrap" 指 `termux-app` 自带的用于启动最小 shell 环境的最小包集合，其 zip 由 [termux-packages releases](https://github.com/termux/termux-packages/releases) 构建发布。

## 2. 系统要求

| 项目 | 要求 |
|------|------|
| Android | `>= 8.0`（API 26） |
| targetSdk / compileSdk | `28` / `37` |
| 支持架构 | `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` |

## 3. 下载与安装

| 渠道 | 说明 |
|------|------|
| [GitHub Releases](https://github.com/TiG-Kira/Termux-Ultra/releases) | **正式版（稳定版）**，发布页 `Assets` 下提供各架构 APK |
| [GitHub CI](https://github.com/TiG-Kira/Termux-Ultra/actions/workflows/ci.yml) | CI 自动构建（测试版），每次 commit 触发，需登录 GitHub 账号下载 Artifacts |

体积参考：

- Debug 版本仅输出 universal APK（`termux-ultra_debug_universal.apk`），安装包 + bootstrap 约 `~180MB`。
- Release 版本输出各架构独立 APK，使用架构包约 `~120MB`。
- GitHub 来源的 APK 均为 `debuggable`，彼此兼容，但与其他来源不兼容。

### 关于 Google Play 商店（已弃用）

原版 Termux 及其插件因 [Android 10 问题](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10) 已在 Play Store 停止更新，最后版本为 `v0.101`。**强烈建议不再从 Play Store 安装 Termux 系应用**，请迁移至 GitHub 或 F-Droid 来源。

## 4. 首次启动（OOBE）

首次打开会进入引导页（OOBE），依次完成：

1. 欢迎页与版本信息
2. 存储权限申请
3. 通知权限申请（LiveUpdate 实时通知依赖此项）
4. 终端初始化：解压 bootstrap、生成基础目录结构
5. 可选：登录/跳过、查看当前版本的 Release Notes

初始化期间请勿杀掉进程。若长时间卡在初始化，可到 `设置 → 调试` 把日志级别调到 `Verbose` 后重启并抓取 logcat（见第 13 节）。

## 5. 界面导览

底部导航栏四个页签，支持**左右横滑手势**切换：

| 页签 | 内容 |
|------|------|
| **终端** | 多会话管理、搜索、新建/关闭/重命名 |
| **文件** | 内置文件管理器、FTP 服务器 |
| **远程** | VNC 远程桌面、SSH 连接管理、SSH 隧道 |
| **资源** | 一键部署脚本、第三方资源中心、插件中心 |

交互细节：

- 页面切换有叠加动画与左右切换动画，支持**预测式返回**。
- 卡片圆角与点击反馈裁剪统一。
- 底部导航有避让与边距修正，防止误触；支持玻璃 / 柔光 / 浮动三种效果。
- 导航栏指示器支持拖动切页。

## 6. 终端会话

### 基本操作

- **新建会话**：点击会话列表的 `+`
- **切换会话**：点击列表项
- **重命名 / 关闭**：长按或点击会话项右侧菜单
- **搜索**：顶部搜索框实时过滤（输入标题搜索，无结果显示"未找到"）

### 保活与保护

- **服务状态检测**：持续监控终端运行状态，支持 Wake Lock 保活。
- **内存监控与保护**：内存超限时自动冻结会话，防止数据丢失。
- **会话保活提示**：Android 12+ 配合 `tmux` 实现后台持久化。

### 终端引擎

内置 **LibTerminal** 终端核心引擎，负责终端仿真与屏幕渲染，性能与兼容性均有大幅提升。

### 终端外观

`Termux:Styling` 集成工具开启后，可在设置中管理配色方案与字体。

## 7. 集成工具开关

5 款 Termux 插件已内置到应用中（源码位于 `vendor/termux-addons/`），**无需额外安装独立 APK**，在 `设置 → 集成工具` 中按需开关：

| 工具 | 作用 |
|------|------|
| **Termux:API** | 提供 Android 系统功能调用（传感器、通知、TTS 等） |
| **Termux:Boot** | 开机自动执行 `~/.termux/boot/` 下的脚本 |
| **Termux:Styling** | 终端配色方案与字体管理（使用合并包名 `com.termux`） |
| **Termux:Tasker** | Tasker 自动化集成 |
| **Termux:Widget** | 桌面快捷方式与小组件 |

> 工具**默认关闭**。开启时通过 `PackageManager.setComponentEnabledSetting()` 动态启用对应组件，关闭时禁用。**若设备已安装官方独立 APK，开关将自动禁用并提示冲突**——此时需先卸载独立 APK。

## 8. 文件管理

- 完整的文件 / 文件夹操作：新建、复制、剪切、粘贴、删除、重命名
- 多种打开方式：查看内容（`cat`）、编辑（`vi`）、执行（`bash`）、复制路径
- 深色模式适配的文件详情面板
- 内置 **FTP 服务器**：局域网文件传输
- 支持下拉刷新、多文件选择与批量操作、文件类型图标

## 9. 远程管理

### VNC 远程桌面

基于 AVNC + libvncserver，支持：

- 手势缩放
- 多种输入模式
- 特殊按键发送
- 色彩格式配置
- **自动扫描本地 VNC 端口**

### SSH 连接管理

基于 connectbot sshlib：

- 保存 / 编辑 / 删除多个连接配置
- 自动安装 `ssh` / `sshpass`
- **SSH 隧道**：支持本地端口转发、主机密钥验证、多 IP 重试

远程页使用统一的搜索 UI 与卡片式管理。

## 10. Linux 容器与虚拟机（资源页一键部署）

资源页分为**实用工具中心**与**第三方资源中心**，内置常用环境的一键安装脚本：

| 条目 | 说明 |
|------|------|
| Linux 容器 | 基于 proot 一键安装 Ubuntu（Noble/Jammy）或 Debian（Bookworm），共享 Termux 主目录 |
| QEMU 安装 | 支持在容器内或 Termux 内安装 QEMU，提供完整系统虚拟化 |
| QEMU on VNC | 通过 QEMU 启动虚拟机并经由 VNC 显示桌面，支持自定义 CPU / 内存 / 磁盘 / ISO |
| Seed ISO | 自动生成 seed ISO 用于虚拟机初始化配置 |
| 朱雀面板（LightPanel） | 一键部署 Web 管理面板 |
| Python 环境 | 一键部署 Python 运行环境 |
| tmux | 保持容器与项目存活 |
| 第三方资源中心 | 社区维护的扩展资源 |

## 11. 插件中心

入口：**资源页 → 插件中心**。

- 安装：从文件安装 `.tup`（ZIP 格式插件包）
- 管理：启用 / 禁用、查看配置、卸载
- 权限：ROOT 执行、会话访问、文件读写、跨应用联动、H5 WebView、网络访问、Agent 修改
- `OVERWRITE` 模式的 System Prompt 修改会弹出二次确认

插件开发详见 [插件构建文档]({{ '/zh/plugins/' | relative_url }})。

## 12. 设置与仪表盘

| 功能 | 说明 |
|------|------|
| 网络信息卡片 | 实时刷新公网 IP 与所属国家 |
| 设备信息 | 机型、Android 版本、内核版本 |
| 备份 / 恢复 | 备份与恢复 Termux 数据 |
| 集成工具开关面板 | 含独立 APK 冲突检测 |
| 生物识别认证 | 指纹解锁 |
| 多语言 | 中文 / 英文（100% 中文覆盖） |
| 深色 / 浅色 | 跟随系统或手动切换 |
| Miuix 风格设置页 | ArrowPreference 套件 |

## 13. AI 助手

内置 AI 助手，通过自然语言与终端、文件系统、远程连接交互。

配置方式：`AI 助手 → 设置`，填写 OpenAI 兼容 API 的 `Base URL`、`API Key`、模型名，可配置 `temperature` 等参数；也支持本地大模型端点。

能力：

- **技能系统**：新建/关闭会话、执行命令、文件读写、VNC/SSH 连接、QEMU 虚拟机管理
- **安全机制**：危险操作检测（`rm -rf`、`dd`、fork bomb 等）与二次确认
- **上下文感知**：可获取会话信息、文件列表、执行结果等实时数据
- **深度思考展示**：模型支持时展示推理过程
- **插件扩展**：插件可添加自定义 Skill、修改 System Prompt

## 14. 卸载

如需彻底卸载，必须卸载设备上**所有** Termux 或其插件 APK。

进入 `Android 设置 → 应用`，搜索 `termux`，逐个卸载。即便未安装过插件，也建议在应用列表中再次确认。

## 15. 调试与日志

在 `设置 → 调试` 中配置 `logcat` 日志级别（需应用版本 `>= 0.118.0`）：

| 级别 | 说明 |
|------|------|
| `Off` | 不记录 |
| `Normal` | 记录 error / warn / info 及堆栈（默认） |
| `Debug` | 记录 debug 信息 |
| `Verbose` | 记录 verbose 信息 |

查看日志：

```bash
# 终端内实时查看（Ctrl+c 停止）
logcat

# 导出日志快照
logcat -d > logcat.txt
```

也可通过**长按终端菜单 → More → Report Issue** 自动生成 stat 信息与 logcat 快照。反馈问题时请附上完整报告（可去除敏感信息），**仅截图的报告通常会被关闭**。

> 调试完成后请把日志级别恢复 `Normal`，避免敏感数据写入 logcat 并降低性能。

## 16. 常见问题

**Q：安装时提示签名不匹配 / `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`？**
A：设备上存在其它来源的 Termux 或插件。卸载全部 Termux 系 APK 后，从同一来源重新安装。

**Q：集成工具开关是灰的？**
A：说明系统检测到已安装对应的官方独立 APK，存在冲突。卸载独立 APK 后开关会恢复可用。

**Q：终端长时间后台后被杀？**
A：Android 12+ 建议使用 `tmux` 保持会话；同时在系统设置中关闭本应用的电池优化，并允许后台运行与锁定最近任务。

**Q：通知不弹出？**
A：检查是否已授予通知权限；Android 15+ 需 `POST_PROMOTED_NOTIFICATIONS`，应用已在 `AndroidManifest.xml` 中声明并做旧 API 兼容回退。

**Q：终端没有提示符 / 输入无回显 / oh-my-bash 主题崩坏？**
A：升级到 v1.7.0+ 的 VorteX Guard Engine，该问题已通过 Shell hook 架构重写修复。

**Q：Windows 写入的 shell 脚本执行报奇怪错误？**
A：Windows 编辑器可能写入 UTF-8 BOM 导致 `bash source` 报错，已修复；仍有问题请转成无 BOM 的 UTF-8 或 LF 换行。

**Q：新版 APK 装上后打不开？**
A：确认架构匹配（`arm64-v8a` 为主流）；确认没有残留的旧签名安装；必要时抓取 logcat 提交 Issue。
