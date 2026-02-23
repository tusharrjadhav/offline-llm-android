package com.android.gguf_llama_jin.domain.websearch

import com.android.gguf_llama_jin.data.websearch.WebSearchHit

object GroundedPromptBuilder {
    fun build(userPrompt: String, hits: List<WebSearchHit>): String {
        if (hits.isEmpty()) return userPrompt
        val context = hits.take(3).mapIndexed { index, hit ->
            "[${index + 1}] ${hit.title}\nURL: ${hit.url}\nSnippet: ${hit.snippet}"
        }.joinToString("\n\n")

        return """
            User question:
            $userPrompt

            Web context (top results):
            $context

            Instructions:
            - Use the web context for factual claims.
            - If a claim is from web context, cite with [1], [2], or [3].
            - If context is insufficient, say what is missing.
        """.trimIndent()
    }
}

