            val paramPattern = Regex(
                """<parameter\s+name\s*=\s*["']([^"']+)["']\s*>([\s\S]*?)</parameter>""",
                RegexOption.DOT_MATCHES_ALL
            )
            val jsonObject = JsonObject()
            paramPattern.findAll(block).forEach { pm ->
                val pname = pm.groupValues[1].trim()
                val pval = pm.groupValues[2].trim()
                try { jsonObject.add(pname, JsonPrimitive(pval)) } catch (_: Exception) { }
            }
            params = jsonObject
            val key = """$skillType:${params}"""
            if (key !in seen) {
                seen.add(key)
                results.add(skillType to params)
            }
