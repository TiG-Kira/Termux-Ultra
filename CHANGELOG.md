# Changelog

## 1.7.0 (2026-09-12)

> ⚠️ **原增强防护模块已全面改版升级为 VorteX Guard Engine (VGE)**，Shell hook 架构重写，彻底解决长时间运行后终端无提示符、输入无回显等问题

### ✨ 新功能

- **VorteX Guard Engine (VGE)** — 终端安全检测模块正式上线
  - 高危命令拦截：su/sudo/dd/mkfs/fdisk/shutdown 等
  - 脚本执行检测：bash/sh/zsh/ksh/dash/fish 脚本 + `./script.sh` 直接执行
  - 危险参数组合检测：`rm -rf /`、`chmod -R 777 /`、`--no-preserve-root` 等
  - 增强模式：OFF（关闭）/ WARN_ONLY（仅提示）/ AUTO_BLOCK（自动拦截）/ WARN_VERIFY（警告并弹窗验证）
  - 服务端二次确认弹窗 + Snackbar 提示 + 自动拦截三种响应
  - Agent 智能判定接入（配置 AI 后自动分析脚本风险）
  - 设置实时生效：增强模式切换后 hook 与 SecuritySocketServer 立即重启

### 🐛 Bug 修复

- **[核心] 解决 DENY/PASS 后终端无提示符、输入无回显、oh-my-bash 主题崩坏**
  - 根因：v10 之前 Shell hook 的 `exec 3<>/dev/tcp/...` 在父进程修改 fd 表，导致 PTY termios 被意外修改（ECHO 关闭）
  - 修复：所有 TCP 通信放入子 Shell `( ... )` 执行，子进程退出后 fd 改动自动消失，父进程零影响
  - 验证：手动执行普通命令（cat/ls/echo）不再触发 prompt 消失

- **[核心] 解决 DEBUG trap extdebug 全局常开干扰 PS1 渲染**
  - 根因：v10 之前 extdebug 在 trap 内常开，导致 oh-my-bash 的 prompt 渲染函数内部每条命令都被 DEBUG trap 拦截，trap return 1 时 bash 跳过 PS1 渲染
  - 修复：extdebug 仅在 DENY 跳过命令时瞬时开启（只为让 bash 评估 trap 返回值跳过命令），trap 开头立刻 `shopt -u extdebug`，precmd 兜底 `shopt -u extdebug`
  - 白名单优化：trap 内对 tas_*/__tas_*/_omb_*/trap/shopt 等命令直接 return 0，零干扰

- **[核心] OMB/OMZ 初始化脚本被送检测**
  - 根因：hook source 阶段 DEBUG trap 已安装，OMB 内部调用的 `sh`/`bash`/`source` 也被拦截，导致初始化卡死
  - 修复：
    1. 新增 `__TAS_READY` 标记，hook source 完成后才设为 1，初始化阶段 wrapper 检测到未就绪直接 `command` 放行
    2. DEBUG trap 安装延迟到首次 precmd 执行时
    3. 5 秒宽限期：trap 安装后 5 秒内只记录日志不送 TCP

- **[核心] JNI PTY termios 未显式设置 ECHO**
  - 根因：Android `/dev/ptmx` 默认 termios lflag 中 ECHO 位关闭，bash 继承后 readline 不回显
  - 修复：`termux.c` 在 fork 后子进程打开 PTS、dup2 前显式设置 `ECHO|ICANON|ISIG`

- **[修复] TerminalSession PTY 输出管线因异常静默死亡**
  - 根因：`launchEmulatorProcessor` 协程无 try-catch，emulator.append() 抛异常后协程崩溃，chunk 池耗尽导致 launchInputReader 永久阻塞
  - 修复：全包 try-catch + while 循环自重启 + 最大 5 次 + 100ms 退避

- **[修复] TerminalSession 自递归 StackOverflow**
  - 根因：catch 块里直接 `launchEmulatorProcessor()` 自调用，持续性 crash 时无限递归
  - 修复：改为 `while` 循环 + `consecutiveCrashes` 计数 + 最大重试 5 次

- **[修复] 加载时 `#!/system/bin/sh: No such file or directory`**
  - 根因：Windows Write 工具自动添加 UTF-8 BOM，bash 把 BOM+shebang 当成命令执行
  - 修复：打包前 PowerShell 手动移除 BOM

- **[修复] shell 命令覆盖导致 OMB 初始化报错**
  - 根因：覆盖 `bash`/`sh`/`zsh` 后，OMB 内部调用 `sh` 时递归或找不到真正 shell
  - 修复：初始化阶段 `__TAS_READY` 未设时 wrapper 直接 `command` 放行

### 🏗️ 架构优化

- **Shell hook 重构（v10 → v21）**
  - v10：trap + extdebug 瞬时开关 + DENY 抑制窗
  - v15：OMB/OMZ 插件注入方案探索
  - v17：零 trap 函数覆盖版（证明 trap 是问题根源）
  - v20：TCP 子 Shell 隔离（证明 TCP fd 操作是问题根源）
  - v21：trap + 子 Shell TCP（当前稳定版）

- **检测方式分层**
  | 命令类型 | 检测方式 | 干扰程度 |
  |----------|----------|----------|
  | 高危单词（su/sudo/dd/mkfs...） | 函数覆盖 | 零 |
  | shell 命令（bash/sh/zsh...） | 函数覆盖 + 初始化宽限期 | 极低 |
  | 直接执行脚本（./script.sh） | DEBUG trap | 低（仅 ./..//* 开头检测） |
  | 危险参数（rm -rf /, chmod 777 /） | 函数内参数匹配 | 零 |
  | 普通命令（cat/ls/echo...） | 不包装 | 零 |

- **Kotlin/Java 侧**
  - `RiskConfirmManager.setProtectionLevel()` 新增 `applySecurityConfiguration()`，增强模式切换后 hook + server 实时重启
  - `TermuxService.deploySecurityHook()` 改为 `public static`，让 RiskConfirmManager 可调用
  - `SecuritySocketServer` 请求上限从 64KB 调整为 128KB，支持 base64 编码脚本内容
  - `handleCheckScript` 支持 base64 脚本内容 + Agent 判定接入
  - `TerminalSession.launchEmulatorProcessor` / `launchInputReader` 全包异常捕获 + 自重启

### 📝 已知局限

- `source script.sh` 执行的脚本暂不拦截（bash 内置命令，不能被函数覆盖）
- `./script.sh` 依赖 DEBUG trap，EXTDEBUG 瞬时开关在极端场景（脚本内部函数调用时 return 1）可能仍有轻微影响
- Android toybox `stty` 在部分设备上可能不生效（JNI termios 修复已兜底）

### 📦 版本信息

- versionCode: 1100
- versionName: 1.7.0
- Termux 核心版本: 0.118.3
- 新星引擎 (LibTerminal) 版本: 3.0.0
