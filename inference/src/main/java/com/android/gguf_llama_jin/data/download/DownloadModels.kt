package com.android.gguf_llama_jin.data.download

import com.android.gguf_llama_jin.core.ModelRuntime

data class DownloadFile(
    val fileName: String,
    val url: String,
    val targetPath: String,
    val expectedSha256: String? = null,
    val expectedSizeBytes: Long? = null
)

data class DownloadRequest(
    val modelId: String,
    val runtime: ModelRuntime,
    val variant: String,
    val files: List<DownloadFile>
)

enum class DownloadState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELED
}

data class DownloadTaskState(
    val request: DownloadRequest,
    val state: DownloadState,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val activeFile: String? = null,
    val error: String? = null
)

fun DownloadRequest.id(): String = "${runtime.name}_${modelId}_${variant}"
