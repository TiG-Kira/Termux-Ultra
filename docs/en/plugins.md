---
title: Plugin Development
lang: en
ref: plugins
description: Complete Termux Ultra plugin guide — manifest.json reference, permissions, resource cards, agent skills, system prompt, H5 pages and JS bridge, Compose JSON DSL, host action bridge, persistent sessions, packaging and debugging.
---

## 1. Overview

The Termux Ultra v2.0.0 plugin system lets third-party developers extend the app. Plugins are packaged as **ZIP files** with the `.tup` extension and installed from **Resources page → Plugin center**.

New in v2.0.0 compared with v1.2.0:

- **Compose JSON DSL pages**: `pages[].type = "compose"` declares a UI rendered natively by the host `ComposeRenderer` (no WebView)
- **Host action bridge (`HostActionRegistry`)**: resource-card `action.hostActionId`, Compose `onClick: "action:xxx"`, and H5 `hostAction()` all funnel into the same host entry points
- **Page navigation API**: `navigate(pageId)` from H5 jumps to any compose/h5 sub-page of the same plugin
- **Unified action string protocol**: `ActionExecutor` supports `shell:` / `action:` / `nav:` / bare URL prefixes
- **Plugin persistent sessions**: host-side `PluginPersistentSession` provides a long-lived shell with incremental reads and Ctrl+C / EOF support (host-side Kotlin API, not the JS bridge)
- **`minHostVersion` now defaults to `2.0.0`**

## 2. Quick start

1. Create the plugin directory structure
2. Write `manifest.json`
3. Add your functionality (H5 / Compose / skills / resource cards)
4. Zip it up and rename to `.tup`
5. Install and test from the plugin center

## 3. Plugin directory structure

```
my-plugin/
├── manifest.json          # Required: plugin manifest
├── icon.png               # Recommended: 192x192 PNG icon
├── web/                   # Optional: H5 pages (all H5 files live here)
│   ├── index.html         # Home page (referenced by h5Home.entry)
│   ├── about.html         # H5 sub-page (referenced by pages[].entry)
│   └── settings.html
├── compose/               # Optional: Compose JSON DSL pages
│   ├── home.json          # Compose sub-page (pages[].entry, type="compose")
│   └── adb_list.json
└── skills/                # Optional: custom skills (JSON definitions)
    └── my_skill.json
```

> **Page file location rule**: every page file (HTML / CSS / JS / images / Compose JSON) must be packaged under the plugin root, and `entry` fields in `manifest.json` use paths **relative to the plugin root** (e.g. `web/index.html`, `compose/home.json`). Installation validates that all files referenced by `entry` exist; missing files abort the install.

## 4. manifest.json field reference

```json
{
  "id": "com.example.myplugin",
  "name": "My Plugin",
  "version": "1.0.0",
  "minHostVersion": "2.0.0",
  "description": "What the plugin does",
  "author": "Developer name",
  "icon": "icon.png",
  "permissions": ["H5_WEBVIEW", "TERMUX_SESSION_ACCESS", "FILE_SYSTEM_READ"],
  "entryPoints": {
    "resourceCards": [],
    "settingItems": [],
    "agentSkills": [],
    "h5Home": {},
    "pages": []
  },
  "systemPrompt": {}
}
```

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | string | yes | — | Unique plugin ID, reverse-domain format `^[a-zA-Z][a-zA-Z0-9_.]*$` |
| `name` | string | yes | — | Display name |
| `version` | string | yes | — | Semantic version |
| `minHostVersion` | string | no | `2.0.0` | Minimum host version; older hosts refuse to install |
| `description` | string | no | `""` | Short description |
| `author` | string | no | `""` | Author |
| `icon` | string | no | `null` | Icon path, relative |
| `permissions` | string[] | no | `[]` | Permission list |
| `entryPoints` | object | no | `null` | Entry point config (resource cards / settings / skills / H5 home / sub-pages) |
| `systemPrompt` | object | no | `null` | System prompt modification strategy |

## 5. Permissions

Plugins declare the permissions they need and the host requests user consent at use time:

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

## 6. Resource cards

Declare them under `entryPoints.resourceCards`. Tapping a card runs its `action`. Four action types are supported:

```json
{
  "entryPoints": {
    "resourceCards": [
      {
        "id": "my_shell",
        "title": "Run command",
        "description": "Runs pkg install",
        "action": { "type": "SHELL_COMMAND", "command": "pkg install git -y" }
      },
      {
        "id": "my_url",
        "title": "Open link",
        "description": "Opens the external browser",
        "action": { "type": "OPEN_URL", "url": "https://example.com" }
      },
      {
        "id": "my_host",
        "title": "Open VNC settings",
        "description": "Calls a native host entry (new in v2.0.0)",
        "action": { "type": "HOST_ACTION", "hostActionId": "open_vnc_settings" }
      },
      {
        "id": "my_custom",
        "title": "Custom",
        "description": "Handled by host extensions",
        "action": { "type": "CUSTOM" }
      }
    ]
  }
}
```

| `action.type` | Required fields | Description |
|---------------|-----------------|-------------|
| `SHELL_COMMAND` | `command` | Run a command in the Termux shell (needs `TERMUX_SESSION_ACCESS` or `ROOT_EXECUTE`) |
| `OPEN_URL` | `url` | Open a link in the external browser |
| `HOST_ACTION` | `hostActionId` | Call a native entry registered in the host `HostActionRegistry` (see section 10) |
| `CUSTOM` | — | Custom type handled by host extensions |

> `id` must be unique within a plugin; the exposed card ID is `{pluginId}.{cardId}`.

## 7. Custom agent skills

Declared under `entryPoints.agentSkills`. The AI assistant injects them into the skill cards of its system prompt:

```json
{
  "entryPoints": {
    "agentSkills": [
      {
        "id": "demo_check_env",
        "name": "Check environment",
        "description": "Check whether the Termux runtime is healthy",
        "category": "System",
        "handler": "echo 'Checking environment...' && which bash && which pkg && echo 'OK'",
        "requiresClick": false,
        "hasOutput": true,
        "riskLevel": "NONE"
      },
      {
        "id": "demo_root_op",
        "name": "ROOT operation",
        "description": "High-risk operation example",
        "category": "High risk",
        "handler": "su -c 'mount | grep system'",
        "requiresClick": true,
        "hasOutput": true,
        "riskLevel": "HIGH"
      }
    ]
  }
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `id` | string | — | Unique skill ID, exposed as `{pluginId}.{skillId}` |
| `name` | string | — | Skill card display name |
| `description` | string | — | What the skill does; the AI uses it to decide when to call |
| `category` | string | — | Skill category |
| `handler` | string | — | Handling logic, currently a shell command |
| `requiresClick` | boolean | `true` | Whether the user must tap to confirm execution |
| `hasOutput` | boolean | `false` | Whether output is echoed back |
| `riskLevel` | string | `"NONE"` | `NONE` / `LOW` / `MEDIUM` / `HIGH` / `CRITICAL` |
| `cardFormat` | object | `null` | Custom card rendering (only needed if the plugin changes prompt card logic) |

> `cardFormat` is optional. If the plugin does not change the system prompt card logic, default rendering is used.

## 8. System prompt extension

```json
{
  "systemPrompt": {
    "mode": "APPEND",
    "content": "## Extra instructions from plugin \"My Plugin\"\nYou can also use:\n- skill demo_check_env to check the environment\n- skill demo_hello to say hello\n- the demo resource card to run commands quickly",
    "cardFormat": null
  }
}
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `mode` | string | `"APPEND"` | `APPEND` / `MODIFY` / `OVERWRITE` |
| `content` | string | — | Inline text written straight into the system prompt (**file paths are not supported**) |
| `cardFormat` | object | `null` | Custom card format (optional) |

Modes:

- `APPEND`: append to the end of the core prompt (low risk, takes effect immediately)
- `MODIFY`: replace a specific section (medium risk)
- `OVERWRITE`: fully replace the core rules (**extremely high risk**; shows the `PluginOverwriteDialog` confirmation before taking effect)

> **Note**: `content` is inline text and does not support file path references. In `OVERWRITE` mode the plugin's system prompt replaces the original entirely, and the custom system prompt entry in settings becomes "Restore system prompt".

## 9. H5 multi-page interface (h5Home + pages)

`pages[].type` can be `"h5"` (WebView loading HTML) or `"compose"` (native rendering, see section 11).

```json
{
  "entryPoints": {
    "h5Home": {
      "enabled": true,
      "entry": "web/index.html",
      "title": "Plugin home title"
    },
    "pages": [
      { "id": "page_about", "title": "About", "type": "h5", "entry": "web/about.html" },
      { "id": "page_settings", "title": "Settings", "type": "h5", "entry": "web/settings.html" },
      { "id": "page_compose_home", "title": "Native page", "type": "compose", "entry": "compose/home.json" }
    ]
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `h5Home.enabled` | boolean | yes | Whether the H5 home page is enabled |
| `h5Home.entry` | string | yes | Home entry file path (relative to plugin root, default `web/index.html`) |
| `h5Home.title` | string | no | Home display name (falls back to the plugin name) |
| `pages[].id` | string | yes | Unique sub-page identifier |
| `pages[].title` | string | yes | Sub-page display name |
| `pages[].icon` | string | no | Sub-page icon path, relative |
| `pages[].type` | string | yes | `h5` (WebView) or `compose` (native) |
| `pages[].entry` | string | yes* | Entry file path (required for compose) |

**Navigating between H5 pages**: use relative paths inside the WebView (same directory):

```html
<a href="about.html">About</a>
<a href="settings.html">Settings</a>
```

> The WebView resolves relative paths against the directory of the current HTML file; external `http://` / `https://` links are intercepted and opened in the system browser.

### 9.1 JS bridge API

H5 pages reach native capabilities through `window.TermuxUltra`:

| API | Returns | Description | Since |
|-----|---------|-------------|-------|
| `getPluginInfo()` | JSON string | Plugin metadata (id / name / version / enabled / permissions) | v1.2.0 |
| `getConfig()` | JSON string | Plugin config map | v1.2.0 |
| `setConfig(key, value)` | boolean | Save a config entry to SharedPreferences | v1.2.0 |
| `exec(command)` | JSON string | Run a terminal command `{success, output\|error}` | v1.2.0 |
| `readFile(path)` | JSON string | Read a file inside the plugin package `{success, content\|error}` | v1.2.0 |
| `openUrl(url)` | void | Open a link in the external browser | v1.2.0 |
| `toast(message)` | void | Show a Toast | v1.2.0 |
| `getDeviceInfo()` | JSON string | Device info (model / brand / androidVersion / sdkVersion / termuxVersion / rootAvailable) | v1.2.0 |
| `finishPage()` | void | Close the current plugin page | v1.2.0 |
| `hostAction(actionId)` | JSON string | Call a host action registered in `HostActionRegistry` | v2.0.0 |
| `navigate(pageId)` | JSON string | Jump to another page of the same plugin (compose or h5) | v2.0.0 |

**Example**:

```javascript
var bridge = window.TermuxUltra;

// 1. Basic calls
var info = JSON.parse(bridge.getPluginInfo());
var result = JSON.parse(bridge.exec('echo Hello from ' + info.name));
bridge.toast('Command finished');

// 2. Config persistence
bridge.setConfig('last_open', new Date().toISOString());
var cfg = JSON.parse(bridge.getConfig());
bridge.toast('Last opened: ' + cfg.last_open);

// 3. Call native host entries (new in v2.0.0)
bridge.hostAction('open_vnc_settings');     // open the AVNC settings page
bridge.hostAction('open_plugin_center');    // open the plugin center
bridge.hostAction('open_system_settings');  // open Termux Ultra system settings

// 4. Navigate to other pages of the same plugin (new in v2.0.0)
bridge.navigate('page_about');        // jump to the H5 page with pages[].id = "page_about"
bridge.navigate('page_compose_home'); // works even if the target is a compose page
```

> `hostAction` / `navigate` return `{"success": true/false}`; they return `false` when the `actionId` is not registered or the `pageId` cannot be found.

## 10. Host action bridge (HostActionRegistry)

`HostActionRegistry` is a host-side Kotlin singleton holding an `actionId → handler` map, called from three places:

1. **Resource cards**: `action.type = "HOST_ACTION"` + `action.hostActionId`
2. **Compose nodes**: `onClick: "action:xxx"`
3. **H5 JS bridge**: `bridge.hostAction("xxx")`

The host registers all built-in entries in `HostActionRegistry.registerDefaults()` at startup. **Plugin authors can only reference already-registered IDs — they cannot register new ones** (adding host extensions requires modifying host source).

| Call site | Code |
|-----------|------|
| manifest.json resource card | `{"action": {"type":"HOST_ACTION","hostActionId":"open_vnc_settings"}}` |
| Compose node onClick | `"onClick": "action:open_vnc_settings"` |
| H5 JS bridge | `bridge.hostAction("open_vnc_settings")` |

### 10.1 Built-in host actions

| `hostActionId` | Description |
|----------------|-------------|
| `open_vnc_settings` | Open the AVNC settings page |
| `open_termux_styling` | Open Termux:Styling (colors / fonts) |
| `open_termux_tasker` | Open Termux:Tasker |
| `open_termux_widget` | Open Termux:Widget |
| `open_plugin_center` | Open the plugin center |
| `open_system_settings` | Open Termux Ultra system settings |

> The host can register more entries in `HostActionRegistry.registerDefaults()` — adding a native page costs a single `register(...)` line.

## 11. Compose JSON DSL pages

Instead of HTML, a plugin can describe a page in JSON that the host `ComposeRenderer` renders natively. Set `type` to `"compose"` in `pages[]` and point `entry` at a JSON file:

```json
{
  "entryPoints": {
    "pages": [
      {
        "id": "page_compose_home",
        "title": "Native page",
        "type": "compose",
        "entry": "compose/home.json"
      }
    ]
  }
}
```

`compose/home.json` is a `ComposeUiNode` tree shaped as `{ "type", "props", "children" }`:

```json
{
  "type": "column",
  "props": { "padding": 12 },
  "children": [
    {
      "type": "card",
      "props": { "title": "Device status" },
      "children": [
        {
          "type": "listItem",
          "props": {
            "title": "Open VNC settings",
            "subtitle": "Jump to the AVNC settings page",
            "onClick": "action:open_vnc_settings"
          }
        },
        {
          "type": "listItem",
          "props": {
            "title": "Show uname",
            "subtitle": "Runs a shell command",
            "onClick": "shell:uname -a"
          }
        },
        {
          "type": "listItem",
          "props": {
            "title": "About page",
            "subtitle": "Jump to the H5 about page",
            "onClick": "nav:page_about"
          }
        }
      ]
    },
    {
      "type": "switch",
      "props": {
        "stateKey": "auto_refresh",
        "label": "Auto refresh",
        "onChange": "shell:echo auto_refresh={value} >> ~/.termux/plugin.cfg"
      }
    },
    {
      "type": "button",
      "props": {
        "text": "Open website",
        "onClick": "https://example.com"
      }
    },
    {
      "type": "lazyColumn",
      "props": {
        "itemsSource": { "type": "shell", "command": "adb devices" },
        "itemTemplate": {
          "type": "listItem",
          "props": {
            "title": "{serial}",
            "subtitle": "{status}",
            "onClick": "shell:adb -s {serial} shell echo hi"
          }
        }
      }
    }
  ]
}
```

### 11.1 Supported Compose node types

| type | Key props | Description |
|------|-----------|-------------|
| `column` | `padding` | Vertical layout, children in `children` |
| `row` | — | Horizontal layout |
| `text` | `text`, `fontSize`, `fontWeight` (`"Bold"`) | Text |
| `card` | `title` | Card container, children in `children` |
| `listItem` | `title`, `subtitle`, `onClick` | List item; tap triggers an action |
| `switch` | `stateKey`, `label`, `onChange` | Switch; value persisted to plugin config |
| `button` | `text`, `onClick` | Button |
| `slider` | `stateKey` | Slider; value stored in the state store |
| `spacer` | `height` | Spacing (dp) |
| `divider` | — | Horizontal divider |
| `lazyColumn` | `itemsSource`, `itemTemplate` | Dynamic list, supports shell data sources |

> `lazyColumn.itemsSource` currently supports only `{ "type": "shell", "command": "..." }`. Shell output is parsed as a JSON array first, falling back to whitespace-separated lines (fields mapped to `serial` / `status` / `raw`). `itemTemplate` props support `{key}` placeholders.

### 11.2 Action string protocol (onClick / onChange)

| Prefix | Example | Description |
|--------|---------|-------------|
| `shell:` | `shell:uname -a` | Run a command in the plugin shell (needs `TERMUX_SESSION_ACCESS` or `ROOT_EXECUTE`) |
| `action:` | `action:open_vnc_settings` | Call a host action (see section 10.1) |
| `nav:` | `nav:page_about` | Navigate to another page of the same plugin (matched by `pages[].id`, compose or h5) |
| `http://` / `https://` | `https://example.com` | Open in the external browser |
| `{value}` | `shell:echo {value}` | In `switch.onChange`, replaced with the current boolean (`true` / `false`) |

> A string matching no prefix is ignored (returns `false`).

## 12. Plugin persistent sessions (host-side Kotlin API)

`PluginPersistentSession` is a **long-lived shell session** offered by the host to Kotlin code (custom agent skill handlers, Compose rendering extensions, native modules). It is **not exposed through the JS bridge**.

Unlike `PluginManager.executeShellCommand()` (which spawns a new process each call), a persistent session stays alive until the plugin closes it explicitly or `TermuxService` is destroyed.

```kotlin
// 1. Open a session (requires TERMUX_SESSION_ACCESS)
val session = PluginManager.openPersistentSession(
    context,
    pluginId = "com.example.myplugin",
    sessionName = "My Plugin Session"
) ?: return  // null when permission is missing or creation fails

// 2. Write a command and read incremental output
session.writeln("ls -la /data")
Thread.sleep(200)
val output = session.readNew()  // new transcript since the last read

// 3. Control signals
session.interrupt()  // Ctrl+C, interrupt the foreground program
session.sendEof()    // Ctrl+D

// 4. State queries
val running = session.isRunning
val cwd = session.cwd           // current working directory (may be null)
val pid = session.pid           // process PID (0=not started, >0=running, -1=finished)
val exit = session.exitCode     // exit code (only valid once finished)

// 5. Close the session (idempotent, safe to call repeatedly)
PluginManager.closePersistentSession(session.sessionId, "com.example.myplugin")
```

### 12.1 API overview

| Member | Description |
|--------|-------------|
| `sessionId` | Unique session ID (`${pluginId}::${first 8 chars of uuid}`) |
| `pluginId` | Owning plugin ID |
| `sessionName` | Session name (shown on the terminal page) |
| `isRunning` | Whether it is alive |
| `cwd` | Shell current working directory (may be null) |
| `pid` | Process PID |
| `exitCode` | Exit code |
| `write(data)` | Write raw bytes (stdin) |
| `writeln(line)` | Write a line of text (newline appended) |
| `executeCommand(cmd)` | Equivalent to `writeln` |
| `readNew(mark=true)` | Read new output incrementally (advances the cursor by default; `mark=false` peeks) |
| `readAll()` | Read the whole transcript (does not move the cursor) |
| `resetReadCursor()` | Reset the cursor to the end, ignoring history |
| `interrupt()` | Send Ctrl+C (0x03) |
| `sendEof()` | Send EOF (0x04) |
| `close()` | End the session (SIGKILL, idempotent) |

> Sessions auto-register into `TermuxService.mTermuxSessions`, so the terminal page can manage them too. When a session exits, `PluginPersistentSessionRegistry` resolves the session id via `TerminalSession.mHandle` and cleans up the `PluginManager` registry. Same-plugin access is verified by `PluginPersistentSession.pluginId`; cross-plugin access logs a warning and returns null.

## 13. Packaging and installation

1. Lay out the plugin files per the structure above
2. Zip them and rename to `.tup`
3. Push the `.tup` file to the device
4. Open `Termux Ultra → Resources page → Plugin center → Install from file`

```bash
# Packaging example
cd my-plugin
zip -r ../my-plugin.tup .
adb push ../my-plugin.tup /sdcard/Download/
```

## 14. Debugging tips

- Plugin loading logs: `Settings → Debug → log level` set to `Verbose`
- H5 home page debugging: use Chrome DevTools to remotely debug the WebView
- Permission testing: revoke a permission on the plugin management page and call the API again to exercise the grant flow

## 15. Example plugin

The `demo-plugin/` directory of the project contains a complete example plugin (v1.1.0) demonstrating:

- A multi-page H5 interface (home + about + settings) configured via `h5Home` + `pages`
- Full JS bridge usage (`window.TermuxUltra`)
- Resource card definitions (`SHELL_COMMAND` type)
- Agent skill definitions (custom handlers)
- System prompt appending (`APPEND` mode)
- Plugin config persistence (`setConfig` / `getConfig`)
- The complete packaging flow into `.tup`

Contents: `manifest.json`, `README.md`, `web/index.html`, `web/about.html`, `web/settings.html`.
