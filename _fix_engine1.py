import re

fpath = r"d:/KiTerminal-UX/app/src/main/java/com/termux/app/compose/AiTermuxEngine.kt"
with open(fpath, "r", encoding="utf-8") as f:
    c = f.read()

# Change 1: Update comment
c = c.replace(
    "/** 从 AI 回复内容中解析技能块（支持多种代码块标记或直接 JSON） */",
    "/** 从 AI 回复内容中解析技能块（支持多种代码块标记、直接 JSON、以及行业标准 tool_call 格式） */",
    1
)

# Change 2: Insert tool_call strategy call
c = c.replace(
    "        // 策略 2: 直接在文本中查找包含 skillType 的 JSON 对象（兜底，与策略1互补）",
    "        // 策略 2: 解析行业标准 <tool_call> / <tool_call> XML 格式\n        parseToolCallBlocks(content, results, seen)\n\n        // 策略 3: 直接在文本中查找包含 skillType 的 JSON 对象（兜底）",
    1
)

# Change 3: case-insensitive isValidSkillType
c = c.replace(
    "            SkillType.valueOf(type)",
    "            SkillType.valueOf(type.uppercase())",
    1
)

with open(fpath, "w", encoding="utf-8") as f:
    f.write(c)
print("Changes 1-3 done, len:", len(c))
