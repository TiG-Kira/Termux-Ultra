# Termux Ultra 代码优化 - 产品需求文档

## 概述
- **摘要**: 对 Termux Ultra 应用进行全面优化，涵盖终端页 UI、Toast→Snackbar 迁移、防护模式分级、检测模式配置、脚本检测、ROOT 用户增强防护、主页卡片排列切换、独立版本号体系与更新检查重构、核心版本号动态化共 9 项需求。
- **目的**: 提升应用的视觉一致性（全面使用 miuix 组件）、功能可扩展性（防护等级化/检测模式化）、安全性（ROOT 专属防护）和产品化（独立版本号、更新检查、脚本扫描）。
- **目标用户**: Termux Ultra 的 Android 用户，包括普通用户、开发者和 ROOT 用户。

## 目标
1. 统一 UI 组件风格，全面屏蔽 Material Design 组件
2. 将 Toast 迁移为 miuix Snackbar，提升体验一致性
3. 将防护模式从布尔开关重构为 4 级分级控制
4. 新增静态/运行时两种防护检测模式
5. 支持脚本文件批量扫描检测
6. 为 ROOT 用户提供针对性的防护措施
7. 主页卡片支持竖/横排列切换
8. 建立独立的 `R<x.y.z>` 版本号体系
9. 动态化 Termux 核心版本号显示

## 非目标
- 不引入新的第三方依赖（除已有 OkHttp 等）
- 不修改底层终端模拟器核心逻辑
- 不改变现有功能的核心行为（仅扩展）

## 背景与上下文
- 仓库: `tig-kira/termux-ultra`（分支: `ReBuild`）
- 当前版本: `versionCode 1058`, `versionName "118.3.71"`
- 当前防护管理: `RiskConfirmManager` 使用布尔开关 (`KEY_ENABLED`)，`RiskCommandDetector` 仅支持静态正则检测
- UI 框架: miuix 0.9.4-rc01 + Compose 1.8.3 + Material3 1.3.0
- 关键文件: `RiskConfirmManager.kt`, `RiskCommandDetector.kt`, `TerminalTopBar.kt`, `TerminalListScreen.kt`, `SettingsScreen.kt`, `AboutScreen.kt`, `UpdateChecker.kt`, `build.gradle`

## 功能需求

### FR-1: 终端页 UI 优化（需求 1）
- 查找终端相关页面中所有 Material Design 弹窗、对话框、Toast
- 将其全部替换为 miuix 组件（`OverlayDialog`、`Snackbar`、miuix Switch 等）
- 在 `TerminalTopBar.kt` 新增折叠按钮，支持折叠/展开状态
- 监听 TerminalSession 生命周期事件，会话结束后 TopBar 进入常驻模式
- TopAppBar "新会话" 的文案改为 `新会话 [X]`（X 指原生编号，从 TermuxSession.mHandle 获取）

### FR-2: 全局 Toast → Snackbar 迁移（需求 2）
- 查找项目中所有 `Toast.makeText()` 使用点
- 创建统一 Snackbar 工具类 `SnackbarHelper.kt`
- 每个 Activity 的 Scaffold 顶层包裹 `SnackbarHost`
- 非 Compose 环境使用 `com.google.android.material.snackbar.Snackbar` 作为 fallback
- 所有 Toast 迁移为 Snackbar，保持原有触发逻辑和文案

### FR-3: 增强防护模式分级（需求 3）
- 新增 `ProtectionLevel` 枚举：LEVEL_0_OFF, LEVEL_1_WARN_ONLY, LEVEL_2_WARN_VERIFY, LEVEL_3_AUTO_BLOCK
- 各等级行为：关闭→不检测；仅提示→Snackbar 提示不拦截；警告并验证→弹窗+倒计时+生物认证；自动拦截→直接拒绝
- 迁移 `KEY_ENABLED`（boolean）→ `KEY_PROTECTION_LEVEL`（int）
- SettingsScreen UI 改为分级选择器

### FR-4: 防护检测模式配置（需求 4）
- 新增 `DetectionMode` 枚举：NONE, STATIC, RUNTIME
- 静态侦测：复用现有正则匹配逻辑
- 运行时解析：实现 `RuntimeCommandInterceptor` 接口
- `KEY_DETECTION_MODE` 与 `KEY_PROTECTION_LEVEL` 联动

### FR-5: 脚本文件检测（需求 5）
- 在文件管理页执行脚本前自动扫描脚本内容
- 新增 `detectScript()` 方法返回 `ScriptDetectionResult` 列表
- 检测到危险时弹出对话框显示行号、内容、查看/编辑/继续执行/取消按钮

### FR-6: ROOT 用户增强防护（需求 6）
- 应用启动时异步检测 ROOT 权限
- ROOT 用户默认防护等级设为 Level 3（自动拦截）
- 终端页显示 ROOT 状态卡片
- 创建安全模式会话前弹出警告对话框
- 对 ROOT 用户额外检测原生 su/sudo、原始块设备访问、setprop 命令
- 设置页新增 ROOT 管理卡片（授予 ROOT 权限 + 宽松模式）

### FR-7: 主页卡片排列方式切换（需求 7）
- 设置页「外观」分类下新增 `主页卡片排列` 设置项
- 竖向（默认）/横向两种排列方式
- 横向使用 miuix HorizontalPager，支持左右滑动切换
- 卡片数量 ≥ 2 时切换可操作

### FR-8: 独立版本号体系与更新检查重构（需求 8）
- 版本号格式改为 `R<x.y.z>`，当前版本 `R0.9.0`
- `versionCode` 从 1058 递增至 1059
- 新增 `AppVersion` 数据类支持版本号解析和比较
- 重构 `UpdateChecker` 支持新版本号格式和 GitHub API 查询
- 设置页「关于」显示新版本号和检查更新按钮

### FR-9: Termux 核心版本号动态化（需求 9）
- `build.gradle` 新增 `resValue "string", "termux_core_version", "0.118.3"`
- `AboutScreen.kt` 硬编码字符串改为动态读取

## 非功能需求

### NFR-1: 代码风格
- 沿用现有 Kotlin 编码规范，不引入新的第三方依赖
- 文件名和包结构保持不变

### NFR-2: 兼容性
- 所有 SharedPreferences 变更需做旧数据迁移
- 版本迁移需有明确的升级逻辑

### NFR-3: 性能
- 运行时解析模式需在后台线程执行，避免阻塞 UI
- Snackbar 切换不能引起额外的性能开销

### NFR-4: 安全性
- 生物识别相关代码直接复用现有 `launchBiometricAuth` 实现
- ROOT 检测和防护逻辑必须可靠

### NFR-5: 可追溯性
- 版本号变更需在 `build.gradle` 中有明确记录
- 更新检查结果需有日志

## 约束

### 技术约束
- 不引入新的第三方依赖
- 保持对旧版本 Android（API 21+）的兼容性
- Compose 版本锁定 1.8.3

### 业务约束
- 当前版本: `versionCode 1058` → 需递增至 `1059`
- 当前 `versionName "118.3.71"` → 需改为 `"R0.9.0"`
- Termux 上游核心版本号: `0.118.3`

### 依赖
- OkHttp 4.12.0（已有，用于 GitHub API）
- miuix 0.9.4-rc01（已有，UI 组件）
- Room 2.7.2（已有，可能用于存储配置）

## 假设
- GitHub API 可达且 rate limit 充足
- 现有 `SnackbarHostState` 在全局 Compose 树中可用
- `RiskConfirmDialogHost` 已在 Activity 中设置
- ROOT 检测的 `su -c` 命令在 Termux 环境中可用
- miuix HorizontalPager 组件可正常使用

## 验收标准

### AC-1: 终端页 UI 完全使用 miuix 组件
- **类型**: `rule`
- **给定**: 用户打开终端页
- **当**: 执行所有交互操作（长按、新建、关闭、键盘切换）
- **然后**: 所有弹出的对话框和提示均使用 miuix 组件，无 Material Design 组件残留
- **通过条件**: 代码中无 `Toast.makeText` 用于终端页交互；所有弹窗使用 `OverlayDialog`
- **证据**: 代码审查 + 编译通过 + 手动测试截图

### AC-2: Toast → Snackbar 完全迁移
- **类型**: `rule`
- **给定**: 项目中存在需要用户提示的场景
- **当**: 触发提示
- **然后**: 通过 `SnackbarHelper.show()` 显示 Snackbar 而非 Toast
- **通过条件**: `grep Toast.makeText` 结果为空（除不可避免的 fallback 路径外）
- **证据**: grep 结果 + 编译通过

### AC-3: 防护模式四级分级正确实现
- **类型**: `rule`
- **给定**: 用户在设置页配置防护等级
- **当**: 分别选择 Level 0-3
- **然后**: 各等级行为符合规范描述
- **通过条件**: `RiskConfirmManager` 正确根据等级分派行为
- **证据**: 单元测试 + 手动测试

### AC-4: 版本号体系正确切换
- **类型**: `rule`
- **给定**: 构建应用
- **当**: 查看 About 页和检查更新
- **然后**: 显示 `Termux Ultra R0.9.0`，更新检查支持新版本号格式
- **通过条件**: `versionCode = 1059`, `versionName = "R0.9.0"`
- **证据**: APK 分析 + About 页截图

### AC-5: 更新检查与 GitHub Releases 正确对接
- **类型**: `rule`
- **给定**: 用户点击「检查更新」
- **当**: 网络可达且 GitHub Releases 有新版本
- **然后**: 正确识别最新版本并提示
- **通过条件**: `UpdateChecker.checkForUpdates()` 返回正确结果
- **证据**: API 调用日志 + 手动测试

### AC-6: ROOT 用户检测和防护生效
- **类型**: `rule`
- **给定**: 设备已获取 ROOT 权限
- **当**: 应用启动
- **然后**: 终端页显示 ROOT 状态卡片，防护等级自动设为 Level 3
- **通过条件**: ROOT 检测逻辑正确，UI 提示正确显示
- **证据**: 手动测试（需要 ROOT 设备）

### AC-7: 主页卡片横/竖切换流畅
- **类型**: `rubric`
- **维度**: 卡片切换体验
- **评估标准**: 1-5 分
- **档位**: 1=功能不可用或崩溃; 3=功能可用但有明显卡顿; 5=切换流畅、手势响应自然、指示器同步正确
- **通过阈值**: ≥ 4
- **证据**: 手动测试录屏

### AC-8: 脚本检测功能完整
- **类型**: `rule`
- **给定**: 用户在文件管理页点击执行脚本
- **当**: 脚本中包含危险命令
- **然后**: 弹出对话框显示危险命令详情和操作选项
- **通过条件**: 脚本能被正确扫描并列出所有危险命令
- **证据**: 单元测试 + 手动测试

### AC-9: 核心版本号动态化显示
- **类型**: `rule`
- **给定**: 构建应用
- **当**: 打开 About 页
- **然后**: 显示 `基于 Termux 0.118.3 稳定版`，版本号从资源动态获取
- **通过条件**: 字符串硬编码已被替换
- **证据**: 代码审查

## 未解决问题
- [ ] miuix HorizontalPager 在当前版本 (0.9.4-rc01) 中是否可用？需验证
- [ ] 运行时解析模式的实际实现方式——是监听 shell 输出还是在 TermuxSession 层拦截？
- [ ] ROOT 检测是否需要考虑 Magisk / KernelSU 等不同 ROOT 方案的兼容性？
