package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.model

import android.content.Context
import android.os.StatFs
import java.io.File

class ModelStorage(private val context: Context) {

    val rootDir: File =
        File(context.filesDir, "models/gemma-4-e2b-it-onnx").apply { mkdirs() }

    fun fileFor(entry: ManifestEntry): File =
        File(rootDir, entry.path).apply { parentFile?.mkdirs() }

    fun partialFor(entry: ManifestEntry): File =
        File(rootDir, "${entry.path}.partial").apply { parentFile?.mkdirs() }

    fun isInstalled(manifest: ModelManifest): Boolean =
        manifest.files.all { entry ->
            val f = fileFor(entry)
            f.exists() && f.length() == entry.sizeBytes
        }

    fun installedBytes(manifest: ModelManifest): Long =
        manifest.files.sumOf { entry ->
            fileFor(entry).takeIf { it.exists() }?.length() ?: 0L
        }

    fun freeBytes(): Long = StatFs(context.filesDir.absolutePath).availableBytes

    fun delete() {
        rootDir.deleteRecursively()
        rootDir.mkdirs()
    }
}
