# 冷启动优化 Implementation Plan

## Repository Research

### 当前启动流程
```
点击图标 → Zygote fork → TermuxApplication.onCreate() 
→ SplashActivity.onCreate() → (读 SharedPreferences) → MainActivity / OobeActivity
→ setContent { MainScreen(...) }
```

### 发现的关键瓶颈

| # | 瓶颈 | 位置 | 影响量级 |
|---|------|------|----------|
| 1 | **SplashActivity 继承 AppCompatActivity** | `SplashActivity.java:14` | **大** — AppCompat 初始化需加载大量资源、主题解析 |
| 2 | **LogManager.init() 在主线程做磁盘 I/O** | `TermuxApplication.java:81` → `LogManager.java:78` | **中** — cleanOldLogs 读取+重写整个日志文件 |
| 3 | **没有 Baseline Profile** | 整个项目 | **大** — Compose 代码每次冷启动 JIT 解释，首帧渲染慢 |
| 4 | **TermuxApplication.onCreate() 主线程工作过多** | `TermuxApplication.java:58-109` | **中** — crash handler 设置等可延后 |
| 5 | **SplashActivity 无专用启动主题** | Manifest | **中** — 使用 Application 的 AppCompat 主题 |

### 已做的优化（但效果有限）
- TermuxApplication 部分初始化已放后台线程（第 112-135 行）
- native crash handler 已放后台线程（第 89-96 行）

这些改动方向正确，但最大的瓶颈 SplashActivity/AppCompatActivity 没处理。

---

## Files and Modules

- `app/src/main/java/com/termux/app/SplashActivity.java`: 改基类 AppCompatActivity → Activity，加启动主题
- `app/src/main/java/com/termux/app/TermuxApplication.java`: 精简主线程，LogManager/其他初始化放后台
- `app/src/main/java/com/termux/app/utils/LogManager.java`: cleanOldLogs 异步化
- `app/src/main/res/values/styles.xml`: 添加 SplashScreen 主题
- `app/src/main/AndroidManifest.xml`: SplashActivity 加独立主题
- `app/build.gradle`: 添加 Baseline Profile 依赖
- `app/baseline-profile.txt` 或 `app/src/main/baseline-profile.txt`: 新增 Baseline Profile 文件
- `tools/setup-baseline-profile.sh` 或类似：生成 Baseline Profile 的工具

---

## Implementation Steps

### Step 1: SplashActivity 改基类（最直接见效）
- 把 `AppCompatActivity` 改为 `Activity`
- 因为 SplashActivity 只做 SharedPreferences 读 + startActivity + finish，完全不需要 AppCompat
- `applySavedLanguage` 方法中使用了 `Locale` 和 `Configuration`，这些都是 Android framework 原生类，不需要 AppCompat
- 预计节省：**300-800ms**（取决于设备性能）

### Step 2: 添加 SplashActivity 专用启动主题
- 在 `styles.xml` 中添加 `Theme.Termux.Splash` 主题
- 设置 `windowBackground` 为纯色（如黑色/品牌色），避免启动时白屏
- 在 Manifest 中给 SplashActivity 指定这个主题
- 注意：不要继承 AppCompat 主题，用 `@android:style/Theme.Black.NoTitleBar.Fullscreen` 或直接 `android:Theme.Black`

### Step 3: LogManager.init() 异步化
- `LogManager.init()` 构造函数中的 `cleanOldLogs(3)` 是磁盘 I/O
- 把 cleanOldLogs 改为延迟执行（MessageQueue.IdleHandler 或首帧后）
- 构造函数只创建 File 对象，不做 I/O
- TermuxApplication 中 LogManager.init 可以继续在主线程（因为 init 本身很轻量了），但 cleanOldLogs 要异步

### Step 4: TermuxApplication.onCreate() 精简主线程
- 把 `TermuxCrashUtils.setCrashHandler(this)` 也放到后台线程（虽然它本身很轻量，但可以和其他后台任务合并）
- 保持主线程只做：必要的 ActivityLifecycleCallbacks 注册 + 后台线程启动
- 所有重活放后台

### Step 5: 创建 Baseline Profile
- 添加 `androidx.profileinstaller:profileinstaller` 依赖
- 创建 `baseline-profile.txt` 规则文件，包含启动关键路径的类
- 规则覆盖：TermuxApplication、SplashActivity、MainActivity、MainScreen 及其依赖的 Compose 类
- 这样安装时系统会预编译这些类，冷启动时直接执行 AOT 代码

### Step 6: 移除不必要的 SDK_INT 检查开销
- 检查 SplashActivity、MainActivity 中是否有可移除的版本检查分支
- minSdk=26，可以移除 Build.VERSION.SDK_INT >= 26 这种检查

---

## Dependencies and Considerations

- `SplashActivity.applySavedLanguage` 使用了 `Configuration.setLocale()` — 这是 framework API，Activity 基类没问题
- `SplashActivity` 当前 `theme` 是 Manifest Application 级别的 `Theme.Termux`（AppCompat），改成 Activity 基类后需要用非 AppCompat 主题
- Baseline Profile 需要 minSdk 24+，项目 minSdk=26 满足
- profileinstaller 依赖本身很小（<50KB）

---

## Validation

1. **构建验证**: `./gradlew :app:assembleDebug` 确认编译通过
2. **冷启动时间测量**: 
   - 使用 `adb shell am force-stop com.termux && adb shell am start -W -n com.termux/.app.MainActivity`
   - 记录 `WaitTime` 和 `TotalTime`
   - 或使用 Macrobenchmark（如果有 macrobenchmark 模块）
3. **功能验证**: 
   - 正常启动能进入 MainActivity
   - 首次安装能进入 OobeActivity
   - 崩溃检测 dialog 正常工作
   - 语言切换功能正常

---

## Risks

- **SplashActivity 改基类后 applySavedLanguage 可能行为变化**: Activity 的 attachBaseContext 同样能接受 Context wrapper，风险低
- **Baseline Profile 规则写不全**: 需要覆盖实际启动路径上所有类，否则效果打折扣；可以运行后生成完整版
- **异步化 LogManager 后如果有代码在 init 前调用 getInstance**: 当前是 synchronized + throw，如果 LogManager 内部状态还没初始化好会崩溃；但 LogManager 被调用的地方（异常处理）已经在 init 之后了，风险低
