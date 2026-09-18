package com.termux.app.plugin

import com.google.gson.Gson

data class ComposeUiNode(
    val type: String,
    val props: Map<String, Any?> = emptyMap(),
    val children: List<ComposeUiNode>? = null
)

object ComposeUiNodeParser {
    private val gson = Gson()
    fun parse(json: String): ComposeUiNode? = runCatching {
        gson.fromJson(json, ComposeUiNode::class.java)
    }.getOrNull()
}
