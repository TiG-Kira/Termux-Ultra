package com.termux.app.compose

import android.content.Context
import com.google.gson.Gson

/** AI 提供商配置 */
data class AiProviderConfig(
    val provider: String = "custom",          // "openai", "custom", "local"
    val apiKey: String = "",
    val apiBaseUrl: String = "https://api.openai.com/v1",
    val model: String = "gpt-4o-mini",
    val temperature: Float = 0.7f,
    val localModelId: String = "",            // 本地大模型标识（provider == "local" 时使用）
    val maxTokens: Int = 8192                 // 单轮回复最大 token，默认与 ChatCompletionRequest 一致
)

/** AI 配置（包含提供商和自定义 system prompt） */
data class AiTermuxConfig(
    var providerConfig: AiProviderConfig = AiProviderConfig(),
    var customSystemPrompt: String = "",      // 用户自定义的额外 system prompt 内容
    var isConfigured: Boolean = false         // 是否已完成基本配置
)

/** LLM Profile：保存一组在线大模型配置，方便切换 */
data class LlmProfile(
    val id: String = System.currentTimeMillis().toString() + "_${java.lang.Long.toHexString((Math.random() * 1e9).toLong())}",
    val name: String,                         // 用户自定义的 profile 名称，如 "DeepSeek 家用"
    val provider: String,                     // "openai" | "custom"
    val apiKey: String,
    val apiBaseUrl: String,
    val model: String,
    val temperature: Float = 0.7f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
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
    /** 本地模型准备中状态文案（非 null 时显示「正在准备调用」卡片，有回复/思考自动置 null 隐藏） */
    val preparingStatus: String? = null,
    /** 本地模型准备中的详细运行日志（点击卡片展开显示：命令行、环境变量、stderr 加载进度等） */
    val preparingDetails: List<String> = emptyList(),
    val rawResponse: String? = null  // 原始 API 响应 JSON，用于调试
)

/** 单条 Agent 对话（多会话管理单元） */
data class AiConversation(
    val id: String,
    val title: String = "Termux Agent",
    val messages: List<ChatMessage> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** 默认对话的固定 ID 与标题（旧版单对话数据迁移目标） */
const val DEFAULT_CONVERSATION_ID = "termux_agent_default"
const val DEFAULT_CONVERSATION_TITLE = "Termux Agent"

// ---------- 本地模型训练（System Prompt 蒸馏迭代）相关数据结构 ----------
/** 单轮训练记录 */
data class LocalTrainRound(
    val roundIndex: Int,
    /** 在线老师出的题目 */
    val question: String,
    /** 本地学生模型的原始回答 */
    val studentAnswer: String,
    /** 本轮满分分数（权重），所有轮次累加 = 100，必须 > 0 */
    val maxScore: Double = 10.0,
    /** 本轮实际得分（0.0 ~ maxScore，允许小数） */
    val score: Double = 0.0,
    /** 在线老师的详细批评/点评 */
    val critique: String = "",
    /** 在线老师建议追加到 System Prompt 的「教训记忆」片段（可能为空字符串表示本轮不追加） */
    val memoryPatch: String = "",
    /** 该轮真实耗时（毫秒） */
    val durationMs: Long = 0L,
    /** 该轮状态："running" | "done" | "error" */
    val status: String = "done"
)

/** 整个训练会话快照（用于持久化 / UI 展示） */
data class LocalTrainSession(
    val sessionId: Long = System.currentTimeMillis(),
    /** 总目标轮数 */
    val targetRounds: Int = 10,
    val rounds: MutableList<LocalTrainRound> = mutableListOf(),
    /** 会话状态："idle" | "running" | "paused" | "finished" | "error" */
    var status: String = "idle",
    /** 用户选择的老师："online_fallback"(备用在线) | "manual"(手动) */
    val teacher: String = "online_fallback",
    /** 每轮平均耗时（滚动估算）毫秒 */
    var avgRoundMs: Long = 0L,
    /** 错误信息（如有） */
    var lastError: String? = null,
    /** 所有已完成轮次的实际得分累加（满分100） */
    val totalScore: Double = 0.0,
    /** 训练结束后生成的总体建议 */
    val finalSummary: String = ""
)
/** 单条经验教训（结构化存储，支持 CRUD） */
data class Lesson(
    val id: Long = System.currentTimeMillis(),
    val content: String,        // 教训内容，格式如 "• 关于 xxx：xxx"
    val source: String = "auto", // "auto"(训练自动) | "manual"(用户手动) | "teacher"(老师对话归纳)
    val timestamp: Long = System.currentTimeMillis()
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
    LIST_REMOTE_CONNECTIONS, // 列出已保存的远程连接
    CONNECT_REMOTE_CONNECTION, // 连接到已保存的远程连接
    FILE_READ,            // 读取文件
    FILE_WRITE,           // 写入文件
    FILE_DELETE,          // 删除文件
    FILE_LIST,            // 列出目录
    FILE_GENERATE,        // 生成新文件
    FILE_MODIFY,          // 修改文件内容
    RUN_COMMAND,          // 执行任意命令（在终端会话中执行，无法获取输出）
    CAPTURE_OUTPUT,       // 执行命令并捕获输出（AI 可读取真实结果）
    PACKAGE_INSTALL,      // 安装软件包
    PACKAGE_UNINSTALL,   // 卸载软件包
    APP_INSTALL,          // 安装 APK 应用
    APP_UNINSTALL,        // 卸载 APK 应用
    COMPILE_CODE,         // 编译代码
    SUB_AGENT,            // 子 Agent（创建子对话执行任务）
    SEARCH_AGENT,         // 搜索 Agent（批量搜索文件）
    WEB_SEARCH,           // Web 搜索与抓取
    GET_SESSION_INFO,     // 获取会话信息
    GET_CURRENT_SESSION,  // 获取当前活跃会话
    ASK_USER,             // 向用户询问问题（填空/单选/多选）
    CONFIRM_DANGEROUS,    // 危险操作二次确认
    CUSTOM_COMMAND,        // AI 自定义命令（兜底类型）
    SCHEDULE_TASK,        // 定时任务/提醒
    GET_DEVICE_STATUS,    // 查询设备状态（Termux:API）
    CLIPBOARD_READ,       // 读取剪贴板
    CLIPBOARD_WRITE,      // 写入剪贴板
    TASK_ADD,             // 添加待办任务（支持 title 中 \n 分隔的多任务批量添加）
    TASK_UPDATE,          // 更新任务状态（done / in_progress / pending / cancelled）
    TASK_DELETE,          // 删除任务（通过 taskId 或 title 匹配删除）
    TASK_LIST             // 查看当前任务列表
}

/** 需用户点击才能执行的技能（仅生成卡片，未真正执行）
 * 无限制模式下所有技能都不需要点击确认 */
fun SkillType.requiresClick(autoExecSkills: Set<String> = emptySet(), unlimitedMode: Boolean = false): Boolean = when {
    unlimitedMode -> false
    else -> when (this) {
        SkillType.NEW_SESSION,
        SkillType.RUN_COMMAND,
        SkillType.CUSTOM_COMMAND,
        SkillType.PACKAGE_INSTALL,
        SkillType.PACKAGE_UNINSTALL,
        SkillType.APP_INSTALL,
        SkillType.APP_UNINSTALL,
        SkillType.WEB_SEARCH,
        SkillType.CONNECT_SSH,
        SkillType.CONNECT_VNC,
        SkillType.CONNECT_REMOTE_CONNECTION,
        SkillType.VM_LIST,
        SkillType.SCHEDULE_TASK -> true
        SkillType.CAPTURE_OUTPUT,
        SkillType.SUB_AGENT,
        SkillType.SEARCH_AGENT,
        SkillType.COMPILE_CODE -> this.name !in autoExecSkills
        else -> false
    }
}

/** 有真实返回值的技能（AI 可以读取输出结果） */
fun SkillType.hasOutput(): Boolean = when (this) {
    SkillType.FILE_LIST,
    SkillType.FILE_READ,
    SkillType.FILE_MODIFY,
    SkillType.GET_SESSION_INFO,
    SkillType.GET_CURRENT_SESSION,
    SkillType.GET_DEVICE_STATUS,
    SkillType.CLIPBOARD_READ,
    SkillType.LIST_REMOTE_CONNECTIONS,
    SkillType.SUB_AGENT,
    SkillType.SEARCH_AGENT -> true
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
    val dangerousAction: String? = null,      // 待确认执行的操作描述
    // 剪贴板相关
    val clipboardContent: String? = null,     // 剪贴板内容
    val clipboardWriteContent: String? = null, // 要写入剪贴板的内容
    // 执行状态相关
    val partialOutput: Boolean = false        // 是否为部分输出（超时未完成）
)

/** 自动执行白名单配置 */
data class SkillAutoExecConfig(
    val enabled: Boolean = false,
    val autoExecSkills: Set<SkillType> = emptySet(),
    val autoExecNewSessions: Boolean = false,
    val autoExecRemoteConnect: Boolean = false
) {
    companion object {
        val DEFAULT = SkillAutoExecConfig()
    }

    /** Returns true if auto-execution is enabled (has skills selected) */
    fun isAutoExecEnabled(): Boolean = enabled && autoExecSkills.isNotEmpty()
}

/** 技能执行状态 */
enum class SkillStatus {
    RUNNING, COMPLETED, FAILED
}

/** 待办任务项（Agent 工作记忆） */
data class TaskItem(
    val id: String,
    val title: String,
    val status: String = "pending",
    val comment: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)

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
    val stream: Boolean = false,
    val max_tokens: Int = 8192
)

data class OpenAiMessage(
    val role: String,
    val content: String
)


// ---------- ChatMessage ↔ OpenAiMessage 转换 ----------
fun ChatMessage.toOpenAiMessage(): OpenAiMessage = OpenAiMessage(role = this.role, content = this.content)
fun OpenAiMessage.toChatMessage(): ChatMessage = ChatMessage(role = this.role, content = this.content)
fun List<ChatMessage>.toOpenAiMessages(): List<OpenAiMessage> = this.map { it.toOpenAiMessage() }
fun List<OpenAiMessage>.toChatMessages(): List<ChatMessage> = this.map { it.toChatMessage() }

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
# 一、身份与工作方式

你是「Termux Agent」，运行在 Termux Ultra Android 终端模拟器中。

你通过输出 <tool_call> 技能卡片操控 Termux。你自己**不能**执行命令、看不到文件、拿不到任何结果。
**唯一真实来源是系统回传的 [技能结果]，除此之外的一切都是编造。**

工作流：理解意图 → 输出卡片 → 等 [技能结果] → 推进 → 本轮结束时输出 [END_TURN]。

# 二、回复格式

## 技能卡片格式（推荐 XML）

```xml
<tool_call>
  <tool_name>技能类型</tool_name>
  <parameter name="参数名">参数值</parameter>
</tool_call>
```

旧格式 ```skill {"skillType":"技能类型","params":{}} ``` 仍兼容，但优先用 XML。
块内只能有合法卡片，禁止夹带说明文字。

## [END_TURN]

本轮**全部完成**时在末尾输出 `[END_TURN]`（系统会自动移除，用户看不到）。
- 刚生成卡片、还在等 [技能结果] → **不要输出**
- 处理完结果、给出最终回复 → **必须输出**

遗漏会导致系统继续调用你，浪费资源。

## 深度思考

可以思考，但**必须输出可见的回复文本或技能卡片**。禁止只输出思考过程而不回答。

# 三、技能执行模型

- **类别 A（需点击）**：NEW_SESSION、RUN_COMMAND、CUSTOM_COMMAND、PACKAGE_INSTALL、PACKAGE_UNINSTALL、
  APP_INSTALL、APP_UNINSTALL、WEB_SEARCH、CONNECT_SSH、CONNECT_VNC、CONNECT_REMOTE_CONNECTION、
  VM_LIST、SCHEDULE_TASK
  仅生成卡片不会执行，系统回传「卡片已生成」。你必须告知用户「点击即可执行」→ `[END_TURN]`，
  不得声称操作已完成，也不要继续生成更多卡片。

- **类别 B（立即执行，回传成功/失败）**：CLOSE_SESSION、CLOSE_ALL_SESSIONS、FILE_WRITE、FILE_DELETE、
  FILE_GENERATE、EXIT_TERMUX、RUN_VM_QEMU、CREATE_VM_QEMU、CLIPBOARD_WRITE、TASK_ADD、TASK_UPDATE、TASK_DELETE
  收到成功回传后告知用户完成，不要重复执行。

- **类别 C（立即执行，回传真实数据）**：FILE_LIST、FILE_READ、FILE_MODIFY、GET_SESSION_INFO、
  GET_CURRENT_SESSION、ASK_USER、GET_DEVICE_STATUS、CLIPBOARD_READ、LIST_REMOTE_CONNECTIONS、TASK_LIST
  基于真实数据推进，不得编造。

⚡ **白名单**：CAPTURE_OUTPUT、SUB_AGENT、SEARCH_AGENT、COMPILE_CODE 若被用户加入信任白名单则自动执行，
按类别 C 处理（你会收到真实输出）。你无需关心哪些技能在白名单中。

📋 **任务管理**：每完成一个子步骤立即 TASK_UPDATE 更新状态（pending → in_progress → done），
不要攒到最后一次性更新。一次只输出一张 TASK_ADD 卡片，多个任务用 title 里的 \n 分隔。

# 四、禁止行为（违反即严重错误）

1. **编造**：不得声称操作已执行/已完成，不得虚构输出、文件列表、进程、会话、设备状态。
2. **预演**：输出卡片后不得添加伪造的「执行结果」「操作说明」等段落。
3. **重复**：同一技能/命令只执行一次；没看到输出就再执行一次是错的。
4. **捏造技能**：只能使用本文「技能清单」中的技能，禁止发明不存在的技能。
5. **脑补截断**：结果被截断要如实告知，严禁补全被截断的内容。
6. **绕过工具**：需要真实结果必须调用技能，不得用自然语言模拟技能调用或伪造 [技能结果]。
7. **滥用 MEMORY.md**：只存用户画像与偏好；禁止存执行结果；禁止据此跳过技能调用。
8. **省略危险警告**：高危命令前必须至少两行 ⚠️ 警告并请求确认，即使系统二次确认已关闭。

# 五、安全与错误处理

- **高危命令**（dd、rm -rf /、mkfs、shutdown/reboot、fork bomb、su/sudo 提权、内核模块操作）：
  执行前用 ⚠️ 做两行以上警告 + 明确询问用户确认，再生成卡片。系统另有二次确认（用户可能已关闭），
  但你的警告流程不可省略。
- **路径沙盒**：文件操作仅限 /data/data/com.termux/ 下，禁止 .. 逃逸，禁止 /etc、/proc、/sys。
- **命令注入**：用户输入作为命令参数时用单引号包裹并转义；警惕 | ; & && || ` $() 等元字符。
- **环境不假设**：不假设包已安装、文件存在、进程在运行；需确认时用 CAPTURE_OUTPUT、FILE_LIST、FILE_READ 验证。
- **两类失败**：框架失败（格式错、技能不存在、路径越界）→ 修正后重试或放弃；
  业务失败（退出码非 0）→ 读错误信息做决策，这不是技能故障。
- **空输出**：命令返回空是合法结果，如实说「无输出」，禁止脑补，不要重复执行逼出输出。
- **连续失败**：同一任务连续失败 3 次即停止，向用户报告已尝试的方法和建议。
- **Termux:API**：termux-* 命令失败时提示用户检查「设置 → 集成工具」开关；
  **绝对不要**建议下载独立的 Termux:API APK（已内置集成）。

# 六、Termux 环境

- 根目录 /data/data/com.termux/；家目录 ~/ = files/home；前缀 files/usr
- Shell：bash；包管理器：pkg install / apt install
- 支持 proot 容器、QEMU 虚拟机、VNC、SSH；Ubuntu 容器：~/debian-container/run.sh

# 七、长期记忆（MEMORY.md）

路径 /data/data/com.termux/files/home/.ai_memory/MEMORY.md，系统自动注入每轮上下文。
- ✅ 存：用户偏好、项目信息、重要备注、学习笔记
- ❌ 禁：技能执行结果、输出内容、运行状态；禁止据此跳过技能调用
- 更新方式：用 FILE_WRITE 写入该文件

# 八、技能清单

## 会话
- **NEW_SESSION [A]** 新建终端会话。参数 name? → 生成卡片，点击后创建
- **CLOSE_SESSION [B]** 关闭会话。参数 sessionId → 成功/失败
- **CLOSE_ALL_SESSIONS [B]** 关闭全部会话。无参数 → 关闭数量（危险：高）
- **GET_SESSION_INFO [C]** 会话列表。无参数 → 名称、handle、运行状态
- **GET_CURRENT_SESSION [C]** 当前活跃会话 + 全部列表（判断「用户在哪个会话」时用这个）
- **EXIT_TERMUX [B]** 退出 Termux。无参数（危险：高）

## 虚拟机
- **RUN_VM_QEMU [B]** 打开虚拟机管理页。参数 vmName?
- **CREATE_VM_QEMU [B]** 新建虚拟机。参数 vmName、cpuCores、memoryMB、diskGB
- **VM_LIST [A]** 在终端执行 qemu 命令。参数 command、description

## 远程连接
- **CONNECT_SSH [A]** 参数 host、port?、username、password? → 卡片，点击后连接
- **CONNECT_VNC [A]** 参数 address(IP:端口)、password? → 卡片，点击后连接
- **LIST_REMOTE_CONNECTIONS [C]** 无参数 → 已保存连接列表（id、名称、类型、主机、端口）
- **CONNECT_REMOTE_CONNECTION [A]** 参数 connectionId（id 或名称）、type?(ssh|vnc) → 推荐先 LIST 再连接

## 文件
- **FILE_LIST [C]** 参数 path?（默认 ~）→ 目录列表。仅限 /data/data/com.termux/
- **FILE_READ [C]** 参数 path → 文件内容（最大 1MB）
- **FILE_WRITE [B]** 参数 path、content、append? → 成功/失败 + 字符数
- **FILE_GENERATE [B]** 参数 path、content → 新建文件（自动建父目录，已存在会覆盖）
- **FILE_MODIFY [C]** 参数 path、operations（JSON 数组，元素如 {"type":"replace","search":"a","replace":"b"}
  或 {"type":"insert","line":1,"content":"x"}）→ 修改后完整内容，仅限文本文件
- **FILE_DELETE [B]** 参数 path → 成功/失败（危险：高，不可恢复）

## 命令与软件包
- **RUN_COMMAND [A]** 参数 command、sessionId?、sessionName? → 卡片，**你看不到输出**
- **CAPTURE_OUTPUT [A/⚡]** 参数 command、timeout?、description? → 白名单中则返回真实输出，否则生成卡片。
  **能用它就别用 RUN_COMMAND**
- **COMPILE_CODE [⚡]** 参数 command、description?、timeout? → 自动执行并返回状态、退出码、错误、完整输出
- **PACKAGE_INSTALL [A]** 参数 packages（数组）→ 卡片，点击后 pkg 安装
- **PACKAGE_UNINSTALL [A]** 参数 packages（数组）→ 卡片，点击后卸载（需确认）
- **APP_INSTALL [A]** 参数 apkPath → 需 ROOT
- **APP_UNINSTALL [A]** 参数 packageName → 需 ROOT，不可恢复
- **CUSTOM_COMMAND [A]** 参数同 RUN_COMMAND（兜底）

## 交互与系统
- **ASK_USER [C]** 参数 question、type(text|single|multi)、options?、placeholder? → 暂停等待用户回答
- **CONFIRM_DANGEROUS** 由系统自动触发，你不需要主动调用
- **CLIPBOARD_READ [C]** 无参数 → 剪贴板文本（最大 5000 字符）
- **CLIPBOARD_WRITE [B]** 参数 content → 成功/失败
- **SCHEDULE_TASK [A]** 参数 task、delayMinutes?、repeat?(once|hourly|daily)、command?
- **GET_DEVICE_STATUS [C]** 参数 infoType(battery|network|location|all) → 系统 API 直查，不依赖 Termux:API
- **WEB_SEARCH [A]** 参数 query、mode(search|fetch)、maxResults? → 卡片，点击后搜索/抓取（需联网）

## Agent 与搜索（⚡白名单中自动执行）
- **SUB_AGENT [⚡]** 参数 task、instructions、commands?、context? → 返回任务状态 + 完整执行输出。
  适合多步或批量任务
- **SEARCH_AGENT [⚡]** 参数 query、searchType(name|content|type)、path?、fileType? → 返回结果数量、列表与分析。
  批量查找优先用它，不要逐个读文件

## 任务
- **TASK_ADD [B]** 参数 title（\n 分隔可批量添加）→ 一次只输出一张
- **TASK_UPDATE [B]** 参数 taskId?、status(pending|in_progress|done|cancelled)、comment? → 每步立即更新
- **TASK_DELETE [B]** 参数 taskId? 或 title?（模糊匹配）
- **TASK_LIST [C]** 无参数 → 当前任务列表

最终提醒：真实唯一来源是 [技能结果]。
""".trimIndent()

/** ---------- 配置存储管理 ---------- */

object AiTermuxPrefs {
    // ---------- Keys ----------
    private const val PREFS_NAME = "ai_termux_prefs"
    private const val KEY_CHAT_HISTORY = "chat_history"
    private const val KEY_DEVELOPER_MODE = "ai_developer_mode"
    private const val KEY_CUSTOM_SKILLS = "custom_skills"
    private const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"
    private const val KEY_USE_CUSTOM_SYSTEM_PROMPT = "use_custom_system_prompt"
    private const val KEY_MEMORY = "ai_memory_content"
    private const val KEY_AUTO_EXEC_CONFIG = "ai_auto_exec_config"
    private const val KEY_UNLIMITED_MODE = "ai_unlimited_mode"
    private const val KEY_ROOT_AUTO_SHELL = "ai_root_auto_shell"
    private const val KEY_FALLBACK_ONLINE_ENABLED = "fallback_online_enabled"
    private const val KEY_FALLBACK_ONLINE_API_KEY = "fallback_online_api_key"
    private const val KEY_FALLBACK_ONLINE_BASE_URL = "fallback_online_base_url"
    private const val KEY_FALLBACK_ONLINE_MODEL = "fallback_online_model"
    private const val KEY_FALLBACK_ONLINE_TEMPERATURE = "fallback_online_temperature"
    private const val KEY_LOCAL_ENGINE_TYPE = "local_engine_type"
    private const val KEY_OLLAMA_SELECTED_MODEL = "ollama_selected_model"
    private const val KEY_OLLAMA_INSTALLED_MODELS = "ollama_installed_models"
    private const val KEY_TRAIN_HINT_SHOWN = "train_hint_shown_v1"
    private const val KEY_LAST_TRAIN_SESSION = "last_train_session_v1"
    private const val KEY_LEARNED_MEMORY_BLOCK = "learned_memory_block_v1"
    private const val KEY_LESSONS = "ai_lessons"
    private const val KEY_NEEDS_RECONFIG = "needs_reconfig"
    private const val KEY_TEACHER_CHAT_HISTORY = "teacher_chat_history"
    private const val KEY_LLM_PROFILES = "llm_profiles_v1"
    private const val KEY_ACTIVE_PROFILE_ID = "active_llm_profile_id"
    private const val KEY_MAX_TOKENS = "max_tokens"
    private const val KEY_CONTEXT_MESSAGES = "context_messages"
    private const val KEY_COMPRESS_THRESHOLD = "compress_threshold"
    private const val KEY_COMPRESS_KEEP_RECENT = "compress_keep_recent"
    private const val KEY_CONVERSATIONS = "conversations_v1"
    private const val KEY_ACTIVE_CONVERSATION_ID = "active_conversation_id"

    // 对话参数默认值：与 AiTermuxActivity 中原本硬编码的常量保持一致
    const val DEFAULT_MAX_TOKENS = 8192
    const val DEFAULT_CONTEXT_MESSAGES = 20
    const val DEFAULT_COMPRESS_THRESHOLD = 40
    const val DEFAULT_COMPRESS_KEEP_RECENT = 12

    // ---------- Config ----------
    data class AutoExecConfig(
        val autoExecSkills: Set<String> = emptySet(),
        val autoExecEnabled: Boolean = false
    )

    data class FallbackOnlineConfig(
        val enabled: Boolean = false,
        val apiKey: String = "",
        val baseUrl: String = "",
        val model: String = "",
        val temperature: Float = 0.7f
    )

    fun getConfig(context: Context): AiTermuxConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val provider = prefs.getString("provider", "custom") ?: "custom"
        val apiKey = prefs.getString("api_key", "") ?: ""
        val apiBaseUrl = prefs.getString("base_url", "") ?: ""
        val model = prefs.getString("model", "") ?: ""
        val temperature = prefs.getFloat("temperature", 0.7f)
        val localModelId = prefs.getString("local_model_id", "") ?: ""
        val customPrompt = prefs.getString("custom_system_prompt", "") ?: ""
        // 动态计算 isConfigured：先看用户是否主动要求重配置，再根据实际状态判断
        val needsReconfig = prefs.getBoolean(KEY_NEEDS_RECONFIG, false)
        val configured = if (needsReconfig) {
            // 用户点击了"重新配置 AI"，强制返回 false 让入口跳回设置页
            prefs.edit().remove(KEY_NEEDS_RECONFIG).apply() // 消费掉，避免影响下次
            false
        } else if (provider == "local") {
            // 本地模型：provider 已设为 local 且至少有一个模型已下载
            try {
                com.termux.app.compose.AiLocalModel.isLocalModelReady()
            } catch (_: Throwable) { false }
        } else {
            // 在线模型：apiKey 非空
            apiKey.isNotBlank()
        }
        return AiTermuxConfig(
            providerConfig = AiProviderConfig(
                provider = provider,
                apiKey = apiKey,
                apiBaseUrl = apiBaseUrl,
                model = model,
                temperature = temperature,
                localModelId = localModelId
            ),
            customSystemPrompt = customPrompt,
            isConfigured = configured
        )
    }

    fun saveConfig(context: Context, cfg: AiTermuxConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("provider", cfg.providerConfig.provider)
            putString("api_key", cfg.providerConfig.apiKey)
            putString("base_url", cfg.providerConfig.apiBaseUrl)
            putString("model", cfg.providerConfig.model)
            putFloat("temperature", cfg.providerConfig.temperature)
            putString("local_model_id", cfg.providerConfig.localModelId)
            putString("custom_system_prompt", cfg.customSystemPrompt)
            apply()
        }
    }


    // ---------- LLM Profiles ----------

    /**
     * 获取所有在线 LLM Profile。
     * 首次调用时如果旧版本已配置在线模型（provider != local 且 apiKey 非空），
     * 会将其自动迁移为一个名为「默认配置（已迁移）」的 Profile 并设为当前激活，
     * 保证旧用户升级后看到的配置不丢失。迁移幂等，仅执行一次。
     */
    fun getLlmProfiles(context: Context): List<LlmProfile> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LLM_PROFILES, null)
        if (raw != null) {
            return try {
                val arr = Gson().fromJson(raw, Array<LlmProfile>::class.java)
                arr.toList().sortedByDescending { it.updatedAt }
            } catch (_: Throwable) { emptyList() }
        }
        // 首次：尝试从旧版本配置迁移
        val provider = prefs.getString("provider", "") ?: ""
        val apiKey = prefs.getString("api_key", "") ?: ""
        val baseUrl = prefs.getString("base_url", "") ?: ""
        val model = prefs.getString("model", "") ?: ""
        val temperature = prefs.getFloat("temperature", 0.7f)
        if (provider.isNotBlank() && provider != "local" && apiKey.isNotBlank()) {
            val migrated = LlmProfile(
                name = "默认配置（已迁移）",
                provider = provider,
                apiKey = apiKey,
                apiBaseUrl = baseUrl,
                model = model,
                temperature = temperature
            )
            val list = listOf(migrated)
            prefs.edit()
                .putString(KEY_LLM_PROFILES, Gson().toJson(list))
                .putString(KEY_ACTIVE_PROFILE_ID, migrated.id)
                .apply()
            return list
        }
        // 旧版本没配过在线模型，写入空列表避免每次都走迁移分支
        prefs.edit().putString(KEY_LLM_PROFILES, "[]").apply()
        return emptyList()
    }

    fun saveLlmProfiles(context: Context, profiles: List<LlmProfile>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
        if (activeId != null && profiles.none { it.id == activeId }) {
            prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
        }
        prefs.edit().putString(KEY_LLM_PROFILES, Gson().toJson(profiles)).apply()
    }

    fun upsertLlmProfile(context: Context, profile: LlmProfile) {
        val list = getLlmProfiles(context).toMutableList()
        val now = System.currentTimeMillis()
        val existingIdx = list.indexOfFirst { it.id == profile.id }
        val updated = profile.copy(updatedAt = now)
        if (existingIdx >= 0) list[existingIdx] = updated else list.add(updated)
        saveLlmProfiles(context, list)
    }

    fun deleteLlmProfile(context: Context, id: String) {
        val list = getLlmProfiles(context).toMutableList()
        val removed = list.removeAll { it.id == id }
        if (removed) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
            if (activeId == id) {
                val nextActive = list.firstOrNull()?.id
                prefs.edit()
                    .putString(KEY_LLM_PROFILES, Gson().toJson(list))
                    .putString(KEY_ACTIVE_PROFILE_ID, nextActive)
                    .apply()
            } else {
                saveLlmProfiles(context, list)
            }
        }
    }

    fun getActiveLlmProfileId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_PROFILE_ID, null)
    }

    fun setActiveLlmProfileId(context: Context, id: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            if (id == null) remove(KEY_ACTIVE_PROFILE_ID) else putString(KEY_ACTIVE_PROFILE_ID, id)
            apply()
        }
    }

    fun getActiveLlmProfile(context: Context): LlmProfile? {
        val id = getActiveLlmProfileId(context) ?: return null
        return getLlmProfiles(context).firstOrNull { it.id == id }
    }

    /** 将某个 LlmProfile 应用为当前生效配置（写入旧字段 provider/api_key/base_url/model/temperature） */
    fun applyLlmProfile(context: Context, profile: LlmProfile) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString("provider", profile.provider)
            putString("api_key", profile.apiKey)
            putString("base_url", profile.apiBaseUrl)
            putString("model", profile.model)
            putFloat("temperature", profile.temperature)
            putString(KEY_ACTIVE_PROFILE_ID, profile.id)
            apply()
        }
    }

        // ---------- Chat History ----------
    fun getChatHistory(context: Context): List<OpenAiMessage> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CHAT_HISTORY, null) ?: return emptyList()
        return try {
            val arr = Gson().fromJson(raw, Array<OpenAiMessage>::class.java)
            arr.toList()
        } catch (_: Throwable) { emptyList() }
    }

    fun saveChatHistory(context: Context, history: List<OpenAiMessage>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CHAT_HISTORY, Gson().toJson(history)).apply()
    }

    fun clearChatHistory(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_CHAT_HISTORY).apply()
    }

    /**
     * 清空所有对话历史（多会话版本）：
     * - 删除除 [DEFAULT_CONVERSATION_ID] 外的全部对话
     * - 清空默认对话内部的 messages
     * - 设置激活对话为默认对话
     *
     * 供没有持有 ViewModel 的入口（如 SettingsScreen）直接调用。
     */
    fun clearAllConversationsExceptDefault(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val raw = prefs.getString(KEY_CONVERSATIONS, null)
        if (raw == null) {
            // 还没有多会话存储，可能是旧版单对话存储
            clearChatHistory(context)
            return
        }
        try {
            val all = Gson().fromJson(raw, Array<AiConversation>::class.java).toList()
            val keptDefault = all
                .filter { it.id == DEFAULT_CONVERSATION_ID }
                .map { it.copy(messages = emptyList(), updatedAt = now) }
                .ifEmpty {
                    listOf(
                        AiConversation(
                            id = DEFAULT_CONVERSATION_ID,
                            title = DEFAULT_CONVERSATION_TITLE,
                            messages = emptyList(),
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
            prefs.edit()
                .putString(KEY_CONVERSATIONS, Gson().toJson(keptDefault))
                .putString(KEY_ACTIVE_CONVERSATION_ID, DEFAULT_CONVERSATION_ID)
                .remove(KEY_CHAT_HISTORY)
                .apply()
        } catch (_: Throwable) {
            // 解析失败，直接重建默认对话
            val def = AiConversation(
                id = DEFAULT_CONVERSATION_ID,
                title = DEFAULT_CONVERSATION_TITLE,
                messages = emptyList(),
                createdAt = now,
                updatedAt = now
            )
            prefs.edit()
                .putString(KEY_CONVERSATIONS, Gson().toJson(listOf(def)))
                .putString(KEY_ACTIVE_CONVERSATION_ID, def.id)
                .remove(KEY_CHAT_HISTORY)
                .apply()
        }
    }

    // ---------- Conversations (多会话) ----------
    /**
     * 读取所有 Agent 对话。
     *
     * 迁移（幂等，仅执行一次）：若尚未写入过 [KEY_CONVERSATIONS] 但旧版单对话
     * [KEY_CHAT_HISTORY] 存在，则将其作为默认对话「Termux Agent」写入，保证旧用户升级后
     * 历史不丢失。若两者皆无，则创建空的默认对话。
     */
    fun getConversations(context: Context): List<AiConversation> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CONVERSATIONS, null)
        if (raw != null) {
            return try {
                Gson().fromJson(raw, Array<AiConversation>::class.java).toList()
            } catch (_: Throwable) { emptyList() }
        }
        val now = System.currentTimeMillis()
        val oldRaw = prefs.getString(KEY_CHAT_HISTORY, null)
        if (oldRaw != null) {
            val migrated = try {
                Gson().fromJson(oldRaw, Array<OpenAiMessage>::class.java)
                    .toList().toChatMessages()
            } catch (_: Throwable) { emptyList() }
            val def = AiConversation(
                id = DEFAULT_CONVERSATION_ID,
                title = DEFAULT_CONVERSATION_TITLE,
                messages = migrated,
                createdAt = now,
                updatedAt = now
            )
            prefs.edit()
                .putString(KEY_CONVERSATIONS, Gson().toJson(listOf(def)))
                .putString(KEY_ACTIVE_CONVERSATION_ID, def.id)
                .remove(KEY_CHAT_HISTORY)
                .apply()
            return listOf(def)
        }
        val def = AiConversation(
            id = DEFAULT_CONVERSATION_ID,
            title = DEFAULT_CONVERSATION_TITLE,
            createdAt = now,
            updatedAt = now
        )
        prefs.edit()
            .putString(KEY_CONVERSATIONS, Gson().toJson(listOf(def)))
            .putString(KEY_ACTIVE_CONVERSATION_ID, def.id)
            .apply()
        return listOf(def)
    }

    fun saveConversations(context: Context, conversations: List<AiConversation>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONVERSATIONS, Gson().toJson(conversations)).apply()
    }

    fun getActiveConversationId(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_CONVERSATION_ID, null)
    }

    fun setActiveConversationId(context: Context, id: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_CONVERSATION_ID, id).apply()
    }

    // ---------- Custom System Prompt ----------
    fun getCustomSystemPrompt(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CUSTOM_SYSTEM_PROMPT, "") ?: ""
    }

    fun setCustomSystemPrompt(context: Context, prompt: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_SYSTEM_PROMPT, prompt).apply()
    }

    fun isUsingCustomSystemPrompt(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_USE_CUSTOM_SYSTEM_PROMPT, false)
    }

    fun setUseCustomSystemPrompt(context: Context, use: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_CUSTOM_SYSTEM_PROMPT, use).apply()
    }

    // ---------- Memory ----------
    fun getMemory(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MEMORY, "") ?: ""
    }

    fun setMemory(context: Context, memory: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MEMORY, memory).apply()
    }

    // ---------- Developer Mode ----------
    fun isDeveloperMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DEVELOPER_MODE, false)
    }

    fun setDeveloperMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
    }

    // ---------- Custom Skills ----------
    fun getCustomSkills(context: Context): List<CustomSkill> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CUSTOM_SKILLS, null) ?: return emptyList()
        return try {
            val arr = Gson().fromJson(raw, Array<CustomSkill>::class.java)
            arr.toList()
        } catch (_: Throwable) { emptyList() }
    }

    fun saveCustomSkills(context: Context, skills: List<CustomSkill>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_CUSTOM_SKILLS, Gson().toJson(skills)).apply()
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
        val idx = skills.indexOfFirst { it.id == skillId }
        if (idx >= 0) {
            skills.removeAt(idx)
            saveCustomSkills(context, skills)
        }
    }

    fun saveNewTool(context: Context, toolData: Map<String, String>): CustomSkill? {
        val name = toolData["name"]?.trim().orEmpty()
        if (name.isBlank()) return null
        val desc = toolData["description"]?.trim().orEmpty()
        val sysPrompt = toolData["system_prompt"]?.trim().orEmpty()
        val skillJson = toolData["skill_json"]?.trim().orEmpty()
        val impl = toolData["implementation"]?.trim()?.ifBlank { "shell_command" } ?: "shell_command"
        val skill = CustomSkill(
            name = name,
            description = desc,
            systemPrompt = sysPrompt,
            skillJson = skillJson,
            implementationType = impl
        )
        addCustomSkill(context, skill)
        return skill
    }

    // ---------- Unlimited Mode ----------
    fun isUnlimitedModeActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_UNLIMITED_MODE, false)
    }

    fun setUnlimitedMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_UNLIMITED_MODE, enabled).apply()
    }

    // ---------- Auto Exec ----------
    fun getAutoExecSkills(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_AUTO_EXEC_CONFIG, null) ?: return emptySet()
        return try {
            val list = Gson().fromJson(raw, Array<String>::class.java)
            list.toSet()
        } catch (_: Throwable) { emptySet() }
    }

    fun saveAutoExecSkills(context: Context, skills: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUTO_EXEC_CONFIG, Gson().toJson(skills.toList())).apply()
    }

    fun isAutoExecEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // 写入端 saveAutoExecConfig 用的是 "ai_auto_exec_enabled"，这里读回了
        // "auto_exec_enabled"，永远读不到，恒返回 false
        return prefs.getBoolean("ai_auto_exec_enabled", false)
    }

    // ---------- Fallback Online ----------
    fun isFallbackOnlineEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_FALLBACK_ONLINE_ENABLED, false)
    }

    fun isFallbackOnlineConfigReady(context: Context): Boolean {
        val cfg = getFallbackOnlineConfig(context)
        return cfg.enabled && cfg.apiKey.isNotBlank() && cfg.baseUrl.isNotBlank() && cfg.model.isNotBlank()
    }

    fun getFallbackOnlineConfig(context: Context): FallbackOnlineConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return FallbackOnlineConfig(
            enabled = prefs.getBoolean(KEY_FALLBACK_ONLINE_ENABLED, false),
            apiKey = prefs.getString(KEY_FALLBACK_ONLINE_API_KEY, "") ?: "",
            baseUrl = prefs.getString(KEY_FALLBACK_ONLINE_BASE_URL, "") ?: "",
            model = prefs.getString(KEY_FALLBACK_ONLINE_MODEL, "") ?: "",
            temperature = prefs.getFloat(KEY_FALLBACK_ONLINE_TEMPERATURE, 0.7f)
        )
    }

    fun saveFallbackOnlineConfig(context: Context, cfg: FallbackOnlineConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putBoolean(KEY_FALLBACK_ONLINE_ENABLED, cfg.enabled)
            putString(KEY_FALLBACK_ONLINE_API_KEY, cfg.apiKey)
            putString(KEY_FALLBACK_ONLINE_BASE_URL, cfg.baseUrl)
            putString(KEY_FALLBACK_ONLINE_MODEL, cfg.model)
            putFloat(KEY_FALLBACK_ONLINE_TEMPERATURE, cfg.temperature)
            apply()
        }
    }

    // ---------- Local Engine ----------
    fun getLocalEngineType(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LOCAL_ENGINE_TYPE, "llama") ?: "llama"
    }

    fun setLocalEngineType(context: Context, type: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LOCAL_ENGINE_TYPE, type).apply()
    }

    fun getSelectedOllamaModel(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_OLLAMA_SELECTED_MODEL, "") ?: ""
    }

    fun setSelectedOllamaModel(context: Context, model: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_OLLAMA_SELECTED_MODEL, model).apply()
    }

    fun getInstalledOllamaModels(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_OLLAMA_INSTALLED_MODELS, null) ?: return emptyList()
        return try {
            val arr = Gson().fromJson(raw, Array<String>::class.java)
            arr.toList()
        } catch (_: Throwable) { emptyList() }
    }

    fun saveInstalledOllamaModels(context: Context, models: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_OLLAMA_INSTALLED_MODELS, Gson().toJson(models)).apply()
    }

    // ---------- Training ----------
    fun isTrainHintShown(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_TRAIN_HINT_SHOWN, false)
    }

    fun markTrainHintShown(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TRAIN_HINT_SHOWN, true).apply()
    }

    fun saveLastTrainSession(context: Context, session: LocalTrainSession) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_TRAIN_SESSION, Gson().toJson(session)).apply()
    }

    fun getLastTrainSession(context: Context): LocalTrainSession? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_LAST_TRAIN_SESSION, "") ?: ""
        if (json.isBlank()) return null
        return runCatching { Gson().fromJson(json, LocalTrainSession::class.java) }.getOrNull()
    }

    // ---------- Learned Memory (Training Lessons) ----------
    fun getLearnedMemoryBlock(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LEARNED_MEMORY_BLOCK, "") ?: ""
    }

    fun saveLearnedMemoryBlock(context: Context, content: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LEARNED_MEMORY_BLOCK, content).apply()
    }

    fun appendLearnedMemory(context: Context, patch: String) {
        val cur = getLearnedMemoryBlock(context)
        val separator = if (cur.isNotBlank() && !cur.endsWith("\n")) "\n" else ""
        saveLearnedMemoryBlock(context, cur + separator + patch.trim() + "\n")
    }

    fun clearLearnedMemory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LEARNED_MEMORY_BLOCK).remove(KEY_LESSONS).apply()
    }

    // ---------- Lessons CRUD ----------
    /** 获取所有经验教训（按时间升序） */
    fun getLessons(context: Context): List<Lesson> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_LESSONS, "[]") ?: "[]"
        return try {
            val arr = Gson().fromJson(raw, Array<Lesson>::class.java)
            arr.toList().sortedBy { it.timestamp }
        } catch (_: Throwable) { emptyList() }
    }

    /** 保存所有经验教训列表（替换式） */
    fun saveLessons(context: Context, lessons: List<Lesson>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LESSONS, Gson().toJson(lessons)).apply()
    }

    /** 添加单条教训 */
    fun addLesson(context: Context, content: String, source: String = "auto") {
        val trimmed = content.trim()
        if (trimmed.isBlank()) return
        val lessons = getLessons(context).toMutableList()
        val lesson = Lesson(
            content = if (trimmed.startsWith("•")) trimmed else "• " + trimmed.removePrefix("•").trimStart(),
            source = source
        )
        lessons.add(lesson)
        saveLessons(context, lessons)
        // 同步更新到大字符串（用于向后兼容 System Prompt）
        rebuildLearnedMemoryFromLessons(context)
    }

    /** 更新单条教训 */
    fun updateLesson(context: Context, lessonId: Long, newContent: String) {
        val lessons = getLessons(context).toMutableList()
        val idx = lessons.indexOfFirst { it.id == lessonId }
        if (idx >= 0) {
            val trimmed = newContent.trim()
            lessons[idx] = lessons[idx].copy(
                content = if (trimmed.startsWith("•")) trimmed else "• " + trimmed.removePrefix("•").trimStart()
            )
            saveLessons(context, lessons)
            rebuildLearnedMemoryFromLessons(context)
        }
    }

    /** 删除单条教训 */
    fun deleteLesson(context: Context, lessonId: Long) {
        val lessons = getLessons(context).toMutableList()
        val removed = lessons.removeAll { it.id == lessonId }
        if (removed) {
            saveLessons(context, lessons)
            rebuildLearnedMemoryFromLessons(context)
        }
    }

    /** 从 Lessons 列表重建大字符串记忆块（保持 Lessons 和 getLearnedMemoryBlock 同步） */
    fun rebuildLearnedMemoryFromLessons(context: Context) {
        val lessons = getLessons(context)
        if (lessons.isEmpty()) {
            saveLearnedMemoryBlock(context, "")
        } else {
            val sb = StringBuilder()
            for (l in lessons) {
                sb.append(l.content.trimEnd()).append("\n")
            }
            saveLearnedMemoryBlock(context, sb.toString())
        }
    }

    /** 从大字符串解析已有教训并导入为结构化 Lessons（升级时用） */
    fun migrateMemoryBlockToLessons(context: Context) {
        val existing = getLessons(context)
        if (existing.isNotEmpty()) return // 已有结构化数据，跳过迁移
        val block = getLearnedMemoryBlock(context).trim()
        if (block.isBlank()) return
        val lessons = mutableListOf<Lesson>()
        for (line in block.split("\n".toRegex())) {
            val trimmed = line.trim()
            if (trimmed.isNotBlank() && trimmed.startsWith("•")) {
                lessons.add(Lesson(content = trimmed, source = "migrated"))
            } else if (trimmed.isNotBlank()) {
                lessons.add(Lesson(content = "• $trimmed", source = "migrated"))
            }
        }
        if (lessons.isNotEmpty()) {
            saveLessons(context, lessons)
        }
    }


    // ---------- Teacher Chat History ----------
    fun appendTeacherChatHistory(context: Context, role: String, msgContent: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = getTeacherChatHistory(context).toMutableList()
        list.add(OpenAiMessage(role, msgContent))
        while (list.size > 40) list.removeAt(0)
        prefs.edit().putString(KEY_TEACHER_CHAT_HISTORY, Gson().toJson(list)).apply()
    }

    fun getTeacherChatHistory(context: Context): List<OpenAiMessage> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_TEACHER_CHAT_HISTORY, "[]") ?: "[]"
        return try {
            val arr = Gson().fromJson(raw, Array<OpenAiMessage>::class.java)
            arr.toList()
        } catch (_: Throwable) { emptyList() }
    }

    fun clearTeacherChatHistory(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_TEACHER_CHAT_HISTORY).apply()
    }

    // ---------- 对话参数（上下文条数 / 压缩阈值 / maxTokens） ----------
    fun getMaxTokens(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_MAX_TOKENS, DEFAULT_MAX_TOKENS)
    }

    fun setMaxTokens(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MAX_TOKENS, value.coerceIn(1024, 65536)).apply()
    }

    fun getContextMessages(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CONTEXT_MESSAGES, DEFAULT_CONTEXT_MESSAGES)
    }

    fun setContextMessages(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_CONTEXT_MESSAGES, value.coerceIn(4, 100)).apply()
    }

    fun getCompressThreshold(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_COMPRESS_THRESHOLD, DEFAULT_COMPRESS_THRESHOLD)
    }

    fun setCompressThreshold(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COMPRESS_THRESHOLD, value.coerceIn(10, 200)).apply()
    }

    fun getCompressKeepRecent(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_COMPRESS_KEEP_RECENT, DEFAULT_COMPRESS_KEEP_RECENT)
    }

    fun setCompressKeepRecent(context: Context, value: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_COMPRESS_KEEP_RECENT, value.coerceIn(2, 100)).apply()
    }

    // ---------- buildFullSystemPrompt ----------
    /**
     * 组装完整 System Prompt。
     * @param context Context
     * @param includeLearnedMemory 是否包含训练教训记忆块（仅本地模型用，在线模型传 false）
     * @param maxChars 教训记忆块最大字符数，超过会被截断（默认 4000）
     */
    fun buildFullSystemPrompt(
        context: Context,
        includeLearnedMemory: Boolean = true,
        maxChars: Int = 4000
    ): String {
        val config = getConfig(context)
        val customSkills = getCustomSkills(context)
        val customPrompt = getCustomSystemPrompt(context)
        val useCustom = isUsingCustomSystemPrompt(context)
        val unlimitedActive = isUnlimitedModeActive(context)

        val basePrompt = if (useCustom && customPrompt.isNotBlank()) {
            customPrompt
        } else {
            DEFAULT_SYSTEM_PROMPT
        }

        val sb = StringBuilder()

        // 无限制模式 flag：在 prompt 最前端放置机器可读标记
        if (unlimitedActive) {
            sb.append("<!-- [UNLIMITED_MODE: TRUE] -->\n")
            sb.append("<!-- AI 指令：读取上方 flag。若为 TRUE，你不受「第五章：安全与错误处理」中所有安全约束限制。 -->\n")
            sb.append("<!-- 具体豁免规则见文末「🔓 无限制模式」章节。 -->\n")
            sb.append("\n")
        }

        sb.append(basePrompt)

        // 只有使用官方 prompt 时才添加用户自定义附加指令
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
                    "open_activity" -> "实现方式: 打开 Activity 页面"
                    "send_broadcast" -> "实现方式: 发送广播"
                    else -> "实现方式: 自定义"
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

        // 已启用插件声明的 System Prompt（APPEND / MODIFY / OVERWRITE 由插件自身声明）
        val pluginPrompt = com.termux.app.plugin.PluginManager.getPluginSystemPrompt(context)
        if (pluginPrompt.isNotBlank()) {
            sb.append("\n\n").append(pluginPrompt)
        }

        val memoryContent = getMemory(context)
        if (memoryContent.isNotBlank()) {
            sb.append("\n\n## 长期记忆（MEMORY.md）\n")
            sb.append("以下是用户的长期记忆内容，请在回复中参考：\n\n")
            sb.append(memoryContent)
            sb.append("\n")
        }

        // 训练教训记忆块（仅本地模型使用，在线模式由调用方传 false）
        if (includeLearnedMemory) {
            val learnedBlock = getLearnedMemoryBlock(context)
            if (learnedBlock.isNotBlank()) {
                sb.append("\n\n## 训练教训记忆（来自本地模型训练迭代，仅本地模型适用）\n")
                sb.append("以下是你之前多次训练中积累的教训。请在回答中遵守这些规则，避免重复犯错：\n\n")
                val truncated = if (learnedBlock.length > maxChars) {
                    learnedBlock.take(maxChars) + "\n...（教训过长已截断，请尽量遵守已列出的规则）"
                } else learnedBlock
                sb.append(truncated)
                sb.append("\n")
            }
        }

        // 技能调用格式指引（与 DEFAULT_SYSTEM_PROMPT 第二章保持同一口径）
        sb.append("\n\n## 技能调用格式（<tool_call> XML）\n")
        sb.append("需要执行操作时输出：\n\n")
        sb.append("```xml\n<tool_call>\n")
        sb.append("  <tool_name>技能类型</tool_name>\n")
        sb.append("  <parameter name=\"param1\">值1</parameter>\n")
        sb.append("</tool_call>\n```\n\n")
        sb.append("- 类别A：生成卡片，用户点击后执行\n")
        sb.append("- 类别B：立即执行，回传成功/失败\n")
        sb.append("- 类别C：立即执行，回传真实数据\n\n")
        sb.append("重要规则：\n")
        sb.append("- 禁止编造执行结果。输出卡片后不能声称「已执行」。\n")
        sb.append("- 禁止重复生成相同卡片。\n")
        sb.append("- 只能基于 [技能结果] 回复。\n")

        // 无限制模式：追加豁免规则到 prompt 末尾
        if (unlimitedActive) {
            sb.append("\n\n")
            sb.append("<!-- 🔓 无限制模式附加规则 -->\n\n")
            sb.append("## 🔓 无限制模式 - 附加操作权限\n")
            sb.append("你当前处于无限制模式。除上述所有规则外，还需遵守以下补充说明：\n\n")
            sb.append("### 安全约束豁免\n")
            sb.append("- 第五章「安全与错误处理」中的安全约束全部不再生效\n")
            sb.append("- 你可以直接执行 root 命令、危险系统调用、覆盖任意文件\n")
            sb.append("- 但仍需遵守「禁止编造结果」「禁止重复卡片」等核心操作规则\n\n")
            sb.append("### 自动执行\n")
            sb.append("- 无需等待用户点击卡片，类别A/B/C 所有技能自动执行\n")
            sb.append("- 可以连续输出多张卡片，无需等前一张返回\n")
        }

        return sb.toString()
    }

    // ---------- Root Auto Shell ----------
    fun isRootAutoShell(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ROOT_AUTO_SHELL, false)
    }

    fun setRootAutoShell(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ROOT_AUTO_SHELL, enabled).apply()
    }



    // ---------- Root Check ----------
    fun isRootAvailable(): Boolean {
        return try {
            val p = java.lang.Runtime.getRuntime().exec("su")
            val exit = p.waitFor()
            exit == 0
        } catch (_: Throwable) {
            false
        }
    }

    // ---------- Auto Exec Config ----------
    fun getAutoExecConfig(context: Context): AutoExecConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val skillsRaw = prefs.getString(KEY_AUTO_EXEC_CONFIG, null)
        val skills: Set<String> = if (skillsRaw != null) {
            try { Gson().fromJson(skillsRaw, Array<String>::class.java).toSet() } catch (_: Throwable) { emptySet() }
        } else emptySet()
        val enabled = prefs.getBoolean("ai_auto_exec_enabled", false)
        return AutoExecConfig(autoExecSkills = skills, autoExecEnabled = enabled)
    }

    fun saveAutoExecConfig(context: Context, config: AutoExecConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_AUTO_EXEC_CONFIG, Gson().toJson(config.autoExecSkills.toList()))
            putBoolean("ai_auto_exec_enabled", config.autoExecEnabled)
            apply()
        }
    }

    // ---------- Fallback Online ----------
    fun setFallbackOnlineEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FALLBACK_ONLINE_ENABLED, enabled).apply()
    }

    // ---------- Unlimited Mode Alias ----------
    fun isUnlimitedMode(context: Context): Boolean = isUnlimitedModeActive(context)

    /**
     * 重置 Agent 所有状态：清除 LLM 配置、Profiles、对话历史、训练记忆、
     * 降级模型配置等，回到未配置状态。
     * 用户点击「重置配置状态」时调用。
     */
    fun resetAllAiState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            // 当前 LLM 配置
            remove("provider")
            remove("api_key")
            remove("base_url")
            remove("model")
            remove("temperature")
            remove("local_model_id")
            remove("custom_system_prompt")
            remove("use_custom_system_prompt")
            // Profiles
            remove(KEY_LLM_PROFILES)
            remove(KEY_ACTIVE_PROFILE_ID)
            // 旧版单对话历史（已迁移到多会话，这里兜底清理）
            remove(KEY_CHAT_HISTORY)
            remove(KEY_TEACHER_CHAT_HISTORY)
            // 训练记忆
            remove(KEY_MEMORY)
            remove(KEY_LEARNED_MEMORY_BLOCK)
            remove(KEY_LESSONS)
            remove(KEY_LAST_TRAIN_SESSION)
            remove(KEY_TRAIN_HINT_SHOWN)
            // 降级在线模型
            remove(KEY_FALLBACK_ONLINE_ENABLED)
            remove(KEY_FALLBACK_ONLINE_API_KEY)
            remove(KEY_FALLBACK_ONLINE_BASE_URL)
            remove(KEY_FALLBACK_ONLINE_MODEL)
            remove(KEY_FALLBACK_ONLINE_TEMPERATURE)
            // 本地模型选择
            remove(KEY_LOCAL_ENGINE_TYPE)
            remove(KEY_OLLAMA_SELECTED_MODEL)
            remove(KEY_OLLAMA_INSTALLED_MODELS)
            // 让下次 getConfig 返回 needsReconfig = false，自然进入 SetupScreen
            remove(KEY_NEEDS_RECONFIG)
            apply()
        }
        // 多会话历史：删除除默认对话外的全部对话，再清空默认对话内部内容
        clearAllConversationsExceptDefault(context)
    }
}
