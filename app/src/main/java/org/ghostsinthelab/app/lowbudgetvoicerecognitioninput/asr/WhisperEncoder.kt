package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Whisper encoder session wrapper (Breeze-ASR-25 fine-tune). Takes a log-mel
 * spectrogram and returns per-layer cross-attention K/V tensors the decoder
 * will reuse on every decode step.
 *
 * Runtime contract (verified on-device 2026-04-18):
 *   - input  `mel`             f32 [1, 80, 3000]
 *   - output `n_layer_cross_k` f32 [32, 1, 1500, 1280]
 *   - output `n_layer_cross_v` f32 [32, 1, 1500, 1280]
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

    /**
     * Owns the cross-attn K/V tensors after the underlying Result is closed.
     * Tensors are cloned into caller-owned direct buffers so the encoder
     * session can be freed while the decoder loop runs — important when the
     * device barely has room for both models at once.
     */
    class CrossCache(
        val kTensor: OnnxTensor,
        val vTensor: OnnxTensor,
    ) : Closeable {
        override fun close() {
            kTensor.close()
            vTensor.close()
        }
    }

    /**
     * Runs the encoder on a flat mel array of size `numBins * numFrames`
     * (mels first, frames inner — matches [WhisperPreprocessor]'s output).
     */
    fun encode(
        mel: FloatArray,
        numFrames: Int = 3000,
        numBins: Int = 80,
    ): CrossCache {
        require(mel.size == numBins * numFrames) {
            "mel size ${mel.size} != ${numBins * numFrames}"
        }
        val shape = longArrayOf(1L, numBins.toLong(), numFrames.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), shape).use { melTensor ->
            session.run(mapOf("mel" to melTensor)).use { out ->
                val kSrc = out.get("n_layer_cross_k").get() as OnnxTensor
                val vSrc = out.get("n_layer_cross_v").get() as OnnxTensor
                return CrossCache(
                    kTensor = cloneFloatTensor(kSrc),
                    vTensor = cloneFloatTensor(vSrc),
                )
            }
        }
    }

    private fun cloneFloatTensor(src: OnnxTensor): OnnxTensor {
        val shape = src.info.shape
        val srcBuf = src.floatBuffer
        val n = srcBuf.remaining()
        val dstBytes = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())
        val dst = dstBytes.asFloatBuffer()
        dst.put(srcBuf.duplicate())
        dst.rewind()
        return OnnxTensor.createTensor(env, dst, shape)
    }

    override fun close() {
        session.close()
    }
}
