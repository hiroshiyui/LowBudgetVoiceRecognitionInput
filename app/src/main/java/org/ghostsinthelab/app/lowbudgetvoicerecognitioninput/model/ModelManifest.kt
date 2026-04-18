package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.model

import android.content.Context
import org.json.JSONObject

data class ManifestEntry(
    val path: String,
    val sizeBytes: Long,
    val sha256: String?,
)

data class ModelManifest(
    val modelId: String,
    val revision: String,
    val variant: String,
    val baseUrl: String,
    val files: List<ManifestEntry>,
) {
    val totalBytes: Long get() = files.sumOf { it.sizeBytes }

    companion object {
        fun load(context: Context, bundle: ModelBundle): ModelManifest =
            load(context, bundle.manifestAsset)

        fun load(
            context: Context,
            assetName: String = ModelBundle.Breeze.manifestAsset,
        ): ModelManifest {
            val text = context.assets.open(assetName).bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            val entries = json.getJSONArray("files").let { arr ->
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    ManifestEntry(
                        path = o.getString("path"),
                        sizeBytes = o.getLong("sizeBytes"),
                        sha256 = if (o.isNull("sha256")) null else o.getString("sha256"),
                    )
                }
            }
            return ModelManifest(
                modelId = json.getString("modelId"),
                revision = json.getString("revision"),
                variant = json.getString("variant"),
                baseUrl = json.getString("baseUrl"),
                files = entries,
            )
        }
    }
}
