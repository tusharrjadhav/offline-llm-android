package com.android.gguf_llama_jin.data.download

data class DownloadRequest(
    val modelId: String,
    val quant: String,
    val url: String,
    val targetPath: String,
    val expectedSha256: String? = null,
    val expectedSizeBytes: Long? = null
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
    val error: String? = null
)

fun DownloadRequest.id(): String = "${modelId}_${quant}"
