package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

/**
 * Replaces the embedding rows at every `audioTokenId` placeholder in `inputIds`
 * with rows from `audioFeatures`, in order. Gemma 4's audio encoder emits features
 * already in the decoder's hidden-size space (1536), so no projection is needed —
 * just a row-level copy.
 *
 * Both [inputsEmbeds] and [audioFeatures] are assumed to be batch-1 and laid out
 * row-major over `[seqLen, hiddenSize]` / `[numAudioRows, hiddenSize]`.
 */
object AudioScatter {
    fun scatter(
        inputsEmbeds: FloatArray,
        inputIds: IntArray,
        audioFeatures: FloatArray,
        audioTokenId: Int,
        hiddenSize: Int = DecoderSession.HIDDEN_SIZE,
    ) {
        require(inputsEmbeds.size == inputIds.size * hiddenSize) {
            "inputsEmbeds size ${inputsEmbeds.size} != seq ${inputIds.size} * hidden $hiddenSize"
        }
        val audioRows = audioFeatures.size / hiddenSize
        require(audioFeatures.size == audioRows * hiddenSize) {
            "audioFeatures size ${audioFeatures.size} not a multiple of hidden $hiddenSize"
        }

        var used = 0
        for (i in inputIds.indices) {
            if (inputIds[i] != audioTokenId) continue
            require(used < audioRows) {
                "more audio placeholders in prompt than audio feature rows ($audioRows)"
            }
            System.arraycopy(
                audioFeatures, used * hiddenSize,
                inputsEmbeds, i * hiddenSize,
                hiddenSize,
            )
            used++
        }
        require(used == audioRows) {
            "prompt has $used audio placeholders but encoder produced $audioRows feature rows"
        }
    }
}
