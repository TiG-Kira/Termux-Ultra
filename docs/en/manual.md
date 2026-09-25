---
title: User Manual
lang: en
ref: manual
description: Termux Ultra installation, first run, terminal sessions, file manager, VNC/SSH, containers and VMs, settings and backup, AI assistant, logging, and FAQ.
---

## 1. Read before installing: signing must match

Termux Ultra shares the `sharedUserId` (`com.termux`) with upstream Termux and all of its plugins. Therefore **the app and every plugin APK on the device must come from the same signing source**, otherwise they cannot cooperate and installation fails with:

- `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`
- `signatures do not match`

Rules:

- **Never mix sources** (for example one APK from F-Droid and another from GitHub).
- To switch sources, **uninstall all installed Termux and plugin APKs first**, then reinstall everything from the single new source.
- Back up your data first — see [Backing up Termux](https://wiki.termux.com/wiki/Backing_up_Termux).

> "bootstrap" refers to the minimal package set shipped by `termux-app` to boot a minimal shell environment. Its zip is built and published by [termux-packages releases](https://github.com/termux/termux-packages/releases).

## 2. System requirements

| Item | Requirement |
|------|-------------|
| Android | `>= 8.0` (API 26) |
| targetSdk / compileSdk | `28` / `37` |
| Supported ABIs | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` |

## 3. Download and install

| Channel | Notes |
|---------|-------|
| [GitHub Releases](https://github.com/TiG-Kira/Termux-Ultra/releases) | **Stable releases**; per-ABI APKs are listed under `Assets` |
| [GitHub CI](https://github.com/TiG-Kira/Termux-Ultra/actions/workflows/ci.yml) | Automated CI builds (beta); triggered per commit, requires a GitHub account to download artifacts |

Size reference:

- Debug builds only produce a universal APK (`termux-ultra_debug_universal.apk`), roughly `~180MB` including the bootstrap.
- Release builds produce per-ABI APKs, roughly `~120MB` each.
- APKs from GitHub are all `debuggable` and mutually compatible, but incompatible with other sources.

### About the Google Play Store (deprecated)

Upstream Termux and its plugins stopped updating on the Play Store because of the [Android 10 issue](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10); the last version there is `v0.101`. **Do not install Termux-family apps from the Play Store** — migrate to GitHub or F-Droid.

## 4. First launch (OOBE)

The first start opens the onboarding wizard (OOBE), which walks you through:

1. Welcome screen and version information
2. Storage permission request
3. Notification permission request (required by LiveUpdate)
4. Terminal initialization — extracting the bootstrap and creating the base directory tree
5. Optional: sign in or skip, and read the Release Notes of the current version

Do not kill the process during initialization. If it stalls, set the log level to `Verbose` under `Settings → Debug`, restart, and capture logcat (see section 15).

## 5. Interface tour

The bottom bar has four tabs, and you can also **swipe horizontally** between them:

| Tab | Contents |
|-----|----------|
| **Terminal** | Multi-session management, search, create/close/rename |
| **Files** | Built-in file manager, FTP server |
| **Remote** | VNC remote desktop, SSH connection management, SSH tunnels |
| **Resources** | One-tap deployment scripts, third-party resource center, plugin center |

Interaction details:

- Page transitions use stacked and horizontal animations, with **predictive back** support.
- Card corner radii and press feedback clipping are consistent.
- The bottom bar has avoidance logic and margin corrections to prevent mis-taps, and supports glass / soft-light / floating effects.
- The navigation indicator can be dragged to switch pages.

## 6. Terminal sessions

### Basics

- **New session**: tap `+` in the session list
- **Switch**: tap a list item
- **Rename / close**: long-press, or use the menu on the right of the session item
- **Search**: the top search box filters in real time (matches titles; shows "not found" when empty)

### Keep-alive and protection

- **Service state detection**: continuously monitors terminal state, with Wake Lock keep-alive.
- **Memory monitoring and protection**: freezes sessions when memory pressure is high, preventing data loss.
- **Session keep-alive hints**: on Android 12+ this works together with `tmux` for background persistence.

### Terminal engine

**LibTerminal** is the terminal core engine, handling terminal emulation and screen rendering with substantially better performance and compatibility.

### Terminal appearance

After enabling the `Termux:Styling` integrated tool, color schemes and fonts can be managed from settings.

## 7. Integrated tool toggles

Five Termux plugins are built into the app (sources under `vendor/termux-addons/`), so **no separate APKs are needed**. Toggle them under `Settings → Integrated tools`:

| Tool | Purpose |
|------|---------|
| **Termux:API** | Exposes Android system functions (sensors, notifications, TTS, …) |
| **Termux:Boot** | Runs scripts under `~/.termux/boot/` at boot |
| **Termux:Styling** | Terminal color schemes and font management (uses the merged package name `com.termux`) |
| **Termux:Tasker** | Tasker automation integration |
| **Termux:Widget** | Home-screen shortcuts and widgets |

> Tools are **off by default**. Enabling calls `PackageManager.setComponentEnabledSetting()` to enable the corresponding components; disabling does the reverse. **If the official standalone APK is installed, the toggle is automatically disabled with a conflict warning** — uninstall the standalone APK first.

## 8. File management

- Full file / folder operations: create, copy, cut, paste, delete, rename
- Multiple open actions: view contents (`cat`), edit (`vi`), execute (`bash`), copy path
- A file details panel adapted for dark mode
- Built-in **FTP server** for LAN transfers
- Pull-to-refresh, multi-select with batch operations, and file type icons

## 9. Remote management

### VNC remote desktop

Based on AVNC + libvncserver, supporting:

- Pinch-to-zoom gestures
- Multiple input modes
- Special key sending
- Color format configuration
- **Automatic local VNC port scanning**

### SSH connection management

Based on connectbot sshlib:

- Save / edit / delete multiple connection profiles
- Automatically installs `ssh` / `sshpass`
- **SSH tunnels**: local port forwarding, host key verification, multi-IP retry

The remote page uses a unified search UI with card-based management.

## 10. Linux containers and virtual machines (one-tap deployment)

The resources page is split into a **utility center** and a **third-party resource center**:

| Entry | Description |
|-------|-------------|
| Linux container | proot-based one-tap install of Ubuntu (Noble/Jammy) or Debian (Bookworm), sharing the Termux home directory |
| QEMU install | Install QEMU inside a container or directly in Termux for full system virtualization |
| QEMU on VNC | Boot a VM with QEMU and display it over VNC, with custom CPU / memory / disk / ISO |
| Seed ISO | Automatically generates a seed ISO for VM initial configuration |
| LightPanel | One-tap deployment of a web management panel |
| Python environment | One-tap Python runtime deployment |
| tmux | Keeps containers and projects alive |
| Third-party resource center | Community-maintained extension resources |

## 11. Plugin center

Entry: **Resources page → Plugin center**.

- Install: install a `.tup` (ZIP-format plugin package) from file
- Manage: enable / disable, view configuration, uninstall
- Permissions: ROOT execution, session access, file read/write, cross-app bridge, H5 WebView, internet access, agent modification
- `OVERWRITE` system prompt changes trigger a confirmation dialog

See the [plugin development docs]({{ '/en/plugins/' | relative_url }}) for authoring plugins.

## 12. Settings and dashboard

| Feature | Description |
|---------|-------------|
| Network info card | Live public IP and country |
| Device info | Model, Android version, kernel version |
| Backup / restore | Back up and restore Termux data |
| Integrated tool panel | Includes standalone-APK conflict detection |
| Biometric auth | Fingerprint unlock |
| Languages | Chinese / English (100% Chinese coverage) |
| Dark / light | Follow system or choose manually |
| Miuix-style settings | ArrowPreference suite |

## 13. AI assistant

A built-in assistant that talks to the terminal, file system, and remote connections in natural language.

Configure it under `AI assistant → Settings`: fill in the OpenAI-compatible `Base URL`, `API Key`, and model name, and optionally tune `temperature`. Local model endpoints are supported as well.

Capabilities:

- **Skill system**: create/close sessions, run commands, read/write files, VNC/SSH connections, QEMU VM management
- **Safety**: dangerous-operation detection (`rm -rf`, `dd`, fork bombs, …) with confirmation
- **Context awareness**: reads live session info, file listings, and command results
- **Deep thinking display**: shows reasoning when the model supports it
- **Plugin extension**: plugins can add skills and modify the system prompt

## 14. Uninstalling

For a clean uninstall you must remove **all** Termux or plugin APKs on the device.

Open `Android Settings → Apps`, search for `termux`, and uninstall them one by one. Even if you never installed a plugin, double-check the app list.

## 15. Debugging and logs

Configure the `logcat` level under `Settings → Debug` (requires app version `>= 0.118.0`):

| Level | Description |
|-------|-------------|
| `Off` | Nothing logged |
| `Normal` | error / warn / info plus stack traces (default) |
| `Debug` | Debug messages |
| `Verbose` | Verbose messages |

Viewing logs:

```bash
# Follow logs inside the terminal (Ctrl+c to stop)
logcat

# Export a log snapshot
logcat -d > logcat.txt
```

You can also **long-press the terminal menu → More → Report Issue** to auto-generate stat info and a logcat snapshot. When reporting a problem, attach the complete report (scrub sensitive data if needed) — **screenshot-only reports are usually closed**.

> Restore the log level to `Normal` once you are done, to avoid writing sensitive data to logcat and to keep performance up.

## 16. FAQ

**Q: Installation fails with a signature mismatch / `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE`.**
A: A Termux or plugin APK from another source is present. Uninstall all Termux-family APKs and reinstall from one source.

**Q: The integrated tool toggle is greyed out.**
A: The official standalone APK is installed and conflicts. Uninstall it and the toggle becomes available.

**Q: Sessions get killed after a long time in the background.**
A: On Android 12+ use `tmux` to persist sessions; also disable battery optimization for the app, allow background activity, and lock it in Recents.

**Q: Notifications do not appear.**
A: Check that the notification permission is granted. On Android 15+ `POST_PROMOTED_NOTIFICATIONS` is required; the app declares it and falls back to the legacy API on older versions.

**Q: No prompt in the terminal / no echo / oh-my-bash theme breaks.**
A: Upgrade to VorteX Guard Engine v1.7.0+ — the shell hook architecture was rewritten to fix this.

**Q: Shell scripts written on Windows fail with odd errors.**
A: Windows editors may add a UTF-8 BOM, which breaks `bash source`. This is handled, but you can also re-save as UTF-8 without BOM with LF line endings.

**Q: The new APK will not open after installing.**
A: Confirm the ABI matches (`arm64-v8a` is the common one), confirm no stale install with a different signature remains, and if needed capture logcat and open an issue.
