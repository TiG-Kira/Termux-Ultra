"""
接入 AiTermuxActivity 到 LiveUpdateState：
  1. 在所有 runInScope { isLoading = true ... } 块前后加 agentStart/agentStop
  2. 注册 STOP_AGENT broadcast receiver → cancelGeneration()
"""
import os

PATH = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\activities\AiTermuxActivity.kt'
with open(PATH, 'r', encoding='utf-8') as f:
    src = f.read()

errors = []

# ===== 1. 改写 sendUserMessage 的 runInScope 块 =====
# 找所有 `runInScope {\n            isLoading = true\n            try {` 模式
# 在 isLoading = true 之后加 LiveUpdateState.agentStart()
# 在 finally 里加 LiveUpdateState.agentStop()

# 模式 A: 标准的 sendUserMessage 结构
OLD_A = '''        runInScope {
            isLoading = true
            try {
                processUserMessage(ctx, text)
            } finally {
                isLoading = false
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
            }
        }'''

NEW_A = '''        runInScope {
            isLoading = true
            LiveUpdateState.agentStart()
            try {
                processUserMessage(ctx, text)
            } finally {
                isLoading = false
                LiveUpdateState.agentStop()
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
            }
        }'''

count = src.count(OLD_A)
if count >= 1:
    src = src.replace(OLD_A, NEW_A)
    print(f'  sendUserMessage: replaced {count} block(s)')
else:
    errors.append('sendUserMessage block not found')

# 模式 B: submitAnswer / confirmDangerous / rejectDangerous 的 runInScope 块
OLD_B_PATTERNS = [
    '''        runInScope {
            isLoading = true
            try {
                processAiTurn(ctx, "[用户回答] ${card.askQuestion}\n回答：$answer")''',
    '''        runInScope {
            isLoading = true
            try {
                processAiTurn(ctx, "[用户在二次确认中拒绝了危险操作] ${card.dangerousAction ?: card.title}，用户选择不执行。")''',
    '''        runInScope {
            isLoading = true
            try {
                processAiTurn(ctx, "[用户取消了危险操作] ${card.dangerousAction ?: card.title}，用户选择不执行。")''',
]

# 替换这三个 — 它们的 finally 块结构相同
OLD_FINALLY = '''            } finally {
                isLoading = false
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
            }
        }'''

NEW_FINALLY = '''            } finally {
                isLoading = false
                LiveUpdateState.agentStop()
                AiTermuxPrefs.saveChatHistory(ctx, messages.toOpenAiMessages())
            }
        }'''

# 对每个 B 模式，在 isLoading = true 后加 agentStart
for i, old_b in enumerate(OLD_B_PATTERNS):
    if old_b in src:
        new_b = old_b.replace(
            '            isLoading = true\n            try {',
            '            isLoading = true\n            LiveUpdateState.agentStart()\n            try {'
        )
        src = src.replace(old_b, new_b)
        print(f'  submitAnswer-like block #{i}: patched')

# 替换这些 finally 块（带 agentStop）
count_b = src.count(OLD_FINALLY)
# 注意不要重复替换已经改过的
# 实际上上面 NEW_A 的 finally 已经改了，所以 OLD_FINALLY 的 count 应该是 3（B 模式的三个）
# 但为了安全，我只在确定是 B 模式上下文的时候替换
# 让我直接看一下，把剩下的 OLD_FINALLY 替换掉（因为 NEW_A 用了不同的 finally 格式）
if OLD_FINALLY in src:
    src = src.replace(OLD_FINALLY, NEW_FINALLY)
    print(f'  B-mode finally blocks: replaced')

# ===== 2. 在 onCreate 中注册 STOP_AGENT broadcast receiver =====
# 找到 onCreate 的 super.onCreate 之后的位置，或者找 runInScope 的定义
# 实际上更简单：在类的 companion object 或 onCreate 里注册
# 让我找 onCreate

# 先看看有没有 onCreate
if 'override fun onCreate(' in src:
    OLD_ONCREATE_END = '''        super.onCreate(savedInstanceState)'''
    if OLD_ONCREATE_END in src:
        NEW_ONCREATE_END = OLD_ONCREATE_END + '''

        // 注册停止 Agent 的 broadcast receiver（通知按钮触发）
        val filter = android.content.IntentFilter()
        filter.addAction(com.termux.app.TermuxService.ACTION_STOP_AGENT)
        val stopReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
                if (intent.action == com.termux.app.TermuxService.ACTION_STOP_AGENT) {
                    cancelGeneration()
                }
            }
        }
        registerReceiver(stopReceiver, filter)'''
        src = src.replace(OLD_ONCREATE_END, NEW_ONCREATE_END)
        print('  onCreate: STOP_AGENT receiver registered')
    else:
        errors.append('onCreate super call not found')
else:
    errors.append('onCreate not found')

# ===== 3. 验证所有 runInScope 块都改到了 =====
checks = [
    'LiveUpdateState.agentStart()',
    'LiveUpdateState.agentStop()',
    'cancelGeneration()',
    'TermuxService.ACTION_STOP_AGENT',
]
for c in checks:
    if c in src:
        print(f'  OK: {c}')
    else:
        print(f'  MISS: {c}')
        errors.append(c)

with open(PATH, 'w', encoding='utf-8') as f:
    f.write(src)

print(f'\\nAiTermuxActivity.kt 改写完成. Errors: {len(errors)}')
for e in errors:
    print(f'  - {e}')
