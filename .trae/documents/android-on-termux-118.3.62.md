# 118.3.62 新功能：Android on Termux 实现方案

## Context

用户要求在 118.3.62 版本新增"Android on Termux"功能：使用 `qemu-system-aarch64` 运行 ARM64 Android 系统（7.0-16），从预设列表选择版本，支持推荐配置（可修改）、ROOT 开关（旧版 su 注入 + 新版 Magisk）、ADB 集成。基本逻辑复用 QEMU with VNC（Android 15+ 宿主也走 proot 容器），但没有 ISO/自选镜像功能，改为从我们提供的版本列表下载镜像。

**用户确认的关键决策：**
- 镜像来源：综合 Google 官方模拟器镜像 + AndroidGeneric/Bliss OS，优先官方；不支持 ARM64 的版本（2.3-6.x）省略
- ROOT：旧版 su 注入 + 新版 Magisk
- 页面架构：新建 AndroidVmActivity 独立页面，与 QEMU with VNC 完全分离

## 现有架构参考（复用模式）

- **数据模型 + 脚本生成 + 持久化**：[QemuVmConfig.kt](file:///D:/KiTerminal-UX/app/src/main/java/com/termux/app/compose/QemuVmConfig.kt) — `QemuVmConfig` data class + `generateScript()` + `QemuVmManager` (SharedPreferences + Gson)
- **VM 列表页**：[QemuVmActivity.kt](file:///D:/KiTerminal-UX/app/src/main/java/com/termux/app/activities/QemuVmActivity.kt) — `QemuVmScreen` + `VmCard` + `startTermuxSession()` 启动脚本
- **配置 Sheet UI**：[QemuOnVncSheet.kt](file:///D:/KiTerminal-UX/app/src/main/java/com/termux/app/compose/QemuOnVncSheet.kt) — `OverlayBottomSheet` + `TextField`/`SwitchPreference` 组件
- **容器 proot 逻辑**：[QemuVmConfig.kt:15](file:///D:/KiTerminal-UX/app/src/main/java/com/termux/app/compose/QemuVmConfig.kt#L15) — `shouldUseQemuInContainer()` (SDK>=35 走容器)
- **资源页入口**：[UtilityCenterActivity.kt:64-113](file:///D:/KiTerminal-UX/app/src/main/java/com/termux/app/activities/UtilityCenterActivity.kt#L64-L113) — `ResourceItem` 列表，`type="qemu_on_vnc"` 跳转 QemuVmActivity

## 实现方案

### 1. 新建 `AndroidVmConfig.kt`（compose 包）

**核心数据结构：**

```kotlin
// Android 版本预设信息
data class AndroidVersionInfo(
    val version: String,          // "7.0", "10", "16"
    val apiLevel: Int,            // 24, 29, 36
    val imageUrl: String,         // 下载 URL（Google 仓库 / 社区镜像）
    val imageFileName: String,    // 本地文件名
    val recommendedCpu: Int,      // 推荐核心数
    val recommendedMem: Int,      // 推荐内存 MB
    val rootMethod: String,       // "su" | "magisk"
    val description: String       // "Android 7.0 (牛轧糖)"
)

// VM 配置
data class AndroidVmConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val androidVersion: String,
    val apiLevel: Int,
    val imageUrl: String,
    val imagePath: String,              // 本地镜像路径
    val imageDownloaded: Boolean = false,
    val cpuCores: Int = 2,
    val memoryMB: Int = 2048,
    val vncPort: Int = 5900,
    val enableRoot: Boolean = false,
    val rootMethod: String = "magisk",
    val enableAdb: Boolean = true,
    val shareDir: String = "\$HOME/storage/shared/android_vm_share",
    val diskSizeGB: Int = 8
)
```

**版本列表**（ARM64 only，7.0 起）：

| 版本 | API | ROOT 方式 | 推荐配置 | 镜像来源 |
|------|-----|-----------|----------|----------|
| 7.0 | 24 | su | 2核/1.5G | Google arm64-v8a sys-img |
| 8.0 | 26 | su | 2核/2G | Google arm64-v8a sys-img |
| 9 | 28 | su | 2核/2G | Google arm64-v8a sys-img |
| 10 | 29 | Magisk | 3核/2G | Google arm64-v8a sys-img |
| 11 | 30 | Magisk | 3核/3G | Google arm64-v8a sys-img |
| 12 | 31 | Magisk | 4核/3G | Google arm64-v8a sys-img |
| 13 | 33 | Magisk | 4核/4G | Google arm64-v8a sys-img |
| 14 | 34 | Magisk | 4核/4G | Google arm64-v8a sys-img |
| 15 | 35 | Magisk | 4核/4G | Google arm64-v8a sys-img |
| 16 | 36 | Magisk | 4核/4G | Google arm64-v8a sys-img |

**ROOT 阈值**：Android <10 (API<29) = su 注入；Android 10+ (API>=29) = Magisk

**`generateScript()` 脚本流程**（复用 `shouldUseQemuInContainer()` 判断原生/容器）：

```
1. 安装 qemu-system-aarch64（pkg install 或容器内 apt install）
2. termux-setup-storage + mkdir 共享目录
3. 下载镜像（若 imageDownloaded=false）：
   - wget -c $imageUrl -O $imagePath --show-progress
   - 解压（Google sys-img 是 zip，含 system.img/kernel/ramdisk）
4. 创建 userdata 磁盘：qemu-img create -f qcow2 userdata.qcow2 ${diskSizeGB}G
5. ROOT 处理（若 enableRoot）：
   - su 方式：挂载 system.img → 推送 su 二进制到 /system/xbin/ → 设置权限 → 卸载
   - Magisk 方式：下载 Magisk.zip → 用 magiskboot 修补 ramdisk.img → 替换
6. ADB 安装（若 enableAdb）：pkg install android-tools
7. 启动 qemu-system-aarch64：
   qemu-system-aarch64 -M virt -cpu cortex-a72 -m $memoryMB -smp $cpuCores \
     -kernel kernel-ranchu -initrd ramdisk.img \
     -append "console=ttyAMA0 androidboot.hardware=ranchu" \
     -drive file=system.img,if=virtio,readonly=on \
     -drive file=userdata.qcow2,if=virtio \
     -netdev user,id=net0,hostfwd=tcp::5555-:5555 \
     -vnc :$vncDisplay
8. 等待 VNC 就绪 + ADB 连接：adb connect 127.0.0.1:5555
```

**`AndroidVmManager`** — 复用 QemuVmManager 模式：SharedPreferences `android_vms_prefs` + Gson 序列化 + loadVms/saveVm/deleteVm。

### 2. 新建 `AndroidVmActivity.kt`（activities 包）

复用 [QemuVmActivity.kt](file:///D:/KiTerminal-UX/app/src/main/java/com/termux/app/activities/QemuVmActivity.kt) 模式：
- `onCreate` → setContent → `AndroidVmScreen()`
- `startTermuxSession(name, command)` — 同 QemuVmActivity
- `AndroidVmScreen`：
  - TopAppBar "Android 虚拟机"
  - 空状态（Android 图标 + "没有 Android 虚拟机"）
  - VM 卡片列表：名称、版本、配置摘要、下载状态、启动/编辑/删除按钮
  - 下载状态显示：未下载/下载中(进度)/已下载
  - "创建" 按钮 → 弹出 `AndroidVmCreateSheet`
  - "ADB 工具" 按钮（已启动且 enableAdb）→ 打开终端并预置 `adb connect 127.0.0.1:5555`

### 3. 新建 `AndroidVmCreateSheet.kt`（compose 包）

复用 [QemuOnVncSheet.kt](file:///D:/KiTerminal-UX/app/src/main/java/com/termux/app/compose/QemuOnVncSheet.kt) 的 `OverlayBottomSheet` 模式：
- **版本选择**：列表/下拉选择 Android 版本（从 `AndroidVersionInfo` 列表）
- **推荐配置自动填充**：选择版本后自动填入推荐 CPU/内存，用户可修改
  - TextField: 名称
  - TextField: CPU 核心数
  - TextField: 内存 MB
  - TextField: 磁盘大小 GB
  - TextField: VNC 端口
- **ROOT 开关**：SwitchPreference（默认根据版本决定 rootMethod，显示"su 注入"或"Magisk"）
- **ADB 开关**：SwitchPreference（默认开）
- **保存**：创建 AndroidVmConfig → saveVm → 触发下载脚本

### 4. 修改 `UtilityCenterActivity.kt`

在 `utilityItems` 列表中新增资源项（放在 QEMU with VNC 之后）：

```kotlin
ResourceItem(
    title = "Android on Termux",
    description = "使用 qemu-system-aarch64 运行 ARM64 Android 系统（7.0-16），支持 ROOT 和 ADB",
    url = "",
    scriptUrl = "",
    iconRes = R.drawable.ic_android,  // 需要确认是否有此图标，否则用 ic_computer
    type = "android_on_termux",
    requiredFeature = ApiCompat.Feature.QEMU_VM_MANAGER
)
```

在 `onToggleExpand` 中新增分支：
```kotlin
if (item.type == "android_on_termux") {
    val intent = Intent(context, AndroidVmActivity::class.java)
    context.startActivity(intent)
}
```

### 5. 修改 `AndroidManifest.xml`

注册新 Activity：
```xml
<activity android:name=".activities.AndroidVmActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout" />
```

### 6. 修改 `ApiCompat.kt`

新增 Feature 枚举（或复用 QEMU_VM_MANAGER）：
```kotlin
ANDROID_ON_TERMUX(31, Page.RESOURCES, "Android 12", "Android 虚拟机"),
```

### 7. 字符串资源

在 `strings.xml` / `strings-zh-rCN.xml` 新增：
- `android_on_termux` / "Android on Termux"
- `android_vm_title` / "Android 虚拟机"
- `android_vm_empty` / "没有 Android 虚拟机"
- `android_vm_create` / "创建 Android 虚拟机"
- `android_vm_download_pending` / "等待下载"
- `android_vm_downloading` / "下载中"
- `android_vm_downloaded` / "已下载"
- `android_vm_root` / "ROOT"
- `android_vm_adb` / "ADB"
- `android_vm_select_version` / "选择 Android 版本"
- `android_vm_adb_tool` / "ADB 工具"

### 8. 版本号

`build.gradle`：versionCode 1051→1052，versionName "118.3.61"→"118.3.62"

## 技术挑战与应对

1. **Google 模拟器镜像在标准 QEMU 启动**：sys-img 含 `kernel-ranchu`（为 Android 模拟器定制，期望 goldfish 设备）。方案：使用 `-M virt` + `kernel-ranchu` + `androidboot.hardware=ranchu` 参数，ranchu 内核对 virt 机器有兼容支持；若不兼容则回退到社区 GSI 镜像 + 主线 arm64 内核。

2. **Magisk 在 QEMU 中 ROOT**：QEMU 直接启动 kernel+ramdisk，无传统 boot 分区。方案：Magisk 的 `magiskboot` 工具直接修补 `ramdisk.img`，注入 magisk init；脚本在下载解压后、启动前自动执行修补。

3. **su 注入（旧版）**：system.img 是 ext4 只读镜像。方案：`mount -o loop, rw system.img /mnt` → 下载对应架构 su 二进制 → `cp su /mnt/system/xbin/` → `chmod 6755` → `umount`。在容器内执行（需要 root 权限，proot 容器提供）。

4. **ADB 端口转发**：QEMU `-netdev user,hostfwd=tcp::5555-:5555` 将宿主 5555 转发到虚拟机 adbd。VM 内 adbd 需在 5555 监听（Android 默认）。脚本启动后自动 `adb connect 127.0.0.1:5555`。

## 文件清单

| 操作 | 文件 | 说明 |
|------|------|------|
| 新建 | `app/.../compose/AndroidVmConfig.kt` | 数据模型 + 版本列表 + 脚本生成 + AndroidVmManager |
| 新建 | `app/.../activities/AndroidVmActivity.kt` | VM 列表页 Activity |
| 新建 | `app/.../compose/AndroidVmCreateSheet.kt` | 创建/编辑配置 Sheet |
| 修改 | `app/.../activities/UtilityCenterActivity.kt` | 新增资源入口 |
| 修改 | `app/.../AndroidManifest.xml` | 注册 Activity |
| 修改 | `app/.../compose/ApiCompat.kt` | 新增 Feature |
| 修改 | `app/src/main/res/values/strings.xml` | 英文字符串 |
| 修改 | `app/src/main/res/values-zh-rCN/strings.xml` | 中文字符串 |
| 修改 | `app/build.gradle` | 版本号 → 118.3.62 |

## 验证方式

1. **编译验证**：`./gradlew.bat :app:compileDebugKotlin :app:compileDebugJavaWithJavac` 通过
2. **功能验证**：
   - 资源页显示"Android on Termux"入口卡片
   - 点击进入 AndroidVmActivity，显示空状态
   - 点击创建 → 选择版本 → 配置自动填充 → 保存
   - VM 卡片显示下载状态，下载脚本在终端执行
   - 下载完成后点击启动 → QEMU 启动 → VNC 可连接
   - ADB 工具按钮可连接到虚拟机
3. **低版本限制**：Android 11 及以下设备，入口变灰 + 弹窗提示
