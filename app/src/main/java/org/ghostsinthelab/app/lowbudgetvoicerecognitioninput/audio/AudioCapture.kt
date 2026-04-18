package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class AudioCapture(
    private val sampleRateHz: Int = 16_000,
    private val frameSamples: Int = 320,
    private val maxDurationSec: Int = 30,
) {
    sealed interface State {
        data object Idle : State
        data object Recording : State
        data class Done(val pcm: ShortArray, val truncated: Boolean) : State
        data class Error(val cause: Throwable) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val _samplesRead = MutableStateFlow(0)
    val samplesRead: StateFlow<Int> = _samplesRead.asStateFlow()

    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (job != null) return
        _state.value = State.Recording
        _amplitude.value = 0f
        _samplesRead.value = 0

        job = scope.launch(Dispatchers.IO) {
            val maxSamples = sampleRateHz * maxDurationSec
            val pcm = ShortArray(maxSamples)
            var written = 0
            var truncated = false
            var recorder: AudioRecord? = null
            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                check(minBuffer > 0) { "AudioRecord min buffer size unavailable: $minBuffer" }
                val bufferBytes = maxOf(minBuffer, frameSamples * 2 * 4)
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
                check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                    "AudioRecord initialization failed"
                }
                recorder.startRecording()

                val frame = ShortArray(frameSamples)
                while (isActive && written < maxSamples) {
                    val n = recorder.read(frame, 0, frameSamples)
                    if (n <= 0) break
                    val toCopy = minOf(n, maxSamples - written)
                    System.arraycopy(frame, 0, pcm, written, toCopy)
                    written += toCopy

                    var acc = 0.0
                    for (i in 0 until toCopy) {
                        val v = frame[i] / 32768.0
                        acc += v * v
                    }
                    _amplitude.value = sqrt(acc / toCopy).toFloat()
                    _samplesRead.value = written

                    if (written >= maxSamples) {
                        truncated = true
                        break
                    }
                }
                _state.value = State.Done(pcm.copyOf(written), truncated)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    _state.value = State.Done(pcm.copyOf(written), truncated)
                    throw t
                }
                _state.value = State.Error(t)
            } finally {
                runCatching { recorder?.stop() }
                recorder?.release()
                _amplitude.value = 0f
                job = null
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
