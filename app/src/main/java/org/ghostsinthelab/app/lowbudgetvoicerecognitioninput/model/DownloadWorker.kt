package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        setForeground(buildForegroundInfo(0, 1, "Preparing model download…"))

        val manifest = ModelManifest.load(ctx)
        val storage = ModelStorage(ctx)
        val downloader = ModelDownloader()

        val totalBytes = manifest.totalBytes
        var overallBaseBytes = manifest.files.sumOf { e ->
            storage.fileFor(e).takeIf { it.exists() && it.length() == e.sizeBytes }?.length() ?: 0L
        }

        try {
            for ((index, entry) in manifest.files.withIndex()) {
                val target = storage.fileFor(entry)
                if (target.exists() && target.length() == entry.sizeBytes) continue

                val partial = storage.partialFor(entry)
                val url = "${manifest.baseUrl}/${entry.path}"
                val baseAtStart = overallBaseBytes
                val label = "${entry.path} (${index + 1}/${manifest.files.size})"

                downloader.download(
                    url = url,
                    target = target,
                    partial = partial,
                    expectedSize = entry.sizeBytes,
                    expectedSha256 = entry.sha256,
                    onProgress = { fileBytes ->
                        val now = baseAtStart + fileBytes
                        setProgress(
                            workDataOf(
                                KEY_OVERALL_BYTES to now,
                                KEY_TOTAL_BYTES to totalBytes,
                                KEY_CURRENT_FILE to label,
                            )
                        )
                        setForeground(buildForegroundInfo(now, totalBytes, label))
                    },
                )
                overallBaseBytes = baseAtStart + entry.sizeBytes
            }
            return Result.success()
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            return Result.failure(workDataOf(KEY_ERROR to (t.message ?: t.javaClass.simpleName)))
        }
    }

    private fun buildForegroundInfo(progress: Long, total: Long, text: String): ForegroundInfo {
        ensureChannel(applicationContext)
        val pct = if (total > 0) ((progress * 100) / total).toInt().coerceIn(0, 100) else 0
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading voice model")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    companion object {
        const val WORK_NAME = "model-download"
        const val NOTIFICATION_ID = 0xD0
        const val CHANNEL_ID = "model-download"

        const val KEY_OVERALL_BYTES = "overallBytes"
        const val KEY_TOTAL_BYTES = "totalBytes"
        const val KEY_CURRENT_FILE = "currentFile"
        const val KEY_ERROR = "error"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Model download",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
    }
}
