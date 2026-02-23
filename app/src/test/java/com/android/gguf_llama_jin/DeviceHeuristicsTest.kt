package com.android.gguf_llama_jin

import com.android.gguf_llama_jin.core.DeviceHeuristics
import com.android.gguf_llama_jin.core.FitVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceHeuristicsTest {
    @Test
    fun storageVerdict_blocks_when_not_enough_space() {
        val verdict = DeviceHeuristics.storageVerdict(
            freeBytes = 1_000,
            modelBytes = 10_000
        )
        assertEquals(FitVerdict.BLOCK, verdict)
    }

    @Test
    fun storageVerdict_warns_when_margin_missing() {
        val verdict = DeviceHeuristics.storageVerdict(
            freeBytes = 11_000,
            modelBytes = 10_000
        )
        assertEquals(FitVerdict.WARN, verdict)
    }

    @Test
    fun ramVerdict_fits_when_peak_under_threshold() {
        val verdict = DeviceHeuristics.ramVerdict(
            availableRamBytes = 10_000,
            estimatedPeakBytes = 5_000
        )
        assertEquals(FitVerdict.FIT, verdict)
    }
}
