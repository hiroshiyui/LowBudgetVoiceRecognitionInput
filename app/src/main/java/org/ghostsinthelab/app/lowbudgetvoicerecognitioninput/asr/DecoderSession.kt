package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer

/**
 * Gemma 4 E2B ONNX decoder (decoder_model_merged_q4f16.onnx).
 *
 * 35 transformer layers but only 15 carry their own KV cache (`num_kv_shared_layers=20`
 * in config.json — the last 20 layers reuse earlier caches). This class owns the cache
 * across successive [prefill] + [decodeStep] calls and hands the outputs from one forward
 * pass back as the past-KV inputs on the next.
 *
 * Per-layer head_dim mirrors the layer's attention type:
 *   - sliding attention: head_dim = 256
 *   - full attention  : head_dim = 512  (layers 4, 9, 14 of the 15 cached indices)
 *
 * f16 handling: logits + KV tensors are float16. Empty past-KV tensors are sent in the
 * first pass as `[1, 1, 0, head_dim]` with a dummy buffer. Output KV tensors are cloned
 * from the Result before it's closed — the Result owns the native memory and closing it
 * would free tensors we want to keep for the next call.
 */
class DecoderSession(
    modelFile: File,
    envOverride: OrtEnvironment? = null,
) : Closeable {

    private val env = envOverride ?: OrtEnvironment.getEnvironment()
    private val session: OrtSession

    val inputSummary: Map<String, String>
    val outputSummary: Map<String, String>
    val executionProvider: String

    private val kvCache: Array<OnnxTensor?> = arrayOfNulls(2 * NUM_KV_CACHED_LAYERS)
    private var pastSeqLen: Int = 0

    init {
        require(modelFile.exists()) { "Model file not found: $modelFile" }
        val (s, ep) = AsrSessionOptions.openSession(env, modelFile)
        session = s
        executionProvider = ep
        inputSummary = session.inputInfo.mapValues { (_, v) -> v.info.toString() }
        outputSummary = session.outputInfo.mapValues { (_, v) -> v.info.toString() }
    }

    /**
     * First forward pass with the full prompt (`seqLen` tokens).
     * Returns the logits of the LAST token as a float array of size [VOCAB_SIZE].
     */
    fun prefill(embeds: EmbedTokensSession.Result): FloatArray {
        reset()
        return forward(embeds, isFirst = true)
    }

    /**
     * Incremental forward pass for a single newly generated token. Expects
     * `embeds.seqLen == 1`. Returns logits for that token.
     */
    fun decodeStep(oneTokenEmbeds: EmbedTokensSession.Result): FloatArray {
        require(oneTokenEmbeds.seqLen == 1) { "decodeStep expects seqLen=1" }
        require(kvCache.all { it != null }) { "Call prefill before decodeStep" }
        return forward(oneTokenEmbeds, isFirst = false)
    }

    fun pastSequenceLength(): Int = pastSeqLen

    fun reset() {
        for (t in kvCache) t?.close()
        for (i in kvCache.indices) kvCache[i] = null
        pastSeqLen = 0
    }

    override fun close() {
        reset()
        session.close()
    }

    // ---------- internals ----------

    private fun forward(embeds: EmbedTokensSession.Result, isFirst: Boolean): FloatArray {
        val seqLen = embeds.seqLen
        val totalSeqLen = pastSeqLen + seqLen

        val inputsEmbedsT = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(embeds.inputsEmbeds),
            longArrayOf(1L, seqLen.toLong(), HIDDEN_SIZE.toLong()),
        )
        val perLayerT = OnnxTensor.createTensor(
            env, FloatBuffer.wrap(embeds.perLayerInputs),
            longArrayOf(
                1L, seqLen.toLong(),
                NUM_PHYSICAL_LAYERS.toLong(),
                PER_LAYER_INPUT_DIM.toLong(),
            ),
        )
        val attnMaskT = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(totalSeqLen) { 1L }),
            longArrayOf(1L, totalSeqLen.toLong()),
        )
        val positionIdsT = OnnxTensor.createTensor(
            env, LongBuffer.wrap(LongArray(seqLen) { (pastSeqLen + it).toLong() }),
            longArrayOf(1L, seqLen.toLong()),
        )
        val numLogitsT = OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(1L)), longArrayOf(),
        )

        // Past KV: empty placeholders on first pass, else reuse from previous call.
        val createdEmpties: Array<OnnxTensor>? = if (isFirst) {
            Array(2 * NUM_KV_CACHED_LAYERS) { slot ->
                makeEmptyF16Tensor(kvHeadDims[slot / 2])
            }
        } else null
        val pastTensors: Array<OnnxTensor> =
            createdEmpties ?: Array(2 * NUM_KV_CACHED_LAYERS) { slot -> kvCache[slot]!! }

        val inputs = HashMap<String, OnnxTensor>(5 + 2 * NUM_KV_CACHED_LAYERS)
        inputs["inputs_embeds"] = inputsEmbedsT
        inputs["per_layer_inputs"] = perLayerT
        inputs["attention_mask"] = attnMaskT
        inputs["position_ids"] = positionIdsT
        inputs["num_logits_to_keep"] = numLogitsT
        for (layer in 0 until NUM_KV_CACHED_LAYERS) {
            inputs["past_key_values.$layer.key"] = pastTensors[2 * layer]
            inputs["past_key_values.$layer.value"] = pastTensors[2 * layer + 1]
        }

        val result = session.run(inputs)
        try {
            val logitsTensor = result.get("logits").get() as OnnxTensor
            val logitsShortBuf = logitsTensor.byteBuffer
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
            val count = logitsShortBuf.remaining()
            val logits = FloatArray(count) { Fp16.toFloat(logitsShortBuf.get(it)) }

            // Clone all new KV tensors before touching the old cache — failure-atomic.
            val newCache = arrayOfNulls<OnnxTensor>(2 * NUM_KV_CACHED_LAYERS)
            try {
                for (layer in 0 until NUM_KV_CACHED_LAYERS) {
                    val keyT = result.get("present.$layer.key").get() as OnnxTensor
                    val valT = result.get("present.$layer.value").get() as OnnxTensor
                    newCache[2 * layer] = cloneF16Tensor(keyT)
                    newCache[2 * layer + 1] = cloneF16Tensor(valT)
                }
            } catch (t: Throwable) {
                for (c in newCache) c?.close()
                throw t
            }

            if (!isFirst) for (c in kvCache) c?.close()
            for (i in newCache.indices) kvCache[i] = newCache[i]
            pastSeqLen = totalSeqLen

            return logits
        } finally {
            result.close()
            inputsEmbedsT.close()
            perLayerT.close()
            attnMaskT.close()
            positionIdsT.close()
            numLogitsT.close()
            // On first pass, the past-KV placeholders we built here are ours to close.
            // On subsequent passes, pastTensors aliases the old kvCache which we've
            // already closed above.
            createdEmpties?.forEach { it.close() }
        }
    }

    private fun makeEmptyF16Tensor(headDim: Int): OnnxTensor {
        // ORT requires a non-null buffer even when the tensor is empty. A tiny
        // 2-byte direct buffer satisfies the API; shape has a 0 so no bytes are read.
        val buf = ByteBuffer.allocateDirect(2)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
        return OnnxTensor.createTensor(
            env, buf,
            longArrayOf(1L, 1L, 0L, headDim.toLong()),
            OnnxJavaType.FLOAT16,
        )
    }

    private fun cloneF16Tensor(src: OnnxTensor): OnnxTensor {
        val shape = src.info.shape
        val srcShorts: ShortBuffer = src.byteBuffer
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
        val numShorts = srcShorts.remaining()
        val dstBytes = ByteBuffer.allocateDirect(maxOf(numShorts * 2, 2))
            .order(ByteOrder.nativeOrder())
        val dstShorts = dstBytes.asShortBuffer()
        if (numShorts > 0) {
            dstShorts.put(srcShorts)
            dstShorts.rewind()
        }
        return OnnxTensor.createTensor(env, dstShorts, shape, OnnxJavaType.FLOAT16)
    }

    companion object {
        const val HIDDEN_SIZE = 1536
        const val NUM_PHYSICAL_LAYERS = 35
        const val PER_LAYER_INPUT_DIM = 256
        const val NUM_KV_CACHED_LAYERS = 15
        const val VOCAB_SIZE = 262144

        /** head_dim per cached layer index — full-attention layers (4, 9, 14) use 512. */
        val kvHeadDims: IntArray = IntArray(NUM_KV_CACHED_LAYERS) { i ->
            if (i == 4 || i == 9 || i == 14) 512 else 256
        }
    }
}
