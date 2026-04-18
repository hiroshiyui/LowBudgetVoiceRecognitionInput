package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.model

import android.app.ActivityManager
import android.content.Context

object Preflight {
    private const val GIB = 1024L * 1024 * 1024

    private const val MIN_RAM_BYTES = 6 * GIB
    private const val WARN_RAM_BYTES = 8 * GIB
    private const val MIN_DISK_HEADROOM_BYTES = 4 * GIB

    sealed interface Result {
        data object Ok : Result
        data class Warn(val message: String) : Result
        data class Block(val message: String) : Result
    }

    fun check(context: Context, manifest: ModelManifest, storage: ModelStorage): Result {
        val ram = totalRamBytes(context)
        val free = storage.freeBytes()
        val needed = manifest.totalBytes - storage.installedBytes(manifest)
        val freeNeeded = maxOf(MIN_DISK_HEADROOM_BYTES, needed + 200L * 1024 * 1024)

        if (ram < MIN_RAM_BYTES) {
            return Result.Block(
                ("This device has %.1f GB RAM. The voice model needs at least " +
                    "6 GB; 8 GB recommended.").format(ram.toDouble() / GIB)
            )
        }
        if (free < freeNeeded) {
            return Result.Block(
                "Need %.1f GB free; only %.1f GB available.".format(
                    freeNeeded.toDouble() / GIB,
                    free.toDouble() / GIB,
                )
            )
        }
        if (ram < WARN_RAM_BYTES) {
            return Result.Warn(
                ("Only %.1f GB RAM detected. The voice model may run out of " +
                    "memory; 8 GB is recommended.").format(ram.toDouble() / GIB)
            )
        }
        return Result.Ok
    }

    fun totalRamBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }
}
