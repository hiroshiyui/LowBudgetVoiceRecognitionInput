package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.model

/**
 * Identifies a downloadable model bundle: its manifest JSON in `assets/`,
 * its on-device storage subdirectory (under `filesDir/models/`), the
 * WorkManager unique-work name for its download, and a human label.
 *
 * Having both [Gemma] and [Breeze] side by side lets the pivot to Breeze
 * proceed without breaking existing Gemma debug tooling until Breeze is
 * validated end-to-end.
 */
enum class ModelBundle(
    val manifestAsset: String,
    val storageSubdir: String,
    val workName: String,
    val label: String,
) {
    Gemma(
        manifestAsset = "model_manifest.json",
        storageSubdir = "gemma-4-e2b-it-onnx",
        workName = "model-download-gemma",
        label = "Gemma 4 E2B (legacy)",
    ),
    Breeze(
        manifestAsset = "breeze_model_manifest.json",
        storageSubdir = "breeze-asr-25-onnx",
        workName = "model-download-breeze",
        label = "Breeze-ASR-25",
    ),
}
