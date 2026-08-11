# Termux Ultra 项目 - 完整工作上下文

> 项目命名：**Termux Ultra**（而非 KiTerminal UX）
> 最后更新：2026-08-11

---

## 一、最近操作：Android on Termux 功能

### 功能概述

实现"Android on Termux"功能，使用 `qemu-system-x86_64` 在 Termux 上运行 x86_64 架构的 Android 系统虚拟机（Android 7.0 - 16）。支持版本选择、镜像类型（AOSP/Google APIs/Play Store）、ROOT 开关、ADB 集成、VNC 显示等功能。

### 核心架构

```
┌─────────────────────────────────────────────────────────┐
│                    Termux Ultra App                      │
│  ┌─────────────────────────────────────────────────┐   │
│  │  AndroidVmActivity (管理页面)                     │   │
│  │  ├── AndroidVmCreateSheet (创建/编辑配置)         │   │
│  │  └── AndroidVmConfig (数据模型 + 脚本生成)         │   │
│  └─────────────────────┬───────────────────────────┘   │
│                        │ generateScript()               │
│                        ▼                                │
│  ┌─────────────────────────────────────────────────┐   │
│  │  生成 Bash 启动脚本                               │   │
│  │  ├── Termux 层: 下载/解压/配置                    │   │
│  │  ├── 容器层: QEMU 启动 + VNC + ADB                │   │
│  │  └── 复用 QEMU with VNC 的 Ubuntu 容器             │   │
│  └─────────────────────┬───────────────────────────┘   │
│                        │                                │
│  ┌─────────────────────┴───────────────────────────┐   │
│  │  Ubuntu 容器 (proot)                              │   │
│  │  ├── qemu-system-x86_64 (x86_64 模拟)             │   │
│  │  ├── KVM 加速检测                                 │   │
│  │  ├── VNC 显示 (端口 5900+)                        │   │
│  │  └── ADB 端口转发 (5555)                          │   │
│  └───────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

### 文件清单

#### 新建文件（3 个）

| 文件路径 | 行数 | 说明 |
|---------|------|------|
| `app/src/main/java/com/termux/app/compose/AndroidVmConfig.kt` | 744 | 核心数据模型 + 脚本生成逻辑 |
| `app/src/main/java/com/termux/app/compose/AndroidVmCreateSheet.kt` | 562 | 创建/编辑 VM 配置的 Compose Sheet |
| `app/src/main/java/com/termux/app/activities/AndroidVmActivity.kt` | 453 | Android VM 管理页面（列表+操作） |

#### 修改文件（4 个）

| 文件路径 | 修改内容 |
|---------|---------|
| `app/src/main/AndroidManifest.xml` | 注册 `AndroidVmActivity` |
| `app/src/main/java/com/termux/app/activities/UtilityCenterActivity.kt` | 添加"Android on Termux"入口卡片（第76行），点击跳转 `AndroidVmActivity`（第275-276行） |
| `app/src/main/res/values/strings.xml` | 添加字符串资源（第564-576行）：`android_on_termux`、`android_vm_title`、`android_vm_empty` 等 |
| `app/src/main/res/values-zh-rCN/strings.xml` | 添加中文翻译对应条目 |

#### 关联文件（1 个，复用）

| 文件路径 | 说明 |
|---------|------|
| `app/src/main/assets/container_run.sh` | Ubuntu 容器启动脚本，Android VM 容器模式复用此脚本的 proot 配置 |

### 关键代码结构

#### AndroidVmConfig 数据模型

```kotlin
data class AndroidVmConfig(
    val id: String,                    // UUID
    val name: String,                  // 显示名称
    val androidVersion: String,       // Android 版本 (7.0-16)
    val apiLevel: Int,                 // API 等级
    val imageType: ImageType,          // AOSP / Google_APIS / PLAY_STORE
    val imageUrl: String,              // 主下载 URL
    val imageUrls: List<String>,       // 多镜像源 fallback 列表
    val imageFileName: String,         // 镜像 zip 文件名
    val imageDir: String,              // 镜像存储目录（含 $HOME 字面量）
    val imageDownloaded: Boolean,       // 是否已下载
    val cpuCores: Int,                 // CPU 核心数
    val memoryMB: Int,                 // 内存 (MB)
    val vncPort: Int,                  // VNC 端口 (5900-5999)
    val enableRoot: Boolean,           // ROOT 开关
    val rootMethod: String,            // "su" 或 "magisk"
    val enableAdb: Boolean,            // ADB 开关
    val shareDir: String,              // 下载临时目录
    val diskSizeGB: Int                // userdata 大小
)
```

#### ImageType 枚举

```kotlin
enum class ImageType {
    AOSP,           // 无 Google 服务
    GOOGLE_APIS,    // 含 Google APIs
    PLAY_STORE      // 含完整 Google Play
}
```

#### 版本预设（Android 7.0-16）

- 支持 API 24-36
- 每个版本包含：推荐 CPU 核心数、内存大小、镜像文件名
- Android 17+ 仅支持 Google APIs 和 Play Store 类型

### 脚本生成逻辑

#### 容器模式脚本（核心修复区域）

**文件**: `AndroidVmConfig.kt` 的 `generateContainerScript()` 方法

##### 最新修复（路径映射问题）

**问题根因**：
1. `imageDir` 使用 `$HOME/storage/shared/android_vm_X` 路径
2. `container_run.sh` 中 `STORAGE_BIND` 将 `/storage/emulated/0` 绑定到 `/root/shared/storage/shared`
3. 与主绑定 `-b /data/data/com.termux/files/home:/root/shared` 产生**绑定挂载冲突**
4. 导致容器内无法通过 `/root/shared/storage/shared/android_vm_X` 访问文件

**修复方案**：

1. **镜像存储路径变更**：
   - 旧路径：`$HOME/storage/shared/android_vm_X`
   - 新路径：`$HOME/android_vm_X`
   - 避免与 STORAGE_BIND 绑定冲突

2. **Kotlin 端统一转义策略**：
   ```kotlin
   val d = '$'  // Bash $ 符号变量
   // 所有 Bash 变量引用用 ${d}VAR 形式，彻底避免 Kotlin 模板转义问题
   sb.append("eval IMAGE_DIR=\"${d}_RAW_IMG_DIR\"\n")
   ```

3. **Bash 端展开 `$HOME`**（而非 Kotlin 静态替换）：
   ```bash
   _RAW_IMG_DIR='$HOME/android_vm_11'  # 单引号包裹，保持字面量
   eval IMAGE_DIR="$_RAW_IMG_DIR"       # Bash 自行展开
   ```

4. **容器路径 Bash 端计算**：
   ```bash
   CONTAINER_IMAGE_DIR=$(echo "$IMAGE_DIR" | sed -e "s|^$TERMUX_HOME|/root/shared|")
   # 直接替换 $HOME 前缀为 /root/shared，无需处理 storage/emulated/0
   ```

5. **VM 脚本位置参数传参**（避免嵌套引号）：
   ```kotlin
   // Termux 层：通过 RUN_SCRIPT 传参
   sb.append("\"${d}RUN_SCRIPT\" \"$containerVmScriptPath\" \"${d}CONTAINER_IMAGE_DIR\" \"...\"")
   // 容器内 VM 脚本：$1=imageDir, $2=userdata, $3-6=配置参数
   ```

6. **数据迁移**：`normalize()` 自动将旧路径 `$HOME/storage/shared/android_vm` 迁移为新路径 `$HOME/android_vm`

##### 容器模式脚本结构

```
generateContainerScript()
├── §1 路径初始化：eval 展开 $HOME，计算容器映射路径
├── §2 检查 Ubuntu 容器 + 容器内安装 QEMU
├── §3 创建目录（用展开后的 Bash 变量）
├── §4 下载镜像（多源 fallback）+ 解压 + 扁平化
├── §5 创建 userdata 磁盘
├── §6 ROOT 处理（su 或 Magisk）
├── §7 安装 ADB 工具
├── §8 写入容器内 VM 脚本（位置参数 $1-$6 传参）
│   ├── 接收参数：IMG_DIR, USERDATA, ADB_P, VNC_D, CPU_C, MEM_M
│   ├── KVM 加速检测
│   ├── 镜像文件完整性检查
│   └── qemu-system-x86_64 启动命令
├── §9 用 RUN_SCRIPT 进入容器执行 VM 脚本
└── §10 ADB 连接（端口转发）
```

#### 原生模式脚本

- 宿主系统 Android < 15 时使用
- 直接在 Termux 原生环境运行 qemu-system-x86_64
- 路径处理相对简单（无容器映射）

### 下载 Fallback 机制

```kotlin
private fun downloadWithFallbackFn(): String {
    // 生成 download_with_fallback Bash 函数
    // 支持从多个 URL 依次尝试下载
    // 超时和失败自动切换下一个源
}
```

每个版本支持的镜像源：
1. Google 官方（主源）
2. 国内镜像源 fallback（腾讯云、阿里云、清华 TUNA 等）
3. 开源社区版本（AndroidGeneric、Bliss OS、LineageOS）

### ROOT 实现

- **Android < 10**：su 二进制注入（从 Magisk v20.4 提取 libmagisk64.so 作为 su）
- **Android 10+**：Magisk APK 安装 + 直接修补 ramdisk.img（容器内完成）

### ADB 集成

- Termux 层：`pkg install android-tools`
- QEMU 层：端口转发 `hostfwd=tcp::5555-:5555`
- 自动连接：`adb connect 127.0.0.1:$adbPort`（最多 10 次重试）

### 数据持久化

- SharedPreferences + Gson 序列化
- Prefs 名称：`android_vms_prefs`，Key：`vms_list`
- `normalize()` 方法处理兼容性（默认值 + 旧路径迁移）

### 开发时间线

#### 迭代历程

1. **初始方案**：ARM64 架构 + qemu-system-aarch64 → 遇到 ARM64 Android 镜像限制（仅 Android 7.0+）
2. **架构切换**：改用 x86_64 + qemu-system-x86_64，支持更完整的 Android 版本
3. **容器方案**：复用 QEMU with VNC 的 Ubuntu 容器，避免 Android 15+ 宿主限制
4. **镜像源优化**：实现多源 fallback + 国内镜像源 + 开源社区备用
5. **ROOT 方案**：旧版 su 注入 + 新版 Magisk 修补 ramdisk
6. **当前修复**：容器内路径映射问题（绑定挂载冲突），改用 HOME 直属路径

#### 已知遗留问题

- 首次下载镜像可能较慢（建议添加下载进度显示）
- 容器模式下 VNC 显示需要手动连接 VNC 客户端
- userdata.img 大小固定，不支持动态调整
- Android 15+ 宿主的 SELinux 可能阻止部分操作

---

## 二、Miuix UI 渲染体系（最完整说明）

### 核心原则：异常兜底而非预检测

**为什么放弃预检测？**
- 最初通过反射预检测 miuix-ui 可用性 → Android 10/11 设备产生大量误报（实际支持 miuix-ui 但被误判为不支持）
- `minSdk=24`，反射检测逻辑不严谨

**现行策略**：
1. `ApiCompat.canLoadMiuixUi()` 默认返回 `true`
2. 直接尝试正常加载 miuix Compose UI
3. **仅当 `setContent{}` 期间实际抛出 `Throwable` 时**，才调用 `markMiuixUiFailed()` 标记失败
4. 进入降级模式（终端模式）

### Fallback 模式完整流程

#### 1. 标记层：FallbackHelper

```kotlin
object FallbackHelper {
    @Volatile
    private var miuixUiFailed: Boolean = false  // 进程内 volatile，重启自动重置

    fun handleMiuixRenderFailure(activity: ComponentActivity, throwable: Throwable) {
        markMiuixUiFailed()                              // 1. 标记失败状态
        notifyUserFallback(activity)                     // 2. 通知用户
        transitionToTerminalMode(activity)               // 3. 切换到终端模式
    }

    private fun notifyUserFallback(activity: Context) {
        // 弹出 Toast：
        // "启动时出现错误，可能是您的Android版本问题。已降级运行并锁定为终端模式。"
    }
}
```

#### 2. Activity 层 try-catch 包裹

**MainActivity / OobeActivity**：
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    try {
        setContent {
            // 正常 miuix Compose UI
        }
    } catch (t: Throwable) {
        FallbackHelper.handleMiuixRenderFailure(this, t)
    }
}
```

**SplashActivity**：移除预检测逻辑，直接走 OOBE → MainActivity 流程（让 setContent 的实际渲染结果决定）

#### 3. TermuxActivity 降级行为

当处于 fallback 模式时，TermuxActivity：
- 跳过 miuix toolbar 初始化
- 按返回键直接返回首页（不弹 Fragment 管理）
- 自动创建终端会话（无需用户操作）

---

## 三、集成工具硬约束（Termux:API / Boot / Styling / Tasker / Widget）

### 通用开关规则

| 规则 | 说明 |
|------|------|
| 开关联动 | 所有集成工具的开关打开时必须激活对应组件，关闭时必须停用 |
| 官方插件替代 | 若检测到官方独立插件 APK 已安装，对应集成插件开关变为**不可选**，描述改为「已由官方插件替代」 |
| 卸载恢复 | 官方独立插件 APK 卸载后，集成开关必须自动恢复为可选状态 |
| 冲突提示 | 检测到官方独立插件时，提示用户「不建议再安装对应插件，请使用系统内置的插件」 |
| 中文化 | 所有集成工具必须 100% 中文覆盖（`values-zh-rCN/strings.xml`） |

### Termux:Styling 特殊约束

- 合并包名必须使用 **`com.termux`**（而非独立时的 `com.termux.styling`）

### Termux:Boot 特殊约束

- Boot 组件（`BootActivity` / `BootReceiver` / `BootJobService`）在 `AndroidManifest.xml` 中声明时必须带 **`android:enabled="false"`**
- 启用/禁用由工具开关动态控制

### Termux:Widget 特殊约束

```kotlin
// TermuxCreateShortcutActivity 必须继承 AppCompatActivity
class TermuxCreateShortcutActivity : AppCompatActivity() {
    // 必须使用 getSupportActionBar() 并做 null 检查，避免 NPE
    val actionBar = supportActionBar
    actionBar?.setDisplayHomeAsUpEnabled(true)
}

// TermuxWidgetActivity 必须使用动态包名
fun setComponentEnabled(context: Context, enabled: Boolean) {
    // ❌ 错误：硬编码 com.termux.widget
    // ✅ 正确：context.packageName
    val component = ComponentName(context.packageName, clazz.name)
    // ...
}
```

### Termux:Tasker 特殊约束

```kotlin
// EditConfigurationActivity：pluginHostPackage 为 null 或等于宿主包名时跳过权限检查
fun checkPermissions() {
    val pluginHost = intent.getStringExtra(EXTRA_PLUGIN_HOST_PACKAGE)
    if (pluginHost == null || pluginHost == packageName) {
        return  // 跳过
    }
    // 否则执行正常权限检查
}

// FireReceiver：用 context.packageName 代替硬编码 com.termux
fun onReceive(context: Context, intent: Intent) {
    // ❌ 错误：ComponentName("com.termux", "com.termux.app.TermuxService")
    // ✅ 正确：
    val serviceIntent = Intent().apply {
        component = ComponentName(context.packageName, "com.termux.app.TermuxService")
    }
    context.startService(serviceIntent)
}

// TermuxAppUtils：优先用当前 context 的包名判断 Termux 安装状态
fun isTermuxInstalled(context: Context): Boolean {
    return try {
        context.packageManager.getPackageInfo(context.packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}
```

### Termux:API 特殊约束

```kotlin
// 1. 设置组件类名时必须用宿主包名动态拼装
fun <T> ComponentName(context: Context, cls: Class<T>) {
    return ComponentName(context.packageName, cls.name)  // 不能硬编码
}

// 2. 必须启动 LocalServerSocket 监听在 `com.termux.api`
//    用于处理命令执行，防止终端卡住
fun startApiServer() {
    val serverSocket = LocalServerSocket("com.termux.api")
    // ... accept 连接并执行命令
}
```

---

## 四、UI/UX 完整设计规范

### 全局主题规范

| 项目 | 要求 |
|------|------|
| 语言 | 100% 中文覆盖 |
| TopBar 文字 | 必须居中 |
| TopBar 状态栏 padding 颜色 | 与 TopBar 背景色一致 |
| 状态栏文字颜色 | 浅色模式：黑色；深色模式：白色 |
| 页面过渡动画 | 主/子页面：优雅淡入淡出叠加；首页按钮切换：左右滑动；支持 predictive back（小动画预览→确认后完整动画） |
| 首页水平滑动 | 终端 ↔ 文件 ↔ 远程；远程页 VNC 启用时：VNC ↔ SSH ↔ 资源；VNC ← 文件 |
| 全局页面 side padding | 添加，防止边缘按钮/TabRow 误触 |
| 全局页面 bottom padding | 必须增加，防止内容被底部导航栏遮挡 |
| 按钮点击效果裁剪 | 裁剪圆角与卡片圆角一致 |

### Material3 Tab 规范

- TabRow / Tab 的选中颜色：**黑色或深灰色**（禁止使用紫色或系统 Monet 色）

### 资源页（Resource Page）规范

| 项目 | 要求 |
|------|------|
| 执行后刷新 | 会话执行后资源页实时刷新 |
| 说明按钮字体颜色 | 跟随深色/浅色模式切换 |
| 执行按钮位置 | 右下角 |
| 说明按钮位置 | 执行按钮左侧（旁边） |
| 执行按钮（除新建会话）背景 | 浅色：灰色；深色：深灰 |
| 执行按钮文字/图标颜色 | 浅色：黑色；深色：白色 |
| ">" 按钮 | **移除**（与执行按钮重复） |
| 分隔线颜色 | 深灰色 |
| 卡片背景（浅色） | `#FAFAFA`（比 #F5F5F5 稍白，确保卡片边缘可见） |
| 执行按钮文本 | 改为「复制指令」，配复制图标（与其他复制图标颜色逻辑分离） |
| 执行按钮底部 | 蓝色背景 |
| 朱雀面板 | 允许**执行**（不是复制命令），位置放靠前 |
| tmux 强制 | **取消**使用 tmux 的强制要求 |

### 终端页（Terminal Page）规范

| 项目 | 要求 |
|------|------|
| 搜索 | 用**搜索框**代替搜索按钮 |
| 搜索框激活时 | 隐藏下方所有卡片（用屏幕外定位或遮罩覆盖，不要 remove，保持可搜索） |
| 搜索空输入占位 | 「输入相关标题来搜索」 |
| 搜索无结果提示 | 「未找到」 |
| 刷新指示器 top padding | 12dp（与搜索栏分隔） |
| 服务状态卡图标 | 使用 miuix 图标（对勾/提示/错误） |
| 服务状态卡文字颜色 | 浅色：黑色；深色：白色 |
| 服务状态卡背景 | 跟随深色/浅色模式 |
| 服务状态卡图标位置 | 卡片右下角，64dp 圆形背景，一半露出卡片外 |
| Switch 开关 | **必须使用 miuix Switch**（非 Material3） |
| Switch 激活颜色 | 与资源页执行按钮蓝色一致 |

### 远程页（Remote Page）规范

| 项目 | 要求 |
|------|------|
| Sticky Header | 支持 sticky header 吸顶效果 |
| VNC/SSH 管理页卡片 | 顶部圆角 |
| 搜索框 | 必须显示（与终端页样式统一） |
| 搜索框激活时 | 隐藏下方所有卡片（同终端页逻辑） |
| TopBar 水平滑动手势 | 向右滑→资源页；向左滑→文件页 |

### VNC 设置页规范

| 项目 | 要求 |
|------|------|
| 输入选项键盘图标颜色（浅色） | 黑色（与其他页面图标一致） |
| 输入选项键盘图标颜色（深色） | 白色（单独适配） |

### 会话页（Session Page）规范

| 项目 | 要求 |
|------|------|
| 键盘图标颜色 | 跟随会话页其他按钮颜色逻辑 |
| 按钮触摸反馈 | 圆形水波纹 |

### 首页（Home Page）规范

| 项目 | 要求 |
|------|------|
| 提示卡片关闭 | **必须用户点击关闭按钮**才关闭（禁止自动关闭） |
| 提示卡片样式 | 参考 SuperSPM 的 GPS 卡片（灰色 + 灰色提示符号） |
| 欢迎+提示卡图标 | 使用 miuix 图标（对勾/提示/错误） |
| 欢迎+提示卡文字颜色 | 浅色：黑；深色：白 |
| 欢迎+提示卡背景 | 跟随模式 |
| 欢迎+提示卡图标位置 | 卡片右下角，64dp 圆形背景，半露 |

### 设置页（Settings Page）规范

| 项目 | 要求 |
|------|------|
| 打开下一页/对话框的元素 | **全部使用 ArrowPreference 套件** |
| Switch 开关 | **必须使用 miuix Switch**（非 Material3） |
| Switch 激活颜色 | 与资源页执行按钮蓝色一致 |
| Tasker 页面标题 | 改为「Tasker 设置」 |
| 快捷方式与微件页面标题 | 改为「快捷方式与微件设置」 |
| 快捷方式与微件按钮文本 | 100% 中文 |

### 文件管理页（File Page）规范

| 项目 | 要求 |
|------|------|
| 卡片背景（浅色） | 白色 |
| 卡片背景（深色） | 深灰色 |
| 警告卡片背景（浅色） | `#FFF9C4`（黄色） |
| 警告卡片背景（深色） | `#3D3514` |
| 警告卡片图标（浅色） | 琥珀色 `#FF8F00` |
| 警告卡片图标（深色） | `#FFB300` |
| 文件夹图标背景（浅色） | 实色蓝 `#3F8DD6` |
| 文件夹图标背景（深色） | `#1A5A96` |
| 文件项背景 | 实色灰 |
| BottomSheet 标题 | **空字符串** |
| BottomSheet 详情面板 | 支持深色模式 |
| BottomSheet 详情信息顺序 | 路径 → 扩展名 → 类型 → 大小 → 权限 → 修改时间 |
| BottomSheet 底部按钮左 | 「复制路径」（灰底黑字/深色相反） |
| BottomSheet 底部按钮右 | 「执行脚本」（主题色底 + 终端图标 + 白字）；**仅 .sh 可执行文件显示** |
| 其他操作 | 保留：查看内容(cat)、编辑(vi)、其他方式打开等原有命令 |
| 扩展名识别 | 支持 1500+ 扩展名 + 双扩展名（如 .tar.gz） |
| 图标分类 | 图片/音频/视频/文档/代码/压缩/数据库/证书/字体/可执行 |
| OverlayBottomSheet 底部边距 | 足够大，避免被 Android 系统手势区域遮挡 |

#### 文件页辅助函数

```kotlin
fun getFileExtension(file: File): String           // 含双扩展名识别
fun getCanonicalExtension(ext: String): String     // 归一化（.tar.gz → tar.gz）
fun getFileTypeDescription(ext: String): String    // 返回中文类型描述
// 配套 EXTENSION_MAP：1500+ 键值对，按分类组织
```

### Dashboard 页规范

| 项目 | 要求 |
|------|------|
| 计时器 | UI 回收后必须持久化保存（恢复时继续计时） |
| Profile 卡选中态 | UI 回收后保持选中状态 |
| 网络信息 | 实时刷新 |
| 网络信息卡内容 | 显示公网 IP + 对应国家名称 |
| 按钮位置 | 向上移动，避免被底部导航栏遮挡 |
| 底部导航栏 | 不得遮挡按钮（按钮上移） |

### 代理页（Proxy Page）规范

| 项目 | 要求 |
|------|------|
| 延迟测试按钮 | 向上移动，不被底部导航栏遮挡 |

### SNI Bypass 页规范

| 项目 | 要求 |
|------|------|
| 全局 SNI Bypass 按钮 | **仅当模块未安装时**可点击；已安装则不可点击 |

### QEMU 虚拟机页规范

| 项目 | 要求 |
|------|------|
| 右下角添加按钮位置 | Box 手动定位 + `Modifier.align(Alignment.BottomEnd)` + `padding(bottom = 72.dp)`（避免被导航栏遮挡）；位置需调高 |

### 第三方资源中心按钮规范

- 布局：图标在上一行，文字在下一行，两者都居中

### 初始化失败错误

- 错误文本颜色：黑色（提高可见度）

---

## 五、QEMU 虚拟机完整硬约束

### 基础配置约束

| 规则 | 要求 |
|------|------|
| 运行中数量统计命令 | `pgrep -c -x qemu-system-x86_64`（精确匹配，避免包含脚本/包装进程） |
| 运行中数量统计（正则技巧） | `[q]emu-system-x86_64` 避免匹配自身 grep 进程 |
| Termux 安装包名 | `qemu-system-x86-64`（连字符） |
| 二进制命令名 | `qemu-system-x86_64`（下划线） |
| 共享文件夹实现（9p virtio） | `-fsdev local,security_model=mapped-file,id=fsdev_shared,path=[目录]` + `-device virtio-9p-pci,id=fs0,fsdev=fsdev_shared,mount_tag=hostshare` |
| 缺失字段默认值 | `machineType="q35"`，`diskInterface="ide"`，`newDiskFormat="qcow2"` |
| 旧配置反序列化 | 必须做字段迁移，null 值用默认值填充，**禁止 NPE** |
| QEMU 计数范围 | 同时覆盖 Termux 原生和容器内进程 |

### 创建/编辑页配置

- 必须支持选择**硬盘连接方法**（virtio / ide 等）
- 必须支持选择**虚拟 PC 类型**（acpi PC / q35 等）
- 必须智能识别 ISO 文件包含的操作系统（Windows 系列 / Ubuntu / Debian 等）
- 未填写虚拟机名称时，**自动以 ISO 识别的系统名作为默认名称**
- 对 ISO 识别的系统，**自动应用推荐配置**（用户可修改）

### Android 版本分流

| 宿主 Android 版本 | 运行模式 |
|------------------|---------|
| ≤ 14 | Termux 原生环境直接运行 |
| 15+ | **proot 容器**内运行 |
| 检测到 17 | 自动切换为 proot 容器模式 |

#### Android 16 → 17 升级用户适配

- 原有 Debian 虚拟机文件通过 **bind mount** 复用，**无需迁移**

#### Debian QEMU 已有镜像处理流程

1. 检测到已有镜像 → **先询问用户是否重装**
2. 选择「否」→ 再询问「是否更新启动脚本」
3. 更新启动脚本：保留原有虚拟机数据，仅重新生成容器适配的启动脚本和配置

### 启动脚本分流

```
generateScript()
├── Android <= 14 → generateNativeScript()      // 原生环境
└── Android >= 15 → generateContainerScript()   // proot 容器
    ├── 自动检查/创建容器
    ├── 安装 QEMU 依赖
    ├── bind mount 共享目录
    └── aarch64 架构：从 qemu-efi-aarch64 包提取 UEFI 固件
```

### QEMU 音频后端（四模式）

```kotlin
enum class QemuAudioMode {
    DISABLED,           // 关闭
    VNC_RFB,            // 优先 VNC RFB 扩展，容器不支持时自动回退 PA（推荐）
    PA_FOLLOW_SCREEN,   // PulseAudio 跟随 VNC 页面生命周期（推荐）
    PA_PERSIST          // PulseAudio 持续播放
}
```

#### 开关文案差异

| 模式 | 标题 | 说明 |
|------|------|------|
| 容器模式 | 声音 | 「优先通过 VNC RFB 扩展直接传递虚拟机声音；容器内 QEMU 不支持时自动回退到 PulseAudio 服务」 |
| 原生模式 | 声音 | 「通过 VNC RFB 扩展直接传递虚拟机声音，使用 HDA 音频设备」 |

#### 通用规则

- 容器模式音频开关**不禁用**，用户自由开/关
- 配置写入标记文件：`$HOME/.qemu_vm_audio_${id}.env`（启动脚本读取）
- 旧配置 `hasSound=true` 自动迁移为 `VNC_RFB` 模式
- UI 用 RadioButtonPreference 四选一
- 切换模式提示用户**重启虚拟机**生效
- 配置保存在 SharedPreferences

#### 容器模式音频逻辑

```
容器模式音频启动
├── VNC_RFB 模式
│   ├── 先尝试安装 VNC audio driver
│   ├── 成功 → 使用 -audiodev vnc 参数（与原生相同）
│   └── 失败 → 自动降级到 PulseAudio
├── PA_FOLLOW_SCREEN / PA_PERSIST 模式
│   ├── 直接配置 PulseAudio（跳过 VNC 扩展检测）
│   ├── 检测容器内是否有 pulseaudio，没有则自动安装
│   ├── 安装失败 → 提示手动安装或关闭声音开关
│   ├── 启动守护进程：pulseaudio --start
│   └── QEMU 参数：-audiodev pa,id=pa_audio,server=tcp:127.0.0.1:4713
└── 标记文件写入音频模式 + 端口信息
```

#### 原生模式音频逻辑

```
原生模式音频启动
├── VNC_RFB 模式
│   ├── QEMU 参数：-audiodev vnc,id=vnc_audio,server
│   └──         -device hda-output,audiodev=vnc_audio
│   └── VNC 命令包含 audiodev=vnc_audio
└── PA 模式
    ├── pkg install pulseaudio
    ├── pulseaudio --start
    ├── pactl load-module module-null-sink
    ├── pactl load-module module-simple-protocol-tcp port=4714
    ├── 设置环境变量 PULSE_SERVER
    └── QEMU 参数：-audio pa,model=hda
```

#### Android 端 PulseAudio 播放器（PulseAudioPlayer.kt）

```kotlin
object PulseAudioPlayer {
    // 协程单例
    suspend fun start(host: String = "127.0.0.1", port: Int = 4714)
    suspend fun stop()
    fun isPlaying(): Boolean

    // 实现细节：
    // - AudioTrack STREAM_MUSIC 通道
    // - 连接 tcp://127.0.0.1:4714 接收 PCM 流
    // - 处理连接中断 + 自动重连
    // - 支持音量控制 / 静音
    // - 支持不同采样率 / 声道配置
    // - 支持均衡器 / 音效调节
    // - 支持音频路由（扬声器/耳机）
    // - 支持录制控制（开始/停止/文件保存）
    // - 后台播放显示通知 + 暂停/继续
    // - 应用退出时彻底释放资源
    // - 低延迟模式
    // - 加密连接传输
    // - 自适应缓冲策略
    // - 播放失败显示中文友好错误
}
```

#### VNC 页面生命周期与 PA_FOLLOW_SCREEN

```kotlin
// VncScreen / VncActivity
fun onStart() {
    super.onStart()
    if (effectiveAudioMode == PA_FOLLOW_SCREEN) {
        PulseAudioPlayer.start()  // VNC 可见时开始播放
    }
}
fun onStop() {
    super.onStop()
    if (effectiveAudioMode == PA_FOLLOW_SCREEN) {
        PulseAudioPlayer.stop()   // VNC 不可见时停止
    }
}
// effectiveAudioMode 获取：
// 1. 通过 EXTRA_QEMU_AUDIO_MODE extra 传递
// 2. 或通过 VM ID 匹配 QemuVmManager.loadVms() + 音频模式标记文件
// 3. 自动重连和状态保存时必须保留/透传
```

---

## 六、构建与打包规范

### 编译访问路径

- **必须通过 `D:\KiTerminal-UX` 硬链接访问项目**（避免路径含空格导致 NDK 编译失败）

### APK 生成规则

| 类型 | 输出 |
|------|------|
| Debug（默认） | 仅输出 **universal APK**（单架构通用包） |
| Release | 输出**所有架构特定 APK**（arm64-v8a / armeabi-v7a / x86_64 / x86 等） |
| 构建输出日志 | **禁止 `-q` 参数**，必须显示完整构建进度 |
| 体积优化 | 需要减重（APK 体积优化） |

### Release 构建 JVM 内存

`gradle.properties` 中 JVM 内存从 `-Xmx2048M` → **`-Xmx4096M`**（避免 OOM）

### 版本号升级

同步更新 `build.gradle` 中的：
- `versionCode`（整数递增）
- `versionName`（语义化版本字符串）

### Native 库打包

- `AndroidManifest.xml` **不指定** `android:extractNativeLibs`
- 由 `build.gradle` 的 `packagingOptions.jniLibs.useLegacyPackaging = true` 统一控制
- 两处设置必须**保持一致**（不能矛盾）

### 代码编码

- **所有代码文件禁止有 BOM 字符**（会导致 Kotlin 编译器解析失败，并级联影响同模块其他文件编译）

---

## 七、经验教训（Lessons Learned）

### 1. Miuix UI 检测
- ❌ 通过反射预检测 → Android 10/11 设备误报率极高（minSdk=24）
- ✅ 异常兜底：直接渲染，捕获 Throwable 后才降级

### 2. QEMU 进程统计
- ❌ `pgrep qemu` 会匹配到包装脚本和自身 grep 进程
- ✅ `pgrep -c -x qemu-system-x86_64` 精确匹配，或正则技巧 `[q]emu-system-x86_64`

### 3. 容器路径映射
- ❌ 镜像放 `$HOME/storage/shared/` → 与 `container_run.sh` 的 STORAGE_BIND 冲突
- ✅ 镜像放 `$HOME/` 直属目录，容器内用 `sed` 替换 `$TERMUX_HOME` → `/root/shared`

### 4. Kotlin 模板与 Bash `$` 冲突
- ❌ `$VAR` 在 Kotlin 字符串模板中被解析
- ✅ 定义 `val d = '$'`，统一用 `${d}VAR` 形式

### 5. Bash 变量展开时机
- ❌ Kotlin 端静态替换 `$HOME` → 多环境不一致
- ✅ Bash 端用单引号字面量 + `eval` 运行时展开

### 6. 嵌套引号传递参数
- ❌ 多层引号嵌套导致 Bash 解析错误
- ✅ 位置参数 `$1-$6` 传参，脚本内直接引用位置变量
