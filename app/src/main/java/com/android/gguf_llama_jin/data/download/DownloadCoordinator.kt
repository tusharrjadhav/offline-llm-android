package com.android.gguf_llama_jin.data.download

import android.content.Context
import com.android.gguf_llama_jin.core.AppLogger
import com.android.gguf_llama_jin.core.Constants
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.io.RandomAccessFile

object DownloadCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, DownloadTaskState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadTaskState>> = _states.asStateFlow()

    fun enqueue(context: Context, request: DownloadRequest) {
        val key = request.id()
        if (jobs.containsKey(key)) return

        updateState(key, DownloadTaskState(request, DownloadState.QUEUED, 0L, request.expectedSizeBytes ?: -1L))
        persistQueue(context)

        jobs[key] = scope.launch {
            runDownload(context, request)
        }
    }

    suspend fun pause(request: DownloadRequest) {
        val key = request.id()
        jobs.remove(key)?.cancelAndJoin()
        val current = _states.value[key]
        if (current != null) {
            updateState(key, current.copy(state = DownloadState.PAUSED))
        }
    }

    fun resume(context: Context, request: DownloadRequest) {
        enqueue(context, request)
    }

    suspend fun cancel(request: DownloadRequest) {
        val key = request.id()
        jobs.remove(key)?.cancelAndJoin()
        val current = _states.value[key]
        if (current != null) {
            val file = File(request.targetPath)
            if (file.exists()) file.delete()
            updateState(key, current.copy(state = DownloadState.CANCELED, error = null, downloadedBytes = 0L))
        }
    }

    private suspend fun runDownload(context: Context, request: DownloadRequest) {
        val key = request.id()
        val target = File(request.targetPath)
        target.parentFile?.mkdirs()
        var conn: HttpURLConnection? = null

        try {
            val existingBytes = if (target.exists()) target.length() else 0L
            updateState(
                key,
                DownloadTaskState(
                    request = request,
                    state = DownloadState.DOWNLOADING,
                    downloadedBytes = existingBytes,
                    totalBytes = request.expectedSizeBytes ?: -1L
                )
            )

            conn = URL(request.url).openConnection() as HttpURLConnection
            if (existingBytes > 0) {
                conn.setRequestProperty("Range", "bytes=$existingBytes-")
            }
            conn.setRequestProperty("User-Agent", Constants.USER_AGENT)
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000

            val responseCode = conn.responseCode
            if (responseCode !in 200..299 && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw IllegalStateException("HTTP $responseCode ${conn.responseMessage}. ${errBody.take(180)}")
            }
            val appendMode = responseCode == HttpURLConnection.HTTP_PARTIAL
            if (existingBytes > 0 && !appendMode) {
                target.delete()
            }

            val totalFromHeader = conn.getHeaderFieldLong("Content-Length", -1L)
            val total = if (appendMode && totalFromHeader > 0) totalFromHeader + existingBytes else totalFromHeader

            conn.inputStream.use { input ->
                RandomAccessFile(target, "rw").use { raf ->
                    if (appendMode) raf.seek(existingBytes) else raf.setLength(0L)
                    val buffer = ByteArray(Constants.DOWNLOAD_BUFFER_SIZE)
                    var downloaded = if (appendMode) existingBytes else 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        raf.write(buffer, 0, read)
                        downloaded += read
                        _states.value[key]?.let { state ->
                            updateState(
                                key,
                                state.copy(
                                    downloadedBytes = downloaded,
                                    totalBytes = if (total > 0) total else state.totalBytes
                                )
                            )
                        }
                    }
                }
            }

            updateState(key, _states.value[key]!!.copy(state = DownloadState.VERIFYING))

            val sizeOk = FileVerifier.verifySize(target, request.expectedSizeBytes)
            val shaOk = FileVerifier.verifySha(target, request.expectedSha256)
            if (!sizeOk || !shaOk) {
                val quarantine = File(target.parentFile, target.name + ".bad")
                target.renameTo(quarantine)
                updateState(key, _states.value[key]!!.copy(state = DownloadState.FAILED, error = "Verification failed"))
            } else {
                updateState(
                    key,
                    _states.value[key]!!.copy(
                        state = DownloadState.COMPLETED,
                        downloadedBytes = target.length(),
                        totalBytes = target.length(),
                        error = null
                    )
                )
            }
        } catch (t: Throwable) {
            AppLogger.e("Download failed", t)
            val state = _states.value[key]
            if (state != null && state.state != DownloadState.CANCELED) {
                updateState(key, state.copy(state = DownloadState.FAILED, error = t.message ?: "Unknown error"))
            }
        } finally {
            runCatching { conn?.disconnect() }
            jobs.remove(key)
            persistQueue(context)
        }
    }

    private fun updateState(key: String, value: DownloadTaskState) {
        _states.value = _states.value.toMutableMap().apply { put(key, value) }
    }

    private fun persistQueue(context: Context) {
        val file = File(context.filesDir, "download_queue.json")
        val arr = JSONArray()
        _states.value.values
            .filter { it.state == DownloadState.QUEUED || it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PAUSED }
            .forEach {
                arr.put(
                    JSONObject()
                        .put("modelId", it.request.modelId)
                        .put("quant", it.request.quant)
                        .put("url", it.request.url)
                        .put("targetPath", it.request.targetPath)
                        .put("expectedSha256", it.request.expectedSha256)
                        .put("expectedSizeBytes", it.request.expectedSizeBytes)
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
                add(
                    DownloadRequest(
                        modelId = obj.optString("modelId"),
                        quant = obj.optString("quant"),
                        url = obj.optString("url"),
                        targetPath = obj.optString("targetPath"),
                        expectedSha256 = if (obj.has("expectedSha256") && !obj.isNull("expectedSha256")) obj.optString("expectedSha256") else null,
                        expectedSizeBytes = if (obj.has("expectedSizeBytes")) obj.optLong("expectedSizeBytes") else null
                    )
                )
            }
        }
    }
}
