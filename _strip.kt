        // 2. 移除所有 <tool_call> 行业标准格式块
        val toolCallStrip = Regex(
            """<tool_call>([\s\S]*?)</tool_call>""",
            RegexOption.DOT_MATCHES_ALL
        )
        result = toolCallStrip.replace(result, """""").trim()

