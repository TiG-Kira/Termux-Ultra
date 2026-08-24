fpath = r"d:/KiTerminal-UX/app/src/main/java/com/termux/app/compose/AiTermuxEngine.kt"
with open(fpath, "r", encoding="utf-8") as f:
    c = f.read()

# Insert parseToolCallBlocks function before isValidSkillType
anchor = "    private fun isValidSkillType(type: String): Boolean {"
assert anchor in c, "Anchor not found!"

newfunc = """    /** 解析行业标准 <tool_call> / <tool_call> XML 格式的技能调用 */
    private fun parseToolCallBlocks(
        content: String,
        results: MutableList<Pair<String, JsonObject>>,
        seen: MutableSet<String>
    ) {
        val toolCallPattern = Regex(
            """<tool_call>([\\s\\S]*?)</tool_call>""",
            RegexOption.DOT_MATCHES_ALL
        )
        for (match in toolCallPattern.findAll(content)) {
            val block = match.groupValues.getOrNull(1)?.trim() ?: continue
            val namePattern = Regex(
                """<tool_name>\\s*([\\s\\S]*?)\\s*</tool_name>""",
                RegexOption.DOT_MATCHES_ALL
            )
            val nameMatch = namePattern.find(block) ?: continue
            val skillType = nameMatch.groupValues[1].trim()
            if (skillType.isBlank() || !isValidSkillType(skillType)) continue
            val params = JsonObject()
            val paramPattern = Regex(
                """<parameter\\s+name\\s*=\\s*["']([^"']+)["]"""
# Cut off here - the rest has the problematic characters
