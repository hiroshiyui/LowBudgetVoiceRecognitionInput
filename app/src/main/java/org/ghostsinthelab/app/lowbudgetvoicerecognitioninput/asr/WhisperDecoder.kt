package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File

/**
 * Stub wrapper for the Breeze-ASR-25 Whisper decoder ONNX session. Whisper
 * decoders have a more involved contract (self-attention KV cache plus
 * cross-attention K/V threaded in from the encoder output) — exposing the
 * runtime contract here lets us write the generate loop once the real tensor
 * names / shapes / dtypes are known.
 */
class WhisperDecoder(
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
