            // 智能完成检测：不依赖 [END_TURN]，无技能调用即视为回复完毕
            val hasSkills = skills.isNotEmpty()
            val cleanedReply = replyText.replace("[END_TURN]", "").trimEnd()
