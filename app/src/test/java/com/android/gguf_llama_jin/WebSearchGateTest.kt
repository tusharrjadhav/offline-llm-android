package com.android.gguf_llama_jin

import com.android.gguf_llama_jin.domain.websearch.RuleBasedWebSearchGate
import com.android.gguf_llama_jin.domain.websearch.WebSearchDecision
import org.junit.Assert.assertEquals
import org.junit.Test

class WebSearchGateTest {
    private val gate = RuleBasedWebSearchGate()

    @Test
    fun greeting_is_skip() {
        val result = gate.evaluate(
            prompt = "hi",
            webAllowed = true,
            explicitUserRequest = false,
            explicitOffline = false
        )
        assertEquals(WebSearchDecision.SKIP, result.decision)
    }

    @Test
    fun recency_prompt_is_search() {
        val result = gate.evaluate(
            prompt = "latest weather in pune",
            webAllowed = true,
            explicitUserRequest = false,
            explicitOffline = false
        )
        assertEquals(WebSearchDecision.SEARCH, result.decision)
    }

    @Test
    fun ambiguous_prompt_is_suggest() {
        val result = gate.evaluate(
            prompt = "tell me about rust memory model",
            webAllowed = true,
            explicitUserRequest = false,
            explicitOffline = false
        )
        assertEquals(WebSearchDecision.SUGGEST, result.decision)
    }

    @Test
    fun explicit_offline_overrides_web() {
        val result = gate.evaluate(
            prompt = "latest bitcoin price",
            webAllowed = true,
            explicitUserRequest = true,
            explicitOffline = true
        )
        assertEquals(WebSearchDecision.SKIP, result.decision)
    }
}

