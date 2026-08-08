# Termux Ultra — 近期工作上下文（供 Trae IDE 读取）

> 本文件用于让新会话/工具快速了解近期完成的编译与修改。最后更新：**2026-08-08（夜间）**。

## 项目概况

- **项目名**：Termux Ultra（非 KiTerminal UX）
- **基础版本**：Termux 0.118.3
- **当前版本号**：`118.3.60`（versionCode 1050）
- **包名**：`com.termux`
- **构建方式**：通过硬链接 `D:\KiTerminal-UX`（无空格路径）构建，避免 NDK 因路径空格编译失败；不加 `-q` 以保留进度；debug 默认只产 universal APK，release 产各架构 APK。
- **JVM 内存**：Release 构建前需把 `gradle.properties` 的 JVM 内存从 `-Xmx2048M` → `-Xmx4096M`，避免 R8/D8 OOM。
- **语言**：100% 中文覆盖（同时支持英文），所有新增内容禁止硬编码字符串。

---

## 近期完成的修改

### 1. 集成 Termux 官方工具（API / Boot / Styling / Tasker / Widget）

将 5 个 Termux 官方附加应用整合为库模块，集成进主应用。默认全部关闭，设置页开关启用；Boot 等清单组件默认 `android:enabled="false"`，由开关运行时启用。

核心文件：
- [IntegratedTools.kt](app/src/main/java/com/termux/app/compose/IntegratedTools.kt) — `componentsFor(tool)` + `applyComponentState(context, tool, enabled)` 用 `PackageManager.setComponentEnabledSetting` 批量切组件；`isStandaloneInstalled` 检测官方独立 APK，`showStandaloneConflictPrompt` 冲突弹窗。
- [SettingsScreen.kt](app/src/main/java/com/termux/app/compose/SettingsScreen.kt) — "集成工具"开关卡片 + "工具配置" ArrowPreference 分区（Styling/Tasker/Widget 打开对应 Activity；API/Boot 弹说明对话框）；开关独立 APK 安装时变灰 + 「已由官方插件替代」summary + 点击行弹冲突提示；`IntegratedToolSwitch` 扩展 `enabled: Boolean` + `onDisabledClick`。

已解决的冲突：多模块 AppTheme → TermuxTaskerTheme 重命名；库 R.string 缺 termux-shared → 引用 `com.termux.shared.R`；`Theme.BaseActivity` 不存在 → 用同期版本工具；`ReportActivity` 重复声明 → 从 api/widget 清单移除；脱糖 → `coreLibraryDesugaringEnabled true` + `desugar_jdk_libs:1.1.5`。

### 2. termux-api 命令卡死修复 & NotificationAPI 包名硬编码修复

- [TermuxApplication.java](app/src/main/java/com/termux/app/TermuxApplication.java) — `onCreate()` 末尾启动 `TermuxAPI-Listener` 线程：`LocalServerSocket("com.termux.api")` 循环 accept，按协议 (method_length int, method, argc, argv*N, stdin_len, stdin_bytes) 读 → 组装 `Intent(context, TermuxApiReceiver.class)` + ResultReceiver extra → `context.sendOrderedBroadcast`（同步执行完毕后写回）→ socket 输出 `write(0)` flush（符合 termux-api 客户端结束协议，客户端退出）。失败记 `Logger.logError`，不崩溃。
- [NotificationAPI.java](vendor/termux-addons/termux-api/app/src/main/java/com/termux/api/NotificationAPI.java) — `deleteNotificationChannel` 内 `setClassName` 硬编码 `"com.termux.api"` → `setClassName(oldIntent.component?.packageName ?: "com.termux.api", ...)`（从触发 Intent 动态拿宿主包名）。

### 3. Tasker 与 Widget 页面标题中文化 + 按钮翻译 + 创建快捷方式 ActionBar NPE 修复

- [EditConfigurationActivity.java](vendor/termux-addons/termux-tasker/app/src/main/java/com/termux/tasker/EditConfigurationActivity.java)：标题「Tasker 设置」（`title_tasker_settings`）+ `onOptionsItemSelected(android.R.id.home) → finish()`。
- [TermuxWidgetActivity.java](vendor/termux-addons/termux-widget/app/src/main/java/com/termux/widget/activities/TermuxWidgetActivity.java)：标题「快捷方式与微件设置」（`title_widget_settings`）+ 按钮 `action_disable_launcher_icon` / `action_create_shortcut` / `action_execute_script` / `action_edit` / `action_delete` 全部在 termux-widget 模块 `values` 与 `values-zh-rCN` 补齐中英文。
- [TermuxCreateShortcutActivity.java](vendor/termux-addons/termux-widget/app/src/main/java/com/termux/widget/TermuxCreateShortcutActivity.java)：父类 `Activity → AppCompatActivity`，主题 `Theme.MaterialComponents.Light.DarkActionBar`，`getActionBar() → getSupportActionBar()` 并 null 检查，标题「创建快捷方式」+ 返回键。

### 4. Debug 仅 universal APK / Release 各架构 APK 修复

[app/build.gradle](app/build.gradle) — `splits.abi.enable` 改用 `gradle.startParameter.taskNames` 检测 release 任务：**仅 release 启用 splits**，**debug 禁用 splits** 产 universal；重命名逻辑恢复 `app-debug.apk → termux-ultra_debug_universal.apk`。

### 5. 备份与恢复改用 termux-backup / termux-restore

[BackupManager.kt](app/src/main/java/com/termux/app/compose/BackupManager.kt) — 调用 Termux 自带 termux-backup/restore，后台会话运行，保留进度条，执行完毕后台会话立即退出并返回结果。

### 6. QEMU 虚拟机 — 页面按钮定位 & 运行中数量精确计数 & 共享文件夹 9p

- **右下角按钮位置防导航栏遮挡**：[QemuVmActivity 关联页面代码] 右下角添加按钮 Box 容器手动定位：`Modifier.align(Alignment.BottomEnd) + padding(bottom = 72.dp)`。
- **运行中 VM 数精确计数**（同时覆盖 Termux 原生 + 容器内进程）：
  - 命令：`pgrep -c -x qemu-system-x86_64` 精确匹配完整进程名；并使用 `[q]emu-system-x86_64` 正则技巧避免 pgrep 自身被计数。
  - Termux 安装包名 `qemu-system-x86-64`（连字符）；二进制命令名 `qemu-system-x86_64`（下划线）。
- **共享文件夹 9p virtio 挂载**：
  - 参数 1：`-fsdev local,security_model=mapped-file,id=fsdev_shared,path=[目录]`
  - 参数 2：`-device virtio-9p-pci,id=fs0,fsdev=fsdev_shared,mount_tag=hostshare`

### 7. QEMU 虚拟机 — 创建/编辑页增强（硬盘接口、虚拟PC类型、ISO识别）

- 新增字段（缺失时兜底默认值，旧配置反序列化字段迁移防 NPE）：
  - `machineType = "q35"`（pc/q35/acpi PC 等多种选择）
  - `diskInterface = "ide"`（virtio/ide/scsi/sata 等硬盘连接方法下拉）
  - `newDiskFormat = "qcow2"`（qcow2/raw/vdi/vmdk 等）
- **ISO 智能识别**：根据 ISO 文件名 + 内部体积/boot 特征判断包含的操作系统系列：Windows (95/98/2000/XP/7/8/10/11/Server)、Ubuntu、Debian、Fedora、Arch、CentOS、openSUSE、FreeBSD、macOS...
- **自动填名称**：若虚拟机名称留空，以 ISO 识别的系统名做默认名（如 "Ubuntu 24.04"、"Windows 11"）。
- **推荐配置自动应用**：识别出的系统自动加载对应 RAM / CPU 核数 / 磁盘大小 / 推荐 machineType 与 diskInterface（用户可手动修改）。

### 8. QEMU — proot 容器模式（Android 15+）与 Android 16→17 升级兼容

- **分流规则**：生成启动脚本时判断 `Build.VERSION.SDK_INT`：
  - **Android ≤14**：`generateNativeScript()`，Termux 原生环境直接起 QEMU。
  - **Android 15+**：`generateContainerScript()`，proot 容器内装 QEMU 依赖 + bind mount 共享目录/VM 磁盘镜像；aarch64 架构需从 `qemu-efi-aarch64` 包提取 UEFI 固件。
  - **Android 17 自动切换**：检测到 Android 17 时整体切到容器模式。
- **Android 16→17 升级复用**：检测到原有 Debian 虚拟机文件时，先询问「是否重装」— 选择「否」再问「是否更新启动脚本」，选是则 bind mount 直接复用 VM 文件，仅重新生成容器适配的启动脚本与配置（**不迁移数据**）。
- **容器模式初始化**：自动检查/创建容器 → apt install qemu-system-x86 qemu-system-gui qemu-utils qemu-efi-aarch64 pulseaudio（按需） → bind mount 共享目录。
- **脚本生成**：`generateContainerScript()` 与 `generateNativeScript()` 完全独立分流，避免互相覆盖；在 [QemuVmManager.kt](app/src/main/java/com/termux/app/qemu/QemuVmManager.kt) 通过 switch 调用。

### 9. QEMU 音频 — 4 模式完整实现（DISABLED / VNC_RFB / PA_FOLLOW_SCREEN / PA_PERSIST）

**枚举定义**：`enum class QemuAudioMode { DISABLED, VNC_RFB, PA_FOLLOW_SCREEN, PA_PERSIST }`

**RadioButtonPreference 四选一卡片**：[QemuOnVncSheet.kt](app/src/main/java/com/termux/app/compose/QemuOnVncSheet.kt) 实现，选项：
- 关闭（DISABLED）
- **VNC RFB 扩展（推荐）**
- **PulseAudio - 跟随 VNC 页面（推荐）**
- PulseAudio - 持续播放

**音频模式迁移**：旧配置 `hasSound=true` 自动映射为 `VNC_RFB`；配置写入 `SharedPreferences`，并同步 `$HOME/.qemu_vm_audio_${id}.env` 标记文件供启动脚本读取（包含 effectiveAudioMode、端口等）。

**原生模式（Android ≤14）**：
- VNC_RFB：`-audiodev vnc,id=vnc_audio,server` + `-device hda-output,audiodev=vnc_audio`（VNC 页面原生命令需携带 `audiodev=vnc_audio`）。
- PA_FOLLOW_SCREEN / PA_PERSIST：`pkg install pulseaudio` → `pulseaudio --start` → 加载 `module-null-sink` + `module-simple-protocol-tcp port=4714` → QEMU 使用 `-audio pa,model=hda`（QEMU PA 后端）连接本机服务。PA_PERSIST 服务持续运行，PA_FOLLOW_SCREEN 虚拟机停止后自动停 PulseAudio（PERSIST 模式除外）。

**容器模式（Android 15+）**：
- VNC_RFB：**先尝试安装容器内 VNC audio driver**；成功则走与原生一致的 `-audiodev vnc...` 参数链；失败自动**降级回 PA**。
- PA_FOLLOW_SCREEN / PA_PERSIST：脚本先检测 `pulseaudio -v` 是否可用，不可用就 `apt install -y pulseaudio`；安装失败时 UI 提示「手动安装或关闭声音开关」。成功后 `pulseaudio --start` 启动 daemon，用 `-audiodev pa,id=pa_audio,server=tcp:127.0.0.1:4713` 起 QEMU（PA 模式配置 `PULSE_SERVER` 环境变量 + 防火墙允许本地端口）。

**Android 端 PulseAudio 播放器**：[PulseAudioPlayer.kt](app/src/main/java/com/termux/app/audio/PulseAudioPlayer.kt) 协程单例。
- `start(host="tcp://127.0.0.1:4714")` — 连接 PA 服务 simple-protocol-tcp 端口，用 `AudioTrack STREAM_MUSIC` 通道播放 PCM 流，支持不同采样率/声道配置。
- `stop()` — 彻底释放资源。
- `isPlaying()` — 当前播放状态。
- 错误处理：TCP 连接中断 / 重连 / 播放失败 → 中文错误提示；后台播放显示通知（暂停/继续）；支持音量、静音、均衡/音效、音频路由选择。

**VNC 页面生命周期与 EXTRA**：
- `VncActivity` 启动 Intent 附 `EXTRA_QEMU_AUDIO_MODE` extra；`VncScreen.kt` `onStart()` → PA_FOLLOW_SCREEN 模式下调用 `PulseAudioPlayer.start()`；`onStop()` → `PulseAudioPlayer.stop()`；自动重连/状态保存都透传 qemuAudioMode。
- VncActivity 连接前通过 `QemuVmManager.loadVms()` 匹配 VM ID，读对应音频 env 标记文件拿 effectiveAudioMode。
- PA 跟随模式使用独立 `AudioTrack STREAM_MUSIC`，避免音频泄漏。

### 10. 朱雀面板 — 允许执行 & 位置前移

「朱雀面板」按钮从「复制命令」改为**实际执行**（直接在会话运行命令而非复制到剪贴板）；页面布局上按钮位置向前移动。

### 11. 文件页 — 新设计恢复（2026-08-08 本轮）

原 8月4日做的整体布局重构被覆盖，现已按 Design 会话截图完整恢复，位置在 [FileManagerScreen.kt](app/src/main/java/com/termux/app/compose/FileManagerScreen.kt)。

**警告卡片**：
- 背景：亮色 `#FFF9C4`、暗色 `#3D3514`（黄底）。
- info 图标：圆形琥珀色底（亮 `#FFA000` 20% 透明度 / 暗 `#B88600` 20% 透明度）+ 图标色 亮 `#FF8F00` / 暗 `#FFB300`。
- 右上角 X 关闭按钮：点击后 `SharedPreferences files_warning_shown=true` 不再显示。

**FileItem 文件列表项**：
- 文件夹：图标 Box 实色蓝底（亮 `#3F8DD6` / 暗 `#1A5A96`）+ 白色 ic_folder 图标（22dp, 10dp 圆角）。
- 文件：图标 Box 实色灰底（亮 `#EEEEEE` / 暗 `#3A3A3A`）+ 白色 ic_file。
- 卡片：16dp 圆角；文件名 15sp Bold；副标题 N 项文件夹 / 大小 · 时间，12sp 次要色；文件夹右侧 `>` 箭头（非选择模式下）。

**BottomSheet 文件详情面板**（`OverlayBottomSheet`，`title = ""` 空标题，不显示默认标题栏）：

1. **顶部信息行**（padding 16x12）：48dp 大图标 + 文件名 16sp Bold + 副标题「文件类型描述 · 大小」（使用新的上千种扩展名描述函数）。
2. **详情信息卡片**（亮 `#F5F5F5` / 暗 `#252525` 背景，padding 横向 16dp）：
   - **路径** — label 70dp 宽 + value 单行省略。
   - **扩展名** — `.sh` / `.md` / `.tar.gz`（支持双扩展名识别），使用 `getCanonicalExtension()`。
   - **类型** — 「Shell 脚本」/「JPEG 图片」/「Markdown 文档」等，来自 `getFileTypeDescription()`。
   - **大小** — `Formatter.formatFileSize`。
   - **权限** — `-rwx` / `-rw-` 9 位 Owner 三元组（Read/Write/Execute）。
   - **修改时间** — `YYYY-MM-DD HH:MM`。
3. **.sh 文件警告卡**（仅 sh 文件）：黄底 + 琥珀色 info 图标 + Shell 脚本执行风险文案。
4. **操作行**（保留原「查看/编辑」等命令）：
   - `查看内容 (cat)` → 执行 `cat 路径`。
   - `编辑 (vi)` → `vimPath 存在? vi 路径 : pkg install vim -y && vi 路径`。
   - `用其他方式打开` → `content://com.termux.files` + `ACTION_VIEW` chooser（`FLAG_GRANT_READ_URI_PERMISSION`）。
   - 每项图标在上、文字在中、右侧 `>` 箭头。
5. **底部双按钮**（padding 底部导航栏 + 16dp 安全区）：
   - 左「**复制路径**」：灰底（亮 `#E8E8E8` / 暗 `#3A3A3A`），12dp 圆角，复制图标 + 文字（黑字/白字跟随模式）。动作：ClipManager setPrimaryClip(path)。
   - 右「**执行脚本**」（仅 `.sh` 文件显示，其他文件该按钮不渲染，只剩复制路径一个）：主题色蓝底 + 白字 + 终端图标；动作：执行 `bash 路径`。

**上千种扩展名映射表 EXTENSION_MAP（lazy HashMap 容量 1500）**：
- 分 7 大分类 arrayOf：`imageExts` (300+，jpg/png/raw/psd/stl/3d...)、`audioExts` (100+，mp3/flac/dsf/wavpack/mod...)、`videoExts` (100+，mp4/mkv/字幕 srt/ass...)、`docExts` (1000+，办公/编程/压缩/数据库/证书/字体/可执行/systemd 单元等，含双扩展名 `.tar.gz` / `.7z.001` / `.d.ts` / `.php8` 等)。
- 辅助函数：
  - `getFileExtension(filename)` — lastIndexOf('.') 找扩展名，额外检查倒数第二个点，组合成 `.tar.gz` 等双扩展名；全部小写。
  - `getFileTypeDescription(file)` — 目录→「文件夹」；空扩展名→「未知文件」；查表→中文描述；不在表→「{UPPER} 文件」。
  - `getCanonicalExtension(file)` — 返回「.{ext}」（SH→.sh，TAR.GZ→.tar.gz），目录→「—」。

**字符串资源补齐**（values + values-zh-rCN）：
- `file_info_ext = 扩展名 / Extension`、`file_info_type = 类型 / Type`
- `execute_script = 执行脚本 / Execute Script`
- `file_type_shell / file_type_text / file_type_binary / file_type_unknown`（保留，实际已由 EXTENSION_MAP 覆盖）

### 12. 代码文件 BOM 清理 & jniLibs 打包一致性

- **BOM 字符**：Kotlin 编译器遇到 UTF-8 BOM 会解析失败并级联影响同模块其他文件。新增/修改文件必须确保无 BOM。
- **jniLibs 打包**：`AndroidManifest.xml` 中**不指定** `android:extractNativeLibs`，由 `build.gradle` 的 `packagingOptions.jniLibs.useLegacyPackaging = true` 全权控制，两者必须一致。

---

## UI 约束速查（来自项目记忆，所有页面修改必须遵守）

- 按钮文字=白色时图标也用白色；Material3 TabRow/Tab 选中色用黑或深灰（不使用紫色/系统 Monet 色）。
- 资源页卡片亮色使用 **#FAFAFA**（比 #F5F5F5 稍白以保证边可见）。
- 所有页面 Bottom 增大 padding 防底部导航栏遮挡；Side 也加 padding 防边缘误触。
- 按钮点击效果裁剪进和卡片一致的圆角。
- miuix 开关（非 Material3 Switch）使用 `SwitchMiuix`，激活色=资源页执行按钮的蓝色（与 PulseAudio 激活、QEMU 开关等一致）。
- TopBar 文字必须水平居中；TopBar 自身颜色与状态栏 padding 颜色匹配；状态栏文字：亮黑 / 暗白。
- 页面过渡：首页 ↔ 子页 覆盖动画；主页 Tab 间左右切动画；预测返回（predictive back）支持：小动画 → 确认后完整动画。
- 首页支持横向滑动切换页：终端 ↔ 文件 ↔ 远程；远程页开启 VNC 时：VNC ↔ SSH ↔ 资源，VNC ← 文件。
- 远程页顶Bar 横向滑动 → 右滑进资源页，左滑进文件页。
- 文件页 OverlayBottomSheet 底部必须有足够 margin，避免被 Android 系统手势区域遮挡。
- 终端页/远程页搜索框样式统一：hint「输入相关标题来搜索」，无结果显示「未找到」；激活时下方所有卡片通过「屏幕移出范围 / overlay 遮罩」而非 removeFromComposition 隐藏（保持可搜索）。
- RefreshIndicator 顶部加 12dp topPadding，与搜索栏分离。
- 第三方资源中心四个按钮：图标在上一行，文字在下一行，全部居中。
- 文件页 FileItem 的 BottomSheet（OverlayBottomSheet）使用足够底部 padding，避免导航栏/手势区遮挡。

---

## 编译状态

| 构建类型 | 状态 | 产物 | 大小 | 时间 |
|----------|------|------|------|------|
| Debug (QEMU音频+文件页新设计) | ✅ **BUILD SUCCESSFUL** (21s, 29 executed) | `termux-ultra_debug_universal.apk` | — | 2026-08-08 23:30 |
| Debug | ✅ BUILD SUCCESSFUL (25s) | `termux-ultra_debug_universal.apk` | 187 MB | 2026-08-01 02:14 |
| Release | ✅ 已成功（前期） | 各架构 release APK（~50 MB/个）+ universal | — | 2026-08-01 00:26 |

- Debug 输出：`D:\KiTerminal-UX\app\build\outputs\apk\debug\termux-ultra_debug_universal.apk`
- Release 输出：`D:\KiTerminal-UX\app\build\outputs\apk\release\`
- 最近一次验证命令：`gradlew.bat :app:assembleDebug` — **BUILD SUCCESSFUL in 21s**，178 tasks（29 executed, 149 up-to-date），8月8日夜间。
- 已知非致命警告：D8 Kotlin metadata 警告（不影响构建）、Java 21 source/target 8 已过时提示（不影响构建）；R8 Kr0.a 堆栈信息若只出现在 dex 转换过程尾部且 BUILD SUCCESSFUL 则为 D8 正常内部调用栈，非错误。
- 已知编译伪影：首次完整构建时 `SettingsScreen.kt` 偶发报 ResourcesScreen `modifier` 参数找不到，第二次 UP-TO-DATE 即通过，实际是 PowerShell CRLF 渲染 `_x000D__x000A_` 伪影，非错误。

---

## 待办任务（需真机/模拟器验证）

1. **termux-api 命令不卡住** — `pkg install termux-api` 后 `termux-battery-status` / `termux-notification` / `termux-toast` 秒返回 JSON / 发通知 / Toast。
2. **集成工具开关 + 官方插件检测** — 安装独立 `com.termux.api` → 设置页开关应灰 + 「已由官方插件替代」→ 点击行弹冲突提示；卸载后恢复可选。
3. **Widget 创建快捷方式不崩溃** — 桌面添加 Termux 快捷方式或进入 Widget 管理页「新建快捷方式」，可返回上一级（setDisplayHomeAsUpEnabled + null 检查）。
4. **QEMU 虚拟机创建** — 创建页「浏览 ISO」→ 系统识别 + 名称自动填 + 推荐配置应用 + machineType/diskInterface 下拉可保存并正确写入启动脚本。
5. **QEMU proot 容器模式（Android 15/16/17）** — 自动创建容器+安装QEMU+bind mount；Android 17 强制走容器；Android 16→17 升级 bind mount 复用 Debian VM 不迁移；选「不重装」后问「是否更新启动脚本」—选是则脚本重写数据保留。
6. **QEMU 音频 4 模式** — DISABLED 无声；VNC_RFB 原生用 VNC 扩展 + PA 容器回退；PA_FOLLOW_SCREEN VNC onStart 播 onStop 停；PA_PERSIST 退出 VNC 仍响；sh 警告卡 + 声音开关说明符合容器/原生模式差异（容器有「容器内 QEMU 不支持 VNC 时回退 PA」文案）。
7. **QEMU 运行中数量计数** — 原生 pgrep 与容器内 pgrep 双重计数，pgrep 自身正则 `[q]emu-xxx` 不被计数。
8. **文件页新设计真机比对** — 警告卡黄底琥珀色；FileItem 文件夹蓝/文件灰实色；BottomSheet 空标题、6 行详情（扩展名/类型新显示）、操作行保留（查看/编辑/其他方式）、底部复制路径 + 仅 .sh 显示执行脚本蓝按钮；点击 .md/.png 则无蓝按钮只显示复制路径；EXTENSION_MAP 各扩展名显示正确（如 .png → PNG 图片、.py → Python 源、.tar.gz → Tar Gzip、.qcow2 → QEMU 写时复制 2）。
9. **APK 瘦身** — debug 187MB 偏大，评估 enable `minifyEnabled` / R8 + 资源压缩。

---

## 关键约束与约定（来自项目记忆）

- **通讯语言**：中文。
- **UI**：按钮白字+图标配色；Material3 TabRow 选中色黑/深灰；miuix 开关（非 material3）激活色 = 资源页执行按钮蓝；设置页 ArrowPreference 打开下一级页面或对话框；TopBar 文字居中；页面过渡覆盖+左右切支持预测返回。
- **构建**：硬链接 `D:\KiTerminal-UX`；debug 仅 universal，release 各架构；不加 `-q`；Release 构建前 `gradle.properties` JVM `-Xmx2048M → -Xmx4096M`。
- **版本**：Termux 0.118.3 基础，工具版本需与 termux-shared 0.118.3 同期；versionCode 1050 ↔ versionName `118.3.60`。
- **国际化**：所有新增字符串必须同时提供 `values/strings.xml` 与 `values-zh-rCN/strings.xml`。
- **代码约束**：无 BOM；`android:extractNativeLibs` 不在 manifest 指定，由 `build.gradle jniLibs.useLegacyPackaging = true` 控制。

---

## 关键文件索引

| 文件 | 作用 |
|------|------|
| [IntegratedTools.kt](app/src/main/java/com/termux/app/compose/IntegratedTools.kt) | 集成工具组件状态 + 官方独立 APK 安装检测 |
| [SettingsScreen.kt](app/src/main/java/com/termux/app/compose/SettingsScreen.kt) | 设置页：开关卡片 + 工具配置 ArrowPreference 分区 + 插件禁用灰 UI |
| [TermuxApplication.java](app/src/main/java/com/termux/app/TermuxApplication.java) | TermuxAPI LocalServerSocket 监听器（防命令卡死） |
| [NotificationAPI.java](vendor/termux-addons/termux-api/app/src/main/java/com/termux/api/NotificationAPI.java) | setClassName 硬编码包名修复 |
| [EditConfigurationActivity.java](vendor/termux-addons/termux-tasker/app/src/main/java/com/termux/tasker/EditConfigurationActivity.java) | Tasker 设置页标题「Tasker 设置」+ 返回键；pluginHostPackage=null 或宿主包名时跳过权限检查；FireReceiver 用 `context.getPackageName()` |
| [TermuxWidgetActivity.java](vendor/termux-addons/termux-widget/app/src/main/java/com/termux/widget/activities/TermuxWidgetActivity.java) | Widget 管理页：标题「快捷方式与微件设置」+ 按钮完整翻译；`getPackageName()` 替代硬编码 com.termux.widget 设置组件 |
| [TermuxCreateShortcutActivity.java](vendor/termux-addons/termux-widget/app/src/main/java/com/termux/widget/TermuxCreateShortcutActivity.java) | AppCompatActivity + getSupportActionBar null 检查，防 NPE |
| [BackupManager.kt](app/src/main/java/com/termux/app/compose/BackupManager.kt) | termux-backup / termux-restore 后台会话执行 |
| [ThirdPartyCenterActivity.kt](app/src/main/java/com/termux/app/activities/ThirdPartyCenterActivity.kt) | 第三方资源中心，四按钮图标在上文字在下居中 |
| [QemuVmManager.kt](app/src/main/java/com/termux/app/qemu/QemuVmManager.kt) | QEMU VM 配置读写；ISO 识别 + 推荐配置；`generateNativeScript()` vs `generateContainerScript()` 分流；音频 env 标记文件写入 |
| [QemuOnVncSheet.kt](app/src/main/java/com/termux/app/compose/QemuOnVncSheet.kt) | QEMU VNC 页面底部 Sheet：音频模式 RadioButtonPreference 4 选 1 + 其他配置 |
| [PulseAudioPlayer.kt](app/src/main/java/com/termux/app/audio/PulseAudioPlayer.kt) | 协程单例 `start(host)` / `stop()` / `isPlaying()`，TCP `tcp://127.0.0.1:4714` 接 PA simple-protocol，AudioTrack STREAM_MUSIC 播放 PCM |
| [VncScreen.kt](app/src/main/java/com/termux/app/compose/VncScreen.kt) / [VncActivity.kt](...) | VNC 页面：`EXTRA_QEMU_AUDIO_MODE` extra 透传；PA_FOLLOW_SCREEN 模式 onStart/onStop 生命周期绑定 PulseAudioPlayer；VNC 命令带 `audiodev=vnc_audio` 参数 |
| [FileManagerScreen.kt](app/src/main/java/com/termux/app/compose/FileManagerScreen.kt) | **文件页完整设计**：警告卡黄底琥珀 + FileItem 实色蓝/灰图标 + BottomSheet（空标题、顶部信息行 + 6 行详情卡(路径/扩展名/类型/大小/权限/修改时间) + sh 警告卡 + 查看/编辑/其他方式打开操作行 + 底部双按钮（复制路径灰/执行脚本蓝，仅.sh 显示后者））；**EXTENSION_MAP 上千种扩展名映射（1447行起始）** + 辅助函数 `getFileExtension` / `getFileTypeDescription` / `getCanonicalExtension` |
| [app/build.gradle](app/build.gradle) | versionCode 1050 / versionName 118.3.60；splits（release 启用、debug 禁用）；脱糖；useLegacyPackaging；重命名 APK 输出 |
| [gradle.properties](gradle.properties) | 含 JVM `-Xmx...`，Release 构建前改到 `-Xmx4096M` |
| [settings.gradle](settings.gradle) | 工具库模块路径（vendor/termux-addons/*） |
| [vendor/termux-addons/](vendor/termux-addons/) | 5 个 Termux 工具库源码目录 |
| [values/strings.xml](app/src/main/res/values/strings.xml) | 英文资源，含 standalone_plugin_*、file_info_*、execute_script、file_type_* |
| [values-zh-rCN/strings.xml](app/src/main/res/values-zh-rCN/strings.xml) | 中文资源，同上 |
| [termux-widget values-zh-rCN](vendor/termux-addons/termux-widget/app/src/main/res/values-zh-rCN/strings.xml) | Widget 页面完整中文翻译 |
| [termux-tasker values-zh-rCN](vendor/termux-addons/termux-tasker/app/src/main/res/values-zh-rCN/strings.xml) | Tasker 页面完整中文翻译 |
