# Termux-Ultra 代码检查与运行报告

> 生成时间：2026-09-19
> 检查对象：`D:\Projects\Termux-Ultra`（Termux 增强分支，Android App + libterminal 等 6 个模块）
> 检查方式：静态代码审查 + 关键功能执行通路追踪 + 定向修复

---

## 一、验证手段与环境说明

| 项目 | 状态 |
|------|------|
| 静态代码审查 | ✅ 已完成（app 模块 11770 行 + libterminal 关键类逐文件/逐行核查） |
| 关键功能执行通路追踪 | ✅ 已完成（快捷指令、终端核心切换、终端设置持久化、风险确认、会话管理等） |
| 编译验证（`gradle compile/assemble`） | ⚠️ 未能完成 |
| 实机覆盖安装验证（`adb`） | ⚠️ 未能完成 |

**未能编译/实机验证的原因（透明说明）：**
本沙箱环境无法访问外网下载 Gradle 构建依赖。执行 `./gradlew :app:assembleDebug` 时在下载 `gradle-9.3.1-all.zip` 分发版阶段即 `Connect timed out`（已确认非本地 JDK/PATH 问题，JDK 25、Android SDK、adb 均就绪，设备 `NNONPRMRKVTWOZO7` 已连接）。完整构建还需拉取 AGP 9.1、Kotlin 2.3、miuix（GitHub Packages）、NDK 28 及 cmake 原生编译，在本环境不可行。因此"构建产物 → `adb install -r`（覆盖安装）"的实机验证链路无法在此执行。**所有修复均经过源码级读校核实，未引入新的编译依赖或 API 误用**（详见第四节一致性核对）。**

---

## 二、测试覆盖范围矩阵

| 功能 / 模块 | 审查文件 | 执行通路验证 | 结论 |
|------|---------|------------|------|
| 快捷指令（经典+新星） | QuickCommandSheet.kt / QuickCommandStore.kt / TerminalTopBar.kt | 添加→持久化→列表→点击执行→写入会话 全链路追踪 | 发现 2 个问题（Q1 高危、Q4 低中），已修复 |
| 终端双核心切换（Java/NDK ↔ Kotlin/Compose） | TerminalRuntimeCore.kt / TermuxActivityBridge.kt | setContent 分支、会话解析回退、插件禁用、killAll 全链路 | 无阻断性 bug |
| Compose 终端设置持久化 | ComposeTerminalSettings.kt | setter→SharedPreferences→StateFlow→重启回读 | 发现 Q2 高危（不落盘），已修复 |
| Compose 会话管理 | ComposeSessionManager.kt | 创建/切换/杀死/全清、id 分配、并发 | 发现 Q3/Q15 中危，已修复 |
| 风险命令确认（VorteX Guard） | RiskConfirmManager.kt | confirm/cancel 各分支、协程挂起、Agent/会话句柄 | 发现 Q5/Q14 中危，已修复；Q6/Q11 为已废弃路径（记录） |
| 设置备份/恢复 | SettingsScreen.kt | 注册接收器→worker→反注册 | 发现 Q7 中危（异常路径泄漏），已修复 |
| AI 活动 | AiTermuxActivity.kt | 注册 BroadcastReceiver | 发现 Q8 中危（永不反注册），已修复 |
| 概览卡片管理 | OverviewScreen.kt | 单例 getInstance 并发 | 发现 Q10 中危（DCL 失效），已修复 |
| 插件会话注册 | PluginManager.kt | ServiceConnection.onServiceConnected 强转 | 发现 Q12 中危，已修复 |
| 安全套接字服务 | SecuritySocketServer.kt | IO 池阻塞确认 | 发现 Q9 中危（设计层，记录建议） |
| Compose 引擎写入 | engine/TerminalSession.kt | write 通道背压 | 发现 Q13 低危（记录建议） |

---

## 三、发现的问题与修复结果

> 严重度：🔴 高危 / 🟠 中危 / 🟡 低危
> 状态：✅ 已修复 / 📋 已记录（建议后续处理，未改代码以避免回归）

### ✅ Q1【高危】快捷指令在 Nova（Kotlin/Compose）模式下完全失效
- **位置**：`app/.../compose/QuickCommandSheet.kt:362` `executeQuickCommand()`
- **原因**：函数仅通过 `activity.currentSession`（Java 侧 `TermuxActivity.getCurrentSession()` → `mTerminalView.getCurrentSession()`）取会话。Nova 模式下 Java 会话列表为空、`mTerminalView` 不被使用，`currentSession` 恒为 `null`，`?: return` 提前返回 → 点击快捷指令**静默无响应**。
- **修复**：新增 Nova 模式分支——`TerminalRuntimeCore.isComposeMode(context)` 为真时改用 `ComposeSessionManager.getInstance(context).currentSession`，并校验 `isRunning` 后 `session.write(text)`。Compose 引擎 `TerminalSession` 已具备 `write(String)` 与 `isRunning`，通路对齐。
- **影响**：恢复新星模式下快捷指令这一核心功能。

### ✅ Q2【高危】Compose 终端设置（字号/主题/光标/滚动/软键盘/工具栏/常亮）修改后不落盘
- **位置**：`libterminal/.../ComposeTerminalSettings.kt:160` `edit()` 内联函数
- **原因**：`block(p.edit())` 创建 `Editor` 并执行写入，但**从未调用 `.apply()`/`.commit()`**。所有 setter 只更新内存 `StateFlow`，`SharedPreferences` 从不写入 → 用户改的设置重启后全部回退（数据丢失）。
- **修复**：改为 `val editor = p.edit(); block(editor); editor.apply()`。
- **影响**：修复 Compose 模式下主题/字号等设置无法持久化的严重数据丢失。

### ✅ Q3【中危】ComposeSessionManager 会话 id 与列表更新存在并发竞争
- **位置**：`libterminal/.../ComposeSessionManager.kt:41,62-71`
- **原因**：`nextId` 为普通 `var`，`_sessions.value = _sessions.value + ...` 是非原子读-改-写。`createSession` 可由 `TermuxService` 的 binder 线程（镜像会话创建）与主线并发调用，会产生重复 id 或丢失会话条目。
- **修复**：`nextId` 改用 `AtomicInteger`；列表更新（创建/杀死/全清）统一用 `synchronized(sessionsLock)` 保护。

### ✅ Q5【中危】RiskConfirmManager.cancel() 缺少 `KEY_PENDING_SESSION_HANDLE` 分支
- **位置**：`app/.../compose/RiskConfirmManager.kt:949` `cancel()`
- **原因**：`confirm()` 顺序为 `blockingRequest → agentAction → sessionHandle → coroutine`，而 `cancel()` 漏掉 `sessionHandle` 分支。会话句柄型确认被用户取消时，不写 `RESULT_DENIED`、不 `navigateBackToTermux`，pending 状态永不清除 → 终端卡死（命令既不放行也不拒绝）。
- **修复**：在 `cancel()` 中补上与 `confirm()` 对称的 `sessionHandle` 分支（写 DENIED + 导航回 Termux）。

### ✅ Q7【中危】SettingsScreen 备份/恢复接收器在异常路径下泄漏
- **位置**：`app/.../compose/SettingsScreen.kt` restore(268-307) 与 backup(366-396)
- **原因**：`cancelReceiver` 仅在 worker 线程**成功返回**后的 `mainHandler.post` 内反注册；若 `openInputStream`/`restoreBackup`/`createBackup` 抛异常，反注册与 `tempFile.delete()` 均被跳过 → BroadcastReceiver 泄漏 + 缓存临时文件残留。
- **修复**：worker 体包 `try/catch/finally`——`finally` 中删除临时文件；无论成功/异常均在 `mainHandler.post` 内（try 包裹）反注册接收器。restore 与 backup 两条路径均已修正。

### ✅ Q8【中危】AiTermuxActivity 注册 stopReceiver 但从不反注册
- **位置**：`app/.../activities/AiTermuxActivity.kt:101`（全文无 `unregisterReceiver`/`onDestroy`）
- **原因**：`onCreate` 注册 `stopReceiver`，无 `onDestroy` → Activity 销毁后接收器泄漏（持有 Activity 引用），重建时叠加多个实例。
- **修复**：将 `stopReceiver` 提升为字段；新增 `onDestroy()`，判空后 `unregisterReceiver`（catch `IllegalArgumentException` 防重复反注册）。

### ✅ Q10【中危】OverviewCardManager.getInstance 双重检查锁定失效
- **位置**：`app/.../compose/OverviewScreen.kt:324`
- **原因**：无 `@Volatile`、无 `synchronized`，并发首访可能重复构造或返回半成品实例。
- **修复**：`@Volatile var instance` + `synchronized` 块（标准 DCL）。

### ✅ Q12【中危】PluginManager 不安全强转 LocalBinder
- **位置**：`app/.../plugin/PluginManager.kt:314` `onServiceConnected`
- **原因**：`(binder as TermuxService.LocalBinder)` 若类型不符抛 `ClassCastException`，被外层 `try/catch` 吞掉，导致插件会话 `registerPluginSession` 未执行（功能失败而非崩溃）。
- **修复**：`binder as? TermuxService.LocalBinder ?: run{ 记录日志; 解绑; return }`，再做 `localBinder.service.registerPluginSession(...)`。

### ✅ Q14【中危】RiskConfirmManager 协程 requestId 同毫秒碰撞
- **位置**：`app/.../compose/RiskConfirmManager.kt:631`
- **原因**：`requestId = System.currentTimeMillis().toString()`，同毫秒并发请求 map key 碰撞，后一个覆盖前一个的 `suspendCancellableCoroutine` → 前者永不 resume（协程泄漏/永久挂起）。
- **修复**：改用 `java.util.UUID.randomUUID().toString()`。

### ✅ Q4【低-中危】QuickCommandStore 增删改非原子（违背"线程安全"注释承诺）
- **位置**：`app/.../compose/QuickCommandStore.kt:95-128`（add/remove/removeById/update/updateById）
- **原因**：读-改-写整段无同步，`apply()` 异步提交时并发增删会互相覆盖丢失。
- **修复**：各 RMW 方法加 `synchronized(this)` 保护整段（读取+修改+保存）。

### ✅ Q15【中危】ComposeSessionManager.killAllSessions 重置 nextId=1 破坏 handle 唯一性
- **位置**：`libterminal/.../ComposeSessionManager.kt:173`
- **原因**：清空会话时把 id 计数器重置为 1，切换核心后若某处仍持有旧 `sessionHandle` intent extra（如 `onNewIntent`），会解析到已不存在的会话，造成歧义/黑屏。
- **修复**：移除 `nextId = 1` 重置，使会话 handle 全局单调递增（随 Q3 一并修改）。

### 📋 Q6 / Q11【潜伏·已废弃路径】InputInterceptor 命令拦截路径与放行脱节
- **位置**：`RiskConfirmManager.kt:1012-1033`（JavaSessionAdapter/ComposeSessionAdapter 的 `confirmPendingCommand()/denyPendingCommand()` 为空实现）、`1092-1119`（脚本分支在 OFF 级别仍拦截，与实时路径不一致）
- **说明**：该路径已被注释为"shell hook 接管后删除"，属**已废弃的潜在（latent）隐患**——当前由 shell 层 `trap DEBUG + PROMPT_COMMAND` + SecuritySocketServer 实际完成拦截，不会触发。一旦误启用，`handleDangerousCommand` 拦截后无真正下发通道。
- **建议**：整体清理该废弃 `InputInterceptor` 调用点，或在 `handleTerminalCommandInternal` 开头尊重 `ProtectionLevel.OFF`、并补全 pending 命令下发；**本次未改，以免误伤当前生效的 shell-hook 路径**。

### 📋 Q9【中危·设计层】SecuritySocketServer 固定 8 线程 IO 池被阻塞确认弹窗占满
- **位置**：`app/.../compose/SecuritySocketServer.kt`（WARN_VERIFY 级别 `requestDetectedConfirmationBlocking` → `doDialogConfirmationBlocking` 中 `latch.await(25s)`）
- **说明**：多个 shell/会话并发触发 CHECK_CMD/CHECK_SCRIPT 时，8 个 IO 池线程被弹窗 `await` 占满，新连接无法被 accept 处理，shell 端 `nc` 长时间等待而卡死。
- **建议**：将阻塞确认移出 8 线程 IO 池（独立单线程/专用作用域），或在 accept 后把"判定+确认"整体移出 IO 池。**本次未改，属线程模型重构，需结合实机压测验证。**

### 📋 Q13【低危】Compose 引擎 TerminalSession.write 通道满时静默丢弃
- **位置**：`libterminal/.../engine/TerminalSession.kt:316` `terminalWriteChannel.trySend(data)`
- **说明**：`Channel.BUFFERED`（默认容量 64）满时 `trySend` 返回 false、字节被丢弃；大段程序化写入（长快捷指令/自动执行命令）极端情况下可能丢字符。
- **建议**：失败后做有限重试/让出，或增大缓冲；交互式单字符输入不受影响。**本次未改热路径，避免引入 ANR 风险。**

---

## 四、修复后一致性核对（读校）

- `ComposeSessionManager`：`nextId` 仅剩 `getAndIncrement` 一处引用，无其他残留；`currentSession` 为 public `val`，`getInstance` 为 `@JvmStatic` ✅
- `RiskConfirmManager`：`RESULT_DENIED`/`RESULT_CONFIRMED`/`KEY_PENDING_SESSION_HANDLE`/`KEY_PENDING_RESULT` 常量均存在（72-76 行）✅
- `QuickCommandSheet.executeQuickCommand`：完整引用 `com.termux.app.compose.terminal.ComposeSessionManager`、`TerminalRuntimeCore.isComposeMode`、`composeSession.isRunning`/`write(String)`，均已在模块内确认存在 ✅
- `PluginManager`：`TermuxService.LocalBinder` / `.service` 与原始代码一致 ✅
- `AiTermuxActivity`：`onDestroy` 在 `super.onDestroy()` 前反注册，判空防 `IllegalArgumentException` ✅
- `ComposeTerminalSettings.edit()`：`block(editor)` 后 `editor.apply()` 落盘 ✅

---

## 五、修复统计

| 类别 | 数量 |
|------|------|
| 🔴 高危（已修复） | 2（Q1、Q2） |
| 🟠 中危（已修复） | 8（Q3、Q5、Q7、Q8、Q10、Q12、Q14、Q15） |
| 🟡 低危（已修复） | 1（Q4） |
| 📋 已记录（建议后续） | 3（Q6/Q11 废弃路径、Q9 IO 池、Q13 通道背压） |
| **合计** | **14 条（11 已修复 + 3 记录）** |

---

## 六、后续建议（实机验证如何补足）

1. **构建**：在具备外网/内网镜像的环境执行 `./gradlew :app:assembleDebug`，或预先缓存 `gradle-9.3.1` 分发版与 miuix/AGP/Kotlin/NDK 依赖。
2. **覆盖安装（严守用户约束）**：`adb -s NNONPRMRKVTWOZO7 install -r app/build/outputs/apk/debug/*.apk`（仅覆盖安装，**不 uninstall、不删除设备任何文件**）。
3. **功能冒烟**：
   - 经典模式：长按终端顶栏键盘图标 → 快捷指令面板 → 添加/执行/删除；
   - Nova 模式（设置→终端核心→新星）：同样路径验证快捷指令现已生效（Q1 修复点）；
   - 设置页改字号/主题 → 返回再进入确认持久化（Q2 修复点）；
   - 备份/恢复中途点通知"取消"按钮，确认无接收器泄漏与临时文件残留（Q7）；
   - 风险命令确认弹窗分别点"确认/取消"，确认终端不卡死（Q5）。
4. **针对 Q9/Q13**：在并发高危命令场景与超大文本粘贴场景下做专项压测。

---

> 注：本环境未能完成编译与实机验证，已修复项均经源码级逐行核实；若构建后出现新编译告警，将集中在上述改动文件范围内排查。
