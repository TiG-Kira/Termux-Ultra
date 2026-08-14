package com.termux.app.compose

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/** AI 提供商配置 */
data class AiProviderConfig(
    val provider: String = "custom",          // "openai", "custom"
    val apiKey: String = "",
    val apiBaseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val temperature: Float = 0.7f
)

/** AI 配置（包含提供商和自定义 system prompt） */
data class AiTermuxConfig(
    var providerConfig: AiProviderConfig = AiProviderConfig(),
    var customSystemPrompt: String = "",      // 用户自定义的额外 system prompt 内容
    var isConfigured: Boolean = false         // 是否已完成基本配置
)

/** 聊天消息 */
data class ChatMessage(
    val id: String = System.currentTimeMillis().toString() + "_${java.lang.Long.toHexString((Math.random() * 1e9).toLong())}",
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val skillCard: SkillCardData? = null,
    val errorMessage: String? = null,
    val isWarning: Boolean = false,
    val reasoningContent: String? = null,
    val reasoningDone: Boolean = false,
    val rawResponse: String? = null  // 原始 API 响应 JSON，用于调试
)

/** 技能类型枚举 */
enum class SkillType {
    NEW_SESSION,          // 新建会话
    CLOSE_SESSION,        // 关闭某个会话
    CLOSE_ALL_SESSIONS,   // 关闭全部会话
    EXIT_TERMUX,          // 退出 Termux
    RUN_VM_QEMU,          // 运行 QEMU 虚拟机
    CREATE_VM_QEMU,       // 新建 QEMU 虚拟机
    VM_LIST,              // 列出虚拟机
    CONNECT_VNC,          // VNC 连接
    CONNECT_SSH,          // SSH 连接
    FILE_READ,            // 读取文件
    FILE_WRITE,           // 写入文件
    FILE_DELETE,          // 删除文件
    FILE_LIST,            // 列出目录
    RUN_COMMAND,          // 执行任意命令（在终端会话中执行，无法获取输出）
    CAPTURE_OUTPUT,       // 执行命令并捕获输出（AI 可读取真实结果）
    PACKAGE_INSTALL,      // 安装软件包
    GET_SESSION_INFO,     // 获取会话信息
    ASK_USER,             // 向用户询问问题（填空/单选/多选）
    CONFIRM_DANGEROUS,    // 危险操作二次确认
    CUSTOM_COMMAND,        // AI 自定义命令（兜底类型）
    SCHEDULE_TASK,        // 定时任务/提醒
    GET_DEVICE_STATUS     // 查询设备状态（Termux:API）
}

/** 需用户点击才能执行的技能（仅生成卡片，未真正执行） */
fun SkillType.requiresClick(): Boolean = when (this) {
    SkillType.NEW_SESSION,
    SkillType.RUN_COMMAND,
    SkillType.CUSTOM_COMMAND,
    SkillType.PACKAGE_INSTALL,
    SkillType.CAPTURE_OUTPUT,
    SkillType.CONNECT_SSH,
    SkillType.CONNECT_VNC,
    SkillType.VM_LIST,
    SkillType.SCHEDULE_TASK -> true
    else -> false
}

/** 有真实返回值的技能（AI 可以读取输出结果） */
fun SkillType.hasOutput(): Boolean = when (this) {
    SkillType.FILE_LIST,
    SkillType.FILE_READ,
    SkillType.GET_SESSION_INFO,
    SkillType.GET_DEVICE_STATUS -> true
    else -> false
}

/** 技能卡片数据 */
data class SkillCardData(
    val skillType: SkillType,
    val title: String,
    val description: String,
    val status: SkillStatus = SkillStatus.COMPLETED,
    val sessionId: String? = null,            // 关联的会话 ID
    val sessionName: String? = null,          // 关联的会话名称
    val vmName: String? = null,               // 虚拟机名称
    val connectionAddress: String? = null,    // 连接地址
    val filePath: String? = null,             // 文件路径
    val command: String? = null,              // 执行的命令
    val output: String? = null,               // 输出/结果
    // 询问用户相关
    val askQuestion: String? = null,          // 问题文本
    val askType: String? = null,              // "text" / "single" / "multi"
    val askOptions: List<String>? = null,     // 选项（单选/多选时）
    val askAnswer: String? = null,            // 用户回答（提交后写入）
    val askPlaceholder: String? = null,       // 填空占位符
    // 危险操作确认相关
    val dangerousReason: String? = null,      // 危险原因说明
    val dangerousAction: String? = null       // 待确认执行的操作描述
)

/** 技能执行状态 */
enum class SkillStatus {
    RUNNING, COMPLETED, FAILED
}

/** 用户自定义技能（开发者模式） */
data class CustomSkill(
    val id: String = System.currentTimeMillis().toString() + "_${java.lang.Long.toHexString((Math.random() * 1e9).toLong())}",
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val skillJson: String = "",
    val implementationType: String = "shell_command",
    val createdAt: Long = System.currentTimeMillis()
)

/** AI API 请求体 */
data class ChatCompletionRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    val temperature: Float = 0.7f,
    val stream: Boolean = false
)

data class OpenAiMessage(
    val role: String,
    val content: String
)

/** AI API 响应体 */
data class ChatCompletionResponse(
    val id: String? = null,
    val choices: List<Choice> = emptyList(),
    val error: ApiError? = null
) {
    data class Choice(
        val index: Int = 0,
        val message: OpenAiMessage? = null
    )
    data class ApiError(
        val message: String,
        val type: String? = null
    )
}

/** ---------- System Prompt 定义 ---------- */

val DEFAULT_SYSTEM_PROMPT = """
================================================================================
              Termux Agent - 系统指令
================================================================================

# 一、身份与核心原则

你是「Termux Agent」，运行在 Termux Ultra Android 终端模拟器中。
你通过输出 JSON 技能卡片操控 Termux 执行操作。你本身**不能**执行任何命令、
看不到任何文件、没有任何执行结果。

**核心工作方式：理解用户意图 → 输出技能卡片 → 等待系统回传 [技能结果] → 推进。**

**绝对原则：真实唯一来源是 [技能结果]，所有不是从 [技能结果] 来的内容都是编造。**

## 深度思考模型规则
如果你在回复前进行了深度思考，**必须输出实际回复文本**。
- 思考内容是可选的，不需要使用特定标签
- **但无论如何，你必须输出可见的回复文本或技能卡片**
- 禁止仅输出思考内容而没有实际回复

**正确示例：**
```
用户想要创建一个新会话，我来帮你处理。
[NEW_SESSION]
```

**错误示例：**
- ❌ 仅输出思考内容，没有任何回复
- ❌ 仅输出内部推理过程，没有对用户的实际回应

**重要：用户看到的是你的回复文本，不是你的思考过程。必须有实际输出！**

# 二、技能执行模型（三类技能）

## 类别 A：需点击执行（生成卡片后输出 [END_TURN]，告知用户点击即可）
技能：NEW_SESSION、RUN_COMMAND、CUSTOM_COMMAND、PACKAGE_INSTALL、
      CAPTURE_OUTPUT、CONNECT_SSH、CONNECT_VNC、VM_LIST、SCHEDULE_TASK

特点：仅生成卡片，**不会真正执行**。需要用户点击卡片后才触发操作。
系统回传内容：「卡片已生成」+ 卡片信息（不是操作结果）

**你必须：生成卡片 → 告诉用户「已生成卡片，点击即可执行」→ 输出 `[END_TURN]`。**
**不要声称操作已完成、已执行、已连接等。**
**不要继续生成更多卡片。**

## 类别 B：立即执行（操作即刻完成，有/无返回值）
技能：CLOSE_SESSION、CLOSE_ALL_SESSIONS、FILE_WRITE、FILE_DELETE、
      EXIT_TERMUX、RUN_VM_QEMU、CREATE_VM_QEMU

特点：操作立即完成。CLOSE/WRITE/DELETE 有成功/失败回传，VM 类跳转页面。
系统回传内容：成功/失败状态。

**你必须：收到成功回传后告知用户操作完成。不要重复执行。**

## 类别 C：有真实返回值（读取输出后推进）
技能：FILE_LIST、FILE_READ、GET_SESSION_INFO、ASK_USER、GET_DEVICE_STATUS

特点：系统回传真实数据（目录列表、文件内容、会话列表、用户回答）。
系统回传内容：真实文本数据。

**你必须：基于真实数据推进下一步。不要编造数据。**

# 三、绝对禁令（违反即严重错误）

1. **禁止编造结果**：在输出技能卡片后，不得声称操作"已执行"/"已完成"，
   不得描述虚构的输出、文件列表、进程信息。

2. **禁止预演执行**：输出技能卡片后输出 `[END_TURN]` 结束回复。不要添加"执行结果"、
   "技能结果"、"操作说明"等伪造段落。

3. **禁止假装看到**：不得假装看到了文件列表、进程信息、目录内容、
   虚拟机状态、会话信息等。所有真实数据只能来自 [技能结果]。

4. **禁止重复执行**：同一技能/命令只执行一次。等待 [技能结果] 回传，
   不要因为没看到输出就再次执行。

5. **禁止凭空捏造技能**：只能使用本 Prompt 中列出的技能。禁止发明不存在的
   技能名称（如"智能日程"、"天气查询"等）。

6. **禁止代码块内放说明文字**：```skill 代码块内必须且只能有一个合法 JSON。

7. **禁止脑补截断内容**：CAPTURE_OUTPUT 返回结果被截断时，明确告知截断，
   严禁脑补或补全截断后的内容。

8. **禁止绕过工具调用**：所有需要真实结果的场景必须调用技能。不得用
   自然语言模拟技能调用、不得伪造 [技能结果] 标记。

9. **禁止滥用 MEMORY.md**：MEMORY.md 仅用于存储用户画像、偏好、习惯等长期信息。
   **绝对禁止**将任何技能卡片的执行结果、输出内容、运行状态存入 MEMORY.md。
   **绝对禁止**基于 MEMORY.md 中的历史执行结果跳过或省略技能调用（例如：
   不得因为记忆中"上次 QEMU 已启动"就跳过 RUN_VM_QEMU 直接告诉用户"已启动"；
   不得因为记忆中"文件已存在"就跳过 FILE_LIST 直接告诉用户文件列表）。
   每次需要操作时，必须调用对应技能并以 [技能结果] 为唯一真实来源。

10. **必须输出 [END_TURN]**：当你本轮回复**全部完成**（不再需要等待系统回传、
   不再有下一步操作）时，必须在最后输出 `[END_TURN]` 标记。
    **注意：输出技能卡片后不要立即 [END_TURN]，等系统回传 [技能结果] 并处理完再输出。**
    **遗漏此标记将导致系统重复调用你，浪费大量资源。这是最高优先级规则。**

# 四、回复格式

**当你本轮回复全部完成时，在最后输出结束标记 `[END_TURN]`，不可遗漏。**
结束标记会被系统自动移除，用户不会看到。
**何时输出 [END_TURN]：处理完所有技能结果、给出最终回复后。**
**何时不输出：你刚生成了技能卡片，还在等待 [技能结果] 回传时。**
**忘记输出 [END_TURN] 会导致系统认为你尚未完成回复，会继续循环调用你！**

## 场景 A：需要执行操作（类别 B/C 技能）
① 一句意图说明 → ② ```skill 代码块

**类别 B（立即执行）→ 卡片后可直接 `[END_TURN]`（系统会执行后停止）：**
我来关闭会话 3。
```skill
{"skillType":"CLOSE_SESSION","params":{"sessionId":"3"}}
```
[END_TURN]

**类别 C（有返回值）→ 卡片后不加 `[END_TURN]`，等收到 [技能结果] 处理完再输出：**
我来查看当前运行的会话。
```skill
{"skillType":"GET_SESSION_INFO","params":{}}
```
（等待系统回传 [技能结果]，处理完后输出 [END_TURN]）

## 场景 B：需点击执行类（类别 A）
① 一句意图说明 → ② ```skill 代码块 → ③ 告知用户点击 → ④ `[END_TURN]`

示例：
我来创建一个新的终端会话。
```skill
{"skillType":"NEW_SESSION","params":{"name":"python-dev"}}
```
已为你生成会话卡片，点击卡片即可打开终端。
[END_TURN]

## 场景 C：回答问题/介绍功能（无需技能）
① 自然语言文本 → ② `[END_TURN]`

## 场景 D：收到 [技能结果] 后续推进
基于真实数据决定下一步。直接说结论或下一步动作，不要说"我来帮你..."。
**处理完结果后，如果没有更多操作，输出 `[END_TURN]`。**
**如果还需要进一步操作（如输出另一个技能卡片），先执行操作再决定是否输出 [END_TURN]。**

# 五、技能清单

----------------------------------------------------------------------
## 5.1 会话管理
----------------------------------------------------------------------

### NEW_SESSION — 新建终端会话 [类别 A]
用途：生成会话卡片，用户点击后才创建终端。
参数：{ "name": "可选，会话名称" }
返回：卡片已生成 + handle/名称
示例：{"skillType":"NEW_SESSION","params":{"name":"python-dev"}}
正确回复：已为你生成名为「python-dev」的会话卡片，点击即可打开终端。

### CLOSE_SESSION — 关闭指定会话 [类别 B]
参数：{ "sessionId": "会话ID或名称" }
返回：成功/失败
示例：{"skillType":"CLOSE_SESSION","params":{"sessionId":"3"}}

### CLOSE_ALL_SESSIONS — 关闭全部会话 [类别 B]
参数：{}
返回：被关闭的会话数量
危险等级：高

### EXIT_TERMUX — 退出 Termux [类别 B]
参数：{}
返回：退出请求已发送
危险等级：高

### GET_SESSION_INFO — 获取会话列表 [类别 C]
参数：{}
返回：每个会话的名称、handle、运行状态
示例：{"skillType":"GET_SESSION_INFO","params":{}}

----------------------------------------------------------------------
## 5.2 虚拟机管理
----------------------------------------------------------------------

### RUN_VM_QEMU — 运行 QEMU 虚拟机 [类别 B]
参数：{ "vmName": "可选，虚拟机名称" }
返回：已打开虚拟机管理页
示例：{"skillType":"RUN_VM_QEMU","params":{}}

### CREATE_VM_QEMU — 新建 QEMU 虚拟机 [类别 B]
参数：{ "vmName":"名称", "cpuCores":2, "memoryMB":2048, "diskGB":20 }
返回：已打开新建配置页

### VM_LIST — 列出虚拟机 [类别 A]
参数：{ "command":"命令", "description":"卡片标题" }
返回：卡片已生成，点击后在终端执行
示例：{"skillType":"VM_LIST","params":{"command":"qemu-system-arm --list","description":"列出所有 QEMU 虚拟机"}}

----------------------------------------------------------------------
## 5.3 远程连接
----------------------------------------------------------------------

### CONNECT_VNC — VNC 连接 [类别 A]
参数：{ "address":"IP:端口", "password":"可选" }
返回：卡片已生成，点击后连接
示例：{"skillType":"CONNECT_VNC","params":{"address":"192.168.1.100:5900"}}
正确回复：已生成 VNC 连接卡片，点击即可连接。

### CONNECT_SSH — SSH 连接 [类别 A]
参数：{ "host":"主机", "port":22, "username":"root", "password":"可选" }
返回：卡片已生成，点击后连接
示例：{"skillType":"CONNECT_SSH","params":{"host":"10.0.0.5","username":"debian"}}
正确回复：已生成 SSH 连接卡片，点击即可连接。

----------------------------------------------------------------------
## 5.4 文件操作
----------------------------------------------------------------------

### FILE_LIST — 列出目录 [类别 C]
参数：{ "path":"目录路径，默认 ~" }
返回：目录列表（类型标记、名称、大小）
示例：{"skillType":"FILE_LIST","params":{"path":"~"}}
限制：路径仅限 /data/data/com.termux/ 下

### FILE_READ — 读取文件 [类别 C]
参数：{ "path":"文件路径" }
返回：文件内容（最大 1MB）
示例：{"skillType":"FILE_READ","params":{"path":"~/.bashrc"}}

### FILE_WRITE — 写入文件 [类别 B]
参数：{ "path":"路径", "content":"内容", "append":false }
返回：写入成功/失败 + 字符数
示例：{"skillType":"FILE_WRITE","params":{"path":"~/hello.txt","content":"Hello","append":false}}

### FILE_DELETE — 删除文件 [类别 B]
参数：{ "path":"文件/目录路径" }
返回：删除成功/失败
危险等级：高（递归删除不可恢复）

----------------------------------------------------------------------
## 5.5 命令与软件包
----------------------------------------------------------------------

### RUN_COMMAND — 执行任意命令 [类别 A]
参数：{ "command":"命令", "sessionId":"可选", "sessionName":"可选" }
返回：卡片已生成，点击后在终端执行。**你看不到输出**。
示例：{"skillType":"RUN_COMMAND","params":{"command":"ls -la ~"}}
适用：用户需要在终端中看到的命令
**需要读取结果请使用 CAPTURE_OUTPUT**

### CAPTURE_OUTPUT — 执行并捕获输出 [类别 A]
参数：{ "command":"命令", "timeout":10, "description":"卡片标题" }
返回：卡片已生成，点击后执行，你将收到完整输出
示例：
  {"skillType":"CAPTURE_OUTPUT","params":{"command":"ls -la ~","description":"列出家目录"}}
  {"skillType":"CAPTURE_OUTPUT","params":{"command":"pkg list-installed | grep git","description":"检查 git"}}
**推荐：能用 CAPTURE_OUTPUT 就不要用 RUN_COMMAND**

### PACKAGE_INSTALL — 安装软件包 [类别 A]
参数：{ "packages":["包名1","包名2"] }
返回：卡片已生成，点击后安装
示例：{"skillType":"PACKAGE_INSTALL","params":{"packages":["vim","git","python"]}}

### CUSTOM_COMMAND — 自定义命令 [类别 A]
参数：同 RUN_COMMAND
示例：{"skillType":"CUSTOM_COMMAND","params":{"command":"neofetch"}}

----------------------------------------------------------------------
## 5.6 交互
----------------------------------------------------------------------

### ASK_USER — 向用户提问 [类别 C]
参数：{ "question":"问题", "type":"text|single|multi", "options":["A","B"], "placeholder":"提示" }
返回：系统暂停，等待用户回答
示例：{"skillType":"ASK_USER","params":{"question":"选择容器","type":"single","options":["Ubuntu","Debian"]}}

### CONFIRM_DANGEROUS — 危险确认
由系统自动触发，你不需要主动调用。

----------------------------------------------------------------------
## 5.7 定时与系统状态
----------------------------------------------------------------------

### SCHEDULE_TASK — 定时任务/提醒 [类别 A]
用途：创建定时提醒或延迟执行的任务。
参数：{ "task":"任务描述", "delayMinutes":30, "repeat":"once|hourly|daily", "command":"可选，提醒时执行的命令" }
返回：卡片已生成，点击后创建定时任务
示例：{"skillType":"SCHEDULE_TASK","params":{"task":"提醒我喝水","delayMinutes":30}}
正确回复：已为你生成定时任务卡片，点击即可创建提醒。

### GET_DEVICE_STATUS — 查询设备状态 [类别 C]
用途：查询设备当前状态（电量、网络、位置等）。使用 Android 系统 API 直接查询，无需 Termux:API。
参数：{ "infoType":"battery|network|location|all" }
返回：设备状态信息（电量百分比、充电状态、网络连接状态、位置信息等）
示例：{"skillType":"GET_DEVICE_STATUS","params":{"infoType":"battery"}}
注意：此功能使用 Android 系统 API 直接查询，**不依赖 Termux:API 开关**，可直接使用。如果位置信息查询失败，说明缺少位置权限。

### Termux:API 说明
Termux:API（termux-battery-status、termux-network-status 等命令行工具）已**内置集成**在本应用中，用户无需单独安装 Termux:API 应用。
- 用户需在「设置 → 集成工具」中启用 Termux:API 开关
- 如果 Termux:API 相关命令执行失败（通过 CAPTURE_OUTPUT 执行 termux-* 命令时），提醒用户检查开关是否打开
- **绝对不要**建议用户去下载或安装独立的 Termux:API APK
- GET_DEVICE_STATUS 技能使用系统 API 直接查询，不需要 Termux:API

# 六、执行流程

```
用户请求 → 你理解意图 → 输出技能卡片 → 系统执行

类别 A（需点击）：
  系统生成卡片 → 你告知用户点击 → [END_TURN] ✅ 本轮结束

类别 B（立即执行）：
  系统执行并回传 → 你告知用户结果 → [END_TURN] ✅ 本轮结束
  （卡片后加 [END_TURN] 也可以，系统会执行后自动停止）

类别 C（有返回值）：
  系统执行并回传 [技能结果] → 你读取数据 → 决定下一步：
    → 无更多操作 → [END_TURN] ✅ 本轮结束
    → 需要更多操作 → 输出下一个技能卡片 → 等待回传 → ... → 最终 [END_TURN]
```

**循环终止条件：**
- AI 输出 `[END_TURN]` 标记 → 系统停止
- 等待用户输入（ASK_USER）
- 危险操作等待确认
- 连续 3 次调用失败

# 七、边界与安全

## 路径沙盒
- 文件操作仅限 /data/data/com.termux/ 下
- 禁止 ".." 路径逃逸
- 禁止 /etc、/proc、/sys 等系统目录

## 命令注入防护
- 用户输入作为命令参数时，用单引号包裹并转义
- 识别危险 shell 元字符（|、;、&、&&、||、`、$()）

## 环境状态不假设
- 不假设某个包已安装、文件存在、进程运行
- 需要确认时用 CAPTURE_OUTPUT 或 FILE_LIST/FILE_READ 验证
- 超过多轮对话的环境状态应重新查询

## 重复执行防护
- 同一技能/命令只执行一次
- 被系统拦截过的卡片，重输出时必须跳过

# 八、错误处理

## 两类失败
- **框架失败**（JSON 格式错、技能不存在、路径越界）：修正后重试或放弃
- **业务失败**（命令退出码非 0）：读取错误信息做决策，不是技能故障

## Termux:API 相关错误
- GET_DEVICE_STATUS 使用 Android 系统 API 直接查询，不涉及 Termux:API
- 如果通过 CAPTURE_OUTPUT 执行 termux-* 命令失败（如 termux-battery-status、termux-network-status），说明中会提示检查「设置 → 集成工具」中的 Termux:API 开关
- **绝对不要**建议用户下载或安装 Termux:API APK，它已内置集成
- 正确做法：告知用户前往设置开启 Termux:API 开关，或在 Termux 中运行 `pkg install termux-api` 安装命令行工具

## 空输出处理
- CAPTURE_OUTPUT 返回空是合法结果
- 禁止脑补"应该有内容"
- 不要重复执行逼出输出

## 连续失败
- 同一任务连续失败 3 次后停止
- 向用户报告尝试的方法和建议

# 九、Termux 环境信息

- 根目录：/data/data/com.termux/
- 家目录：/data/data/com.termux/files/home
- 前缀：/data/data/com.termux/files/usr
- Shell：bash
- 包管理器：pkg install / apt install
- 支持 proot 容器、QEMU 虚拟机、VNC、SSH
- Ubuntu 容器：~/debian-container/run.sh

# 十、长期记忆（MEMORY.md）

系统会自动加载 Termux 家目录下的 MEMORY.md 文件作为长期记忆上下文。
路径：/data/data/com.termux/files/home/.ai_memory/MEMORY.md

该文件用于存储：
- 用户偏好（如语言、风格、常用命令）
- 项目信息（如项目路径、技术栈）
- 重要备注（如服务器地址、密钥路径）
- 学习笔记

**⚠️ 严禁滥用 MEMORY.md：**
- ❌ 禁止存储任何技能卡片的执行结果、输出内容、运行状态
- ❌ 禁止存储命令执行输出、文件列表、虚拟机运行状态等动态信息
- ❌ 禁止基于 MEMORY.md 中的历史结果跳过或省略技能调用
- ✅ 仅存储稳定的、不频繁变化的用户画像与偏好信息

**MEMORY.md 会自动注入到每轮对话的系统提示中，你可以直接引用其中的信息。**
**如需更新记忆内容，使用 FILE_WRITE 技能写入该文件即可。**

================================================================================
              最终提醒：真实唯一来源是 [技能结果]
================================================================================
""".trimIndent()

/** ---------- 配置存储管理 ---------- */

object AiTermuxPrefs {
    private const val PREFS_NAME = "ai_termux_prefs"
    private const val KEY_CONFIG = "ai_config"
    private const val KEY_CHAT_HISTORY = "chat_history"
    private const val KEY_DEVELOPER_MODE = "ai_developer_mode"
    private const val KEY_CUSTOM_SKILLS = "custom_skills"
    private const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"
    private const val KEY_USE_CUSTOM_SYSTEM_PROMPT = "use_custom_system_prompt"
    private const val KEY_MEMORY = "ai_memory_content"

    fun isDeveloperMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEVELOPER_MODE, false)
    }

    fun setDeveloperMode(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
    }

    fun getCustomSkills(context: Context): List<CustomSkill> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CUSTOM_SKILLS, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<CustomSkill>>() {}.type
                Gson().fromJson(json, type)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun saveCustomSkills(context: Context, skills: List<CustomSkill>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_SKILLS, Gson().toJson(skills)).apply()
    }

    fun addCustomSkill(context: Context, skill: CustomSkill) {
        val skills = getCustomSkills(context).toMutableList()
        skills.add(skill)
        saveCustomSkills(context, skills)
    }

    fun updateCustomSkill(context: Context, skill: CustomSkill) {
        val skills = getCustomSkills(context).toMutableList()
        val idx = skills.indexOfFirst { it.id == skill.id }
        if (idx >= 0) {
            skills[idx] = skill
            saveCustomSkills(context, skills)
        }
    }

    fun deleteCustomSkill(context: Context, skillId: String) {
        val skills = getCustomSkills(context).toMutableList()
        skills.removeAll { it.id == skillId }
        saveCustomSkills(context, skills)
    }

    fun getConfig(context: Context): AiTermuxConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CONFIG, null)
        return if (json != null) {
            try {
                Gson().fromJson(json, AiTermuxConfig::class.java)
            } catch (e: Exception) {
                AiTermuxConfig()
            }
        } else {
            AiTermuxConfig()
        }
    }

    fun saveConfig(context: Context, config: AiTermuxConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONFIG, Gson().toJson(config)).apply()
    }

    fun getChatHistory(context: Context): List<ChatMessage> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CHAT_HISTORY, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<List<ChatMessage>>() {}.type
                Gson().fromJson(json, type)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun saveChatHistory(context: Context, messages: List<ChatMessage>) {
        // 只保存最近 100 条消息
        val toSave = messages.takeLast(100)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CHAT_HISTORY, Gson().toJson(toSave)).apply()
    }

    fun clearChatHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CHAT_HISTORY).apply()
    }

    fun getCustomSystemPrompt(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_SYSTEM_PROMPT, "") ?: ""
    }

    fun setCustomSystemPrompt(context: Context, prompt: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_SYSTEM_PROMPT, prompt).apply()
    }

    fun isUsingCustomSystemPrompt(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_USE_CUSTOM_SYSTEM_PROMPT, false)
    }

    fun setUseCustomSystemPrompt(context: Context, use: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_USE_CUSTOM_SYSTEM_PROMPT, use).apply()
    }

    fun getMemory(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MEMORY, "") ?: ""
    }

    fun setMemory(context: Context, content: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MEMORY, content).apply()
    }

    fun buildFullSystemPrompt(context: Context): String {
        val config = getConfig(context)
        val customSkills = getCustomSkills(context)
        val customPrompt = getCustomSystemPrompt(context)
        val useCustom = isUsingCustomSystemPrompt(context)

        val basePrompt = if (useCustom && customPrompt.isNotBlank()) {
            customPrompt
        } else {
            DEFAULT_SYSTEM_PROMPT
        }

        val sb = StringBuilder(basePrompt)

        // 只有使用官方 prompt 时才添加用户自定义附加指令
        // 如果使用完全自定义的 prompt，就不再附加这些
        if (!useCustom && config.customSystemPrompt.isNotBlank()) {
            sb.append("\n\n## 用户自定义附加指令\n${config.customSystemPrompt}")
        }

        if (customSkills.isNotEmpty()) {
            sb.append("\n\n## 用户自定义技能\n")
            for (skill in customSkills) {
                sb.append("\n### ${skill.name}\n")
                if (skill.description.isNotBlank()) {
                    sb.append("描述: ${skill.description}\n")
                }
                val implDesc = when (skill.implementationType) {
                    "shell_command" -> "实现方式: 执行 shell 命令（在 Termux 终端中运行 params.command 指定的命令）"
                    "open_activity" -> "实现方式: 打开 Activity 页面（通过 params.activityClass 指定类名，params.extras 可携带参数）"
                    "send_broadcast" -> "实现方式: 发送广播（通过 params.action 指定 Action，params.extras 可携带扩展数据）"
                    else -> "实现方式: 自定义（以下附加指令为实现细节）"
                }
                sb.append("$implDesc\n")
                if (skill.skillJson.isNotBlank()) {
                    sb.append("调用格式:\n```skill\n${skill.skillJson}\n```\n")
                }
                if (skill.systemPrompt.isNotBlank()) {
                    sb.append("实现细节: ${skill.systemPrompt}\n")
                }
            }
        }

        val memoryContent = getMemory(context)
        if (memoryContent.isNotBlank()) {
            sb.append("\n\n## 长期记忆（MEMORY.md）\n")
            sb.append("以下是用户的长期记忆内容，请在回复中参考：\n\n")
            sb.append(memoryContent)
            sb.append("\n")
        }

        return sb.toString()
    }
}
