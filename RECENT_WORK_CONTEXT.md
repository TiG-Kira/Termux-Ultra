# Termux Ultra — 近期工作上下文（供 Trae Work 读取）

> 本文件用于让新会话/工具快速了解近期完成的编译与修改。最后更新：2026-08-01（下午）。

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

### 6. Tasker 与 Widget 页面标题中文化 + 按钮翻译（本轮新增）

- Tasker 设置页 [EditConfigurationActivity.java](vendor/termux-addons/termux-tasker/app/src/main/java/com/termux/tasker/EditConfigurationActivity.java)：
  - 使用 `AppCompatActivity` + `getSupportActionBar().setTitle(R.string.title_tasker_settings)`，标题显示为「Tasker 设置」。
  - 返回键添加 `onOptionsItemSelected(android.R.id.home) → finish()`。
- Widget 管理页 [TermuxWidgetActivity.java](vendor/termux-addons/termux-widget/app/src/main/java/com/termux/widget/activities/TermuxWidgetActivity.java)：
  - 标题改为「快捷方式与微件设置」（`title_widget_settings`），同样添加返回按钮。
  - 页面顶部「禁用启动器图标」按钮硬编码英文 → 抽成资源 `action_disable_launcher_icon` / `msg_disabling_launcher_icon`，在 termux-widget 与 termux-shared 的 `values-zh-rCN/strings.xml` 中补齐中文翻译。
  - 其余按钮 `action_create_shortcut` / `action_execute_script` / `action_edit` / `action_delete` / `dialog_confirm_button_delete` 同步补齐中英文资源。
- 新增资源文件：`termux-widget/src/main/res/values-zh-rCN/strings.xml`、`termux-tasker/src/main/res/values-zh-rCN/strings.xml`。

### 7. 修复「创建微件 Termux 快捷方式」ActionBar NPE 崩溃（本轮新增）

**报错栈**：`TermuxCreateShortcutActivity.updateListview` L87 调用 `getActionBar().setDisplayHomeAsUpEnabled(...)` → NPE。

**修复**：[TermuxCreateShortcutActivity.java](vendor/termux-addons/termux-widget/app/src/main/java/com/termux/widget/TermuxCreateShortcutActivity.java)
- 父类从 `Activity` → `AppCompatActivity`，主题改为 `Theme.MaterialComponents.Light.DarkActionBar`（同 Tasker Activity）。
- `getActionBar()` → `getSupportActionBar()` 并加 null 检查；标题设置为 `title_create_shortcut`（中文「创建快捷方式」）。
- 补齐 `onOptionsItemSelected` 返回键处理。

### 8. 官方独立插件 APK 安装状态检测（本轮新增）

**需求**：若设备上实际安装了 Termux 官方独立插件（如 `com.termux.api`），则设置页中对应集成插件开关**不可选**、summary 文案替换为「已由官方插件替代」，点击禁用开关时弹窗提示「不建议再安装对应插件，请使用系统内置的插件」；卸载独立 APK 后开关自动恢复可选择。

**核心实现**：
- [IntegratedTools.kt](app/src/main/java/com/termux/app/compose/IntegratedTools.kt)：
  - `Tool` 枚举新增字段 `standalonePackage: String`（如 `TERMUX_API → "com.termux.api"`）。
  - 新增 `isStandaloneInstalled(context, tool)`：用 `PackageManager.getPackageInfo(standalonePackage, 0)` 捕获 `NameNotFoundException` 判断是否安装；同时排除 `standalonePackage == context.packageName`（避免自引用）。
  - 新增 `showStandaloneConflictPrompt(context, tool)`：`AlertDialog` 显示 `standalone_plugin_installed_title` / `standalone_plugin_installed_message`（含工具名与包名占位符）。
- [SettingsScreen.kt](app/src/main/java/com/termux/app/compose/SettingsScreen.kt)：
  - SettingsScreen compose 开头新增 5 个状态：`apiStandaloneInstalled` / `bootStandaloneInstalled` / `stylingStandaloneInstalled` / `taskerStandaloneInstalled` / `widgetStandaloneInstalled`，以及 `replacedSummary`（即 `standalone_plugin_installed_summary`）。
  - `IntegratedToolSwitch` 组件签名扩展：`enabled: Boolean = true`、`onDisabledClick: (() -> Unit)? = null`；当 `!enabled` 时忽略 `onCheckedChange`，改为触发 `onDisabledClick`；外层 `Box` 追加 `clickable` 以让整行点击可弹窗。
  - 5 个开关调用处 `summary` 改为 `if (xxxStandaloneInstalled) replacedSummary else originalSummary`；`enabled = !xxxStandaloneInstalled`；`onDisabledClick = { IntegratedTools.showStandaloneConflictPrompt(...) }`。
- 字符串资源：
  - 英文 `standalone_plugin_installed_title = "Official plugin installed"`，`message = "The official standalone \"%1$s\" plugin (package %2$s) is already installed…"`，`summary = "Replaced by the official standalone plugin"`。
  - 中文对应：「官方插件已安装」/「已由官方插件替代」/「不建议再安装对应插件，请使用本应用内置的插件功能」。

### 9. 修复 termux-api 命令在终端执行卡死（本轮新增）

**现象**：`pkg install termux-api` 后执行 `termux-battery-status` 等命令直接卡住不返回。

**根因**：独立 Termux:APK 模式下由独立 App 启动 `LocalServerSocket("com.termux.api")` 接收命令；但集成模式下没人监听该 socket，`termux-api` 客户端命令连不上后永久阻塞。

**修复 1 — 监听线程**：[TermuxApplication.java](app/src/main/java/com/termux/app/TermuxApplication.java) 新增 `startTermuxApiListener(context)`：
- `new Thread("TermuxAPI-Listener")` 内 `while (true)` 用 `LocalServerSocket(LISTEN_ADDRESS = "com.termux.api")` accept 连接。
- 收到连接后用 `DataInputStream` 按协议读取：先读 1 个 method 长度 int + method 字符串；再读 argc int + N 个 (length + String) 参数；然后读 stdin length + 字节。
- 组装 `Intent(context, TermuxApiReceiver.class)`，将 method/args/stdin 写入 extras（`TermuxApiReceiver.API_CLASS`、`TermuxApiReceiver.API_METHOD`、`TermuxApiReceiver.API_EXTRA*`、`TermuxApiReceiver.API_STDIN`），并附加一个 `ResultReceiver extra` 供 Broadcast 写回返回值。
- 调用 `context.sendOrderedBroadcast(intent, null)`（有序广播，保证 `ResultReceiver` 执行完毕后才继续）。
- 最后向 socket 输出流 `write(0)` 并 `flush`（符合 termux-api 客户端协议，0 表示成功结束），客户端进程退出。
- 失败时记录 `Logger.logError(LOG_TAG, …)` 不崩溃。
- 在 `TermuxApplication.onCreate()` 末尾调用 `startTermuxApiListener(this)` 启动。

**修复 2 — NotificationAPI 硬编码包名**：[NotificationAPI.java](vendor/termux-addons/termux-api/app/src/main/java/com/termux/api/NotificationAPI.java)
- `deleteNotificationChannel` 内原代码 `setClassName("com.termux.api", "com.termux.api.TermuxApiReceiver")`，集成模式下包名实际是宿主 `com.termux`，导致广播投递给不存在的组件、通知删除失败（也可能间接卡住）。
- 改为：`setClassName(oldIntent.component?.packageName ?: "com.termux.api", "com.termux.api.TermuxApiReceiver")`，即从触发它的原 Intent 中动态拿宿主包名。

### 10. termux-shared 与各工具模块缺失中文资源补齐（本轮新增）

编译期曾有少量 `@id/...` / string 引用找不到，已补齐：
- `termux-shared/src/main/res/values-zh-rCN/strings.xml`：新增 `action_disable_launcher_icon` / `msg_disabling_launcher_icon` 等。
- `termux-tasker` 模块新增 `values/strings.xml` 与 `values-zh-rCN/strings.xml`：`title_tasker_settings = "Tasker 设置"`、`edit_configuration_hint` 等独立插件所需资源。
- `termux-widget` 模块 `values/strings.xml` 新增 `title_create_shortcut` / `title_widget_settings` / `action_*` / `msg_*`；并完整翻译中文版本。

---

## 编译状态

| 构建类型 | 状态 | 产物 | 大小 | 时间 |
|----------|------|------|------|------|
| Debug Java+Kotlin 增量 | ✅ BUILD SUCCESSFUL (1s) | — | — | 2026-08-01 下午 |
| Debug | ✅ BUILD SUCCESSFUL (25s) | `termux-ultra_debug_universal.apk` | 187 MB | 2026-08-01 02:14 |
| Release | ✅ 已成功（前期） | 各架构 release APK（~50 MB/个）+ universal | — | 2026-08-01 00:26 |

- Debug 输出路径：`D:\KiTerminal-UX\app\build\outputs\apk\debug\termux-ultra_debug_universal.apk`
- Release 输出路径：`D:\KiTerminal-UX\app\build\outputs\apk\release\`
- 最近一次验证命令：`gradlew.bat :app:compileDebugJavaWithJavac :app:compileDebugKotlin` — 103 个任务 up-to-date，无错误。
- 已知非致命警告：D8 Kotlin metadata 警告（不影响构建，可忽略）、Java 21 下 source/target 8 已过时提示（不影响构建）。
- 已知编译不影响现象：首次运行时 `SettingsScreen.kt` 报 ResourcesScreen 的 `modifier` 参数找不到 → 第二次 UP-TO-DATE 即通过，实际是 PowerShell 对含 CRLF 的错误日志渲染 `_x000D__x000A_` 伪影，非真错误。

---

## 待办任务（需真机/模拟器验证）

1. **验证 termux-api 命令不再卡住** — `pkg install termux-api` 后执行 `termux-battery-status` / `termux-notification` / `termux-toast` 是否秒返回并输出正确 JSON / 发通知 / 出 Toast。
2. **验证集成工具开关 + 官方插件检测** — 安装 `com.termux.api` 独立 APK 后进入设置，开关应变灰并显示「已由官方插件替代」；点击该行弹出冲突提示；卸载独立 APK 后开关恢复可选。
3. **验证 Widget 创建快捷方式不崩溃** — 在桌面长按添加 Termux 快捷方式（或在 Widget 管理页点「新建快捷方式」），应正常进入文件选择并可返回上一级（`setDisplayHomeAsUpEnabled`）。
4. **验证各工具启用后功能完整性** — Styling 颜色选择是否生效、Tasker 插件能否被 Tasker 识别、Boot 开机脚本是否执行。
5. **测试备份/恢复后台运行** — 进度显示与结果返回是否符合预期。
6. **APK 瘦身** — debug universal 187 MB 偏大，需评估是否启用 minify/资源压缩。

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
| [IntegratedTools.kt](app/src/main/java/com/termux/app/compose/IntegratedTools.kt) | 集成工具组件启用状态管理 + 官方独立 APK 检测 |
| [SettingsScreen.kt](app/src/main/java/com/termux/app/compose/SettingsScreen.kt) | 设置页（工具开关 + 工具配置入口 + 插件禁用 UI） |
| [TermuxApplication.java](app/src/main/java/com/termux/app/TermuxApplication.java) | 启动 termux-api LocalServerSocket 监听器，解决命令卡死 |
| [NotificationAPI.java](vendor/termux-addons/termux-api/app/src/main/java/com/termux/api/NotificationAPI.java) | 修复 setClassName 硬编码包名 |
| [EditConfigurationActivity.java](vendor/termux-addons/termux-tasker/app/src/main/java/com/termux/tasker/EditConfigurationActivity.java) | Tasker 设置页：标题「Tasker 设置」+ 返回键 |
| [TermuxWidgetActivity.java](vendor/termux-addons/termux-widget/app/src/main/java/com/termux/widget/activities/TermuxWidgetActivity.java) | Widget 管理页：标题「快捷方式与微件设置」+ 按钮翻译 |
| [TermuxCreateShortcutActivity.java](vendor/termux-addons/termux-widget/app/src/main/java/com/termux/widget/TermuxCreateShortcutActivity.java) | 快捷方式创建页：修复 ActionBar NPE（AppCompatActivity + getSupportActionBar null 检查） |
| [BackupManager.kt](app/src/main/java/com/termux/app/compose/BackupManager.kt) | 备份/恢复（termux-backup/restore） |
| [ThirdPartyCenterActivity.kt](app/src/main/java/com/termux/app/activities/ThirdPartyCenterActivity.kt) | 第三方资源中心 |
| [app/build.gradle](app/build.gradle) | 版本号、splits、依赖、脱糖配置 |
| [settings.gradle](settings.gradle) | 工具库模块路径 |
| [vendor/termux-addons/](vendor/termux-addons/) | 5 个 Termux 工具库模块源码 |
| [values/strings.xml](app/src/main/res/values/strings.xml) | 英文字符串（含 standalone_plugin_* 冲突提示） |
| [values-zh-rCN/strings.xml](app/src/main/res/values-zh-rCN/strings.xml) | 中文字符串（含 standalone_plugin_* 冲突提示） |
| [termux-widget values-zh-rCN](vendor/termux-addons/termux-widget/app/src/main/res/values-zh-rCN/strings.xml) | Widget 页面中文化 |
| [termux-tasker values-zh-rCN](vendor/termux-addons/termux-tasker/app/src/main/res/values-zh-rCN/strings.xml) | Tasker 页面中文化 |
