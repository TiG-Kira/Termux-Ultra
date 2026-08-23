# Termux Ultra 2.0.0.RB 插件系统执行大纲

> **版本**: 2.0.0.RB  
> **类型**: 破坏性版本更新  
> **状态**: 待确认

---

## 第一阶段：插件框架基础设施

**目标**：建立插件系统的核心数据模型和管理框架

### 1.1 插件数据模型定义

- `PluginManifest` 数据类（插件清单，存放在插件 ZIP 包根目录的 `manifest.json`）
  - id、名称、版本、描述、作者、图标资源
  - 权限声明列表
  - 入口点列表（资源卡片、设置项、Agent Skill、H5 主页等）
  - System Prompt 扩展声明（追加/修改/覆盖）
  - Skill 卡片格式声明
  - 打包格式说明：ZIP 格式（`.tup` 后缀，内部结构见附录）

- `PluginPermission` 枚举
  - ROOT_EXECUTE、TERMUX_SESSION_ACCESS、AGENT_MODIFY、FILE_SYSTEM_ACCESS、INTERNET_ACCESS、H5_WEBVIEW

- `PluginState` 枚举
  - INSTALLED、ENABLED、DISABLED、CORRUPTED、NEEDS_PERMISSION

- `PluginEntryPoint` 数据类
  - 类型：RESOURCE_CARD、SETTING_ITEM、AGENT_SKILL、H5_HOME、PAGE

### 1.2 插件管理器 (PluginManager)

- 单例 PluginManager 管理类
- 核心方法：
  - `installPlugin(source, manifest)` → 解析清单、校验签名、提取文件、注册到系统
  - `uninstallPlugin(pluginId)` → 清理插件数据、注销入口、移除权限
  - `enablePlugin(pluginId)` → 加载插件资源、注册入口点、激活扩展
  - `disablePlugin(pluginId)` → 保留数据、移除运行时注册、暂停扩展
  - `getActivePlugins()` → 返回当前启用的插件列表
- 插件文件存储位置：`/data/data/com.termux/files/plugins/{plugin_id}/`

### 1.3 插件存储与持久化

- SharedPreferences 存储插件注册表（清单信息、权限状态、启用状态）
- JSON 格式的插件配置文件解析
- 插件数据隔离（每个插件独立目录，包含：manifest.json、skills/、web/、assets/）
- 插件配置项持久化（用户对插件设置的修改独立存储）

---

## 第二阶段：插件 UI 框架

**目标**：构建插件管理的完整用户界面

### 2.1 插件入口界面（资源页）

- 在 `ResourcesScreen` 添加「插件中心」入口卡片（与实用工具中心、第三方中心并列）
- `PluginCenterActivity` 插件中心主页面
  - 分类浏览：已安装 / 可用 / 推荐
  - 搜索框支持插件搜索
  - 插件卡片展示（图标、名称、描述、状态、操作）

### 2.2 插件管理界面

- 插件列表页（卡片式展示插件信息、状态、操作按钮）
  - 启用/禁用开关
  - 权限状态指示
  - 卸载按钮
  - 进入 H5 主页按钮（若有）

- 插件详情页
  - 插件基本信息（名称、版本、作者、描述）
  - 权限声明列表（每项显示是否已授予）
  - System Prompt 修改摘要（若有）
  - Skill 卡片格式说明（若有）
  - H5 主页预览入口（若有）
  - 配置管理入口
  - 启用/禁用/卸载操作

- 插件安装对话框
  - 从文件选择插件包（.zip / .tup 格式）
  - 权限声明预览与确认
  - System Prompt 修改预览与警告

### 2.3 插件 H5 主页支持

- `PluginWebViewActivity` 通用 H5 宿主
  - WebView 加载插件自带的 H5 主页
  - JavaScript Bridge 暴露 Termux Ultra API（需权限校验）
  - 支持的 Bridge 方法：
    - `termux.exec(command)` → 执行命令
    - `termux.getSessions()` → 获取会话列表
    - `termux.readFile(path)` → 读取文件
    - `termux.requestPermission(permission)` → 请求权限
  - 安全隔离：WebView 单独进程 + 权限白名单

### 2.4 全局插件设置界面

- 全局插件设置页
  - 自动加载新安装插件（开关）
  - 插件间通信开关
  - H5 主页安全等级（严格/标准/宽松）
  - System Prompt 覆盖确认策略（每次询问/记住选择/禁止覆盖）
  - 插件执行全局超时设置

---

## 第三阶段：插件核心功能实现

**目标**：实现插件对现有功能的扩展/屏蔽机制

### 3.1 功能屏蔽机制

- `PluginFeatureFilter` 接口
  - 插件可声明要屏蔽的功能点
  - 屏蔽范围：
    - 系统设置项（隐藏指定设置分组/条目）
    - 资源卡片（隐藏官方/第三方资源）
    - 导航页面（移除底部导航项）
    - Agent 技能（禁用指定 Skill 类型）
  - 与 `ApiCompat` 集成：插件可动态影响 `isFeatureUsable()` 判断
  - 屏蔽状态持久化（用户可在插件管理页恢复，或在全局设置中临时覆盖）
  - 冲突处理：多个插件屏蔽同一功能时，以先启用的插件为准，后启用的给出冲突提示

### 3.2 资源页入口扩展

- `PluginResourceEntry` 接口
  - 插件提供资源卡片数据：title、description、icon、action、category
  - 在 `ResourcesScreen` 的 LazyColumn 中动态插入插件条目
  - 分组显示：官方资源 → 第三方资源 → 插件资源（带插件标识角标）
  - 复用 `ResourceCard` 组件，支持自定义卡片样式
  - 点击触发插件定义的 action（执行命令/跳转 URL/H5 页面）

### 3.3 设置项目扩展

- `PluginSettingItem` 接口
  - 插件提供设置项数据：key、label、type(switch/text/select/slider)、default、options
  - 在 `SettingsScreen` 中注入插件设置分组
  - 设置项 UI 自动生成（基于类型映射到 miuix 组件）
  - 插件设置项值持久化（存入插件专属 SharedPreferences）
  - 设置项变更回调通知插件

### 3.4 导航页扩展

- `PluginPage` 接口
  - 插件注册自定义底部导航页
  - 扩展 `ApiCompat.Page` 枚举，支持动态添加
  - `MainScreen` 动态添加导航项和页面路由
  - 插件页面通过 H5 或原生 Composable 实现

### 3.5 插件主页（H5）接口

- `PluginHomeProvider` 接口
  - 插件提供 H5 主页 URL（本地 assets 或远程 URL）
  - `PluginWebViewActivity` 统一宿主
  - 支持的 Bridge API：
    - `plugin.getConfig()` → 获取插件配置
    - `plugin.setConfig(key, value)` → 保存配置
    - `plugin.openResource(id)` → 打开关联资源
    - `plugin.execShell(command)` → Shell 执行
    - `plugin.toast(message)` → Toast 提示
  - H5 页面安全：
    - 本地资源优先（打包在插件内）
    - 远程 URL 需 HTTPS + 域名白名单
    - JavaScript 注入受权限控制

---

## 第四阶段：权限与执行系统

**目标**：实现插件的权限管理和代码执行能力

### 4.1 插件权限管理

- 权限声明模型
  - 插件 manifest 中声明所需权限
  - 权限等级：NORMAL（无需请求）/ DANGEROUS（首次使用请求）/ ROOT（高危需确认）
  - 权限类型：
    - `ROOT_EXECUTE` — ROOT 命令执行
    - `TERMUX_SESSION_ACCESS` — 会话读写
    - `AGENT_CONTROL` — Agent 行为控制
    - `FILE_SYSTEM_READ` — 文件读取
    - `FILE_SYSTEM_WRITE` — 文件写入
    - `INTERNET_ACCESS` — 网络访问
    - `H5_WEBVIEW` — H5 主页加载
    - `CROSS_APP_BRIDGE` — 跨应用联动
  - 权限请求流程：
    - 插件首次调用需权限的 API 时弹出请求对话框
    - 对话框显示：权限名称、用途说明、风险等级
    - 用户可选：允许 / 拒绝 / 本次允许
    - 权限状态持久化，可随时在插件管理页撤销

### 4.2 ROOT 权限桥接

- `PluginRootExecutor` 桥接类
  - 复用 `RiskConfirmManager.hasRootAccess()` 检测逻辑
  - 统一接口：`executeWithRoot(command, callback)`
  - 权限链：插件权限检查 → ROOT 可用性检测 → 危险命令二次确认 → 执行
  - 危险命令检测：复用 `RiskCommandDetector`，插件定义的命令也走同一检测
  - ROOT 命令日志记录（便于审计）

### 4.3 Termux 会话执行引擎

- `PluginCommandExecutor` 统一执行接口
  - 三种执行模式：
    - 新会话执行：`executeInNewSession(command, sessionName)`
    - 现有会话执行：`executeInSession(sessionId, command)`
    - tmux 执行：`executeInTmux(command, sessionName)`
  - 复用 `TermuxService.createTermuxSession()` 和 `terminalSession.write()`
  - 执行结果回传：`ExecutionResult(success, output, error, duration)`
  - 超时控制：可设置执行超时，超时自动终止

### 4.4 文件系统访问

- 受控文件访问 API
  - `readFile(path, maxSize)` → 读取文件（限制大小）
  - `writeFile(path, content, append)` → 写入文件
  - `deleteFile(path)` → 删除文件
  - `listDir(path)` → 列出目录
  - 路径安全：禁止访问 `/system`、`/data`、其他应用私有目录（除非 ROOT 权限）
  - 路径白名单可在插件配置中自定义

---

## 第五阶段：Termux Agent 插件集成

**目标**：让插件能够扩展和修改 Termux Agent

### 5.1 Agent 扩展接口

- `AgentExtension` 接口
  - `getSystemPromptModifier()` → 返回 System Prompt 修改策略
  - `getCustomSkills()` → 返回插件提供的自定义 Skill 列表
  - `getSkillCardFormats()` → 返回插件自定义 Skill 卡片格式（**仅当修改了 Prompt 卡片格式时才需要实现，否则返回空列表**）
  - `getSkillHandlers()` → 返回 Skill 处理器映射
  - `getBehaviorModifications()` → 返回 Agent 行为修改项

### 5.2 System Prompt 修改机制

- **三种修改模式**（由插件在 manifest 中声明）：
  - `APPEND`（追加）：在核心 System Prompt 末尾追加插件内容
  - `MODIFY`（修改）：替换核心 System Prompt 中指定段落（需段落标识匹配）
  - `OVERWRITE`（覆盖）：完全替换核心 System Prompt（**高危操作**）

- **OVERWRITE 覆盖机制**：
  - 系统检测到 `OVERWRITE` 模式时自动弹出高危警告对话框
  - 警告内容：
    - 「此插件要求完全覆盖 Termux Agent 的核心安全规则」
    - 「覆盖后，Agent 将不再受限于系统内置的安全约束」
    - 「可能导致：危险命令无确认执行、假输出检测失效、文件操作无保护等」
  - 用户必须勾选「我已了解风险」并确认后才能授权
  - 授权状态持久化，可在插件管理页随时撤销
  - 系统保留原始核心 System Prompt 备份，撤销时可一键恢复

- **MODIFY 修改机制**：
  - 插件需指定要修改的段落标识（如 `## 三、绝对禁令`）
  - 系统定位到对应段落并替换为插件提供的内容
  - 修改部分有视觉标记（在 Agent 设置页显示「已被插件修改」）

- **APPEND 追加机制**：
  - 插件内容追加到核心 System Prompt 末尾
  - 追加内容带有插件来源标注
  - 追加内容可在 Agent 设置页查看

### 5.3 自定义 Skill 扩展

- `PluginSkill` 数据模型
  - id、name、description、category
  - skillType 字符串标识（如 `PLUGIN_XXX_ACTION`）
  - cardFormat：卡片格式定义（JSON Schema）**← 仅当插件修改了 Prompt 卡片格式时才需要提供**
  - handler：执行回调
  - requiresClick：是否需用户点击执行
  - hasOutput：是否有返回值
  - riskLevel：NONE / LOW / MEDIUM / HIGH / CRITICAL

- **Skill 卡片格式定义**
  - **⚠️ 前提条件：仅当插件修改了 Prompt 中的卡片格式逻辑时，才需要为每个自定义 Skill 提供卡片格式声明。若插件未修改 Prompt（仅追加内容或不涉及卡片格式），则无需提供此定义，系统将使用默认卡片渲染逻辑。**
  - 格式采用 JSON Schema 描述卡片 UI（仅在修改了 Prompt 卡片格式时使用）：
    ```json
    {
      "type": "object",
      "properties": {
        "title": { "type": "string", "description": "卡片标题" },
        "status": { "enum": ["running", "completed", "failed"] },
        "fields": {
          "type": "array",
          "items": {
            "name": "string",
            "value": "string",
            "icon": "string(optional)"
          }
        },
        "actions": {
          "type": "array",
          "items": {
            "label": "string",
            "actionId": "string",
            "style": "primary|secondary|danger"
          }
        }
      }
    }
    ```
  - 系统根据 Schema 自动渲染卡片 UI
  - 卡片样式需与现有 Skill 卡片视觉一致

- **Skill 执行分发**
  - 在 `SkillExecutor` 中添加插件 Skill 路由
  - 插件 Skill 优先匹配，未命中则走内置 Skill 逻辑
  - 执行结果回传格式与内置 Skill 保持一致

### 5.4 Agent 行为限制修改

- 可修改的限制项：
  - 最大输出长度（默认 4000 tokens）
  - 技能执行超时（默认 30 秒）
  - 自动执行白名单
  - 确认策略（自动/手动/危险命令强制确认）
  - 假输出检测开关
  - ROOT 自动提权策略

- 修改来源标注：Agent 设置页显示每项修改的来源（用户设置 / 插件修改）
- 优先级：用户手动设置 > 插件修改 > 系统默认

---

## 第六阶段：跨应用联动接口

**目标**：提供插件与其他应用联动的标准化接口

### 6.1 联动协议定义

- `PluginBridge` 接口
  - 消息格式：`BridgeMessage(sender, action, payload, timestamp, signature)`
  - 通信模式：
    - **Broadcast 模式**：Android Broadcast + Intent
    - **ContentProvider 模式**：受控数据共享
    - **Webhook 模式**：本地 HTTP 回调

### 6.2 联动实现

- 发送端 API
  - `bridge.send(action, payload)` → 发送联动消息
  - `bridge.broadcast(intentAction, extras)` → 发送 Broadcast
  - 需 `CROSS_APP_BRIDGE` 权限

- 接收端 API
  - `bridge.registerListener(action, callback)` → 注册消息监听
  - 系统级消息路由（基于 action 匹配）
  - 消息存储与转发（离线消息暂存）

- 安全验证
  - 发送方签名校验
  - 权限匹配检查
  - 消息速率限制

### 6.3 联动模板

- 预设常用联动模板：
  - Tasker 联动：发送任务完成通知
  - Auto.js 联动：触发自动化脚本
  - SSH 客户端联动：传递连接信息
  - 文件管理器联动：文件操作通知
  - 系统设置联动：快捷开关控制

---

## 第七阶段：版本升级与兼容

**目标**：完成破坏性版本升级的必要工作

### 7.1 版本号升级

- `versionCode`: 在当前版本基础上递增
- `versionName`: `2.0.0.RB`
- 在 `build.gradle` 中更新版本信息
- 更新 `AndroidManifest.xml` 中的版本声明

### 7.2 数据迁移

- 旧版 SharedPreferences 数据迁移
- 插件数据目录初始化（创建 plugins 目录和子目录结构）
- 兼容性检查（检测现有数据结构，确保平滑过渡）
- 迁移进度提示

### 7.3 降级保护

- 插件系统异常时自动降级到「无插件」模式
  - 检测 PluginManager 初始化异常
  - 检测插件加载时的崩溃
  - 自动禁用所有插件并提示用户
- 崩溃恢复机制
  - 记录导致崩溃的插件 ID
  - 下次启动时自动跳过该插件
  - 提供「安全模式」启动选项
- 安全模式
  - 启动时长按特定键跳过所有插件加载
  - 在设置中可手动触发安全模式

### 7.4 开发者文档

- 插件开发指南
  - 插件目录结构说明
  - manifest.json 格式规范
  - 权限声明说明
  - Skill 卡片格式规范
  - H5 主页开发指南
  - Bridge API 文档
- 示例插件
  - 最小插件示例
  - 带自定义 Skill 的插件示例
  - 带 H5 主页的插件示例
  - 跨应用联动插件示例
- 调试与发布说明

---

## 文件结构预览

```
app/src/main/java/com/termux/app/plugin/
├── PluginManager.kt              # 插件管理器核心
├── PluginManifest.kt             # 插件清单数据模型
├── PluginTypes.kt                # 插件类型、权限、状态枚举
├── PluginLoader.kt               # 插件加载器
├── PluginSecurity.kt             # 插件安全校验
├── PluginBridge.kt               # 跨应用联动桥
├── AgentExtension.kt             # Agent 扩展接口
├── SystemPromptModifier.kt       # System Prompt 修改引擎
├── SkillCardFormat.kt            # Skill 卡片格式解析器
├── engine/
│   ├── PluginCommandExecutor.kt  # 插件命令执行引擎
│   └── PluginRootExecutor.kt     # ROOT 权限桥接
├── bridge/
│   ├── BridgeMessage.kt          # 桥接消息模型
│   └── BridgeRouter.kt           # 消息路由
└── ui/
    ├── PluginCenterActivity.kt   # 插件中心主页
    ├── PluginDetailScreen.kt     # 插件详情页
    ├── PluginInstallDialog.kt    # 安装对话框
    ├── PluginSettingsScreen.kt   # 全局插件设置
    ├── PluginWebViewActivity.kt  # H5 主页宿主
    └── PluginSkillCardRenderer.kt # 自定义 Skill 卡片渲染器
```

---

## 插件打包格式（ZIP）

插件使用 **ZIP** 格式打包，文件扩展名建议为 `.tup`（Termux Ultra Plugin），但也接受 `.zip`。

### 插件 ZIP 内部结构

```
my-plugin.tup (ZIP)
├── manifest.json          # 插件清单（必须）
├── icon.png               # 插件图标（建议，192x192 PNG）
├── skills/                # 自定义 Skill 定义（可选）
│   ├── skill_xxx.json     # 单个 Skill 定义文件
│   └── ...
├── web/                   # H5 主页资源（可选）
│   ├── index.html         # H5 主页入口
│   ├── css/               # 样式文件
│   ├── js/                # 脚本文件
│   └── assets/            # 图片等资源
├── prompts/               # System Prompt 扩展（可选）
│   ├── system_prompt.md   # 追加/修改的 Prompt 内容
│   └── card_format.json   # 卡片格式定义（仅修改了 Prompt 卡片格式时需要）
└── assets/                # 其他资源（可选）
    ├── scripts/           # Shell 脚本
    └── ...
```

### manifest.json 格式

```json
{
  "id": "com.example.myplugin",
  "name": "我的插件",
  "version": "1.0.0",
  "minHostVersion": "2.0.0",
  "description": "插件功能描述",
  "author": "开发者名",
  "icon": "icon.png",
  "permissions": [
    "TERMUX_SESSION_ACCESS",
    "FILE_SYSTEM_READ",
    "FILE_SYSTEM_WRITE"
  ],
  "entryPoints": {
    "resourceCards": [
      {
        "id": "feature_1",
        "title": "功能一",
        "description": "功能描述",
        "icon": "icon_feature1.png",
        "action": {
          "type": "shell_command",
          "command": "echo 'Hello from plugin'"
        }
      }
    ],
    "agentSkills": [
      {
        "id": "SKILL_EXAMPLE",
        "name": "示例技能",
        "description": "技能描述",
        "category": "示例",
        "handler": "plugin_skill_example",
        "requiresClick": true,
        "hasOutput": false,
        "riskLevel": "LOW"
      }
    ],
    "h5Home": {
      "enabled": true,
      "entry": "web/index.html"
    }
  },
  "systemPrompt": {
    "mode": "APPEND",
    "content": "prompts/system_prompt.md"
  }
}
```

### 打包与安装

1. **开发阶段**：将文件按上述结构组织，压缩为 ZIP 文件
2. **签名验证**（可选）：插件可包含签名文件 `signature.sig` 用于校验
3. **安装方式**：
   - 资源页 → 插件中心 → 从文件安装
   - 文件管理器点击 `.tup` 文件触发安装
   - 通过 ADB 推送至 `/data/data/com.termux/files/plugins/install/` 后在插件中心刷新

---

## 关键设计决策

### System Prompt 三级修改权限

| 模式 | 行为 | 风险等级 | 系统行为 |
|------|------|----------|----------|
| APPEND | 追加内容 | 低 | 直接应用，附带来源标注 |
| MODIFY | 替换指定段落 | 中 | 高亮显示修改部分，用户可撤销 |
| OVERWRITE | 完全覆盖 | 极高 | 强制弹出风险警告，用户勾选确认后才允许 |

### Skill 卡片格式要求

- **前提条件：仅当插件修改了 Prompt 中的卡片格式逻辑时，才需要提供卡片格式声明。若插件未修改 Prompt（仅追加内容或不涉及卡片格式），则无需提供此定义，系统将使用默认卡片渲染逻辑。**
- 格式采用 JSON Schema 标准，系统自动校验
- 系统负责渲染，插件不直接操作 UI
- 卡片与内置 Skill 卡片视觉风格统一

### H5 主页安全

- 本地资源优先，远程 URL 需 HTTPS
- JavaScript Bridge 权限分级
- WebView 独立进程
- 加载内容审计日志

---

## 实施优先级

1. **P0（核心）**: 第一阶段（框架）+ 第三阶段（屏蔽/扩展）+ 第四阶段（权限/执行）
2. **P1（重要）**: 第二阶段（UI）+ 第五阶段（Agent 集成）
3. **P2（增强）**: 第六阶段（跨应用联动）+ 第七阶段（版本升级）
4. **P3（可选）**: H5 主页支持 + Skill 卡片格式扩展