package com.android.gguf_llama_jin.inference

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HfTokenizerTest {
    @Test
    fun `encodes and decodes simple bpe tokens`() {
        val tokenizer = HfTokenizer.fromJson(
            """
            {
              "model": {
                "type": "BPE",
                "vocab": { "h": 0, "e": 1, "l": 2, "o": 3 },
                "merges": []
              },
              "added_tokens": []
            }
            """.trimIndent()
        ).getOrThrow()

        val ids = tokenizer.encode("hello")
        assertArrayEquals(longArrayOf(0, 1, 2, 2, 3), ids)
        assertEquals("hello", tokenizer.decode(ids.toList()))
    }

    @Test
    fun `uses merge ranks when available`() {
        val tokenizer = HfTokenizer.fromJson(
            """
            {
              "model": {
                "type": "BPE",
                "vocab": { "h": 0, "e": 1, "he": 2 },
                "merges": ["h e"]
              },
              "added_tokens": []
            }
            """.trimIndent()
        ).getOrThrow()

        val ids = tokenizer.encode("he")
        assertArrayEquals(longArrayOf(2), ids)
        assertEquals("he", tokenizer.decode(ids.toList()))
    }

    @Test
    fun `detects eos from added tokens`() {
        val tokenizer = HfTokenizer.fromJson(
            """
            {
              "model": {
                "type": "BPE",
                "vocab": { "a": 0 },
                "merges": []
              },
              "added_tokens": [
                { "id": 10, "content": "</s>", "special": true }
              ]
            }
            """.trimIndent()
        ).getOrThrow()

        assertTrue(10L in tokenizer.eosTokenIds)
    }
}
