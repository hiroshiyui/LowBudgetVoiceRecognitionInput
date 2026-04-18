package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Whisper decoder session wrapper (Breeze-ASR-25). The decoder has a fixed
 * self-attention KV cache of 448 slots (Whisper's max output length); each
 * call reads positions `[0, offset)`, writes to position `offset` onwards,
 * and returns the FULL updated cache which we feed back in next time.
 *
 * Runtime contract (verified on-device 2026-04-18):
 *   - inputs:
 *       tokens                    i64 [1, N]
 *       in_n_layer_self_k_cache   f32 [32, 1, 448, 1280]
 *       in_n_layer_self_v_cache   f32 [32, 1, 448, 1280]
 *       n_layer_cross_k           f32 [32, 1, 1500, 1280]   (from encoder, constant)
 *       n_layer_cross_v           f32 [32, 1, 1500, 1280]   (from encoder, constant)
 *       offset                    i64 [1]
 *   - outputs:
 *       logits                    f32 [1, N, 51865]
 *       out_n_layer_self_k_cache  f32 [32, 1, 448, 1280]
 *       out_n_layer_self_v_cache  f32 [32, 1, 448, 1280]
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

    /**
     * Holds the outputs of a single decoder forward pass. Keeps the underlying
     * `session.run` Result alive until [close] so caller-facing tensors remain
     * valid (lets us feed them straight back in as the next call's inputs,
     * avoiding a ~150 MB self-KV clone per step).
     */
    class Step(
        private val result: OrtSession.Result,
        val logits: OnnxTensor,
        val selfK: OnnxTensor,
        val selfV: OnnxTensor,
    ) : Closeable {
        override fun close() {
            result.close()
        }
    }

    /**
     * Allocates zero-initialized self-K/V cache tensors for the very first
     * decoder call (before any tokens have been processed). Caller owns the
     * returned tensors and must close them once generation finishes.
     */
    fun emptySelfCache(): Pair<OnnxTensor, OnnxTensor> {
        val shape = longArrayOf(32L, 1L, 448L, 1280L)
        val n = 32 * 1 * 448 * 1280
        val kBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val vBuf = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val k = OnnxTensor.createTensor(env, kBuf, shape)
        val v = OnnxTensor.createTensor(env, vBuf, shape)
        return k to v
    }

    fun forward(
        tokens: LongArray,
        cross: WhisperEncoder.CrossCache,
        selfKIn: OnnxTensor,
        selfVIn: OnnxTensor,
        offset: Long,
    ): Step {
        val tokensShape = longArrayOf(1L, tokens.size.toLong())
        val tokensT = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), tokensShape)
        val offsetT = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(offset)), longArrayOf(1L),
        )
        try {
            val inputs = mapOf(
                "tokens" to tokensT,
                "in_n_layer_self_k_cache" to selfKIn,
                "in_n_layer_self_v_cache" to selfVIn,
                "n_layer_cross_k" to cross.kTensor,
                "n_layer_cross_v" to cross.vTensor,
                "offset" to offsetT,
            )
            val result = session.run(inputs)
            return Step(
                result = result,
                logits = result.get("logits").get() as OnnxTensor,
                selfK = result.get("out_n_layer_self_k_cache").get() as OnnxTensor,
                selfV = result.get("out_n_layer_self_v_cache").get() as OnnxTensor,
            )
        } finally {
            tokensT.close()
            offsetT.close()
        }
    }

    override fun close() {
        session.close()
    }
}
