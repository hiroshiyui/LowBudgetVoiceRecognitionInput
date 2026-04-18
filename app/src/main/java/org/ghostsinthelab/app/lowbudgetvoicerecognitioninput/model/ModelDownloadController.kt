package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.model

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ModelDownloadController(
    private val context: Context,
    val bundle: ModelBundle = ModelBundle.Breeze,
) {

    private val workManager = WorkManager.getInstance(context)
    private val storage = ModelStorage(context, bundle)
    val manifest: ModelManifest = ModelManifest.load(context, bundle.manifestAsset)

    sealed interface State {
        data object NotStarted : State
        data class Running(val bytes: Long, val total: Long, val currentFile: String?) : State
        data object Installed : State
        data class Error(val message: String) : State
    }

    fun enqueue() {
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(bundle.workName)
            .setInputData(workDataOf(DownloadWorker.KEY_BUNDLE to bundle.name))
            .build()
        workManager.enqueueUniqueWork(
            bundle.workName,
            ExistingWorkPolicy.KEEP,
            req,
        )
    }

    fun cancel() {
        workManager.cancelUniqueWork(bundle.workName)
    }

    fun deleteInstalled() {
        cancel()
        storage.delete()
    }

    fun storage(): ModelStorage = storage

    fun observe(): Flow<State> {
        return workManager
            .getWorkInfosForUniqueWorkFlow(bundle.workName)
            .map { infos ->
                val info = infos.lastOrNull()
                when {
                    storage.isInstalled(manifest) -> State.Installed
                    info == null -> State.NotStarted
                    info.state == WorkInfo.State.RUNNING ||
                        info.state == WorkInfo.State.ENQUEUED -> {
                        val p = info.progress
                        val installedNow = storage.installedBytes(manifest)
                        State.Running(
                            bytes = maxOf(
                                installedNow,
                                p.getLong(DownloadWorker.KEY_OVERALL_BYTES, installedNow),
                            ),
                            total = p.getLong(
                                DownloadWorker.KEY_TOTAL_BYTES,
                                manifest.totalBytes,
                            ),
                            currentFile = p.getString(DownloadWorker.KEY_CURRENT_FILE),
                        )
                    }
                    info.state == WorkInfo.State.FAILED ->
                        State.Error(
                            info.outputData.getString(DownloadWorker.KEY_ERROR)
                                ?: "Download failed"
                        )
                    info.state == WorkInfo.State.CANCELLED -> State.NotStarted
                    info.state == WorkInfo.State.SUCCEEDED -> State.Installed
                    else -> State.NotStarted
                }
            }
    }
}
