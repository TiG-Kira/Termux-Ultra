            // === 智能终止逻辑 ===
            if (skillsToExecute.isEmpty()) {
                // 无新技能要执行 → 视为回复完成
                if (hasDuplicateViolation) {
                    currentUserText = duplicateWarning!!
                    continue
                }
                android.util.Log.d("AiTermux", "AI 回复完成（无技能），终止循环")
                return
            }
            // 有技能要执行，重置容错计数
            missingEndTurnRounds = 0

