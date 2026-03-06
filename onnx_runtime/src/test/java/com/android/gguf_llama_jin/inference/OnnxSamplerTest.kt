package com.android.gguf_llama_jin.inference

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class OnnxSamplerTest {
    @Test
    fun `greedy path returns max logit token`() {
        val id = OnnxSampler.sampleNextToken(
            logits = floatArrayOf(0.1f, 2.5f, 1.1f),
            temperature = 0f,
            topP = 1f,
            random = Random(42)
        )
        assertEquals(1, id)
    }

    @Test
    fun `top p path samples from filtered distribution`() {
        val id = OnnxSampler.sampleNextToken(
            logits = floatArrayOf(9f, 4f, 3f, 2f),
            temperature = 0.8f,
            topP = 0.5f,
            random = Random(1)
        )
        assertEquals(0, id)
    }
}
