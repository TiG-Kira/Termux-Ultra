# Termux Ultra 代码优化需求文档

> 仓库：`tig-kira/termux-ultra`（分支：`ReBuild`）
> 日期：2026-08-21
> 目标：根据以下需求对现有代码进行增量修改，保持原有功能完整性

---

## 需求 1：终端页 UI 优化（对应 GitHub Issue #4）

### 1.1 屏蔽 Material 设计元素，统一使用 miuix 组件

**问题**：终端页面长按后弹出的"更多选项"对话框仍使用 Material Design 组件，与应用整体 miuix 风格不统一。

**要求**：
- 查找终端相关页面（`TerminalDetailScreen.kt`、`TermuxActivity.java` 等）中所有 Material Design 弹窗、对话框、Toast
- 将其全部替换为 miuix 组件（`OverlayDialog`、`Snackbar`、`miuix Switch` 等）
- 确保视觉风格与应用其他页面一致

### 1.2 新增按钮折叠/展开功能

**问题**：终端会话页 TopBar 按钮过多（返回、键盘、新建、关闭），导致 `SmallTopAppBar` 标题文字被挤压无法显示。

**要求**：
- 在 `TerminalTopBar.kt` 中新增「折叠按钮」
- **折叠状态**：点击折叠按钮后，隐藏除「返回按钮」和「折叠按钮本身」以外的所有按钮（新建、关闭、键盘），同时显示完整的 TopBar 标题（终端会话名称）
- **展开状态**：再次点击（此时变为展开图标）恢复被隐藏按钮，标题文字隐藏，按钮恢复正常显示
- 折叠/展开状态通过 `TerminalTopBarState` 持久化

### 1.3 会话结束状态处理

**要求**：
- 监听 `TerminalSession` 的生命周期事件（`shellPid == -1` 表示会话结束）
- 会话结束后，TopBar 自动进入常驻模式（不可滚动隐藏）
- 原「新终端」位置改为显示：`会话已结束 (退出代码: N)`
- 结束会话的操作按钮（关闭等）变为 disabled 状态

### 1.4 新终端按钮文案优化

**要求**：
- TopBar 上「新建终端」按钮的 tooltip/`contentDescription` 改为：`新建终端 [原生编号: X]`
- 其中 `X` 为当前终端会话的原生编号（从 `TermuxSession.mHandle` 或 `TerminalSession` 获取）

---

## 需求 2：全局 Toast → Snackbar 迁移

**问题**：应用内部仍有多处使用 `Toast.makeText()` 显示提示，与 miuix Snackbar 体验不一致。

**要求**：
1. **查找所有 Toast 使用点**（重点关注以下文件）：
   - `RiskConfirmManager.kt`（约 6 处 Toast）
   - `FallbackHelper.kt`
   - `MainActivity.kt`
   - 其他 Compose 页面和传统 View 系统

2. **创建统一 Snackbar 工具类** `SnackbarHelper.kt`：
   ```kotlin
   object SnackbarHelper {
       // 初始化 SnackbarHostState（在 Activity setContent 顶层调用）
       fun init(hostState: SnackbarHostState)
       // 显示 Snackbar（可跨 composable 调用）
       fun show(context: Context, message: String, duration: SnackbarDuration = Short)
   }
   ```

3. **迁移步骤**：
   - 每个 Activity 的 `Scaffold {}` 顶层包裹 `SnackbarHost`
   - `SnackbarHelper.init()` 在 Activity `onCreate` 中调用
   - 全局替换 `Toast.makeText(ctx, msg, ...).show()` → `SnackbarHelper.show(ctx, msg)`
   - 非 Compose 环境（如 `TermuxActivity` 传统 View 系统）使用 `com.google.android.material.snackbar.Snackbar` 作为 fallback

4. **确保**：所有原有 Toast 的触发逻辑和文案保持不变，仅改变显示组件

---

## 需求 3：增强防护模式分级（重构 `RiskConfirmManager`）

**问题**：当前防护模式仅有布尔开关（开/关），缺少灵活的分级控制。

### 3.1 新增防护等级枚举

```kotlin
enum class ProtectionLevel(val displayName: String) {
    LEVEL_0_OFF("关闭"),
    LEVEL_1_WARN_ONLY("仅提示"),
    LEVEL_2_WARN_VERIFY("警告并验证"),
    LEVEL_3_AUTO_BLOCK("自动拦截")
}
```

### 3.2 各等级行为定义

| 等级 | 行为描述 |
|------|----------|
| **Level 0（关闭）** | 视为完全关闭防护，`handleTerminalCommand()` 直接返回 `false`，不进行任何检测。关闭时需遵循现有警告弹窗 + 生物认证逻辑 |
| **Level 1（仅提示）** | 检测到高危命令后，通过 Snackbar 提示用户命令危险，但**不拦截、不弹窗**，命令直接放行。从更高等级降到此时，弹出 Snackbar 提示「降级为仅提示模式，不再拦截，风险自行承担」 |
| **Level 2（警告并验证）** | **默认等级**，即现有逻辑：弹窗警告 + 60 秒倒计时 + 生物认证/PIN 验证。用户点击「确认执行」时**暂停自动拒绝倒计时**，完成认证后恢复或放行。从 Level 3 降到 Level 2 时 Snackbar 提示 |
| **Level 3（自动拦截）** | 识别到危险命令后**直接拒绝执行**，通过 Snackbar 通知「已拦截危险命令」。例外：SSH 远程连接、容器内 su/sudo、电源操作按现有 Level 2 逻辑处理，其余一概直接拒绝 |

### 3.3 存储与迁移

- 将原有 `KEY_ENABLED`（boolean）迁移为 `KEY_PROTECTION_LEVEL`（int）
- 迁移逻辑：`KEY_ENABLED == false` → `LEVEL_0_OFF`；`KEY_ENABLED == true` → `LEVEL_2_WARN_VERIFY`
- 新增 SharedPreferences key：`KEY_PROTECTION_MODE`（检测模式，见需求 4）

### 3.4 涉及修改的文件

- `RiskConfirmManager.kt`：核心逻辑重构
- `SettingsScreen.kt`：设置页 UI 改为分级选择器
- `RiskCommandDetector.kt`：可能需适配分级逻辑

---

## 需求 4：防护检测模式配置

**问题**：现有防护仅支持静态检测，缺少运行时动态解析选项。

### 4.1 新增检测模式枚举

```kotlin
enum class DetectionMode(val displayName: String) {
    NONE("不侦测"),
    STATIC("静态侦测（性能优先，默认）"),
    RUNTIME("运行时解析（安全优先）")
}
```

### 4.2 行为定义

| 模式 | 描述 |
|------|------|
| **不侦测** | 仅当防护等级为 Level 0 时可选/默认激活，切换到其他等级时自动切换为用户上次选择的侦测模式 |
| **静态侦测** | 现有逻辑，仅对用户输入的命令文本进行正则匹配检测，性能开销低。UI 提示：「仅检测命令文本，无法识别拼接命令或运行时生成的危险操作」 |
| **运行时解析** | 在命令实际执行时检测子进程创建和系统调用，智能判断危险行为。UI 提示：「可能增加性能开销，存在极少数无法检测的边界场景」 |

### 4.3 实现要点

- **静态侦测**：复用 `RiskCommandDetector.detect()` 正则匹配逻辑
- **运行时解析**：在 `TerminalSession` 层实现命令拦截器接口
  ```kotlin
  interface RuntimeCommandInterceptor {
      fun onCommandAboutToExecute(command: String): InterceptResult
  }
  ```
  通过监听 shell 的实际输出来解析正在执行的命令

### 4.4 存储

- SharedPreferences key：`KEY_DETECTION_MODE`（int）
- 与 `KEY_PROTECTION_LEVEL` 联动：等级 0 时锁定为 `NONE`，退出等级 0 时恢复用户上次选择

---

## 需求 5：脚本文件检测

**问题**：当前仅检测终端内单条命令，不支持对脚本文件进行批量检测。

### 5.1 功能要求

- 在文件管理页（`FileManagerScreen.kt`）点击「执行脚本」前，自动扫描脚本内容
- 扫描方式：根据当前防护等级选择静态（逐行正则匹配）或运行时解析
- 检测到危险命令时，弹出对话框显示：
  - 危险命令所在行号和内容
  - 「查看脚本」按钮（在对话框中显示完整脚本内容）
  - 「编辑脚本」按钮（跳转到编辑器 Activity）
  - 「继续执行」/「取消」按钮

### 5.2 实现要点

```kotlin
// 在 RiskCommandDetector 中新增
fun detectScript(scriptPath: String, detectionMode: DetectionMode): List<ScriptDetectionResult>

data class ScriptDetectionResult(
    val lineNumber: Int,
    val lineContent: String,
    val riskType: RiskType,
    val description: String
)
```

### 5.3 涉及修改

- `RiskCommandDetector.kt`：新增脚本扫描方法
- `FileManagerScreen.kt`：执行脚本前增加预扫描流程
- 新增脚本检测对话框 Composable

---

## 需求 6：ROOT 用户增强防护

**问题**：ROOT 用户面临更高风险，但缺少针对性的防护措施和 UI 提示。

### 6.1 ROOT 检测与默认配置

- 应用启动时异步检测 ROOT 权限：
  ```kotlin
  fun hasRootAccess(): Boolean {
      return try {
          ProcessBuilder("su", "-c", "echo ok").start().inputStream.bufferedReader().readText().trim() == "ok"
      } catch (e: Exception) { false }
  }
  ```
- 检测到 ROOT 时，默认防护等级设为 **Level 3（自动拦截）**
- 终端页顶部显示 ROOT 状态卡片：
  - 标题：「检测到 ROOT 权限」
  - 内容：「您以 ROOT 权限运行，防护等级已设为最高级别。如需更改，请前往设置页的增强防护选项」
  - 强烈建议不建议用户关闭防护（视觉上用红色/警告色突出）

### 6.2 ROOT 用户安全会话创建警告

- 在创建「安全模式会话」（调用安卓宿主系统 Shell）前：
  - 弹出警告对话框：
    ```
    即将调用安卓宿主系统 Shell
    
    安全模式会话会跳出 Termux 会话，启动直接操作 Android 宿主系统分区的 Shell。
    由于您具备 ROOT 权限，若意外误执行命令，可能直接造成：
    • 系统故障
    • 数据丢失
    • 无法开机
    
    是否继续调用宿主系统 Shell？
    ```
  - 用户确认后才创建会话
  - 非 ROOT 用户直接创建，不弹窗

### 6.3 ROOT 专属检测项

对 ROOT 用户额外检测以下内容（非 ROOT 用户不执行）：

| 检测项 | 行为 |
|--------|------|
| **原生 su/sudo 拦截** | ROOT 用户执行 su/sudo：按现有拦截逻辑处理。**非 ROOT 用户执行 su/sudo：Snackbar 提示「无 ROOT 权限」后放行** |
| **原始块设备访问** | 检测到 `> /dev/block/*` 等操作时弹窗警告 |
| **setprop 命令** | 检测到 `setprop` 时弹窗提示风险 |
| **脚本内 su/sudo** | 脚本扫描时额外标记提权命令 |

### 6.4 设置页 ROOT 管理卡片

在 `SettingsScreen.kt` 头部新增 ROOT 管理卡片：

```
┌─────────────────────────────────────┐
│  ⚠️ 正以 ROOT 权限运行中            │
│  Termux 已检测到 ROOT 权限。         │
│                                     │
│  [对 Termux 授予 ROOT 权限]         │
│  [宽松模式]  ← 需生物验证才能开启    │
└─────────────────────────────────────┘
```

- **对 Termux 授予 ROOT 权限**：点击后立即请求一次 ROOT 授权。已获取后按钮置灰，显示「已获取 ROOT 权限」
- **宽松模式**（ROOT 专属总开关，默认关闭）：
  - 开启需通过**风险弹窗 + 生物/PIN 验证**（安全门槛等同防护等级 0）
  - 开启后效果：
    1. 解除 ROOT 用户创建安全会话的确认弹窗
    2. 防护等级自动调整为 Level 1（仅提示）

---

## 需求 7：主页卡片排列方式切换

**问题**：当前终端主页的功能卡片（如欢迎卡、服务状态卡、Termux Agent 入口卡等）采用竖向堆叠排列，当卡片数量较多时占用大量垂直空间，导致会话列表被挤压。

### 7.1 新增设置项

在设置页「外观」分类下新增一项：

- **设置项名称**：`主页卡片排列`
- **可选值**：
  - `竖向`（默认）— 保持现有上下堆叠布局，所有卡片同时可见
  - `横向` — 单卡片可见，通过左右滑动切换不同功能卡片

### 7.2 功能卡片范围

以下卡片计入排列范围：
- 欢迎卡片（`WelcomeCard`）
- 服务状态卡片（`ServiceStatusCard`）
- Termux Agent 入口卡片（`AiTermuxEntryCard`）
- KeepAlive 警告卡片（`KeepAliveWarningCard`）
- 低版本 Android 警告卡片（`LowAndroidWarningCard`）
- ROOT 状态卡片（需求 6 新增）

**注意**：终端会话卡片（`TerminalCard`、`DeadSessionCard`）**不属于**此排列范围，保持原有网格布局。

### 7.3 可用性控制

- **功能卡片 ≥ 2 张时**：设置项可正常切换
- **功能卡片仅 1 张时**：设置项显示但置为 disabled 状态，显示提示文案：「仅一张卡片，无需排列切换」

实现时需实时监听功能卡片数量（通过观察各卡片的显示条件）。

### 7.4 横向滑动实现

**要求**：
- 使用 miuix `HorizontalPager` 或 Compose `Pager` 组件
- 每屏完整显示一张功能卡片，卡片左右留适当 padding
- 支持左右手势滑动切换
- 支持通过底部指示器（Dots）快速定位当前卡片位置
- 切换时带有平滑过渡动画
- 卡片切换不影响下方会话列表的显示

**示意结构**：
```
┌─────────────────────────────┐
│  ← [横向卡片滑动区域] →     │
│  ┌───────────────────────┐  │
│  │   功能卡片 (如欢迎卡)  │  │
│  └───────────────────────┘  │
│         ● ○ ○                │  ← 指示器
├─────────────────────────────┤
│  会话列表 (网格布局)         │
│  ┌─────┐ ┌─────┐            │
│  │会话1│ │会话2│            │
│  └─────┘ └─────┘            │
└─────────────────────────────┘
```

### 7.5 存储

- SharedPreferences key：`KEY_CARD_LAYOUT_MODE`（String，值为 `"vertical"` 或 `"horizontal"`）
- 默认值：`"vertical"`

### 7.6 涉及修改

- `SettingsScreen.kt`：新增「主页卡片排列」设置项（`SwitchPreference` 或 `OverlayDropdownPreference`）
- `TerminalListScreen.kt`：
  - 将功能卡片区域从 `Column` 重构为支持横/竖切换的布局
  - 新增 `CardLayoutMode` 状态观察
  - 新增功能卡片数量统计逻辑
- `strings.xml` / `strings-zh-rCN/strings.xml`：新增相关字符串资源

---

## 需求 8：独立版本号体系与更新检查重构

**问题**：当前版本号沿用 Termux 原版的 `118.3.71` 格式，无法体现 Termux Ultra 的独立迭代节奏。同时更新检查逻辑基于原版本体系，需适配新的版本号格式。

### 8.1 新版本号格式

**格式**：`R<x.y.z>`

| 组成部分 | 含义 | 说明 |
|----------|------|------|
| `R` | Rebuild 标识 | 代表此版本基于 Compose 重建的 Termux Ultra 分支 |
| `x` | 主版本号 | 重大架构变更时递增 |
| `y` | 次版本号 | 功能新增时递增 |
| `z` | 修订号 | Bug 修复时递增 |

**当前版本**：`R0.9.0`

### 8.2 版本号变更

**文件**：`app/build.gradle`

在 `defaultConfig` 中新增 `termuxCoreVersion` 变量并修改版本号：
```groovy
versionCode 1059          // 在当前 1058 基础上 +1，确保系统识别为新版本
versionName "R0.9.0"      // 改为新的独立版本号格式

// 新增：Termux 核心版本号（基于的上游 Termux 版本）
resValue "string", "termux_core_version", "0.118.3"
buildConfigField "String", "TERMUX_CORE_VERSION", "\"0.118.3\""
```

> **规则**：`versionCode` 每次发布新版本时递增 +1（如 1058 → 1059 → 1060...），`versionName` 独立按 `R<x.y.z>` 规则维护。`termuxCoreVersion` 记录当前基于的上游 Termux 版本号（如 `0.118.3`），仅在同步上游 Termux 更新时变更。

### 8.3 更新检查逻辑重构

**目标**：确保更新检查逻辑支持新版本号体系（`R<x.y.z>`）。

#### 8.3.1 检查逻辑需求

1. **检查 GitHub Releases**：通过 GitHub API 查询仓库的 Releases 列表
2. **版本匹配规则**：
   - 当前版本 Tag：`R0.9.0`（需与 `versionName` 对应）
   - 查找最新的 **Latest Release**
   - 若未启用 Beta 开关：仅推荐标记为 `Latest` 的 Release
   - 若启用 Beta 开关：推荐最新的 Release（包括 Pre-release）
3. **版本比较**：
   - 将 `R0.9.0` 解析为 `(x=0, y=9, z=0)` 元组进行数值比较
   - 新版本号 > 当前版本号 → 提示更新
   - 新版本号 ≤ 当前版本号 → 已是最新
4. **容错逻辑**：
   - 找不到与当前版本匹配的 Tag → 视为最新版，不提示更新
   - API 请求失败 → 静默忽略，不打扰用户
   - Release 列表为空 → 视为最新版

#### 8.3.2 实现要点

```kotlin
// 版本号数据类
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<AppVersion> {
    companion object {
        fun parse(versionName: String): AppVersion? {
            // 支持 "R0.9.0" 格式
            val regex = Regex("""^R(\d+)\.(\d+)\.(\d+)$""")
            val match = regex.find(versionName) ?: return null
            return AppVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt()
            )
        }
    }
    
    override fun compareTo(other: AppVersion): Int {
        return compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    }
    
    fun toVersionName(): String = "R$major.$minor.$patch"
}

// 更新检查管理器
object UpdateChecker {
    const val GITHUB_REPO = "tig-kira/termux-ultra"
    
    suspend fun checkForUpdate(
        context: Context,
        currentVersion: AppVersion,
        betaEnabled: Boolean
    ): UpdateResult {
        // 1. 调用 GitHub API 获取 Releases
        // 2. 根据 betaEnabled 筛选
        // 3. 比较版本号
        // 4. 返回结果
    }
}

// 更新检查结果
sealed class UpdateResult {
    data class UpdateAvailable(
        val latestVersion: AppVersion,
        val releaseUrl: String,
        val isBeta: Boolean
    ) : UpdateResult()
    
    object UpToDate : UpdateResult()
    object CheckFailed : UpdateResult()
}
```

#### 8.3.3 GitHub API 调用

```
GET https://api.github.com/repos/tig-kira/termux-ultra/releases
```

**筛选逻辑**：
- `betaEnabled = false`：筛选 `tag_name` 对应 `R*` 且 `prerelease = false` 的最新 Release
- `betaEnabled = true`：筛选 `tag_name` 对应 `R*` 的最新 Release（包括 `prerelease = true`）

#### 8.3.4 UI 展示

在设置页「关于」分类中：
- 显示当前版本号：`Termux Ultra R0.9.0`
- 保留原 Termux 版本号显示（已在设置内，不变更）
- 「检查更新」按钮：点击后执行检查
- 发现新版本时：弹出 `OverlayDialog` 提示，包含新版本号和下载链接

### 8.4 涉及修改

- `app/build.gradle`：`versionCode` 从 1058 递增至 1059，`versionName` 改为 `"R0.9.0"`
- 新建 `UpdateChecker.kt`：版本解析 + GitHub API 检查逻辑
- `SettingsScreen.kt`：
  - 新版本号显示
  - 「检查更新」按钮
  - Beta 开关联动逻辑
- `strings.xml` / `strings-zh-rCN/strings.xml`：新增相关字符串资源

---

## 需求 9：Termux 核心版本号动态化

**问题**：关于页「基于 Termux 0.118.3 稳定版」字样为硬编码字符串，当上游 Termux 版本更新时需手动修改代码。

### 9.1 新增构建变量

**文件**：`app/build.gradle`

在 `defaultConfig` 中新增 `termuxCoreVersion`（已包含在需求 8.2 中）：
```groovy
resValue "string", "termux_core_version", "0.118.3"
buildConfigField "String", "TERMUX_CORE_VERSION", "\"0.118.3\""
```

- `resValue` 生成 `R.string.termux_core_version`，可通过 `context.getString(R.string.termux_core_version)` 获取
- `buildConfigField` 生成 `BuildConfig.TERMUX_CORE_VERSION`，可在 Kotlin 代码中直接引用

### 9.2 关于页 UI 修改

**文件**：`app/src/main/java/com/termux/app/compose/AboutScreen.kt`

**现状**（硬编码）：
```kotlin
Text(
    text = "${context.getString(R.string.based_on_termux_version)} 0.118.3 稳定版",
    style = TextStyle(
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
)
```

**修改后**（动态读取）：
```kotlin
val termuxCoreVersion = remember { 
    context.getString(R.string.termux_core_version) 
}

Text(
    text = "${context.getString(R.string.based_on_termux_version)} $termuxCoreVersion 稳定版",
    style = TextStyle(
        fontSize = 12.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
)
```

### 9.3 UI 逻辑说明

- `termuxCoreVersion` 的显示样式与当前版本号卡片内的版本号（`currentVersion`）使用相同的 UI 逻辑
- 均通过 `remember {}` 包裹，确保只在首次组合时读取一次，避免重建时重复调用
- `resValue` 生成的 `R.string.termux_core_version` 会随 `versionName` 同步变化，无需额外维护

### 9.4 涉及修改

- `app/build.gradle`：新增 `resValue` 和 `buildConfigField`（已包含在需求 8.2 中）
- `AboutScreen.kt`：将硬编码字符串改为从资源文件动态读取
- `strings.xml` / `strings-zh-rCN/strings.xml`：可保留 `based_on_termux_version` 字符串（仅前缀部分，如「基于 Termux」）

---

## 实施注意事项

### 技术约束
1. **代码风格**：沿用现有 Kotlin 编码规范，不引入新的第三方依赖
2. **兼容性**：所有 SharedPreferences 变更需做旧数据迁移
3. **性能**：运行时解析模式需在后台线程执行，避免阻塞 UI
4. **安全性**：生物识别相关代码已有现成实现（`launchBiometricAuth`），直接复用
5. **滑动性能**：横向卡片切换使用 `snapshot` 避免 recomposition 时丢失滚动位置
6. **网络请求**：GitHub API 调用需在子线程执行，添加超时和重试机制

### 建议实施顺序
1. **Phase 1**：需求 1（UI 优化）+ 需求 2（Toast 迁移）— 改动相对独立
2. **Phase 2**：需求 3（防护分级）+ 需求 4（检测模式）— 核心逻辑重构
3. **Phase 3**：需求 5（脚本检测）+ 需求 6（ROOT 功能）— 依赖前两阶段的基础
4. **Phase 4**：需求 7（卡片排列）— 独立的 UI 布局改动，可与前 3 阶段并行
5. **Phase 5**：需求 8（版本号 + 更新逻辑）+ 需求 9（核心版本动态化）— 关联改动，可一并实施

---

### 关键文件索引

| 文件路径 | 职责 |
|----------|------|
| `app/build.gradle` | 版本号配置 + `termuxCoreVersion` 变量 |
| `app/src/main/java/com/termux/app/compose/AboutScreen.kt` | 关于页（动态版本号显示） |
| `app/src/main/java/com/termux/app/compose/RiskCommandDetector.kt` | 危险命令检测引擎 |
| `app/src/main/java/com/termux/app/compose/RiskConfirmManager.kt` | 风险确认管理（核心重构目标） |
| `app/src/main/java/com/termux/app/compose/TerminalTopBar.kt` | 终端页 TopBar（折叠/展开） |
| `app/src/main/java/com/termux/app/compose/TerminalListScreen.kt` | 终端会话列表页（含功能卡片布局） |
| `app/src/main/java/com/termux/app/compose/SettingsScreen.kt` | 设置页（防护配置 + 卡片排列 + 更新检查 UI） |
| `app/src/main/java/com/termux/app/compose/FileManagerScreen.kt` | 文件管理页（脚本检测入口） |
| `app/src/main/java/com/termux/app/utils/UpdateChecker.kt` | 版本解析 + GitHub 更新检查 |
| `app/src/main/java/com/termux/app/FallbackHelper.kt` | 降级辅助（Toast 迁移） |
| `app/src/main/res/values/strings.xml` | 字符串资源 |
| `app/src/main/res/values-zh-rCN/strings.xml` | 中文翻译 |