            // 执行完技能后，如果没有需要回传的结果则直接终止
            val unlimitedModeActive = AiTermuxPrefs.isUnlimitedModeActive(ctx)
            if (executedSkillTypes.isNotEmpty() &&
                executedSkillTypes.all { it.requiresClick(autoExecSkills, unlimitedModeActive) }) {
                return
            }

            // 构建回传给 AI 的结果消息（移除 END_TURN 依赖）
            val baseResult = lastResultText ?: "(技能执行完成，无输出)"
            currentUserText = if (hasDuplicateViolation) {
                ""\n\n"
            } else {
                baseResult
            }
        }
    }
