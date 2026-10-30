package me.rerere.ai.provider

import me.rerere.ai.core.Tool

/** Sort definitions before rendering prompts or placing cache boundaries; never sort tool calls. */
fun List<Tool>.inPromptOrder(): List<Tool> = sortedBy { it.name }

fun Set<BuiltInTools>.inPromptOrder(): List<BuiltInTools> = sortedBy {
    when (it) {
        BuiltInTools.ImageGeneration -> "image_generation"
        BuiltInTools.Search -> "search"
        BuiltInTools.UrlContext -> "url_context"
    }
}
