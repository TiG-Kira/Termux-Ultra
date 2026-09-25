---
title: Features
lang: en
ref: features
description: Termux Ultra architecture, terminal engine, Miuix UI, integrated-tool mechanism, plugin capability matrix and permission model, AI assistant, VorteX Guard security engine, VNC/SSH internals, and tech stack.
---

## 1. Architecture overview

Termux Ultra keeps the native Termux terminal and layers a set of enhanced subsystems on top:

| Subsystem | Location | Responsibility |
|-----------|----------|----------------|
| Terminal core | `libterminal` | Terminal emulation and screen rendering |
| App shell | `app/src/main/java/com/termux/app/` | `TermuxActivity`, `TermuxService`, notification system |
| Compose UI | `app/.../app/compose/` | Home, files, remote, resources, settings, AI assistant |
| Plugin system | `app/.../app/plugin/` | Plugin loading, permissions, action bridge, Compose rendering |
| VNC | `app/.../app/vnc/`, `app/src/main/cpp_avnc/` | AVNC client and libvncserver |
| SSH | `app/.../app/ssh/` | connectbot sshlib connection management |
| FTP | `app/.../app/ftp/` | Built-in FTP server |
| Integrated plugins | `vendor/termux-addons/` | API / Boot / Styling / Tasker / Widget sources |
| Shared library | `termux-shared/` | Cross-module constants and utilities (`TermuxConstants`) |

## 2. Terminal engine

**LibTerminal** is the terminal core engine, handling terminal emulation and screen rendering. Current version **3.1.1** (evolved from 3.0.0). It excels at scrolling, bulk output, and escape sequence handling.

## 3. UI: Jetpack Compose + Miuix

The UI is entirely Jetpack Compose, using the **Miuix (HyperOS)** design language:

- **TopAppBar and floating bottom bar** replicate native HyperOS styling and animation, with automatic OS version detection
- **Glass / soft-light / floating navigation bar**: whiter in light mode, darker in dark mode, with a draggable page indicator
- **Unified TabRow** with page transition animations, **predictive back**, and horizontal swipe gestures
- **Settings screens** built on the Miuix ArrowPreference suite, with consistent card radii and press feedback clipping
- Dark / light adaptation and multiple languages (Chinese / English, 100% Chinese coverage)

Dependency versions: Jetpack Compose `1.8.3`, Material 3 `1.3.0`, Miuix KMP `0.9.4` (ui / icons / preference).

## 4. Integrated tool mechanism

The sources of five Termux plugins live in `vendor/termux-addons/` as **toggleable built-in tools**; no separate APKs are needed.

Implementation notes:

- Every integrated component is declared in `AndroidManifest.xml` with `android:enabled="false"` by default.
- At runtime the `IntegratedTools` singleton handles enable/disable via `setEnabled()` + `applyComponentState()`, which calls `PackageManager.setComponentEnabledSetting()` underneath.
- `IntegratedTools.componentsFor()` registers the component mapping; **never call PackageManager directly** — always go through the singleton.
- Before enabling, the app checks whether the matching official standalone APK is installed; on conflict it disables the toggle and warns you.
- Termux:Styling uses the **merged package name `com.termux`** instead of the original `com.termux.styling`.

## 5. Plugin system

### 5.1 Capability matrix

| Capability | Description |
|------------|-------------|
| Resource page cards | Add entries backed by `SHELL_COMMAND` / `OPEN_URL` / `HOST_ACTION` / `CUSTOM` actions |
| Settings items | Add or modify settings entries |
| Agent skills | Inject custom skills into the AI assistant |
| H5 UI | Multi-page WebView interface (home + sub-pages) |
| Compose pages | JSON-DSL pages rendered natively, **no WebView** |
| Feature blocking | Disable system features, settings entries, or navigation pages |
| System prompt | `APPEND` / `MODIFY` / `OVERWRITE` strategies |
| Cross-app bridge | Broadcast, ContentProvider, Webhook |

### 5.2 Permission model

| Permission | Description | Risk |
|------------|-------------|------|
| `TERMUX_SESSION_ACCESS` | Read/write terminal sessions, open persistent sessions | Medium |
| `ROOT_EXECUTE` | Execute commands with ROOT privileges | High |
| `FILE_SYSTEM_READ` | Read the file system | Medium |
| `FILE_SYSTEM_WRITE` | Write the file system | High |
| `AGENT_MODIFY` | Modify agent behaviour and the system prompt | High |
| `H5_WEBVIEW` | Load the H5 home page | Low |
| `CROSS_APP_BRIDGE` | Cross-app messaging | Medium |
| `INTERNET_ACCESS` | Network access | Low |

### 5.3 Security policy

- Installation validates `manifest.json` and **the existence of every file referenced by an `entry`**; missing files abort the install.
- `minHostVersion` defaults to `2.0.0`; older hosts refuse to install.
- The `OVERWRITE` system prompt mode is **extremely high risk** and shows a `PluginOverwriteDialog` confirmation.
- The management UI supports install, enable/disable, config inspection, and uninstall.

### 5.4 Unified action protocol

`ActionExecutor` understands four prefixes, used directly by Compose node `onClick` / `onChange`:

| Prefix | Example | Meaning |
|--------|---------|---------|
| `shell:` | `shell:uname -a` | Run a command in the plugin shell |
| `action:` | `action:open_vnc_settings` | Call a host `HostActionRegistry` entry |
| `nav:` | `nav:page_about` | Navigate to another page of the same plugin (by `pages[].id`) |
| `http(s)://` | `https://example.com` | Open in the external browser |
| `{value}` | `shell:echo {value}` | In `switch.onChange`, replaced with the current boolean |

### 5.5 Persistent sessions

`PluginPersistentSession` provides a long-lived shell (unlike `PluginManager.executeShellCommand()`, which spawns a new process each time):

- Incremental transcript reads (`readNew()` / `readAll()` / `resetReadCursor()`)
- Send Ctrl+C (`interrupt()`) and EOF (`sendEof()`)
- Query `cwd` / `pid` / `exitCode` / `isRunning`
- Automatically registered in `TermuxService.mTermuxSessions`, so the terminal page can manage it too
- On exit, `PluginPersistentSessionRegistry` resolves the session id via `TerminalSession.mHandle` and cleans up
- Cross-plugin access is blocked by the `pluginId` check

## 6. AI assistant

- **Natural-language interaction**: drive the terminal, file system, and remote connections by chatting
- **Skill system**: create/close sessions, run commands, read/write files, VNC/SSH connections, QEMU VM management
- **Multiple models**: OpenAI-compatible API or custom endpoints, with configurable `temperature`
- **Safety**: dangerous-operation detection (`rm -rf`, `dd`, fork bombs, …) with confirmation
- **Context awareness**: reads live session info, file listings, and command results
- **Deep thinking display**: shows reasoning when the model supports it
- **Plugin extension**: plugins can add skills and modify the system prompt

## 7. Security: VorteX Guard Engine (VGE)

The former enhanced protection module was rewritten as **VorteX Guard Engine**: a new shell-hook architecture with more precise detection and zero terminal interference.

- **TCP communication isolation**: all server communication runs in a sub-shell; the parent process sees zero fd changes, so PTY termios is never clobbered
- **Safe DEBUG trap**: `extdebug` is no longer globally enabled — it is switched on only momentarily when DENY skips a command, and force-disabled before `PROMPT_COMMAND`
- **Function override instead of traps**: `su` / `sudo` / `dd` / `mkfs` and other risky word commands are intercepted by function overrides, leaving bash internals untouched
- **Initialization grace period**: OMB/OMZ init scripts pass through while the security module loads
- **PTY termios fix**: the JNI layer explicitly sets `ECHO|ICANON|ISIG`, working around Android toybox `stty` being ineffective on some devices
- **OMB/OMZ adaptation**: auto-detects oh-my-bash / oh-my-zsh and registers through their native `preexec`/`precmd` hooks, with no DEBUG trap interference
- **Risky command confirmation**: four modes — `OFF`, `WARN_ONLY`, `AUTO_BLOCK`, `WARN_VERIFY`
- **Live settings**: switching modes restarts the hooks and `SecuritySocketServer` immediately, no app restart needed
- **Script detection coverage**: `bash`/`sh`/`zsh`/`ksh`/`dash`/`fish` scripts, direct `./script.sh` execution, and dangerous argument combinations such as `rm -rf /` or `chmod 777`
- **UTF-8 BOM fix**: handles scripts written on Windows that carry a BOM, which previously broke `bash source`

## 8. VNC / SSH internals

| Module | Dependencies | Notes |
|--------|--------------|-------|
| VNC | AVNC, libvncserver, libjpeg-turbo, wolfSSL | Pinch zoom, multiple input modes, special keys, color format config, automatic local port scan |
| SSH | connectbot sshlib `2.2.36` | Multi-profile management, auto-install of `ssh`/`sshpass`, local port forwarding, host key verification, multi-IP retry |

Native CMake targets: `native-vnc`, `vncclient`, `turbojpeg-static`, `wolfssl`, `termux-bootstrap`.

## 9. LiveUpdate notifications

- Download progress shown in **segments**
- Agent thinking state display
- Package manager notification improvements
- The v0.119 base introduces `POST_PROMOTED_NOTIFICATIONS` (Android 15+), with a compatibility layer falling back to regular notifications on `sdk_int < 36`

## 10. Tech stack

| Category | Technology |
| --- | --- |
| Languages | Kotlin, Java, C/C++ |
| UI | Jetpack Compose 1.8.3, Material 3 1.3.0, Miuix KMP 0.9.4 (ui / icons / preference) |
| Architecture | AndroidX, Lifecycle 2.8.5, ViewModel, Navigation, Room 2.7.2, DataBinding |
| Terminal | libterminal |
| VNC | AVNC, libvncserver, libjpeg-turbo, wolfssl |
| SSH | connectbot sshlib 2.2.36 |
| Image loading | Coil Compose 2.7.0 |
| Biometrics | AndroidX Biometric 1.2.0-alpha05 |
| Serialization | Gson 2.10.1, kotlinx-serialization 1.9.0 |
| AI assistant | OpenAI-compatible API, custom endpoints, skill system |
| Plugin system | ZIP packaging, JSON config, WebView bridge, Broadcast bridge |
| Build | Gradle, CMake 3.22.1, NDK 22.1.7171670 |
| Integrated plugins | termux-api, termux-boot, termux-styling, termux-tasker, termux-widget |
| Package name | `com.termux` (sharedUserId) |

## 11. Building this project

Requirements: JDK 21, Android SDK (compileSdk 37), NDK `22.1.7171670`, CMake `3.22.1`.

> To avoid NDK problems caused by spaces in paths, work through a **space-free hard-linked path** (for example `D:\KiTerminal-UX`).

```bash
# Debug build (universal APK only)
./gradlew assembleDebug

# Release build (per-ABI APKs)
./gradlew assembleRelease
```

Output:

- Debug: `app/build/outputs/apk/debug/termux-ultra_debug_universal.apk`
- Release: per-ABI APKs under `app/build/outputs/apk/release/`

Signing: the project ships `ki-terminal-release.jks` (alias `ki-terminal`), used for both Debug and Release.

> Do not pass `-q` when building, so you can watch the progress.
