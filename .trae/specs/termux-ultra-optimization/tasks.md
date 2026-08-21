# Termux Ultra 代码优化 - 实施计划

## Task 1: 需求 8+9 - 版本号体系与核心版本动态化（Phase 5）
- **状态**: `pending`
- **优先级**: high
- **依赖**: None
- **描述**:
  - 修改 `app/build.gradle`：`versionCode` 1058→1059, `versionName` `"118.3.71"`→`"R0.9.0"`
  - 新增 `resValue "string", "termux_core_version", "0.118.3"` 和 `buildConfigField`
  - 创建 `UpdateChecker.kt`：实现 `AppVersion` 数据类，重构更新检查逻辑支持 `R<x.y.z>` 格式
  - 修改 `AboutScreen.kt`：将硬编码 `0.118.3` 改为动态读取
  - 更新 `strings.xml` 和 `strings-zh-rCN/strings.xml` 新增相关字符串
- **验收标准**: AC-4, AC-5, AC-9
- **测试要求**:
  - `rule` TR-1.1: `build.gradle` 中 `versionCode = 1059`, `versionName = "R0.9.0"`
  - `rule` TR-1.2: `UpdateChecker` 能正确解析 `R0.9.0` 格式版本号
  - `rule` TR-1.3: `AboutScreen` 动态显示 `0.118.3`
  - `rule` TR-1.4: GitHub API 调用正确处理 `R*` 格式 tag
  - `rule` TR-1.5: 编译通过无错误
- **备注**: 这是最基础的改动，且相对独立，可以作为第一个任务

## Task 2: 需求 1.1+1.2 - 终端页 UI 优化（Phase 1 部分）
- **状态**: `pending`
- **优先级**: high
- **依赖**: None
- **描述**:
  - 查找终端相关页面中所有 Material Design 弹窗/对话框/Toast
  - 替换为 miuix 组件
  - 在 `TerminalTopBar.kt` 新增折叠按钮功能
  - 扩展 `TerminalTopBarState` 增加折叠状态持久化
  - 折叠状态：仅显示返回和折叠按钮，标题全宽显示
  - 展开状态：恢复所有按钮
- **验收标准**: AC-1
- **测试要求**:
  - `rule` TR-2.1: 终端页无 Material 弹窗残留
  - `rule` TR-2.2: 折叠/展开切换正常工作
  - `rule` TR-2.3: 折叠状态下标题完整显示
  - `rule` TR-2.4: 编译通过
- **备注**: 需仔细检查 TerminalDetailScreen.kt 和 TermuxActivity.java

## Task 3: 需求 1.3+1.4 - 会话结束处理和按钮文案
- **状态**: `pending`
- **优先级**: medium
- **依赖**: Task 2
- **描述**:
  - 在 TerminalTopBar 中监听 shellPid 变化
  - 会话结束（shellPid == -1）后 TopBar 进入常驻模式
  - 显示「会话已结束 (退出代码: N)」
  - 结束会话的按钮变为 disabled
  - TopAppBar "新会话" 的文案改为 `新会话 [X]`（X 指原生编号，从 TermuxSession.mHandle 获取）
- **验收标准**: AC-1
- **测试要求**:
  - `rule` TR-3.1: shellPid == -1 时正确显示结束状态
  - `rule` TR-3.2: 结束状态下关闭按钮 disabled
  - `rule` TR-3.3: tooltip 包含原生编号
  - `rule` TR-3.4: 编译通过
- **备注**: 需要与 TermuxSession 生命周期正确对接

## Task 4: 需求 2 - 全局 Toast → Snackbar 迁移（Phase 1）
- **状态**: `pending`
- **优先级**: high
- **依赖**: None
- **描述**:
  - 创建 `SnackbarHelper.kt` 工具类
  - 在各 Activity 的 Scaffold 顶层包裹 `SnackbarHost`
  - 查找并替换 `RiskConfirmManager.kt`、`FallbackHelper.kt`、`MainActivity.kt` 及其他文件中的 Toast
  - 非 Compose 环境使用 `Snackbar` 作为 fallback
  - 保持原有触发逻辑和文案不变
- **验收标准**: AC-2
- **测试要求**:
  - `rule` TR-4.1: 除 fallback 路径外无 Toast.makeText 调用
  - `rule` TR-4.2: SnackbarHelper.init() 在 Activity 中正确调用
  - `rule` TR-4.3: 所有原有 Toast 文案通过 Snackbar 正确显示
  - `rule` TR-4.4: 编译通过
- **备注**: 涉及 15 个文件，需分批处理

## Task 5: 需求 3 - 增强防护模式分级（Phase 2）
- **状态**: `pending`
- **优先级**: high
- **依赖**: None
- **描述**:
  - 在 `RiskConfirmManager.kt` 中新增 `ProtectionLevel` 枚举
  - 重构 `handleTerminalCommand()` 根据等级分派行为
  - 添加 SharedPreferences 迁移逻辑：`KEY_ENABLED` → `KEY_PROTECTION_LEVEL`
  - 修改 `SettingsScreen.kt` 将开关改为分级选择器
  - 等级降级时显示 Snackbar 提示
- **验收标准**: AC-3
- **测试要求**:
  - `rule` TR-5.1: Level 0 不检测直接返回 false
  - `rule` TR-5.2: Level 1 仅 Snackbar 提示不拦截
  - `rule` TR-5.3: Level 2 保持现有弹窗+倒计时逻辑
  - `rule` TR-5.4: Level 3 直接拒绝并 Snackbar 通知
  - `rule` TR-5.5: 旧数据正确迁移
  - `rule` TR-5.6: 编译通过
- **备注**: 核心重构，需谨慎处理现有逻辑兼容性

## Task 6: 需求 4 - 防护检测模式配置（Phase 2）
- **状态**: `pending`
- **优先级**: high
- **依赖**: Task 5
- **描述**:
  - 新增 `DetectionMode` 枚举
  - 静态侦测：复用 `RiskCommandDetector.detect()`
  - 运行时解析：定义 `RuntimeCommandInterceptor` 接口
  - 实现 `KEY_DETECTION_MODE` SharedPreferences 存储
  - 与 `KEY_PROTECTION_LEVEL` 联动逻辑
  - 在 `SettingsScreen.kt` 新增检测模式选择器
- **验收标准**: AC-3
- **测试要求**:
  - `rule` TR-6.1: 静态侦测模式正常工作
  - `rule` TR-6.2: 运行时解析接口定义正确
  - `rule` TR-6.3: NONE 模式仅在 Level 0 时可用
  - `rule` TR-6.4: 编译通过
- **备注**: 运行时解析的完整实现可后续迭代

## Task 7: 需求 5 - 脚本文件检测（Phase 3）
- **状态**: `pending`
- **优先级**: medium
- **依赖**: Task 5, Task 6
- **描述**:
  - 在 `RiskCommandDetector.kt` 中新增 `detectScript()` 方法
  - 定义 `ScriptDetectionResult` 数据类
  - 创建脚本检测对话框 Composable
  - 在 `FileManagerScreen.kt` 执行脚本前增加预扫描流程
  - 支持查看脚本、编辑脚本、继续执行、取消操作
- **验收标准**: AC-8
- **测试要求**:
  - `rule` TR-7.1: 脚本能被正确扫描
  - `rule` TR-7.2: 对话框显示行号和内容
  - `rule` TR-7.3: 所有操作按钮功能正常
  - `rule` TR-7.4: 编译通过
- **备注**: 需处理文件读取权限

## Task 8: 需求 6 - ROOT 用户增强防护（Phase 3）
- **状态**: `pending`
- **优先级**: medium
- **依赖**: Task 5
- **描述**:
  - 实现 ROOT 检测方法 `hasRootAccess()`
  - 检测到 ROOT 时默认防护等级设为 Level 3
  - 终端页显示 ROOT 状态卡片
  - 创建安全模式会话前弹出 ROOT 警告对话框
  - 新增 ROOT 专属检测项（su/sudo, 块设备, setprop）
  - 设置页新增 ROOT 管理卡片
- **验收标准**: AC-6
- **测试要求**:
  - `rule` TR-8.1: ROOT 检测逻辑正确
  - `rule` TR-8.2: ROOT 状态卡片显示正确
  - `rule` TR-8.3: 安全会话创建前弹窗出现
  - `rule` TR-8.4: 编译通过
- **备注**: 需要 ROOT 设备测试

## Task 9: 需求 7 - 主页卡片排列切换（Phase 4）
- **状态**: `pending`
- **优先级**: medium
- **依赖**: None
- **描述**:
  - 在 `SettingsScreen.kt` 「外观」下新增卡片排列设置项
  - 扩展 `TerminalListScreen.kt` 功能卡片区域支持横/竖切换
  - 实现 HorizontalPager 横向滑动
  - 添加指示器（Dots）
  - `KEY_CARD_LAYOUT_MODE` SharedPreferences 存储
  - 功能卡片数量统计逻辑
- **验收标准**: AC-7
- **测试要求**:
  - `rubric` TR-9.1: 卡片切换体验；维度：流畅度；标准：1-5 分；档位：1=不可用/崩溃; 3=可用但有卡顿; 5=流畅自然；阈值≥4；证据：手动测试录屏
  - `rule` TR-9.2: 竖向模式保持现有布局
  - `rule` TR-9.3: 横向模式支持左右滑动和指示器
  - `rule` TR-9.4: 卡片数量为 1 时设置项 disabled
  - `rule` TR-9.5: 编译通过
- **备注**: 需验证 miuix HorizontalPager 可用性

## Task 10: 字符串资源更新
- **状态**: `pending`
- **优先级**: low
- **依赖**: Task 1, Task 2-9
- **描述**:
  - 更新 `app/src/main/res/values/strings.xml`
  - 更新 `app/src/main/res/values-zh-rCN/strings.xml`
  - 新增所有需求涉及的中英文字符串资源
  - 确保所有硬编码字符串被正确提取
- **验收标准**: AC-4, AC-9
- **测试要求**:
  - `rule` TR-10.1: 所有新增功能的字符串有中英文翻译
  - `rule` TR-10.2: 无遗漏的硬编码中文字符串
  - `rule` TR-10.3: 编译通过

## Task 11: 集成构建验证
- **状态**: `pending`
- **优先级**: high
- **依赖**: Task 1-10
- **描述**:
  - 执行 Debug APK 编译
  - 验证无编译错误
  - 验证版本号正确
  - 执行基本功能手动测试
- **验收标准**: AC-1 至 AC-9
- **测试要求**:
  - `rule` TR-11.1: Debug APK 编译成功
  - `rule` TR-11.2: APK versionCode = 1059
  - `rule` TR-11.3: APK versionName = "R0.9.0"
  - `rule` TR-11.4: 基本功能测试通过
- **备注**: 需要在 Windows 环境下通过硬链接路径编译
