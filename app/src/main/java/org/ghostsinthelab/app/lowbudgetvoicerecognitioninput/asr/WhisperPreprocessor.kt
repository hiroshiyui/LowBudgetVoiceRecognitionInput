package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Computes the 80-bin log-mel spectrogram Whisper expects.
 *
 * Audio is padded or truncated to exactly 30 s (480 000 samples @ 16 kHz).
 * STFT uses a Hann window of length 400. Mel filterbank is Slaney-normalised
 * (not HTK like Gemma's). Output is log10 with floor `1e-10`, then clipped
 * to `max - 8` and re-scaled `(x + 4) / 4`, giving `[80, 3000]` ready for
 * the encoder.
 *
 * Approximation: FFT size is 512 (next power-of-2 ≥ 400) instead of the 400
 * Whisper trains with. The 400-sample window is zero-padded to 512 before
 * FFT and the mel filterbank is computed for 257 bins (= 512/2+1) rather
 * than 201. The centre frequencies of the mel bins are identical; only the
 * FFT grid differs. Exact 400-point FFT would require Bluestein or
 * mixed-radix and is a known TODO — ship the approximation first, check
 * transcription quality empirically.
 */
class WhisperPreprocessor(
    private val sampleRate: Int = 16_000,
    private val frameLength: Int = 400,
    private val hopLength: Int = 160,
    private val fftLength: Int = 512,
    private val melBins: Int = 80,
    private val numFrames: Int = 3_000,
) {
    init {
        require(fftLength and (fftLength - 1) == 0) { "fftLength must be a power of 2" }
        require(frameLength <= fftLength) { "frameLength must fit in fftLength" }
    }

    /** Total audio samples the encoder expects (fixed 30 s at 16 kHz). */
    val numSamples: Int = hopLength * numFrames  // 480_000

    private val hannWindow: FloatArray = FloatArray(frameLength) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (frameLength - 1))).toFloat()
    }

    private val melFilters: Array<FloatArray> = buildSlaneyMelFilterbank()

    /** Returns a `[melBins * numFrames]` row-major flat array (mels first). */
    fun process(pcm: ShortArray): FloatArray {
        val audio = padOrTruncate(pcm)
        val re = FloatArray(fftLength)
        val im = FloatArray(fftLength)
        val out = FloatArray(melBins * numFrames)
        val spectrumBins = fftLength / 2 + 1

        for (f in 0 until numFrames) {
            val start = f * hopLength
            for (i in 0 until frameLength) {
                re[i] = audio[start + i] * hannWindow[i]
                im[i] = 0f
            }
            for (i in frameLength until fftLength) {
                re[i] = 0f; im[i] = 0f
            }
            fftInPlace(re, im)

            for (b in 0 until melBins) {
                val filter = melFilters[b]
                var sum = 0f
                for (k in 0 until spectrumBins) {
                    val p = re[k] * re[k] + im[k] * im[k]
                    sum += p * filter[k]
                }
                // Layout: [melBins × numFrames] row-major, mels outer, frames inner.
                // Matches Whisper's (80, 3000) tensor.
                out[b * numFrames + f] = sum
            }
        }

        // log10 with floor 1e-10
        var maxLog = Float.NEGATIVE_INFINITY
        for (i in out.indices) {
            val v = max(out[i], 1e-10f)
            val lg = log10(v.toDouble()).toFloat()
            out[i] = lg
            if (lg > maxLog) maxLog = lg
        }
        // Whisper clamp + normalise
        val floor = maxLog - 8f
        for (i in out.indices) {
            out[i] = (max(out[i], floor) + 4f) / 4f
        }
        return out
    }

    private fun padOrTruncate(pcm: ShortArray): FloatArray {
        val audio = FloatArray(numSamples)
        val n = min(pcm.size, numSamples)
        for (i in 0 until n) audio[i] = pcm[i] / 32768f
        return audio
    }

    private fun buildSlaneyMelFilterbank(): Array<FloatArray> {
        val lowHz = 0f
        val highHz = sampleRate / 2f
        val lowMel = slaneyMel(lowHz)
        val highMel = slaneyMel(highHz)
        val melPoints = FloatArray(melBins + 2) { i ->
            lowMel + (highMel - lowMel) * i / (melBins + 1)
        }
        val hzPoints = FloatArray(melBins + 2) { slaneyHz(melPoints[it]) }
        val spectrumBins = fftLength / 2 + 1
        val binFreqs = FloatArray(spectrumBins) { k -> k * sampleRate.toFloat() / fftLength }

        val filters = Array(melBins) { b ->
            FloatArray(spectrumBins) { k ->
                val f = binFreqs[k]
                val lo = hzPoints[b]
                val mid = hzPoints[b + 1]
                val hi = hzPoints[b + 2]
                val weight = when {
                    f <= lo || f >= hi -> 0f
                    f <= mid -> (f - lo) / (mid - lo)
                    else -> (hi - f) / (hi - mid)
                }
                weight
            }
        }
        // Slaney normalisation: each filter integrates to 1 in Hz (peak 2/(hi-lo))
        for (b in 0 until melBins) {
            val enorm = 2f / (hzPoints[b + 2] - hzPoints[b])
            for (k in 0 until spectrumBins) filters[b][k] *= enorm
        }
        return filters
    }

    /** Slaney mel: linear below 1000 Hz, log above. */
    private fun slaneyMel(hz: Float): Float {
        val minLogHz = 1000f
        val linSlope = 3f / 200f           // 200/3 Hz per mel → 3/200 mel per Hz
        val minLogMel = minLogHz * linSlope // 15
        val logStep = (ln(6.4) / 27.0).toFloat()
        return if (hz < minLogHz) hz * linSlope
        else minLogMel + ln((hz / minLogHz).toDouble()).toFloat() / logStep
    }

    private fun slaneyHz(mel: Float): Float {
        val minLogMel = 15f
        val minLogHz = 1000f
        val linInv = 200f / 3f
        val logStep = (ln(6.4) / 27.0).toFloat()
        return if (mel < minLogMel) mel * linInv
        else minLogHz * kotlin.math.exp((mel - minLogMel) * logStep.toDouble()).toFloat()
    }

    private fun fftInPlace(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val theta = -2.0 * PI / len
            val wr0 = cos(theta).toFloat()
            val wi0 = sin(theta).toFloat()
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
                    val nwr = wr * wr0 - wi * wi0
                    val nwi = wr * wi0 + wi * wr0
                    wr = nwr; wi = nwi
                }
                i += len
            }
            len = len shl 1
        }
    }
}
