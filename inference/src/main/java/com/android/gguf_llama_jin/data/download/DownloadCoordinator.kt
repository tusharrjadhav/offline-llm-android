package com.android.gguf_llama_jin.data.download

import android.content.Context
import com.android.gguf_llama_jin.core.AppLogger
import com.android.gguf_llama_jin.core.Constants
import com.android.gguf_llama_jin.core.ModelRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object DownloadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, DownloadTaskState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadTaskState>> = _states.asStateFlow()

    fun enqueue(context: Context, request: DownloadRequest) {
        val key = request.id()
        if (jobs.containsKey(key)) return

        val totalExpected = request.files.sumOf { it.expectedSizeBytes ?: 0L }.takeIf { it > 0L } ?: -1L
        updateState(
            key,
            DownloadTaskState(
                request = request,
                state = DownloadState.QUEUED,
                downloadedBytes = 0L,
                totalBytes = totalExpected
            )
        )
        persistQueue(context)

        jobs[key] = scope.launch {
            runDownload(context, request)
        }
    }

    suspend fun pause(request: DownloadRequest) {
        val key = request.id()
        jobs.remove(key)?.cancelAndJoin()
        _states.value[key]?.let { updateState(key, it.copy(state = DownloadState.PAUSED)) }
    }

    fun resume(context: Context, request: DownloadRequest) {
        enqueue(context, request)
    }

    suspend fun cancel(request: DownloadRequest) {
        val key = request.id()
        jobs.remove(key)?.cancelAndJoin()
        _states.value[key]?.let {
            request.files.forEach { file ->
                val f = File(file.targetPath)
                if (f.exists()) f.delete()
            }
            updateState(key, it.copy(state = DownloadState.CANCELED, error = null, downloadedBytes = 0L))
        }
    }

    private suspend fun runDownload(context: Context, request: DownloadRequest) {
        val key = request.id()
        try {
            var downloadedSoFar = 0L
            var totalBytes = request.files.sumOf { it.expectedSizeBytes ?: 0L }.takeIf { it > 0L } ?: -1L

            request.files.forEach { file ->
                File(file.targetPath).parentFile?.mkdirs()
                val existing = File(file.targetPath).takeIf { it.exists() }?.length() ?: 0L
                downloadedSoFar += existing
            }

            updateState(
                key,
                _states.value[key]!!.copy(
                    state = DownloadState.DOWNLOADING,
                    downloadedBytes = downloadedSoFar,
                    totalBytes = totalBytes
                )
            )

            for (file in request.files) {
                val before = File(file.targetPath).takeIf { it.exists() }?.length() ?: 0L
                val downloadedForFile = downloadSingleFile(key, file, downloadedSoFar - before, totalBytes)
                downloadedSoFar = downloadedForFile
                if (_states.value[key]?.state == DownloadState.CANCELED) return
            }

            updateState(key, _states.value[key]!!.copy(state = DownloadState.VERIFYING, activeFile = null))

            val verifyFail = request.files.firstOrNull { file ->
                val target = File(file.targetPath)
                !FileVerifier.verifySize(target, file.expectedSizeBytes) || !FileVerifier.verifySha(target, file.expectedSha256)
            }

            if (verifyFail != null) {
                val bad = File(verifyFail.targetPath)
                if (bad.exists()) bad.renameTo(File(bad.parentFile, bad.name + ".bad"))
                updateState(key, _states.value[key]!!.copy(state = DownloadState.FAILED, error = "Verification failed: ${verifyFail.fileName}"))
            } else {
                val finalSize = request.files.sumOf { File(it.targetPath).takeIf { f -> f.exists() }?.length() ?: 0L }
                updateState(
                    key,
                    _states.value[key]!!.copy(
                        state = DownloadState.COMPLETED,
                        downloadedBytes = finalSize,
                        totalBytes = finalSize,
                        error = null,
                        activeFile = null
                    )
                )
            }
        } catch (t: Throwable) {
            AppLogger.e("Download failed", t)
            _states.value[key]?.let { state ->
                if (state.state != DownloadState.CANCELED) {
                    updateState(key, state.copy(state = DownloadState.FAILED, error = t.message ?: "Unknown error"))
                }
            }
        } finally {
            jobs.remove(key)
            persistQueue(context)
        }
    }

    private fun downloadSingleFile(
        key: String,
        file: DownloadFile,
        downloadedBeforeFile: Long,
        totalBytesHint: Long,
        allowRetryOn416: Boolean = true
    ): Long {
        val target = File(file.targetPath)
        val existingBytes = if (target.exists()) target.length() else 0L
        var conn: HttpURLConnection? = null
        var downloaded = downloadedBeforeFile + existingBytes
        val expectedSize = file.expectedSizeBytes
        try {
            if (expectedSize != null && existingBytes == expectedSize) {
                AppLogger.i("Skipping already-complete file: ${file.fileName} size=$existingBytes")
                return downloaded
            }
            updateState(
                key,
                _states.value[key]!!.copy(
                    state = DownloadState.DOWNLOADING,
                    activeFile = file.fileName,
                    downloadedBytes = downloaded,
                    totalBytes = if (totalBytesHint > 0) totalBytesHint else _states.value[key]!!.totalBytes
                )
            )

            conn = URL(file.url).openConnection() as HttpURLConnection
            if (existingBytes > 0) conn.setRequestProperty("Range", "bytes=$existingBytes-")
            conn.setRequestProperty("User-Agent", Constants.USER_AGENT)
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000

            val responseCode = conn.responseCode
            if (responseCode == 416 && existingBytes > 0L) {
                val serverLength = contentRangeTotal(conn.getHeaderField("Content-Range"))
                val expectedOrServer = expectedSize ?: serverLength
                if (expectedOrServer != null && existingBytes >= expectedOrServer) {
                    AppLogger.i("HTTP 416 but file considered complete: ${file.fileName} existing=$existingBytes expected=$expectedOrServer")
                    return downloadedBeforeFile + existingBytes
                }
                if (allowRetryOn416) {
                    AppLogger.i("HTTP 416 retrying from scratch for ${file.fileName}; existing=$existingBytes expected=$expectedOrServer")
                    target.delete()
                    downloaded -= existingBytes
                    return downloadSingleFile(
                        key = key,
                        file = file,
                        downloadedBeforeFile = downloadedBeforeFile,
                        totalBytesHint = totalBytesHint,
                        allowRetryOn416 = false
                    )
                }
            }
            if (responseCode !in 200..299 && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP $responseCode ${conn.responseMessage}. ${errBody.take(180)}")
            }

            val appendMode = responseCode == HttpURLConnection.HTTP_PARTIAL
            if (existingBytes > 0 && !appendMode) {
                target.delete()
                downloaded -= existingBytes
            }

            conn.inputStream.use { input ->
                RandomAccessFile(target, "rw").use { raf ->
                    if (appendMode) raf.seek(existingBytes) else raf.setLength(0L)
                    val buffer = ByteArray(Constants.DOWNLOAD_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        downloaded += read
                        _states.value[key]?.let { state ->
                            updateState(
                                key,
                                state.copy(downloadedBytes = downloaded)
                            )
                        }
                    }
                }
            }
            return downloaded
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun contentRangeTotal(contentRange: String?): Long? {
        if (contentRange.isNullOrBlank()) return null
        // Example: bytes */123456 or bytes 100-999/123456
        val slash = contentRange.lastIndexOf('/')
        if (slash < 0 || slash >= contentRange.length - 1) return null
        return contentRange.substring(slash + 1).trim().toLongOrNull()
    }

    private fun updateState(key: String, value: DownloadTaskState) {
        _states.value = _states.value.toMutableMap().apply { put(key, value) }
    }

    private fun persistQueue(context: Context) {
        val file = File(context.filesDir, "download_queue.json")
        val arr = JSONArray()
        _states.value.values
            .filter { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PAUSED }
            .forEach { state ->
                val filesArr = JSONArray()
                state.request.files.forEach { f ->
                    filesArr.put(
                        JSONObject()
                            .put("fileName", f.fileName)
                            .put("url", f.url)
                            .put("targetPath", f.targetPath)
                            .put("expectedSha256", f.expectedSha256)
                            .put("expectedSizeBytes", f.expectedSizeBytes)
                    )
                }

                arr.put(
                    JSONObject()
                        .put("modelId", state.request.modelId)
                        .put("runtime", state.request.runtime.name)
                        .put("variant", state.request.variant)
                        .put("files", filesArr)
                )
            }
        file.writeText(arr.toString())
    }

    fun restoreQueue(context: Context): List<DownloadRequest> {
        val file = File(context.filesDir, "download_queue.json")
        if (!file.exists()) return emptyList()
        val text = file.readText().trim()
        if (text.isBlank()) return emptyList()
        val arr = JSONArray(text)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val runtime = runCatching {
                    ModelRuntime.valueOf(obj.optString("runtime"))
                }.getOrDefault(ModelRuntime.LLAMA_CPP_GGUF)
                val filesArr = obj.optJSONArray("files") ?: JSONArray()
                val files = buildList {
                    for (j in 0 until filesArr.length()) {
                        val fileObj = filesArr.optJSONObject(j) ?: continue
                        add(
                            DownloadFile(
                                fileName = fileObj.optString("fileName"),
                                url = fileObj.optString("url"),
                                targetPath = fileObj.optString("targetPath"),
                                expectedSha256 = if (fileObj.has("expectedSha256") && !fileObj.isNull("expectedSha256")) fileObj.optString("expectedSha256") else null,
                                expectedSizeBytes = if (fileObj.has("expectedSizeBytes") && !fileObj.isNull("expectedSizeBytes")) fileObj.optLong("expectedSizeBytes") else null
                            )
                        )
                    }
                }
                add(
                    DownloadRequest(
                        modelId = obj.optString("modelId"),
                        runtime = runtime,
                        variant = obj.optString("variant"),
                        files = files
                    )
                )
            }
        }
    }
}
