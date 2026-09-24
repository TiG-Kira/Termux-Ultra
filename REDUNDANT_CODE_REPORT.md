# Termux-Ultra 冗余代码扫描报告

> 工作区已同步到 `origin/main` @ `d605a9ea`（落后 13 个提交 -> 快进拉取）。
> 扫描对象：首方源码 457 个 Kotlin/Java 文件（约 14.9 万行），另覆盖 res/ XML、Manifest、gradle 与 ProGuard 配置作为引用语料。

## 一、结果总览

| 类别 | 数量 | 置信度 | 说明 |
|---|---:|---|---|
| 孤儿文件（整文件死代码） | 14 | 高/中 | 整个文件的所有顶层声明在首方代码、Manifest、XML、gradle 中均无引用，可整文件删除。 |
| 未使用导入 | 411 | 高 | import 的类/扩展在文件内无任何引用（含通配符导入）。不产生编译错误，但增加耦合噪音。 |
| 未使用私有声明 | 50 | 高 | private/internal 的函数、字段、常量、类型，可见范围内零引用，可安全删除。 |
| 未使用局部变量 | 24 | 高 | 函数体内声明后再未被读取的变量，删除后不影响行为。 |
| 未使用函数参数 | 38 | 中 | 形参在函数体内完全未被使用。Compose 组件的参数常属对外 API，删前需评估。 |
| 重复代码 | 488 | 中 | 归一化后完全相同的代码块（>=8 个有效行），跨文件重复通常应抽取公共组件/工具函数。 |
| 空函数体 | 16 | 低 | 函数体为空。多为框架回调的空实现（受接口约束），删除前需确认。 |
| 注释掉的代码 | 7 | 中 | 连续 >=5 行被注释的实际代码，应进入版本历史而非留在源码里。 |
| 恒假/恒真死条件 | 3 | 高 | `if (false)` / `|| true` 之类恒定条件，导致分支或整段代码永不/总是执行。 |
| 未使用资源 | 697 | 中 | res/ 下无任何 R.xx 或 @xx 引用的资源。项目存在 getIdentifier 动态查找，删除前需二次确认。 |
| 未使用公开声明（需人工确认） | 606 | 低 | public/open 的声明在首方代码中无引用。termux-shared 为库模块，可能被其它 Termux 插件仓库依赖，属低置信度，不可直接删除。 |
| 不可达代码 | 0 | - | 初筛 186 条全部为「跨行语句 / 无花括号 if 守卫」误报，回读源码后清零 |
| 高度相似文件对 | 4 | 中 | 新旧包并行导致的近乎 100% 重复实现 |

重复代码累计冗余行数约 **6,801 行**；可安全删除的高置信度条目 **422 条**。

## 二、逐项明细

### 1. 孤儿文件（整文件死代码）（14 条）

整个文件的所有顶层声明在首方代码、Manifest、XML、gradle 中均无引用，可整文件删除。

| 文件路径 | 行号 | 名称 | 类型 | 说明 |
|---|---:|---|---|---|
| `app/src/main/java/com/gaurav/avnc/ui/prefs/ForgetKnownHostsDialog.kt` | 1 | ForgetKnownHostsDialog | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用 |
| `app/src/main/java/com/gaurav/avnc/util/BindingAdapters.java` | 1 | BindingAdapters | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用 |
| `app/src/main/java/com/gaurav/avnc/util/OpenableDocument.kt` | 1 | OpenableDocument | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用 |
| `app/src/main/java/com/gaurav/avnc/util/SingleShotFlag.kt` | 1 | SingleShotFlag | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用 |
| `app/src/main/java/com/termux/app/compose/RuntimeCommandInterceptor.kt` | 1 | RuntimeCommandInterceptor | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用 |
| `app/src/main/java/com/termux/app/fragments/FileManagerFragment.java` | 1 | FileManagerFragment | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用 |
| `app/src/main/java/com/termux/app/fragments/SettingsFragment.java` | 1 | SettingsFragment | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用 |
| `app/src/main/java/com/termux/app/fragments/TerminalListFragment.java` | 1 | TerminalListFragment | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用 |
| `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionServiceClient.java` | 1 | TermuxTerminalSessionServiceClient | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用 |
| `app/src/main/java/com/termux/app/utils/PluginUtils.java` | 1 | PluginUtils | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用 |
| `termux-shared/src/main/java/com/termux/shared/net/url/UrlUtils.java` | 1 | UrlPart, UrlUtils | dead-file | 文件内全部顶层声明 2 个在首方代码 / Manifest / XML / gradle 中均无引用；属库模块，删除前需确认无外部插件依赖 |
| `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxBootAppSharedPreferences.java` | 1 | TermuxBootAppSharedPreferences | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用；属库模块，删除前需确认无外部插件依赖 |
| `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxStylingAppSharedPreferences.java` | 1 | TermuxStylingAppSharedPreferences | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用；属库模块，删除前需确认无外部插件依赖 |
| `termux-shared/src/main/java/com/termux/shared/termux/theme/TermuxThemeUtils.java` | 1 | TermuxThemeUtils | dead-file | 文件内全部顶层声明 1 个在首方代码 / Manifest / XML / gradle 中均无引用；属库模块，删除前需确认无外部插件依赖 |

### 2. 未使用导入（411 条）

import 的类/扩展在文件内无任何引用（含通配符导入）。不产生编译错误，但增加耦合噪音。

| 文件路径 | 行号 | 名称 | 类型 | 说明 |
|---|---:|---|---|---|
| `app/src/main/java/com/gaurav/avnc/model/db/MainDb.kt` | 12 | androidx.room.AutoMigration | unused-import | import androidx.room.AutoMigration 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/FallbackHelper.kt` | 13 | com.termux.app.utils.LogManager | unused-import | import com.termux.app.utils.LogManager 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/MainActivity.kt` | 12 | androidx.activity.ComponentActivity | unused-import | import androidx.activity.ComponentActivity 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/MainActivity.kt` | 18 | android.content.SharedPreferences | unused-import | import android.content.SharedPreferences 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/MainActivity.kt` | 19 | android.widget.Toast | unused-import | import android.widget.Toast 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/MainActivity.kt` | 20 | androidx.compose.runtime.getValue | unused-import | import androidx.compose.runtime.getValue 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/MainActivity.kt` | 22 | androidx.compose.runtime.setValue | unused-import | import androidx.compose.runtime.setValue 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/MainActivity.kt` | 25 | androidx.compose.runtime.LaunchedEffect | unused-import | import androidx.compose.runtime.LaunchedEffect 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/MainActivity.kt` | 27 | androidx.compose.runtime.snapshotFlow | unused-import | import androidx.compose.runtime.snapshotFlow 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/MainActivity.kt` | 30 | androidx.lifecycle.viewmodel.compose.viewModel | unused-import | import androidx.lifecycle.viewmodel.compose.viewModel 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/MainActivity.kt` | 31 | androidx.lifecycle.compose.LifecycleResumeEffect | unused-import | import androidx.lifecycle.compose.LifecycleResumeEffect 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/MainActivity.kt` | 32 | kotlinx.coroutines.android.awaitFrame | unused-import | import kotlinx.coroutines.android.awaitFrame 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/MainActivity.kt` | 33 | kotlinx.coroutines.delay | unused-import | import kotlinx.coroutines.delay 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/MainActivity.kt` | 43 | com.termux.terminal.TerminalSession | unused-import | import com.termux.terminal.TerminalSession 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/OobeActivity.kt` | 20 | androidx.compose.runtime.getValue | unused-import | import androidx.compose.runtime.getValue 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/OobeActivity.kt` | 22 | androidx.compose.runtime.setValue | unused-import | import androidx.compose.runtime.setValue 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 12 | androidx.activity.ComponentActivity | unused-import | import androidx.activity.ComponentActivity 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 18 | androidx.compose.animation.AnimatedVisibility | unused-import | import androidx.compose.animation.AnimatedVisibility 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 25 | androidx.compose.foundation.layout.FlowRow | unused-import | import androidx.compose.foundation.layout.FlowRow 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 52 | androidx.compose.ui.platform.LocalLifecycleOwner | unused-import | import androidx.compose.ui.platform.LocalLifecycleOwner 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 54 | androidx.compose.ui.res.stringResource | unused-import | import androidx.compose.ui.res.stringResource 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/activities/AlertDialogActivity.kt` | 20 | androidx.compose.runtime.getValue | unused-import | import androidx.compose.runtime.getValue 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/activities/AlertDialogActivity.kt` | 23 | androidx.compose.runtime.setValue | unused-import | import androidx.compose.runtime.setValue 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/activities/AlertDialogActivity.kt` | 45 | com.termux.shared.file.FileUtils | unused-import | import com.termux.shared.file.FileUtils 在文件内无任何引用 |
| `app/src/main/java/com/termux/app/activities/QemuVmActivity.kt` | 14 | androidx.compose.foundation.layout.* | unused-import | import androidx.compose.foundation.layout.* 在文件内无任何引用 |

> 表格仅列出前 25 条，完整清单见同目录 `REDUNDANT_CODE_LIST.csv`。


### 3. 未使用私有声明（50 条）

private/internal 的函数、字段、常量、类型，可见范围内零引用，可安全删除。

| 文件路径 | 行号 | 名称 | 类型 | 说明 |
|---|---:|---|---|---|
| `app/src/main/java/com/termux/app/receiver/MemoryBroadcastReceiver.java` | 20 | BUNDLE_KEY_EXTRA | 未使用字段 | java private field BUNDLE_KEY_EXTRA 全仓无引用 |
| `app/src/main/java/com/termux/app/terminal/TermuxTerminalSessionServiceClient.java` | 16 | LOG_TAG | 未使用字段 | java private field LOG_TAG 全仓无引用 |
| `terminal-view/src/main/java/com/termux/view/TerminalView.java` | 65 | mCursorInvisibleIgnoreOnce | 未使用字段 | java private field mCursorInvisibleIgnoreOnce 全仓无引用 |
| `termux-shared/src/main/java/com/termux/shared/data/IntentUtils.java` | 13 | LOG_TAG | 未使用字段 | java private field LOG_TAG 全仓无引用 |
| `termux-shared/src/main/java/com/termux/shared/file/filesystem/FileTime.java` | 69 | valueAsString | 未使用字段 | java private field valueAsString 全仓无引用 |
| `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxAPIAppSharedPreferences.java` | 18 | LOG_TAG | 未使用字段 | java private field LOG_TAG 全仓无引用 |
| `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxAppSharedPreferences.java` | 24 | LOG_TAG | 未使用字段 | java private field LOG_TAG 全仓无引用 |
| `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxBootAppSharedPreferences.java` | 18 | LOG_TAG | 未使用字段 | java private field LOG_TAG 全仓无引用 |
| `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxFloatAppSharedPreferences.java` | 23 | LOG_TAG | 未使用字段 | java private field LOG_TAG 全仓无引用 |
| `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxStylingAppSharedPreferences.java` | 18 | LOG_TAG | 未使用字段 | java private field LOG_TAG 全仓无引用 |
| `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxTaskerAppSharedPreferences.java` | 18 | LOG_TAG | 未使用字段 | java private field LOG_TAG 全仓无引用 |
| `termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxWidgetAppSharedPreferences.java` | 20 | LOG_TAG | 未使用字段 | java private field LOG_TAG 全仓无引用 |
| `termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxPropertyConstants.java` | 98 | LOG_TAG | 未使用字段 | java private field LOG_TAG 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/ui/vnc/LayoutManager.kt` | 266 | calculateCornerInsets | 未使用函数 | kotlin private function calculateCornerInsets 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/util/SamsungDex.kt` | 25 | isInDexMode | 未使用函数 | kotlin private function isInDexMode 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/vnc/VncClient.kt` | 430 | cbGetPassword | 未使用函数 | kotlin private function cbGetPassword 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/vnc/VncClient.kt` | 433 | cbGetCredential | 未使用函数 | kotlin private function cbGetCredential 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/vnc/VncClient.kt` | 436 | cbVerifyServerCertificate | 未使用函数 | kotlin private function cbVerifyServerCertificate 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/vnc/VncClient.kt` | 444 | cbGotXCutText | 未使用函数 | kotlin private function cbGotXCutText 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/vnc/VncClient.kt` | 458 | cbFramebufferSizeChanged | 未使用函数 | kotlin private function cbFramebufferSizeChanged 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/vnc/VncClient.kt` | 462 | cbBell | 未使用函数 | kotlin private function cbBell 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/vnc/VncClient.kt` | 465 | cbHandleCursorPos | 未使用函数 | kotlin private function cbHandleCursorPos 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/vnc/VncClient.kt` | 471 | cbHandleCursorInfo | 未使用函数 | kotlin private function cbHandleCursorInfo 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/vnc/VncClient.kt` | 481 | cbQemuAudioBegin | 未使用函数 | kotlin private function cbQemuAudioBegin 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/vnc/VncClient.kt` | 484 | cbQemuAudioData | 未使用函数 | kotlin private function cbQemuAudioData 全仓无引用 |
| `app/src/main/java/com/gaurav/avnc/vnc/VncClient.kt` | 487 | cbQemuAudioEnd | 未使用函数 | kotlin private function cbQemuAudioEnd 全仓无引用 |
| `app/src/main/java/com/termux/app/OobeActivity.kt` | 259 | completeOobe | 未使用函数 | kotlin private function completeOobe 全仓无引用 |
| `app/src/main/java/com/termux/app/TermuxService.java` | 421 | requestStopService | 未使用函数 | java private function requestStopService 全仓无引用 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 2346 | formatByteCount | 未使用函数 | kotlin private function formatByteCount 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/AiLocalModel.kt` | 1254 | splitLongSystemIntoMultiple | 未使用函数 | kotlin private function splitLongSystemIntoMultiple 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/AiLocalTrainer.kt` | 980 | callOnlineNonStream | 未使用函数 | kotlin private function callOnlineNonStream 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/OobeScreen.kt` | 1210 | PermissionItem | 未使用函数 | kotlin private function PermissionItem 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 2321 | UsageChart | 未使用函数 | kotlin private function UsageChart 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 2763 | ProcessItemRowCompact | 未使用函数 | kotlin private function ProcessItemRowCompact 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 3480 | readTotalCpuTime | 未使用函数 | kotlin private function readTotalCpuTime 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 3547 | readProcessMemoryPercent | 未使用函数 | kotlin private function readProcessMemoryPercent 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/RiskConfirmManager.kt` | 526 | nextRequestId | 未使用函数 | kotlin private function nextRequestId 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/TextEditorScreen.kt` | 379 | tokenStyle | 未使用函数 | kotlin private function tokenStyle 全仓无引用 |
| `app/src/main/java/com/termux/app/ftp/FtpServiceManager.kt` | 83 | isPortInUse | 未使用函数 | kotlin private function isPortInUse 全仓无引用 |
| `app/src/main/java/com/termux/app/ssh/SshScreen.kt` | 973 | loadConnections | 未使用函数 | kotlin private function loadConnections 全仓无引用 |
| `app/src/main/java/com/termux/app/vnc/VncScreen.kt` | 336 | loadConnections | 未使用函数 | kotlin private function loadConnections 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/AboutBgEffect.kt` | 24 | TAG | 未使用属性/常量 | kotlin private property TAG 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/AiLocalTrainer.kt` | 98 | DEFAULT_ROUNDS | 未使用属性/常量 | kotlin private property DEFAULT_ROUNDS 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/AiLocalTrainerScreen.kt` | 81 | TAG | 未使用属性/常量 | kotlin private property TAG 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/AiTermuxModels.kt` | 1204 | KEY_CONFIG | 未使用属性/常量 | kotlin private property KEY_CONFIG 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/MainScreen.kt` | 72 | SWIPE_VELOCITY_THRESHOLD | 未使用属性/常量 | kotlin private property SWIPE_VELOCITY_THRESHOLD 全仓无引用 |
| `app/src/main/java/com/termux/app/plugin/PluginWebViewActivity.kt` | 48 | EXTRA_URL | 未使用属性/常量 | kotlin private property EXTRA_URL 全仓无引用 |
| `libterminal/src/main/kotlin/com/awkoo/libterminal/view/render/TerminalBlinker.kt` | 17 | blinkerName | 未使用属性/常量 | kotlin private property blinkerName 全仓无引用 |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 3003 | CpuMonitor | 未使用类型 | kotlin private type CpuMonitor 全仓无引用 |
| `app/src/main/java/com/termux/app/plugin/PluginManager.kt` | 373 | cleanupDeadSessions | 未使用函数 | kotlin internal function cleanupDeadSessions 全仓无引用 |

### 4. 未使用局部变量（24 条）

函数体内声明后再未被读取的变量，删除后不影响行为。

| 文件路径 | 行号 | 名称 | 类型 | 说明 |
|---|---:|---|---|---|
| `app/src/main/java/com/termux/app/TermuxService.java` | 716 | newTermuxTask | unused-local | 局部变量 newTermuxTask 声明后未被使用：TermuxTaskCompat newTermuxTask = createTermuxTask(executionCommand); |
| `app/src/main/java/com/termux/app/TermuxService.java` | 1294 | res | unused-local | 局部变量 res 声明后未被使用：Resources res = getResources(); |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 710 | maxMissingEndTurnRounds | unused-local | 局部变量 maxMissingEndTurnRounds 声明后未被使用：val maxMissingEndTurnRounds = 5 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 1373 | missingEndTurnWarning | unused-local | 局部变量 missingEndTurnWarning 声明后未被使用：val missingEndTurnWarning = ""  // 已移除 END_TURN 依赖，保留空字符串供兼容 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 1474 | modelScope | unused-local | 局部变量 modelScope 声明后未被使用：val modelScope = rememberCoroutineScope() |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 2366 | isDark | unused-local | 局部变量 isDark 声明后未被使用：val isDark = isSystemInDarkTheme() |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 2835 | statusColor | unused-local | 局部变量 statusColor 声明后未被使用：val statusColor = when (t.status) { |
| `app/src/main/java/com/termux/app/activities/UtilityCenterActivity.kt` | 58 | appVM | unused-local | 局部变量 appVM 声明后未被使用：val appVM: com.termux.app.AppViewModel by viewModels() |
| `app/src/main/java/com/termux/app/compose/AiLocalTrainerScreen.kt` | 89 | scope | unused-local | 局部变量 scope 声明后未被使用：val scope = rememberCoroutineScope() |
| `app/src/main/java/com/termux/app/compose/OobeScreen.kt` | 523 | density | unused-local | 局部变量 density 声明后未被使用：val density = LocalDensity.current |
| `app/src/main/java/com/termux/app/compose/OobeScreen.kt` | 524 | configuration | unused-local | 局部变量 configuration 声明后未被使用：val configuration = LocalConfiguration.current |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 453 | coroutineScope | unused-local | 局部变量 coroutineScope 声明后未被使用：val coroutineScope = rememberCoroutineScope() |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 503 | unifiedSessionPids | unused-local | 局部变量 unifiedSessionPids 声明后未被使用：val unifiedSessionPids: Set<Int> = if (isComposeRuntime) { |
| `app/src/main/java/com/termux/app/compose/ScriptDetectionDialog.kt` | 68 | context | unused-local | 局部变量 context 声明后未被使用：val context = LocalContext.current |
| `app/src/main/java/com/termux/app/compose/SettingsScreen.kt` | 691 | defaultWelcome | unused-local | 局部变量 defaultWelcome 声明后未被使用：val defaultWelcome = remember { |
| `app/src/main/java/com/termux/app/compose/SettingsScreen.kt` | 2440 | unlimitedScope | unused-local | 局部变量 unlimitedScope 声明后未被使用：val unlimitedScope = rememberCoroutineScope() |
| `app/src/main/java/com/termux/app/compose/SettingsScreen.kt` | 2441 | unlimitedShowBlocked | unused-local | 局部变量 unlimitedShowBlocked 声明后未被使用：val unlimitedShowBlocked: () -> Unit = { |
| `app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt` | 1639 | context | unused-local | 局部变量 context 声明后未被使用：val context = LocalContext.current |
| `app/src/main/java/com/termux/app/compose/TerminalListScreen.kt` | 99 | aiTermuxEnabled | unused-local | 局部变量 aiTermuxEnabled 声明后未被使用：val aiTermuxEnabled = context.getSharedPreferences("app_settings", android.conte |
| `app/src/main/java/com/termux/app/compose/TermuxSettingsScreen.kt` | 211 | prefs | unused-local | 局部变量 prefs 声明后未被使用：val prefs = remember { TermuxAppSharedPreferences.build(context) } |
| `app/src/main/java/com/termux/app/compose/TermuxSettingsScreen.kt` | 603 | context | unused-local | 局部变量 context 声明后未被使用：val context = LocalContext.current |
| `app/src/main/java/com/termux/app/plugin/PluginCenterActivity.kt` | 638 | context | unused-local | 局部变量 context 声明后未被使用：val context = LocalContext.current |
| `app/src/main/java/com/termux/app/plugin/PluginWebViewActivity.kt` | 145 | plugin | unused-local | 局部变量 plugin 声明后未被使用：val plugin = PluginManager.getPluginById(context, pluginId) |
| `app/src/main/java/com/termux/app/terminal/TermuxSessionsListViewController.java` | 59 | isUsingBlackUI | unused-local | 局部变量 isUsingBlackUI 声明后未被使用：boolean isUsingBlackUI = false; |

### 5. 未使用函数参数（38 条）

形参在函数体内完全未被使用。Compose 组件的参数常属对外 API，删前需评估。

| 文件路径 | 行号 | 名称 | 类型 | 说明 |
|---|---:|---|---|---|
| `app/src/main/java/com/gaurav/avnc/model/ServerProfile.kt` | 219 | setValue(kp) | 普通函数参数 | 函数 setValue 的参数 kp 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/FallbackHelper.kt` | 50 | onOobeRenderFailure(throwable) | 普通函数参数 | 函数 onOobeRenderFailure 的参数 throwable 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/TermuxService.java` | 1276 | buildMinimalNotification(piFlags) | 普通函数参数 | 函数 buildMinimalNotification 的参数 piFlags 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/TermuxService.java` | 1293 | buildPkgNotification(piFlags) | 普通函数参数 | 函数 buildPkgNotification 的参数 piFlags 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 2889 | WelcomeChatCard(isDark) | Compose 组件参数 | 函数 WelcomeChatCard 的参数 isDark 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 2957 | QuickChips(inputText) | Compose 组件参数 | 函数 QuickChips 的参数 inputText 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 2957 | QuickChips(setInput) | Compose 组件参数 | 函数 QuickChips 的参数 setInput 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 3971 | AgentSkillCard(msgId) | Compose 组件参数 | 函数 AgentSkillCard 的参数 msgId 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 3971 | AgentSkillCard(vm) | Compose 组件参数 | 函数 AgentSkillCard 的参数 vm 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/AiLocalTrainerScreen.kt` | 545 | StepCard(roundIdx) | Compose 组件参数 | 函数 StepCard 的参数 roundIdx 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/ComposeTerminalListScreen.kt` | 58 | ComposeTerminalListScreen(onNewTerminal) | Compose 组件参数 | 函数 ComposeTerminalListScreen 的参数 onNewTerminal 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/LiquidGlassNavigationBar.kt` | 658 | SoftLightIndicator(isDragging) | Compose 组件参数 | 函数 SoftLightIndicator 的参数 isDragging 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/OobeScreen.kt` | 242 | triggerCompleteReveal(btnCenter) | 普通函数参数 | 函数 triggerCompleteReveal 的参数 btnCenter 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 980 | TipsAgentCard(card) | Compose 组件参数 | 函数 TipsAgentCard 的参数 card 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 980 | TipsAgentCard(onExecuteScript) | Compose 组件参数 | 函数 TipsAgentCard 的参数 onExecuteScript 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 2021 | CpuMonitorCard(temperature) | Compose 组件参数 | 函数 CpuMonitorCard 的参数 temperature 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 2021 | CpuMonitorCard(history) | Compose 组件参数 | 函数 CpuMonitorCard 的参数 history 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 2113 | GpuMonitorCard(history) | Compose 组件参数 | 函数 GpuMonitorCard 的参数 history 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 2237 | MemoryMonitorCard(history) | Compose 组件参数 | 函数 MemoryMonitorCard 的参数 history 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/QemuOnVncSheet.kt` | 1141 | AgentVmConfigTab(context) | Compose 组件参数 | 函数 AgentVmConfigTab 的参数 context 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/QemuOnVncSheet.kt` | 1141 | AgentVmConfigTab(coroutineScope) | Compose 组件参数 | 函数 AgentVmConfigTab 的参数 coroutineScope 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/ScriptDetectionDialog.kt` | 59 | ScriptDetectionDialog(scriptFilePath) | Compose 组件参数 | 函数 ScriptDetectionDialog 的参数 scriptFilePath 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/compose/TermuxApiBroadcastFix.kt` | 109 | applyAmWrapper(context) | 普通函数参数 | 函数 applyAmWrapper 的参数 context 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/ftp/FtpServer.kt` | 266 | handlePort(args) | 普通函数参数 | 函数 handlePort 的参数 args 在函数体内未被使用 |
| `app/src/main/java/com/termux/app/plugin/HostActionRegistry.kt` | 22 | registerDefaults(context) | 普通函数参数 | 函数 registerDefaults 的参数 context 在函数体内未被使用 |
| `terminal-view/src/main/java/com/termux/view/TerminalView.java` | 504 | onContextMenuClosed(menu) | 普通函数参数 | 函数 onContextMenuClosed 的参数 menu 在函数体内未被使用 |
| `terminal-view/src/main/java/com/termux/view/textselection/TextSelectionCursorController.java` | 343 | onTouchEvent(event) | 普通函数参数 | 函数 onTouchEvent 的参数 event 在函数体内未被使用 |
| `termux-shared/src/main/java/com/termux/shared/android/AndroidUtils.java` | 116 | getDeviceInfoMarkdownString(addPhantomProcessesInfo) | 普通函数参数 | 函数 getDeviceInfoMarkdownString 的参数 addPhantomProcessesInfo 在函数体内未被使用 |
| `termux-shared/src/main/java/com/termux/shared/shell/command/ExecutionCommand.java` | 432 | getExecutionOutputLogString(ignoreNull) | 普通函数参数 | 函数 getExecutionOutputLogString 的参数 ignoreNull 在函数体内未被使用 |
| `termux-shared/src/main/java/com/termux/shared/termux/crash/TermuxCrashUtils.java` | 85 | onPreLogCrash(context) | 普通函数参数 | 函数 onPreLogCrash 的参数 context 在函数体内未被使用 |
| `termux-shared/src/main/java/com/termux/shared/termux/crash/TermuxCrashUtils.java` | 85 | onPreLogCrash(thread) | 普通函数参数 | 函数 onPreLogCrash 的参数 thread 在函数体内未被使用 |
| `termux-shared/src/main/java/com/termux/shared/termux/crash/TermuxCrashUtils.java` | 85 | onPreLogCrash(throwable) | 普通函数参数 | 函数 onPreLogCrash 的参数 throwable 在函数体内未被使用 |
| `termux-shared/src/main/java/com/termux/shared/termux/crash/TermuxCrashUtils.java` | 89 | onPostLogCrash(thread) | 普通函数参数 | 函数 onPostLogCrash 的参数 thread 在函数体内未被使用 |
| `termux-shared/src/main/java/com/termux/shared/termux/crash/TermuxCrashUtils.java` | 89 | onPostLogCrash(throwable) | 普通函数参数 | 函数 onPostLogCrash 的参数 throwable 在函数体内未被使用 |
| `termux-shared/src/main/java/com/termux/shared/termux/settings/properties/TermuxSharedProperties.java` | 251 | getInternalTermuxPropertyValueFromValue(context) | 普通函数参数 | 函数 getInternalTermuxPropertyValueFromValue 的参数 context 在函数体内未被使用 |
| `termux-shared/src/main/java/com/termux/shared/termux/shell/TermuxShellManager.java` | 82 | onActionBootCompleted(intent) | 普通函数参数 | 函数 onActionBootCompleted 的参数 intent 在函数体内未被使用 |
| `termux-shared/src/main/java/com/termux/shared/termux/shell/TermuxShellManager.java` | 92 | onAppExit(context) | 普通函数参数 | 函数 onAppExit 的参数 context 在函数体内未被使用 |
| `termux-shared/src/main/java/com/termux/shared/termux/terminal/io/TerminalExtraKeys.java` | 54 | onTerminalExtraKeyButtonClick(view) | 普通函数参数 | 函数 onTerminalExtraKeyButtonClick 的参数 view 在函数体内未被使用 |

### 6. 重复代码（488 条）

归一化后完全相同的代码块（>=8 个有效行），跨文件重复通常应抽取公共组件/工具函数。

| 文件路径 | 行号 | 名称 | 类型 | 说明 |
|---|---:|---|---|---|
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 1509 | 9行 x 12处 | 跨文件重复块 | 重复代码块 9 行 × 12 处：app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt:1509-1517; app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt:2501-2509; app/src/main/java/com/termux/app/activities/QemuVmActivity.kt:173-181; app/src/main/java/com/termux/app/activities/StorageActivity.kt:439-447; app/src/main/java/com/termux/app/activities/StorageActivity.kt:821-829; app/src/main/java/com/termux/app/compose/AboutScreen.kt:887-895; app/src/main/java/com/termux/app/compose/LogViewerScreen.kt:192-200; app/src/main/java/com/termux/app/compose/TermuxCrashReportScreen.kt:57-65; app/src/main/java/com/termux/app/compose/TermuxStylingScreen.kt:197-205; app/src/main/java/com/termux/app/compose/TermuxTaskerScreen.kt:68-76; app/src/main/java/com/termux/app/compose/TermuxWidgetScreen.kt:47-55; app/src/main/java/com/termux/app/plugin/PluginWebViewActivity.kt:153-161 |
| `app/src/main/java/com/gaurav/avnc/ui/prefs/PrefsActivity.kt` | 25 | 9行 x 10处 | 跨文件重复块 | 重复代码块 9 行 × 10 处：app/src/main/java/com/gaurav/avnc/ui/prefs/PrefsActivity.kt:25-33; app/src/main/java/com/termux/app/activities/FeatureCenterActivity.kt:19-27; app/src/main/java/com/termux/app/activities/LogViewerActivity.kt:19-27; app/src/main/java/com/termux/app/activities/NotificationManagerActivity.kt:19-27; app/src/main/java/com/termux/app/activities/PackageManagerActivity.kt:19-27; app/src/main/java/com/termux/app/activities/ProcessListActivity.kt:19-27; app/src/main/java/com/termux/app/activities/TermuxCrashReportActivity.kt:19-27; app/src/main/java/com/termux/app/activities/TermuxSettingsActivity.kt:19-27; app/src/main/java/com/termux/app/activities/TermuxStylingActivity.kt:19-27; app/src/main/java/com/termux/app/activities/TermuxWidgetActivity.kt:19-27 |
| `app/src/main/java/com/gaurav/avnc/ui/prefs/PrefsActivity.kt` | 22 | 8行 x 10处 | 跨文件重复块 | 重复代码块 8 行 × 10 处：app/src/main/java/com/gaurav/avnc/ui/prefs/PrefsActivity.kt:22-29; app/src/main/java/com/termux/app/activities/FeatureCenterActivity.kt:16-23; app/src/main/java/com/termux/app/activities/LogViewerActivity.kt:16-23; app/src/main/java/com/termux/app/activities/NotificationManagerActivity.kt:16-23; app/src/main/java/com/termux/app/activities/PackageManagerActivity.kt:16-23; app/src/main/java/com/termux/app/activities/ProcessListActivity.kt:16-23; app/src/main/java/com/termux/app/activities/TermuxCrashReportActivity.kt:16-23; app/src/main/java/com/termux/app/activities/TermuxSettingsActivity.kt:16-23; app/src/main/java/com/termux/app/activities/TermuxStylingActivity.kt:16-23; app/src/main/java/com/termux/app/activities/TermuxWidgetActivity.kt:16-23 |
| `app/src/main/java/com/termux/app/fragments/settings/termux/DebuggingPreferencesFragment.java` | 81 | 14行 x 5处 | 跨文件重复块 | 重复代码块 14 行 × 5 处：app/src/main/java/com/termux/app/fragments/settings/termux/DebuggingPreferencesFragment.java:81-94; app/src/main/java/com/termux/app/fragments/settings/termux_api/DebuggingPreferencesFragment.java:64-77; app/src/main/java/com/termux/app/fragments/settings/termux_float/DebuggingPreferencesFragment.java:64-77; app/src/main/java/com/termux/app/fragments/settings/termux_tasker/DebuggingPreferencesFragment.java:64-77; app/src/main/java/com/termux/app/fragments/settings/termux_widget/DebuggingPreferencesFragment.java:64-77 |
| `app/src/main/java/com/termux/app/fragments/settings/termux/DebuggingPreferencesFragment.java` | 97 | 12行 x 5处 | 跨文件重复块 | 重复代码块 12 行 × 5 处：app/src/main/java/com/termux/app/fragments/settings/termux/DebuggingPreferencesFragment.java:97-108; app/src/main/java/com/termux/app/fragments/settings/termux_api/DebuggingPreferencesFragment.java:80-91; app/src/main/java/com/termux/app/fragments/settings/termux_float/DebuggingPreferencesFragment.java:80-91; app/src/main/java/com/termux/app/fragments/settings/termux_tasker/DebuggingPreferencesFragment.java:80-91; app/src/main/java/com/termux/app/fragments/settings/termux_widget/DebuggingPreferencesFragment.java:80-91 |
| `app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt` | 711 | 14行 x 4处 | 跨文件重复块 | 重复代码块 14 行 × 4 处：app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt:711-724; app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt:805-818; app/src/main/java/com/termux/app/compose/TerminalDetailScreenCompose.kt:558-571; app/src/main/java/com/termux/app/compose/TerminalDetailScreenCompose.kt:647-660 |
| `app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt` | 755 | 14行 x 4处 | 跨文件重复块 | 重复代码块 14 行 × 4 处：app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt:755-768; app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt:849-865; app/src/main/java/com/termux/app/compose/TerminalDetailScreenCompose.kt:597-610; app/src/main/java/com/termux/app/compose/TerminalDetailScreenCompose.kt:686-699 |
| `app/src/main/java/com/termux/app/compose/TermuxSettingsScreen.kt` | 110 | 14行 x 4处 | 跨文件重复块 | 重复代码块 14 行 × 4 处：app/src/main/java/com/termux/app/compose/TermuxSettingsScreen.kt:110-123; app/src/main/java/com/termux/app/compose/TermuxStylingScreen.kt:202-215; app/src/main/java/com/termux/app/compose/TermuxWidgetScreen.kt:52-65; app/src/main/java/com/termux/app/compose/VncSettingsScreen.kt:87-100 |
| `termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java` | 57 | 14行 x 4处 | 跨文件重复块 | 重复代码块 14 行 × 4 处：termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java:57-70; termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalViewClientBase.java:94-107; termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalSessionClientBase.java:61-74; termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalViewClientBase.java:94-107 |
| `termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java` | 62 | 14行 x 4处 | 跨文件重复块 | 重复代码块 14 行 × 4 处：termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java:62-75; termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalViewClientBase.java:99-112; termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalSessionClientBase.java:66-79; termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalViewClientBase.java:99-112 |
| `termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java` | 67 | 14行 x 4处 | 跨文件重复块 | 重复代码块 14 行 × 4 处：termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java:67-80; termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalViewClientBase.java:104-117; termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalSessionClientBase.java:71-84; termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalViewClientBase.java:104-117 |
| `termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java` | 72 | 14行 x 4处 | 跨文件重复块 | 重复代码块 14 行 × 4 处：termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java:72-85; termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalViewClientBase.java:109-122; termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalSessionClientBase.java:76-89; termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalViewClientBase.java:109-122 |
| `app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt` | 688 | 13行 x 4处 | 跨文件重复块 | 重复代码块 13 行 × 4 处：app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt:688-700; app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt:782-794; app/src/main/java/com/termux/app/compose/TerminalDetailScreenCompose.kt:535-547; app/src/main/java/com/termux/app/compose/TerminalDetailScreenCompose.kt:624-636 |
| `app/src/main/java/com/termux/app/utils/PluginUtils.java` | 73 | 13行 x 4处 | 跨文件重复块 | 重复代码块 13 行 × 4 处：app/src/main/java/com/termux/app/utils/PluginUtils.java:73-85; app/src/main/java/com/termux/app/utils/PluginUtils.java:144-156; termux-shared/src/main/java/com/termux/shared/termux/plugins/TermuxPluginUtils.java:75-87; termux-shared/src/main/java/com/termux/shared/termux/plugins/TermuxPluginUtils.java:175-187 |
| `app/src/main/java/com/termux/app/compose/QuickCommandSheet.kt` | 91 | 25行 x 2处 | 同文件重复块 | 重复代码块 25 行 × 2 处：app/src/main/java/com/termux/app/compose/QuickCommandSheet.kt:91-115; app/src/main/java/com/termux/app/compose/QuickCommandSheet.kt:151-172 |
| `app/src/main/java/com/termux/app/fragments/settings/termux/DebuggingPreferencesFragment.java` | 19 | 10行 x 5处 | 跨文件重复块 | 重复代码块 10 行 × 5 处：app/src/main/java/com/termux/app/fragments/settings/termux/DebuggingPreferencesFragment.java:19-28; app/src/main/java/com/termux/app/fragments/settings/termux_api/DebuggingPreferencesFragment.java:18-27; app/src/main/java/com/termux/app/fragments/settings/termux_float/DebuggingPreferencesFragment.java:18-27; app/src/main/java/com/termux/app/fragments/settings/termux_tasker/DebuggingPreferencesFragment.java:18-27; app/src/main/java/com/termux/app/fragments/settings/termux_widget/DebuggingPreferencesFragment.java:18-27 |
| `app/src/main/java/com/termux/app/ssh/SshScreen.kt` | 418 | 10行 x 5处 | 同文件重复块 | 重复代码块 10 行 × 5 处：app/src/main/java/com/termux/app/ssh/SshScreen.kt:418-427; app/src/main/java/com/termux/app/ssh/SshScreen.kt:468-477; app/src/main/java/com/termux/app/ssh/SshScreen.kt:501-510; app/src/main/java/com/termux/app/ssh/SshScreen.kt:535-544; app/src/main/java/com/termux/app/ssh/SshScreen.kt:577-586 |
| `app/src/main/java/com/termux/app/fragments/settings/termux_api/DebuggingPreferencesFragment.java` | 74 | 12行 x 4处 | 跨文件重复块 | 重复代码块 12 行 × 4 处：app/src/main/java/com/termux/app/fragments/settings/termux_api/DebuggingPreferencesFragment.java:74-85; app/src/main/java/com/termux/app/fragments/settings/termux_float/DebuggingPreferencesFragment.java:74-85; app/src/main/java/com/termux/app/fragments/settings/termux_tasker/DebuggingPreferencesFragment.java:74-85; app/src/main/java/com/termux/app/fragments/settings/termux_widget/DebuggingPreferencesFragment.java:74-85 |
| `app/src/main/java/com/termux/app/compose/FileManagerScreen.kt` | 767 | 12行 x 4处 | 同文件重复块 | 重复代码块 12 行 × 4 处：app/src/main/java/com/termux/app/compose/FileManagerScreen.kt:767-778; app/src/main/java/com/termux/app/compose/FileManagerScreen.kt:787-797; app/src/main/java/com/termux/app/compose/FileManagerScreen.kt:811-821; app/src/main/java/com/termux/app/compose/FileManagerScreen.kt:830-840 |
| `app/src/main/java/com/termux/app/compose/OobeScreen.kt` | 731 | 12行 x 4处 | 同文件重复块 | 重复代码块 12 行 × 4 处：app/src/main/java/com/termux/app/compose/OobeScreen.kt:731-742; app/src/main/java/com/termux/app/compose/OobeScreen.kt:1034-1045; app/src/main/java/com/termux/app/compose/OobeScreen.kt:1264-1275; app/src/main/java/com/termux/app/compose/OobeScreen.kt:1459-1470 |

> 表格仅列出前 20 条，完整清单见同目录 `REDUNDANT_CODE_LIST.csv`。


### 7. 空函数体（16 条）

函数体为空。多为框架回调的空实现（受接口约束），删除前需确认。

| 文件路径 | 行号 | 名称 | 类型 | 说明 |
|---|---:|---|---|---|
| `app/src/main/java/com/gaurav/avnc/ui/prefs/VirtualKeysEditor.kt` | 70 | startTransition | empty-body | startTransition 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/gaurav/avnc/ui/vnc/VncActivity.kt` | 296 | onProfileUpdated | empty-body | onProfileUpdated 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/gaurav/avnc/ui/vnc/input/PointerModes.kt` | 34 | onGestureStart | empty-body | onGestureStart 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/gaurav/avnc/ui/vnc/input/TouchHandler.kt` | 235 | onScaleEnd | empty-body | onScaleEnd 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/termux/app/TermuxApplication.java` | 63 | onActivityCreated | empty-body | onActivityCreated 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/termux/app/TermuxApplication.java` | 64 | onActivityStarted | empty-body | onActivityStarted 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/termux/app/TermuxApplication.java` | 74 | onActivityPaused | empty-body | onActivityPaused 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/termux/app/TermuxApplication.java` | 75 | onActivityStopped | empty-body | onActivityStopped 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/termux/app/TermuxApplication.java` | 76 | onActivitySaveInstanceState | empty-body | onActivitySaveInstanceState 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/termux/app/TermuxApplication.java` | 77 | onActivityDestroyed | empty-body | onActivityDestroyed 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/termux/app/activities/AlertDialogActivity.kt` | 463 | onAuthenticationFailed | empty-body | onAuthenticationFailed 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/termux/app/compose/AuthorizationOverlay.kt` | 219 | onAuthenticationFailed | empty-body | onAuthenticationFailed 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/termux/app/plugin/PluginManager.kt` | 333 | onServiceDisconnected | empty-body | onServiceDisconnected 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `app/src/main/java/com/termux/app/utils/SnackbarHelper.kt` | 86 | onViewDetachedFromWindow | empty-body | onViewDetachedFromWindow 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `termux-shared/src/main/java/com/termux/shared/activities/TextIOActivity.java` | 174 | beforeTextChanged | empty-body | beforeTextChanged 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |
| `termux-shared/src/main/java/com/termux/shared/activities/TextIOActivity.java` | 176 | onTextChanged | empty-body | onTextChanged 函数体为空（多为框架回调的空实现，删除前需确认接口约束） |

### 8. 注释掉的代码（7 条）

连续 >=5 行被注释的实际代码，应进入版本历史而非留在源码里。

| 文件路径 | 行号 | 名称 | 类型 | 说明 |
|---|---:|---|---|---|
| `app/src/main/java/com/termux/app/terminal/TermuxSessionsListViewController.java` | 61 | 5行 | commented-code | 61-65 共 5 行被注释的代码：// if (isUsingBlackUI) { |
| `terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java` | 944 | 9行 | commented-code | 944-952 共 9 行被注释的代码：// kd=down-arrow key |
| `terminal-emulator/src/test/java/com/termux/terminal/KeyHandlerTest.java` | 58 | 6行 | commented-code | 58-63 共 6 行被注释的代码：// Termcap names (with xterm response in parenthesis): |
| `terminal-emulator/src/test/java/com/termux/terminal/TerminalRowTest.java` | 385 | 7行 | commented-code | 385-391 共 7 行被注释的代码：// assertLineStartsWith(points); |
| `terminal-emulator/src/test/java/com/termux/terminal/TerminalRowTest.java` | 393 | 12行 | commented-code | 393-404 共 12 行被注释的代码：// char[] chars = line.mText; |
| `termux-shared/src/main/java/com/termux/shared/shell/ArgumentTokenizer.java` | 119 | 7行 | commented-code | 119-125 共 7 行被注释的代码：//            if (Character.isWhitespace(c)) { |
| `termux-shared/src/main/java/com/termux/shared/shell/ArgumentTokenizer.java` | 127 | 12行 | commented-code | 127-138 共 12 行被注释的代码：//              escaped = true; |

### 9. 恒假/恒真死条件（3 条）

`if (false)` / `|| true` 之类恒定条件，导致分支或整段代码永不/总是执行。

| 文件路径 | 行号 | 名称 | 类型 | 说明 |
|---|---:|---|---|---|
| `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt` | 1937 | \|\| true | \|\| true | 恒定条件 \|\| true：if (ollamaInstalled.value \|\| true) {  // Always show models list |
| `app/src/main/java/com/termux/app/compose/OverviewScreen.kt` | 1029 | \|\| true | \|\| true | 恒定条件 \|\| true：(ApiCompat.isLowAndroid && (ApiCompat.hasAnyForceEnabled(context) \|\| true)) |
| `app/src/main/java/com/termux/app/compose/SettingsScreen.kt` | 2361 | if(false) | if(false) | 恒定条件 if(false)：if (false) { |

### 10. 未使用资源（697 条）

res/ 下无任何 R.xx 或 @xx 引用的资源。项目存在 getIdentifier 动态查找，删除前需二次确认。

| 文件路径 | 行号 | 名称 | 类型 | 说明 |
|---|---:|---|---|---|
| `app/src/main/res/values/arrays.xml` | - | dummy_array | res/array | app 模块 array 资源 dummy_array 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/values/arrays.xml` | - | profile_editor_gesture_style_descriptions | res/array | app 模块 array 资源 profile_editor_gesture_style_descriptions 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/values/arrays.xml` | - | profile_editor_gesture_style_labels | res/array | app 模块 array 资源 profile_editor_gesture_style_labels 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/values/arrays.xml` | - | profile_editor_gesture_style_values | res/array | app 模块 array 资源 profile_editor_gesture_style_values 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/values/arrays.xml` | - | profile_editor_security_labels | res/array | app 模块 array 资源 profile_editor_security_labels 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/values/arrays.xml` | - | theme_entries | res/array | app 模块 array 资源 theme_entries 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/values/arrays.xml` | - | theme_values | res/array | app 模块 array 资源 theme_values 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/values/bool.xml` | - | leak_canary_watcher_auto_install | res/bool | app 模块 bool 资源 leak_canary_watcher_auto_install 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/values/colors.xml` | - | background | res/color | app 模块 color 资源 background 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/values/colors.xml` | - | green | res/color | app 模块 color 资源 green 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/values/colors.xml` | - | primary_dark | res/color | app 模块 color 资源 primary_dark 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/values-night/dimens.xml` | - | editor_bottom_bar_elevation | res/dimen | app 模块 dimen 资源 editor_bottom_bar_elevation 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/values/dimens.xml` | - | urlbar_height | res/dimen | app 模块 dimen 资源 urlbar_height 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/bg_selectable.xml` | - | bg_selectable | res/drawable | app 模块 drawable 资源 bg_selectable 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_auto_connect.xml` | - | ic_auto_connect | res/drawable | app 模块 drawable 资源 ic_auto_connect 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_bookmark.xml` | - | ic_bookmark | res/drawable | app 模块 drawable 资源 ic_bookmark 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_broadcast.xml` | - | ic_broadcast | res/drawable | app 模块 drawable 资源 ic_broadcast 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_check.xml` | - | ic_check | res/drawable | app 模块 drawable 资源 ic_check 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_computer_shortcut.xml` | - | ic_computer_shortcut | res/drawable | app 模块 drawable 资源 ic_computer_shortcut 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_detection.xml` | - | ic_detection | res/drawable | app 模块 drawable 资源 ic_detection 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_experimental.xml` | - | ic_experimental | res/drawable | app 模块 drawable 资源 ic_experimental 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_game.xml` | - | ic_game | res/drawable | app 模块 drawable 资源 ic_game 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_github.xml` | - | ic_github | res/drawable | app 模块 drawable 资源 ic_github 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_gpl.xml` | - | ic_gpl | res/drawable | app 模块 drawable 资源 ic_gpl 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_key.xml` | - | ic_key | res/drawable | app 模块 drawable 资源 ic_key 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | - | ic_launcher_foreground | res/drawable | app 模块 drawable 资源 ic_launcher_foreground 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | - | ic_launcher_monochrome | res/drawable | app 模块 drawable 资源 ic_launcher_monochrome 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_menu.xml` | - | ic_menu | res/drawable | app 模块 drawable 资源 ic_menu 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_password.xml` | - | ic_password | res/drawable | app 模块 drawable 资源 ic_password 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |
| `app/src/main/res/drawable/ic_person.xml` | - | ic_person | res/drawable | app 模块 drawable 资源 ic_person 无任何 R./@ 引用（项目存在 getIdentifier 动态查找，删除前需二次确认） |

> 表格仅列出前 30 条，完整清单见同目录 `REDUNDANT_CODE_LIST.csv`。


### 11. 未使用公开声明（需人工确认）（606 条）

public/open 的声明在首方代码中无引用。termux-shared 为库模块，可能被其它 Termux 插件仓库依赖，属低置信度，不可直接删除。

| 文件路径 | 行号 | 名称 | 类型 | 说明 |
|---|---:|---|---|---|
| `app/src/main/java/com/termux/app/TermuxActivity.java` | 150 | mLastToast | 未使用字段 | java public field mLastToast 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `app/src/main/java/com/termux/app/terminal/TermuxSessionsListViewController.java` | 59 | isUsingBlackUI | 未使用字段 | java public field isUsingBlackUI 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/android/PermissionUtils.java` | 39 | REQUEST_DISABLE_BATTERY_OPTIMIZATIONS | 未使用字段 | java public field REQUEST_DISABLE_BATTERY_OPTIMIZATIONS 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/android/PermissionUtils.java` | 40 | REQUEST_GRANT_DISPLAY_OVER_OTHER_APPS_PERMISSION | 未使用字段 | java public field REQUEST_GRANT_DISPLAY_OVER_OTHER_APPS_PERMISSION 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/errors/Errno.java` | 24 | ERRNO_MINOR_FAILURES | 未使用字段 | java public field ERRNO_MINOR_FAILURES 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/errors/FunctionErrno.java` | 12 | ERRNO_UNSET_PARAMETER | 未使用字段 | java public field ERRNO_UNSET_PARAMETER 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/file/FileUtilsErrno.java` | 15 | ERRNO_EXECUTABLE_REQUIRED | 未使用字段 | java public field ERRNO_EXECUTABLE_REQUIRED 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/file/FileUtilsErrno.java` | 16 | ERRNO_NULL_OR_EMPTY_REGULAR_FILE_PATH | 未使用字段 | java public field ERRNO_NULL_OR_EMPTY_REGULAR_FILE_PATH 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/file/FileUtilsErrno.java` | 17 | ERRNO_NULL_OR_EMPTY_REGULAR_FILE | 未使用字段 | java public field ERRNO_NULL_OR_EMPTY_REGULAR_FILE 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/file/FileUtilsErrno.java` | 18 | ERRNO_NULL_OR_EMPTY_EXECUTABLE_FILE_PATH | 未使用字段 | java public field ERRNO_NULL_OR_EMPTY_EXECUTABLE_FILE_PATH 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/file/FileUtilsErrno.java` | 19 | ERRNO_NULL_OR_EMPTY_EXECUTABLE_FILE | 未使用字段 | java public field ERRNO_NULL_OR_EMPTY_EXECUTABLE_FILE 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/file/FileUtilsErrno.java` | 20 | ERRNO_NULL_OR_EMPTY_DIRECTORY_FILE_PATH | 未使用字段 | java public field ERRNO_NULL_OR_EMPTY_DIRECTORY_FILE_PATH 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/file/FileUtilsErrno.java` | 21 | ERRNO_NULL_OR_EMPTY_DIRECTORY_FILE | 未使用字段 | java public field ERRNO_NULL_OR_EMPTY_DIRECTORY_FILE 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/file/FileUtilsErrno.java` | 63 | ERRNO_DELETING_FILE_FAILED | 未使用字段 | java public field ERRNO_DELETING_FILE_FAILED 全仓无引用（库模块/插件可能外部调用，谨慎） |
| `termux-shared/src/main/java/com/termux/shared/file/FileUtilsErrno.java` | 76 | ERRNO_GET_CHARSET_FOR_NAME_FAILED | 未使用字段 | java public field ERRNO_GET_CHARSET_FOR_NAME_FAILED 全仓无引用（库模块/插件可能外部调用，谨慎） |

> 表格仅列出前 15 条，完整清单见同目录 `REDUNDANT_CODE_LIST.csv`。


### 附：重复代码块位置明细（TOP 20 簇）

**#1　9 行 × 12 处**　`app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt:1509-1517`, `app/src/main/java/com/termux/app/activities/AiTermuxActivity.kt:2501-2509`, `app/src/main/java/com/termux/app/activities/QemuVmActivity.kt:173-181`, `app/src/main/java/com/termux/app/activities/StorageActivity.kt:439-447`, `app/src/main/java/com/termux/app/activities/StorageActivity.kt:821-829`, `app/src/main/java/com/termux/app/compose/AboutScreen.kt:887-895`, `app/src/main/java/com/termux/app/compose/LogViewerScreen.kt:192-200`, `app/src/main/java/com/termux/app/compose/TermuxCrashReportScreen.kt:57-65`, `app/src/main/java/com/termux/app/compose/TermuxStylingScreen.kt:197-205`, `app/src/main/java/com/termux/app/compose/TermuxTaskerScreen.kt:68-76`, `app/src/main/java/com/termux/app/compose/TermuxWidgetScreen.kt:47-55`, `app/src/main/java/com/termux/app/plugin/PluginWebViewActivity.kt:153-161`　（首行：`Box(`）

**#2　9 行 × 10 处**　`app/src/main/java/com/gaurav/avnc/ui/prefs/PrefsActivity.kt:25-33`, `app/src/main/java/com/termux/app/activities/FeatureCenterActivity.kt:19-27`, `app/src/main/java/com/termux/app/activities/LogViewerActivity.kt:19-27`, `app/src/main/java/com/termux/app/activities/NotificationManagerActivity.kt:19-27`, `app/src/main/java/com/termux/app/activities/PackageManagerActivity.kt:19-27`, `app/src/main/java/com/termux/app/activities/ProcessListActivity.kt:19-27`, `app/src/main/java/com/termux/app/activities/TermuxCrashReportActivity.kt:19-27`, `app/src/main/java/com/termux/app/activities/TermuxSettingsActivity.kt:19-27`, `app/src/main/java/com/termux/app/activities/TermuxStylingActivity.kt:19-27`, `app/src/main/java/com/termux/app/activities/TermuxWidgetActivity.kt:19-27`　（首行：`WindowCompat.setDecorFitsSystemWindows(window, false)`）

**#3　8 行 × 10 处**　`app/src/main/java/com/gaurav/avnc/ui/prefs/PrefsActivity.kt:22-29`, `app/src/main/java/com/termux/app/activities/FeatureCenterActivity.kt:16-23`, `app/src/main/java/com/termux/app/activities/LogViewerActivity.kt:16-23`, `app/src/main/java/com/termux/app/activities/NotificationManagerActivity.kt:16-23`, `app/src/main/java/com/termux/app/activities/PackageManagerActivity.kt:16-23`, `app/src/main/java/com/termux/app/activities/ProcessListActivity.kt:16-23`, `app/src/main/java/com/termux/app/activities/TermuxCrashReportActivity.kt:16-23`, `app/src/main/java/com/termux/app/activities/TermuxSettingsActivity.kt:16-23`, `app/src/main/java/com/termux/app/activities/TermuxStylingActivity.kt:16-23`, `app/src/main/java/com/termux/app/activities/TermuxWidgetActivity.kt:16-23`　（首行：`override fun onCreate(savedInstanceState: Bundle?) {`）

**#4　14 行 × 5 处**　`app/src/main/java/com/termux/app/fragments/settings/termux/DebuggingPreferencesFragment.java:81-94`, `app/src/main/java/com/termux/app/fragments/settings/termux_api/DebuggingPreferencesFragment.java:64-77`, `app/src/main/java/com/termux/app/fragments/settings/termux_float/DebuggingPreferencesFragment.java:64-77`, `app/src/main/java/com/termux/app/fragments/settings/termux_tasker/DebuggingPreferencesFragment.java:64-77`, `app/src/main/java/com/termux/app/fragments/settings/termux_widget/DebuggingPreferencesFragment.java:64-77`　（首行：`mInstance = new DebuggingPreferencesDataStore(context);`）

**#5　12 行 × 5 处**　`app/src/main/java/com/termux/app/fragments/settings/termux/DebuggingPreferencesFragment.java:97-108`, `app/src/main/java/com/termux/app/fragments/settings/termux_api/DebuggingPreferencesFragment.java:80-91`, `app/src/main/java/com/termux/app/fragments/settings/termux_float/DebuggingPreferencesFragment.java:80-91`, `app/src/main/java/com/termux/app/fragments/settings/termux_tasker/DebuggingPreferencesFragment.java:80-91`, `app/src/main/java/com/termux/app/fragments/settings/termux_widget/DebuggingPreferencesFragment.java:80-91`　（首行：`default:`）

**#6　14 行 × 4 处**　`app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt:711-724`, `app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt:805-818`, `app/src/main/java/com/termux/app/compose/TerminalDetailScreenCompose.kt:558-571`, `app/src/main/java/com/termux/app/compose/TerminalDetailScreenCompose.kt:647-660`　（首行：`showQuickCommandSheet = true`）

**#7　14 行 × 4 处**　`app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt:755-768`, `app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt:849-865`, `app/src/main/java/com/termux/app/compose/TerminalDetailScreenCompose.kt:597-610`, `app/src/main/java/com/termux/app/compose/TerminalDetailScreenCompose.kt:686-699`　（首行：`else effectiveTopBarContentColor`）

**#8　14 行 × 4 处**　`app/src/main/java/com/termux/app/compose/TermuxSettingsScreen.kt:110-123`, `app/src/main/java/com/termux/app/compose/TermuxStylingScreen.kt:202-215`, `app/src/main/java/com/termux/app/compose/TermuxWidgetScreen.kt:52-65`, `app/src/main/java/com/termux/app/compose/VncSettingsScreen.kt:87-100`　（首行：`contentAlignment = Alignment.Center`）

**#9　14 行 × 4 处**　`termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java:57-70`, `termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalViewClientBase.java:94-107`, `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalSessionClientBase.java:61-74`, `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalViewClientBase.java:94-107`　（首行：`Logger.logError(tag, message);`）

**#10　14 行 × 4 处**　`termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java:62-75`, `termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalViewClientBase.java:99-112`, `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalSessionClientBase.java:66-79`, `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalViewClientBase.java:99-112`　（首行：`Logger.logWarn(tag, message);`）

**#11　14 行 × 4 处**　`termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java:67-80`, `termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalViewClientBase.java:104-117`, `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalSessionClientBase.java:71-84`, `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalViewClientBase.java:104-117`　（首行：`Logger.logInfo(tag, message);`）

**#12　14 行 × 4 处**　`termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java:72-85`, `termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalViewClientBase.java:109-122`, `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalSessionClientBase.java:76-89`, `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalViewClientBase.java:109-122`　（首行：`Logger.logDebug(tag, message);`）

**#13　13 行 × 4 处**　`app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt:688-700`, `app/src/main/java/com/termux/app/compose/TerminalDetailScreen.kt:782-794`, `app/src/main/java/com/termux/app/compose/TerminalDetailScreenCompose.kt:535-547`, `app/src/main/java/com/termux/app/compose/TerminalDetailScreenCompose.kt:624-636`　（首行：`showRenameDialog = true`）

**#14　13 行 × 4 处**　`app/src/main/java/com/termux/app/utils/PluginUtils.java:73-85`, `app/src/main/java/com/termux/app/utils/PluginUtils.java:144-156`, `termux-shared/src/main/java/com/termux/shared/termux/plugins/TermuxPluginUtils.java:75-87`, `termux-shared/src/main/java/com/termux/shared/termux/plugins/TermuxPluginUtils.java:175-187`　（首行：`!isPluginExecutionCommandWithPendingResult, isExecutionCommandLoggingE`）

**#15　25 行 × 2 处**　`app/src/main/java/com/termux/app/compose/QuickCommandSheet.kt:91-115`, `app/src/main/java/com/termux/app/compose/QuickCommandSheet.kt:151-172`　（首行：`onConfirm = { label, command, auto ->`）

**#16　10 行 × 5 处**　`app/src/main/java/com/termux/app/fragments/settings/termux/DebuggingPreferencesFragment.java:19-28`, `app/src/main/java/com/termux/app/fragments/settings/termux_api/DebuggingPreferencesFragment.java:18-27`, `app/src/main/java/com/termux/app/fragments/settings/termux_float/DebuggingPreferencesFragment.java:18-27`, `app/src/main/java/com/termux/app/fragments/settings/termux_tasker/DebuggingPreferencesFragment.java:18-27`, `app/src/main/java/com/termux/app/fragments/settings/termux_widget/DebuggingPreferencesFragment.java:18-27`　（首行：`@Keep`）

**#17　10 行 × 5 处**　`app/src/main/java/com/termux/app/ssh/SshScreen.kt:418-427`, `app/src/main/java/com/termux/app/ssh/SshScreen.kt:468-477`, `app/src/main/java/com/termux/app/ssh/SshScreen.kt:501-510`, `app/src/main/java/com/termux/app/ssh/SshScreen.kt:535-544`, `app/src/main/java/com/termux/app/ssh/SshScreen.kt:577-586`　（首行：`modifier = Modifier.fillMaxWidth(),`）

**#18　12 行 × 4 处**　`app/src/main/java/com/termux/app/fragments/settings/termux_api/DebuggingPreferencesFragment.java:74-85`, `app/src/main/java/com/termux/app/fragments/settings/termux_float/DebuggingPreferencesFragment.java:74-85`, `app/src/main/java/com/termux/app/fragments/settings/termux_tasker/DebuggingPreferencesFragment.java:74-85`, `app/src/main/java/com/termux/app/fragments/settings/termux_widget/DebuggingPreferencesFragment.java:74-85`　（首行：`if (mPreferences == null) return null;`）

**#19　12 行 × 4 处**　`app/src/main/java/com/termux/app/compose/FileManagerScreen.kt:767-778`, `app/src/main/java/com/termux/app/compose/FileManagerScreen.kt:787-797`, `app/src/main/java/com/termux/app/compose/FileManagerScreen.kt:811-821`, `app/src/main/java/com/termux/app/compose/FileManagerScreen.kt:830-840`　（首行：`color = dialogTextColor,`）

**#20　12 行 × 4 处**　`app/src/main/java/com/termux/app/compose/OobeScreen.kt:731-742`, `app/src/main/java/com/termux/app/compose/OobeScreen.kt:1034-1045`, `app/src/main/java/com/termux/app/compose/OobeScreen.kt:1264-1275`, `app/src/main/java/com/termux/app/compose/OobeScreen.kt:1459-1470`　（首行：`.clip(androidx.compose.foundation.shape.CircleShape)`）


### 附：高度相似文件对

| 文件 A | 文件 B | 共享块数 | 相似度 |
|---|---|---:|---:|
| `termux-shared/src/main/java/com/termux/shared/interact/TextInputDialogUtils.java` | `termux-shared/src/main/java/com/termux/shared/termux/interact/TextInputDialogUtils.java` | 30 | 100% |
| `termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalViewClientBase.java` | `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalViewClientBase.java` | 54 | 100% |
| `termux-shared/src/main/java/com/termux/shared/terminal/io/BellHandler.java` | `termux-shared/src/main/java/com/termux/shared/termux/terminal/io/BellHandler.java` | 28 | 100% |
| `termux-shared/src/main/java/com/termux/shared/terminal/TermuxTerminalSessionClientBase.java` | `termux-shared/src/main/java/com/termux/shared/termux/terminal/TermuxTerminalSessionClientBase.java` | 21 | 64% |

以上均为 `com/termux/shared/<x>` 与 `com/termux/shared/termux/<x>` 的新旧包并行实现；经核查**所有调用方均 import 新包**，旧包文件属于事实上的无人调用者，但作为库模块对外兼容层，建议加 `@Deprecated` 而非直接删除。

## 三、方法、验证与已知误报

### 扫描方法
1. 自建静态分析器（Python，位于 `.workbuddy/scan/`）：标识符级引用计数 + 规则匹配。
2. 引用语料包含全部源码、res/ XML、AndroidManifest、gradle/kts、ProGuard 规则，
   避免「只被 XML / 构建脚本引用」被误判。
3. 重复代码采用「归一化行 + 8 行滑动窗口哈希」，再把重叠窗口合并成不重叠区域。
4. 对 Java/Kotlin 分别抽取声明，按可见性（private / internal / public）限定查找范围。

### 误报消除过程（重要）
初筛结果与最终结果差异很大，每一轮收紧都是被回读源码证伪后的修正：

| 阶段 | 条目数 | 触发原因 |
|---|---:|---|
| 未使用导入 初筛 | 962 | 标识符匹配排除了 `.` 前缀，把 Kotlin 扩展函数调用（`view.isVisible`、`stateLock.tryRead`）全部漏掉 |
| 未使用导入 修正后 | 335 | 允许 `.` / `::` 前缀后再计数 |
| 不可达代码 初筛 | 186 | 默认 `return` 后必有死代码，忽略了无花括号的 `if (x) return` 守卫与 `return@label` |
| 不可达代码 第二轮 | 64 | 排除守卫语句与标签 return |
| 不可达代码 第三轮 | 11 | 多层链式调用续行（`.getString(...)`、`+ ...`、`|| ...`）被当成独立语句 |
| 不可达代码 最终 | 4 -> 0 | 剩余 4 条回读源码确认为多行表达式 / 数组初始化续行，**真实不可达代码为 0** |
| 未使用参数 | 167 -> 113 剔除 -> 38 | 113 条是 Android 框架回调（`@Override`）；另修正 `if (...)` 被误认方法、以及在字符串插值里才用到的参数 |
| 未使用局部变量 | 88 -> 24 | 同样由「变量只在 `"$var"` 插值中使用」被误判引起 |
| 整文件死代码 | 58 -> 14 | 两处 bug：① 标识符正则把 `A.B.c` 当成一个 token；② 清洗步骤把 XML 属性值当字符串抹掉，导致 Manifest 中声明的组件查不到 |

**执行清理时又抓出三处（静态扫描阶段看不到，只有真删 + 编译才暴露）**：

| 现象 | 本质 |
|---|---|
| 删掉 `import androidx.compose.runtime.getValue/setValue` 后编译不过（全仓 22 处） | Kotlin 委托属性 `var x by remember { ... }` 隐式调用 `getValue`/`setValue`，源码里**不出现标识符**，引用计数必然为 0 |
| 删掉 `TerminalBlinker` 的 `private val blinkerName` 后编译不过 | 这是**构造器形参**，别的文件用命名实参 `blinkerName = ...` 调用；私有成员的「文件内搜索」规则对它不成立 |
| 差点删掉 `VncClient` 里 12 个 `cb*` 函数 | 它们带 `@Keep`，由 **JNI 从 native 回调**；删了编译照样过，但运行时 VNC 功能直接失效 |
| 差点删掉 `BindingAdapters.java` | 带 `@BindingAdapter`，是 **DataBinding 框架入口**，无需任何显式引用 |

结论：**「无静态引用」≠「无人调用」**。凡是带框架/native 入口注解（`@Keep`、
`@Composable`、`@BindingAdapter`、`@JavascriptInterface`、`@Subscribe`…）的声明，
一律不得纳入自动删除。

### 已知局限
- **静态分析的固有边界**：反射、`Class.forName`、JNI、序列化字段、DataBinding、
  Compose runtime 调用均可能导致「实际有用但查不到引用」，因此 public 声明统一降为低置信度。
- 「孤儿文件」结论同样受上一条影响（`BindingAdapters.java` 就是反例），
  因此本轮清理**没有删除任何文件**，该类别应逐个人工确认。
- 项目中存在 `getIdentifier(...)` 动态资源查找，资源类结论删除前须二次确认。
- `termux-shared` 是会被 Termux 系列插件（Tasker / Widget / Styling 等）依赖的库模块，
  删除其中的公开 API 会破坏跨仓库调用方。
- C/C++ 部分（1486 个文件）几乎全部来自 `app/extern/`（wolfssl、libjpeg-turbo、libvncserver）
  与 `.cxx` 构建产物，自有 native 代码仅 `app/src/main/cpp/termux-bootstrap.c` 等 2 个文件，故未纳入扫描。

## 四、处置优先级建议

1. **P0（风险最低、收益直接）**：335 条未使用导入 → IDE Optimize Imports 或 `ktlint --fix` 批量清理。
2. **P1**：50 条未使用私有声明 + 24 个未使用局部变量；**孤儿文件不建议自动删除**
   （其中 `BindingAdapters.java` 是 DataBinding 入口，属反例，须逐个人工确认）。
3. **P2**：38 个未使用参数（优先处理私有函数，如 `VncActivity.handleServerUnlockFailure(msg)`）。
4. **P3**：3 处恒假/恒真死条件（如 `AiTermuxActivity.kt:1937` 的 `ollamaInstalled.value || true`，
   该 `if` 恒真，注释写明 "Always show models list"，属于被临时钉死的控制流，建议明确化处理）。
5. **P4**：488 簇重复代码 —— 建议先处理 `DebuggingPreferencesFragment` ×5、
   10 个 Activity 的 `onCreate` 样板、`LayerManager` 类 UI 样板，抽公共基类/组件。
6. **P5（需人工确认）**：697 个未使用资源（其中 584 个 string）、606 个未使用公开声明、
   `XKeySym.kt` 中 1994 个未引用常量、4 对相似文件中的旧包实现。
