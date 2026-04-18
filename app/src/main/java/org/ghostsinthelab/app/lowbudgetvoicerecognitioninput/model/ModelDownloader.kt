package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloader(private val client: OkHttpClient = defaultClient()) {

    suspend fun download(
        url: String,
        target: File,
        partial: File,
        expectedSize: Long,
        expectedSha256: String?,
        onProgress: suspend (bytesWritten: Long) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        if (target.exists() && target.length() == expectedSize) {
            onProgress(expectedSize)
            return@withContext
        }

        if (partial.exists()) partial.delete()

        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use { resp ->
            check(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
            val body = resp.body ?: error("Empty body for $url")

            val digest = expectedSha256?.let { MessageDigest.getInstance("SHA-256") }
            val buf = ByteArray(64 * 1024)
            var written = 0L

            FileOutputStream(partial).use { out ->
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest?.update(buf, 0, n)
                        written += n
                        onProgress(written)
                    }
                }
            }

            check(written == expectedSize) {
                "Size mismatch for $url: got $written, expected $expectedSize"
            }
            digest?.let { md ->
                val actual = md.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(expectedSha256, ignoreCase = true)) {
                    "SHA-256 mismatch for $url: got $actual, expected $expectedSha256"
                }
            }
        }

        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "Failed to rename $partial -> $target" }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(60, TimeUnit.MINUTES)
            .build()
    }
}
