package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer

/**
 * Loads `embed_tokens_q4f16.onnx` and produces both outputs Gemma 4's decoder needs:
 *   - `inputs_embeds` [1, seq, 1536]   — the shared token embedding
 *   - `per_layer_inputs` [1, seq, 35, 256] — per-layer additional inputs
 *
 * Gemma 4 E2B's decoder consumes both; feeding only the first gives wrong outputs.
 */
class EmbedTokensSession(
    modelFile: File,
    envOverride: OrtEnvironment? = null,
) : Closeable {

    private val env = envOverride ?: OrtEnvironment.getEnvironment()
    private val session: OrtSession

    val inputSummary: Map<String, String>
    val outputSummary: Map<String, String>

    init {
        require(modelFile.exists()) { "Model file not found: $modelFile" }
        val opts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        session = env.createSession(modelFile.absolutePath, opts)
        inputSummary = session.inputInfo.mapValues { (_, v) -> v.info.toString() }
        outputSummary = session.outputInfo.mapValues { (_, v) -> v.info.toString() }
    }

    data class Result(
        val inputsEmbeds: FloatArray,   // [1, seq, 1536] flattened
        val perLayerInputs: FloatArray, // [1, seq, 35, 256] flattened
        val seqLen: Int,
    )

    fun embed(inputIds: IntArray): Result {
        val longIds = LongArray(inputIds.size) { inputIds[it].toLong() }
        val shape = longArrayOf(1L, inputIds.size.toLong())

        OnnxTensor.createTensor(env, LongBuffer.wrap(longIds), shape).use { idsTensor ->
            val inputs = mapOf("input_ids" to idsTensor)
            session.run(inputs).use { out ->
                val inputsEmbedsTensor = out.get("inputs_embeds").get() as OnnxTensor
                val perLayerTensor = out.get("per_layer_inputs").get() as OnnxTensor

                val embeds = FloatArray(inputsEmbedsTensor.floatBuffer.remaining())
                inputsEmbedsTensor.floatBuffer.get(embeds)

                val perLayer = FloatArray(perLayerTensor.floatBuffer.remaining())
                perLayerTensor.floatBuffer.get(perLayer)

                return Result(embeds, perLayer, inputIds.size)
            }
        }
    }

    override fun close() {
        session.close()
    }
}
