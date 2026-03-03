package com.android.gguf_llama_jin.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.android.gguf_llama_jin.core.Constants

class DownloadForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        startForeground(Constants.DOWNLOAD_NOTIFICATION_ID, baseNotification("Preparing downloads"))

        DownloadCoordinator.restoreQueue(this).forEach {
            DownloadCoordinator.enqueue(this, it)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        val quant = intent?.getStringExtra(EXTRA_QUANT)
        val url = intent?.getStringExtra(EXTRA_URL)
        val path = intent?.getStringExtra(EXTRA_PATH)
        if (!modelId.isNullOrBlank() && !quant.isNullOrBlank() && !url.isNullOrBlank() && !path.isNullOrBlank()) {
            DownloadCoordinator.enqueue(
                this,
                DownloadRequest(
                    modelId = modelId,
                    quant = quant,
                    url = url,
                    targetPath = path,
                    expectedSha256 = intent.getStringExtra(EXTRA_SHA),
                    expectedSizeBytes = intent.getLongExtra(EXTRA_SIZE, -1L).takeIf { it > 0 }
                )
            )
            updateNotification("Downloading $modelId ($quant)")
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

    companion object {
        const val EXTRA_MODEL_ID = "extra_model_id"
        const val EXTRA_QUANT = "extra_quant"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_SHA = "extra_sha"
        const val EXTRA_SIZE = "extra_size"

        fun start(context: Context, request: DownloadRequest) {
            val intent = Intent(context, DownloadForegroundService::class.java)
                .putExtra(EXTRA_MODEL_ID, request.modelId)
                .putExtra(EXTRA_QUANT, request.quant)
                .putExtra(EXTRA_URL, request.url)
                .putExtra(EXTRA_PATH, request.targetPath)
                .putExtra(EXTRA_SHA, request.expectedSha256)
                .putExtra(EXTRA_SIZE, request.expectedSizeBytes ?: -1L)

            context.startForegroundService(intent)
        }
    }
}
