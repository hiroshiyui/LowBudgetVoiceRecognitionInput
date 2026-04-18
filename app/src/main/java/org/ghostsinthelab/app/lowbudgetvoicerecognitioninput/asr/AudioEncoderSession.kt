package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * Minimal wrapper around the Gemma 4 audio encoder ONNX session.
 * Loads `audio_encoder_q4f16.onnx` (+ its `.onnx_data` sidecar, which
 * ORT picks up automatically when the pair sits in the same directory)
 * and runs it on a log-mel feature matrix from [AudioPreprocessor].
 */
class AudioEncoderSession(
    private val modelFile: File,
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

    data class Result(
        val outputName: String,
        val shape: LongArray,
        val data: FloatArray,
    )

    /**
     * Runs the encoder on a single audio clip's mel features.
     * Produces `audio_features` as a flat FloatArray; the caller can
     * reshape using [Result.shape].
     */
    fun encode(mel: AudioPreprocessor.LogMel): Result {
        val featuresShape = longArrayOf(1L, mel.numFrames.toLong(), mel.numBins.toLong())

        val featuresBuf = FloatBuffer.wrap(mel.data)
        // Mask is BOOL per the model's input contract; all-true = no padding.
        val mask2d: Array<BooleanArray> = arrayOf(BooleanArray(mel.numFrames) { true })

        OnnxTensor.createTensor(env, featuresBuf, featuresShape).use { featuresTensor ->
            OnnxTensor.createTensor(env, mask2d).use { maskTensor ->
                val inputs = mapOf(
                    "input_features" to featuresTensor,
                    "input_features_mask" to maskTensor,
                )
                session.run(inputs).use { out ->
                    val entry = out.iterator().next()
                    val name = entry.key
                    val tensor = entry.value as OnnxTensor
                    val info = tensor.info
                    val buf = tensor.floatBuffer
                    val flat = FloatArray(buf.remaining()).also { buf.get(it) }
                    return Result(name, info.shape, flat)
                }
            }
        }
    }

    override fun close() {
        session.close()
    }
}
