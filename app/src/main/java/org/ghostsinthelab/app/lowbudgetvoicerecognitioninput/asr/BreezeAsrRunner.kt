package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import android.os.SystemClock
import java.io.File

/**
 * Runs a single push-to-talk utterance end-to-end through Breeze-ASR-25:
 *   PCM → [WhisperPreprocessor] → [WhisperEncoder] → greedy decode loop
 *   via [WhisperDecoder] → [WhisperTokenizer.decodePlain] → transcript.
 *
 * Session lifecycle is staggered to stay under the ~5 GB RAM target device:
 * the encoder is closed before the decoder opens. Cross-attention K/V
 * tensors are cloned off the encoder into caller-owned direct buffers so
 * they survive the session close and can be fed into every decoder step.
 *
 * Language is a BCP-47-ish tag matching [WhisperTokenizer.SpecialIds.languageIds]
 * (e.g. `"zh"`, `"en"`). The decoder prompt is
 *   `<|startoftranscript|> <|lang|> <|transcribe|> <|notimestamps|>`.
 */
class BreezeAsrRunner(
    private val encoderFile: File,
    private val decoderFile: File,
    private val tokensFile: File,
) {

    data class Phase(val name: String, val ms: Long)

    data class Result(
        val transcript: String,
        val generatedIds: IntArray,
        val phases: List<Phase>,
        val firstPredictedId: Int,
    )

    /**
     * @param pcm raw 16-bit PCM @ 16 kHz (any length up to 30 s; shorter is padded).
     * @param language code among [WhisperTokenizer.SpecialIds.languageIds].
     * @param maxNewTokens hard cap on generated tokens; Whisper's self-KV cache
     *        maxes out at 448 slots total.
     * @param onProgress receives (stepIndex, nextIdJustPredicted) per decode step.
     */
    fun transcribe(
        pcm: ShortArray,
        language: String,
        maxNewTokens: Int = 200,
        /**
         * Mel-frame window the encoder processes. Whisper's reference is
         * 3000 (30 s chunk). On memory-constrained devices, smaller values
         * shrink the encoder's cross-attention K/V linearly — 1000 frames
         * (10 s) cuts peak RAM by ~330 MB and is plenty for push-to-talk
         * utterances ≤ 10 s.
         */
        melFrames: Int = 1_000,
        onProgress: (step: Int, tokenId: Int) -> Unit = { _, _ -> },
    ): Result {
        val phases = ArrayList<Phase>()

        val mel = timed("preprocess", phases) {
            WhisperPreprocessor(numFrames = melFrames).process(pcm)
        }

        val tokenizer = timed("tokenizer", phases) {
            WhisperTokenizer.load(tokensFile)
        }
        val specials = tokenizer.specialIds()
        val langId = specials.languageIds[language]
            ?: specials.languageIds["en"]
            ?: error("No matching language token for '$language' (and no en fallback)")

        val cross = timed("encoder", phases) {
            WhisperEncoder(encoderFile).use { it.encode(mel, numFrames = melFrames) }
        }

        try {
            return WhisperDecoder(decoderFile).use { decoder ->
                runDecodeLoop(
                    decoder = decoder,
                    tokenizer = tokenizer,
                    cross = cross,
                    specials = specials,
                    langId = langId,
                    maxNewTokens = maxNewTokens,
                    phases = phases,
                    onProgress = onProgress,
                )
            }
        } finally {
            cross.close()
        }
    }

    private fun runDecodeLoop(
        decoder: WhisperDecoder,
        tokenizer: WhisperTokenizer,
        cross: WhisperEncoder.CrossCache,
        specials: WhisperTokenizer.SpecialIds,
        langId: Int,
        maxNewTokens: Int,
        phases: MutableList<Phase>,
        onProgress: (Int, Int) -> Unit,
    ): Result {
        val prefixIds = intArrayOf(
            specials.startOfTranscript,
            langId,
            specials.transcribe,
            specials.notimestamps,
        )

        val (initK, initV) = decoder.emptySelfCache()
        var step: WhisperDecoder.Step? = null
        val generated = ArrayList<Int>()
        var firstPredicted = -1
        try {
            val prefill = timed("prefill", phases) {
                decoder.forward(
                    tokens = prefixIds.map { it.toLong() }.toLongArray(),
                    cross = cross,
                    selfKIn = initK,
                    selfVIn = initV,
                    offset = 0L,
                )
            }
            step = prefill
            var offset = prefixIds.size.toLong()
            var nextId = argmaxLastRow(prefill.logits)
            firstPredicted = nextId

            val loopStart = SystemClock.elapsedRealtime()
            var stepCount = 0
            while (stepCount < maxNewTokens && offset < 448 && nextId != specials.endOfText) {
                generated.add(nextId)
                onProgress(stepCount, nextId)
                val prev = step!!
                val next = decoder.forward(
                    tokens = longArrayOf(nextId.toLong()),
                    cross = cross,
                    selfKIn = prev.selfK,
                    selfVIn = prev.selfV,
                    offset = offset,
                )
                prev.close()
                step = next
                offset += 1
                nextId = argmaxLastRow(next.logits)
                stepCount++
            }
            phases += Phase("decode", SystemClock.elapsedRealtime() - loopStart)

            val transcript = tokenizer.decodeBase64(generated.toIntArray(), skipSpecials = true)
            return Result(transcript, generated.toIntArray(), phases, firstPredicted)
        } finally {
            step?.close()
            initK.close()
            initV.close()
        }
    }

    /** Argmax over the last `N` row of a `[1, N, V]` f32 logits tensor. */
    private fun argmaxLastRow(logitsTensor: ai.onnxruntime.OnnxTensor): Int {
        val shape = logitsTensor.info.shape
        val n = shape[1].toInt()
        val v = shape[2].toInt()
        val buf = logitsTensor.floatBuffer
        val rowStart = (n - 1) * v
        var bestIdx = 0
        var bestVal = Float.NEGATIVE_INFINITY
        for (i in 0 until v) {
            val x = buf.get(rowStart + i)
            if (x > bestVal) {
                bestVal = x
                bestIdx = i
            }
        }
        return bestIdx
    }

    private inline fun <T> timed(
        name: String,
        phases: MutableList<Phase>,
        block: () -> T,
    ): T {
        val t0 = SystemClock.elapsedRealtime()
        val r = block()
        phases += Phase(name, SystemClock.elapsedRealtime() - t0)
        return r
    }
}
