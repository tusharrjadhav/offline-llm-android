package com.android.gguf_llama_jin.core

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import kotlin.math.roundToLong

data class CapabilitySnapshot(
    val totalRamMb: Int,
    val availableRamMb: Int,
    val freeStorageBytes: Long,
    val recommendedMaxModelBytes: Long
)

enum class FitVerdict {
    FIT,
    WARN,
    BLOCK
}

object DeviceHeuristics {
    fun snapshot(context: Context): CapabilitySnapshot {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val filesDir = context.filesDir
        val stat = StatFs(filesDir.absolutePath)
        val freeStorage = stat.availableBytes

        val totalRamMb = (memInfo.totalMem / (1024 * 1024)).toInt()
        val availRamMb = (memInfo.availMem / (1024 * 1024)).toInt()
        val recommended = (memInfo.totalMem * 0.35).roundToLong()

        return CapabilitySnapshot(
            totalRamMb = totalRamMb,
            availableRamMb = availRamMb,
            freeStorageBytes = freeStorage,
            recommendedMaxModelBytes = recommended
        )
    }

    fun storageVerdict(freeBytes: Long, modelBytes: Long): FitVerdict {
        val required = (modelBytes * 1.25).toLong()
        return when {
            freeBytes >= required -> FitVerdict.FIT
            freeBytes >= modelBytes -> FitVerdict.WARN
            else -> FitVerdict.BLOCK
        }
    }

    fun ramVerdict(availableRamBytes: Long, estimatedPeakBytes: Long): FitVerdict {
        val threshold = (availableRamBytes * 0.65).toLong()
        return when {
            estimatedPeakBytes <= threshold -> FitVerdict.FIT
            estimatedPeakBytes <= availableRamBytes -> FitVerdict.WARN
            else -> FitVerdict.BLOCK
        }
    }
}
