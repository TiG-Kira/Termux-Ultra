# vendor/termux-addons/termux-x11（预编译 AAR）

来源：https://github.com/termux/termux-x11（:lorie 模块，commit 5986cfc，版本 1.03.01）

- `lorie-release.aar`：上游 `:lorie:assembleRelease` 产物，内含全 ABI（arm64-v8a /
  armeabi-v7a / x86 / x86_64）原生 X Server 与 Kotlin/Java 类。
- 本模块只收产物、不收源码（同 libterminal 模式）；`build.gradle` 以
  `configurations.maybeCreate("default") + artifacts.add` 方式对外提供 AAR，
  清单/资源/原生库照常走 AGP AAR 合并管线。

## 构建时对上游源码施加的本地补丁（未入库）

1. `lorie/build.gradle`：host 探测 hack → 硬编码 `hostApplicationId = "com.termux"`；
   `compileSdkVersion 34 → 35`（本机无 android-34 平台）。
2. `lorie/src/main/AndroidManifest.xml`：去 `.LorieApp` Application 名与 LAUNCHER
   intent-filter；六个组件（MainActivity / MainActivity$MainActivityTagged /
   LoriePreferences / LoriePreferences$Receiver / LorieApp$Receiver /
   utils.KeyInterceptor）加 `android:enabled="false"`，由 IntegratedTools 随
   TERMUX_X11 开关运行时启停。
3. `lorie/src/main/cpp/recipes/xkbcomp.cmake`：宿主工具 `/usr/bin/gcc` →
   `zig cc`（Windows 无宿主 gcc）；makekeys 产物加 `.exe` 后缀。
4. `lorie/src/main/cpp/libx11/include/X11/xlocale.h` → `xlocale.h.disabled`：
   该文件被 NDK `time.h` 的 `#include <xlocale.h>` 误命中（`-I libx11/include/X11`
   优先于 sysroot），遮蔽 NDK 同名头导致 `locale_t` 编译错误；无任何代码引用，移出即可。

## Windows 构建环境备忘

- bison/flex：WinFlexBison（winget），`data/` 目录必须复制到 exe 旁。
- 宿主 C 编译器：zig（winget，单文件自带链接器）。
- NDK：上游 pin 29.0.14206865。
- Gradle：上游 wrapper 9.8.1 / AGP 9.3.1。

## 升级流程

克隆上游 → 重打上述补丁 → `:lorie:assembleRelease` → 替换本目录 AAR →
以 AAR 内清单/组件核对 `IntegratedTools.componentsFor(TERMUX_X11)`。
