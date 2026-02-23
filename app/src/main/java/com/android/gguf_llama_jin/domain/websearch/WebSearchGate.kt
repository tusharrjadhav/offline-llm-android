package com.android.gguf_llama_jin.domain.websearch

enum class WebSearchDecision {
    SKIP,
    SEARCH,
    SUGGEST
}

data class SearchGateResult(
    val decision: WebSearchDecision,
    val reason: String
)

interface WebSearchGate {
    fun evaluate(
        prompt: String,
        webAllowed: Boolean,
        explicitUserRequest: Boolean,
        explicitOffline: Boolean
    ): SearchGateResult
}

class RuleBasedWebSearchGate : WebSearchGate {
    override fun evaluate(
        prompt: String,
        webAllowed: Boolean,
        explicitUserRequest: Boolean,
        explicitOffline: Boolean
    ): SearchGateResult {
        val normalized = prompt.trim().lowercase()
        if (normalized.isBlank()) return SearchGateResult(WebSearchDecision.SKIP, "empty")
        if (!webAllowed) return SearchGateResult(WebSearchDecision.SKIP, "toggle_off")
        if (explicitOffline) return SearchGateResult(WebSearchDecision.SKIP, "user_offline")
        if (explicitUserRequest) return SearchGateResult(WebSearchDecision.SEARCH, "explicit_web_request")

        if (isGreeting(normalized) || isRewriteLike(normalized) || isCreative(normalized)) {
            return SearchGateResult(WebSearchDecision.SKIP, "local_only_intent")
        }

        if (containsRecency(normalized) || containsLiveData(normalized) || containsCitationRequest(normalized)) {
            return SearchGateResult(WebSearchDecision.SEARCH, "recency_or_live_or_source")
        }

        return SearchGateResult(WebSearchDecision.SUGGEST, "ambiguous")
    }

    private fun isGreeting(text: String): Boolean {
        val compact = text.replace(Regex("[^a-z ]"), "").trim()
        return compact in setOf("hi", "hello", "hey", "thanks", "thank you", "how are you")
    }

    private fun isRewriteLike(text: String): Boolean {
        return listOf("rewrite", "rephrase", "summarize", "make shorter", "proofread")
            .any { text.contains(it) }
    }

    private fun isCreative(text: String): Boolean {
        return listOf("brainstorm", "idea", "poem", "story", "caption")
            .any { text.contains(it) }
    }

    private fun containsRecency(text: String): Boolean {
        return listOf("today", "latest", "current", "news", "recent", "this week", "right now")
            .any { text.contains(it) }
    }

    private fun containsLiveData(text: String): Boolean {
        return listOf("weather", "score", "stock", "crypto", "price", "match result")
            .any { text.contains(it) }
    }

    private fun containsCitationRequest(text: String): Boolean {
        return listOf("source", "link", "reference", "citation")
            .any { text.contains(it) }
    }
}

