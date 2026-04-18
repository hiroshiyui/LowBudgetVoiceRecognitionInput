package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File

/**
 * Stub wrapper for the Breeze-ASR-25 Whisper encoder ONNX session. Exposes
 * the runtime input/output contract so the debug activity can dump the real
 * tensor names/shapes before we write inference against them.
 */
class WhisperEncoder(
    modelFile: File,
    envOverride: OrtEnvironment? = null,
) : Closeable {

    private val env = envOverride ?: OrtEnvironment.getEnvironment()
    private val session: OrtSession

    val inputSummary: Map<String, String>
    val outputSummary: Map<String, String>
    val executionProvider: String

    init {
        require(modelFile.exists()) { "Model file not found: $modelFile" }
        val (s, ep) = AsrSessionOptions.openSession(env, modelFile)
        session = s
        executionProvider = ep
        inputSummary = session.inputInfo.mapValues { (_, v) -> v.info.toString() }
        outputSummary = session.outputInfo.mapValues { (_, v) -> v.info.toString() }
    }

    override fun close() {
        session.close()
    }
}
