# Termux Ultra — 近期工作上下文（供 Trae Work 读取）

> 本文件用于让新会话/工具快速了解近期完成的编译与修改。最后更新：2026-08-01。

## 项目概况

- **项目名**：Termux Ultra（非 KiTerminal UX）
- **基础版本**：Termux 0.118.3
- **当前版本号**：`118.3.55`（versionCode 1045）
- **包名**：`com.termux`
- **构建方式**：通过硬链接 `D:\KiTerminal-UX`（无空格路径）构建，避免 NDK 因路径空格编译失败；不加 `-q` 以保留进度；debug 默认只产 universal APK，release 产各架构 APK。
- **语言**：100% 中文覆盖（同时支持英文），所有新增内容禁止硬编码字符串。

---

## 近期完成的修改

### 1. 集成 Termux 官方工具（API / Boot / Styling / Tasker / Widget）

将 5 个 Termux 官方附加应用整合为库模块，集成进主应用，用户无需再单独安装。默认全部关闭，在设置页按开关启用。

**核心文件**：
- [IntegratedTools.kt](app/src/main/java/com/termux/app/compose/IntegratedTools.kt) — 统一管理各工具的组件启用状态。
  - `componentsFor(tool)`：定义每个工具包含的 Android 组件（Receiver/Activity/Service）完整类名列表。
  - `applyComponentState(context, tool, enabled)`：通过 `PackageManager.setComponentEnabledSetting` 批量启用/禁用组件。
  - `requireEnabled` / `showEnablePrompt`：功能未开启时引导用户去设置页开启（而非提示下载安装）。
- [SettingsScreen.kt](app/src/main/java/com/termux/app/compose/SettingsScreen.kt) — 设置页"集成工具"分区的 5 个开关，开关变更时调用 `setEnabled` + `applyComponentState` 同步系统组件状态。

**库模块转换要点**：
- 工具从独立应用转为 `com.android.library`，移除 `applicationId`/`package`/`sharedUserId`。
- 所有组件默认 `android:enabled="false"`，由设置开关运行时动态启用。
- 版本兼容：基于 termux-shared 0.118.3，工具仓库 checkout 到同期版本（termux-api v0.50.1、termux-widget v0.13.0 等），避免 termux-shared 重构导致的类路径变更。

**已解决的冲突**：
- 多模块 `AppTheme` 冲突 → termux-tasker 重命名为 `TermuxTaskerTheme`。
- 库模块 R.string 不含 termux-shared 资源 → 直接引用 `com.termux.shared.R`。
- `Theme.BaseActivity` 不存在 → 选用旧版工具（使用系统 `Theme.Material.Light`）。
- 清单 `ReportActivity` 多模块重复声明 → 从 termux-api/termux-widget 清单移除。
- 核心库脱糖 → app/build.gradle 启用 `coreLibraryDesugaringEnabled true` + `desugar_jdk_libs:1.1.5`。

### 2. 集成各官方插件的设置页到设置页面（本轮新增）

在"集成工具"开关卡片下方新增**"工具配置"分区**（[SettingsScreen.kt:492-496](app/src/main/java/com/termux/app/compose/SettingsScreen.kt)），仅对已启用工具显示 ArrowPreference 入口：

| 工具 | 入口行为 | 目标 Activity |
|------|----------|---------------|
| Termux:Styling | 打开颜色/字体选择器 | `com.termux.styling.TermuxStyleActivity` |
| Termux:Tasker | 打开插件命令配置 | `com.termux.tasker.EditConfigurationActivity` |
| Termux:Widget | 打开快捷方式与微件管理 | `com.termux.widget.activities.TermuxWidgetActivity` |
| Termux:API | 无设置页 → 弹出"API 使用说明"对话框 | — |
| Termux:Boot | 无设置页 → 弹出"开机自启说明"对话框 | — |

- 新增中英文字符串：`tool_config_category`、`termux_styling_config`、`termux_tasker_config`、`termux_widget_config`、`termux_api_help`、`termux_boot_help` 及对应 `_summary`/`_content`。
- 帮助对话框用 `OverlayDialog` + `verticalScroll`，展示常用命令列表 / 开机脚本放置方法。
- 未启用任何工具时该分区不显示。

### 3. 修正 debug 构建仅输出 universal APK（本轮新增）

[app/build.gradle:45-60](app/build.gradle) — 原因：`splits.abi.enable` 始终为 true，导致 debug 也产分架构包；且 `variantFilter` 对 ABI splits 无效（splits 不产生独立 variant），重命名逻辑期望的 `app-debug.apk` 也因此失效。

修复：改用 `gradle.startParameter.taskNames` 检测 release 任务——**仅 release 启用 splits**，**debug 禁用 splits** 只产 universal 包。重命名逻辑（`app-debug.apk` → `termux-ultra_debug_universal.apk`）随之恢复。

### 4. 备份与恢复改用 termux-backup / termux-restore（前期完成）

[BackupManager.kt](app/src/main/java/com/termux/app/compose/BackupManager.kt) — 核心逻辑改用 Termux 自带 `termux-backup`/`termux-restore`，后台会话运行不显示在前台，保持进度显示，执行完毕后台会话立即退出并返回结果。

### 5. 第三方资源中心按钮样式统一（前期完成）

[ThirdPartyCenterActivity.kt](app/src/main/java/com/termux/app/activities/ThirdPartyCenterActivity.kt) — 四个按钮（说明/编辑/删除/执行）调整为图标在上、文字在下的垂直布局，居中对齐，字体加粗，使用 miuix 按钮。

---

## 编译状态

| 构建类型 | 状态 | 产物 | 大小 | 时间 |
|----------|------|------|------|------|
| Debug | ✅ BUILD SUCCESSFUL (25s) | `termux-ultra_debug_universal.apk` | 187 MB | 2026-08-01 02:14 |
| Release | ✅ 已成功（前期） | 各架构 release APK（~50 MB/个）+ universal | — | 2026-08-01 00:26 |

- Debug 输出路径：`D:\KiTerminal-UX\app\build\outputs\apk\debug\termux-ultra_debug_universal.apk`
- Release 输出路径：`D:\KiTerminal-UX\app\build\outputs\apk\release\`
- 已知非致命警告：D8 Kotlin metadata 警告（不影响构建，可忽略）。

---

## 待办任务（需真机/模拟器验证）

1. **验证各工具启用后功能完整性** — 如 `termux-battery-status` 命令是否可用、Styling 颜色选择是否生效、Widget 快捷方式是否可创建。
2. **测试备份/恢复后台运行** — 进度显示与结果返回是否符合预期。
3. **APK 瘦身** — debug universal 187 MB 偏大，需评估是否启用 minify/资源压缩。

---

## 关键约束与约定（来自项目记忆）

- 通讯语言：中文。
- UI：按钮白字+图标配色；Material3 tabRow 选中色用黑/深灰（非紫/Monet）；VNC/SSH 卡片圆角顶部；miuix 开关（非 material3）开关激活色与资源页执行按钮蓝一致；设置页用 ArrowPreference 打开下一级页面/对话框；TopBar 文字居中；页面过渡动画（首页覆盖+左右切换）支持预测返回。
- 构建：通过 `D:\KiTerminal-UX` 硬链接编译；debug 仅 universal，release 各架构；不加 `-q`。
- 版本：基于 Termux 0.118.3，工具版本需与 termux-shared 0.118.3 同期。
- 国际化：新增内容必须支持中英文，禁止硬编码。

## 关键文件索引

| 文件 | 作用 |
|------|------|
| [IntegratedTools.kt](app/src/main/java/com/termux/app/compose/IntegratedTools.kt) | 集成工具组件启用状态管理 |
| [SettingsScreen.kt](app/src/main/java/com/termux/app/compose/SettingsScreen.kt) | 设置页（工具开关 + 工具配置入口） |
| [BackupManager.kt](app/src/main/java/com/termux/app/compose/BackupManager.kt) | 备份/恢复（termux-backup/restore） |
| [ThirdPartyCenterActivity.kt](app/src/main/java/com/termux/app/activities/ThirdPartyCenterActivity.kt) | 第三方资源中心 |
| [app/build.gradle](app/build.gradle) | 版本号、splits、依赖、脱糖配置 |
| [settings.gradle](settings.gradle) | 工具库模块路径 |
| [vendor/termux-addons/](vendor/termux-addons/) | 5 个 Termux 工具库模块源码 |
| [values/strings.xml](app/src/main/res/values/strings.xml) | 英文字符串 |
| [values-zh-rCN/strings.xml](app/src/main/res/values-zh-rCN/strings.xml) | 中文字符串 |
