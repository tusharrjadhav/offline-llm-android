package com.android.gguf_llama_jin.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ServiceInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.android.gguf_llama_jin.core.Constants
import com.android.gguf_llama_jin.core.ModelRuntime
import org.json.JSONArray
import org.json.JSONObject

class DownloadForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        if (!startAsForeground()) {
            stopSelf()
            return
        }

        DownloadCoordinator.restoreQueue(this).forEach {
            DownloadCoordinator.enqueue(this, it)
        }
    }

    private fun startAsForeground(): Boolean {
        val notification = baseNotification("Preparing downloads")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    Constants.DOWNLOAD_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(Constants.DOWNLOAD_NOTIFICATION_ID, notification)
            }
            true
        } catch (exception: SecurityException) {
            Log.e(TAG, "Failed to start dataSync foreground service", exception)
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestJson = intent?.getStringExtra(EXTRA_REQUEST_JSON)
        if (!requestJson.isNullOrBlank()) {
            val request = requestFromJson(requestJson)
            DownloadCoordinator.enqueue(this, request)
            updateNotification("Downloading ${request.modelId} (${request.variant})")
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            Constants.DOWNLOAD_CHANNEL_ID,
            "Model Downloads",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
    }

    private fun baseNotification(content: String): Notification {
        return NotificationCompat.Builder(this, Constants.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("GGUF Model Download")
            .setContentText(content)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(Constants.DOWNLOAD_NOTIFICATION_ID, baseNotification(content))
    }

    private fun requestToJson(request: DownloadRequest): String {
        val files = JSONArray()
        request.files.forEach { file ->
            files.put(
                JSONObject()
                    .put("fileName", file.fileName)
                    .put("url", file.url)
                    .put("targetPath", file.targetPath)
                    .put("expectedSha256", file.expectedSha256)
                    .put("expectedSizeBytes", file.expectedSizeBytes)
            )
        }
        return JSONObject()
            .put("modelId", request.modelId)
            .put("runtime", request.runtime.name)
            .put("variant", request.variant)
            .put("files", files)
            .toString()
    }

    private fun requestFromJson(json: String): DownloadRequest {
        val obj = JSONObject(json)
        val runtime = runCatching { ModelRuntime.valueOf(obj.optString("runtime")) }
            .getOrDefault(ModelRuntime.LLAMA_CPP_GGUF)
        val filesArr = obj.optJSONArray("files") ?: JSONArray()
        val files = buildList {
            for (i in 0 until filesArr.length()) {
                val f = filesArr.optJSONObject(i) ?: continue
                add(
                    DownloadFile(
                        fileName = f.optString("fileName"),
                        url = f.optString("url"),
                        targetPath = f.optString("targetPath"),
                        expectedSha256 = if (f.has("expectedSha256") && !f.isNull("expectedSha256")) f.optString("expectedSha256") else null,
                        expectedSizeBytes = if (f.has("expectedSizeBytes") && !f.isNull("expectedSizeBytes")) f.optLong("expectedSizeBytes") else null
                    )
                )
            }
        }
        return DownloadRequest(
            modelId = obj.optString("modelId"),
            runtime = runtime,
            variant = obj.optString("variant"),
            files = files
        )
    }

    companion object {
        private const val TAG = "DownloadForegroundSvc"
        const val EXTRA_REQUEST_JSON = "extra_request_json"

        fun start(context: Context, request: DownloadRequest) {
            val files = JSONArray()
            request.files.forEach { file ->
                files.put(
                    JSONObject()
                        .put("fileName", file.fileName)
                        .put("url", file.url)
                        .put("targetPath", file.targetPath)
                        .put("expectedSha256", file.expectedSha256)
                        .put("expectedSizeBytes", file.expectedSizeBytes)
                )
            }
            val payload = JSONObject()
                .put("modelId", request.modelId)
                .put("runtime", request.runtime.name)
                .put("variant", request.variant)
                .put("files", files)
                .toString()

            val intent = Intent(context, DownloadForegroundService::class.java)
                .putExtra(EXTRA_REQUEST_JSON, payload)

            context.startForegroundService(intent)
        }
    }
}
