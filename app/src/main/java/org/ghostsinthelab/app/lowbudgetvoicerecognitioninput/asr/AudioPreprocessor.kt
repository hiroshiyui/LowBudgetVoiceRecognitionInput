package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Computes a 128-bin log-mel spectrogram matching
 * `Gemma4AudioFeatureExtractor` in processor_config.json:
 * frame 320 / hop 160 / FFT 512 / 128 HTK-mel bins over 0-8000 Hz,
 * natural log with floor 0.001, no preemphasis, Hamming window.
 * Input PCM 16-bit @ 16 kHz; output `[numFrames][128]` flat.
 */
class AudioPreprocessor(
    private val sampleRate: Int = 16_000,
    private val frameLength: Int = 320,
    private val hopLength: Int = 160,
    private val fftLength: Int = 512,
    private val melBins: Int = 128,
    private val minFreqHz: Float = 0f,
    private val maxFreqHz: Float = 8_000f,
    private val melFloor: Float = 0.001f,
) {
    init {
        require(fftLength and (fftLength - 1) == 0) {
            "fftLength must be a power of 2, got $fftLength"
        }
        require(frameLength <= fftLength) {
            "frameLength ($frameLength) must be <= fftLength ($fftLength)"
        }
    }

    private val window: FloatArray = FloatArray(frameLength) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (frameLength - 1))).toFloat()
    }

    private val melFilters: Array<FloatArray> = buildHtkMelFilterbank()

    data class LogMel(val data: FloatArray, val numFrames: Int, val numBins: Int)

    fun process(pcm: ShortArray): LogMel {
        val audio = FloatArray(pcm.size) { pcm[it] / 32768f }
        val numFrames = when {
            audio.size < frameLength -> 1
            else -> 1 + (audio.size - frameLength) / hopLength
        }
        val out = FloatArray(numFrames * melBins)

        val re = FloatArray(fftLength)
        val im = FloatArray(fftLength)
        val spectrumBins = fftLength / 2 + 1

        for (f in 0 until numFrames) {
            val start = f * hopLength
            for (i in 0 until frameLength) {
                val src = start + i
                re[i] = if (src < audio.size) audio[src] * window[i] else 0f
                im[i] = 0f
            }
            for (i in frameLength until fftLength) {
                re[i] = 0f
                im[i] = 0f
            }

            fftInPlace(re, im)

            for (b in 0 until melBins) {
                val filter = melFilters[b]
                var sum = 0f
                for (k in 0 until spectrumBins) {
                    val power = re[k] * re[k] + im[k] * im[k]
                    sum += power * filter[k]
                }
                out[f * melBins + b] = ln(max(sum, melFloor))
            }
        }
        return LogMel(out, numFrames, melBins)
    }

    private fun buildHtkMelFilterbank(): Array<FloatArray> {
        val lowMel = htkMel(minFreqHz)
        val highMel = htkMel(maxFreqHz)
        val melPoints = FloatArray(melBins + 2) { i ->
            lowMel + (highMel - lowMel) * i / (melBins + 1)
        }
        val hzPoints = FloatArray(melBins + 2) { i -> htkHz(melPoints[i]) }
        val binPoints = FloatArray(melBins + 2) { i ->
            (fftLength + 1).toFloat() * hzPoints[i] / sampleRate
        }

        val spectrumBins = fftLength / 2 + 1
        return Array(melBins) { b ->
            FloatArray(spectrumBins) { k ->
                val kf = k.toFloat()
                when {
                    kf < binPoints[b] || kf > binPoints[b + 2] -> 0f
                    kf <= binPoints[b + 1] ->
                        (kf - binPoints[b]) / (binPoints[b + 1] - binPoints[b])
                    else ->
                        (binPoints[b + 2] - kf) / (binPoints[b + 2] - binPoints[b + 1])
                }
            }
        }
    }

    private fun htkMel(hz: Float): Float =
        2595f * log10(1.0 + hz / 700.0).toFloat()

    private fun htkHz(mel: Float): Float =
        (700.0 * (10.0.pow(mel / 2595.0) - 1.0)).toFloat()

    /** In-place radix-2 Cooley-Tukey FFT. `re.size == im.size` must be a power of 2. */
    private fun fftInPlace(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val theta = -2.0 * PI / len
            val wStepRe = cos(theta).toFloat()
            val wStepIm = sin(theta).toFloat()
            var i = 0
            while (i < n) {
                var wr = 1f
                var wi = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = wr * re[i + k + half] - wi * im[i + k + half]
                    val vi = wr * im[i + k + half] + wi * re[i + k + half]
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + half] = ur - vr
                    im[i + k + half] = ui - vi
                    val newWr = wr * wStepRe - wi * wStepIm
                    val newWi = wr * wStepIm + wi * wStepRe
                    wr = newWr
                    wi = newWi
                }
                i += len
            }
            len = len shl 1
        }
    }
}
