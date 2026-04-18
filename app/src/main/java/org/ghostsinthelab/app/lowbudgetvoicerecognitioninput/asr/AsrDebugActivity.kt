package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.audio.AudioCapture
import org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.model.ModelBundle
import org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.model.ModelManifest
import org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.model.ModelStorage
import org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.ui.theme.LowBudgetVoiceRecognitionInputTheme

class AsrDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LowBudgetVoiceRecognitionInputTheme {
                AsrDebugScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AsrDebugScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    var running by remember { mutableStateOf(false) }
    var hasMic by remember { mutableStateOf(micGranted(context)) }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMic = granted }

    Scaffold(
        topBar = { TopAppBar(title = { Text("ASR debug (M4a)") }) }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            fun launchJob(usage: String, fn: suspend ((String) -> Unit) -> Unit) {
                scope.launch {
                    running = true
                    log.clear()
                    try {
                        fn { log.add(it) }
                    } catch (t: Throwable) {
                        log.add("ERROR: ${t.javaClass.simpleName}: ${t.message}")
                    } finally {
                        val savedAt = runCatching {
                            saveLog(context, usage, log.toList())
                        }.getOrNull()
                        if (savedAt != null) {
                            log.add("")
                            log.add("Saved to ${savedAt.absolutePath}")
                        }
                        running = false
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        enabled = !running,
                        onClick = {
                            if (!hasMic) {
                                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                return@FilledTonalButton
                            }
                            launchJob("pipeline") { runPipeline(context, it) }
                        },
                    ) {
                        Text(
                            when {
                                !hasMic -> "Grant mic"
                                running -> "Running…"
                                else -> "Run pipeline"
                            }
                        )
                    }
                    OutlinedButton(
                        enabled = !running,
                        onClick = { log.clear() },
                    ) { Text("Clear") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !running,
                        onClick = { launchJob("tok") { runTokenizerTest(context, it) } },
                    ) { Text("Test tokenizer") }
                    OutlinedButton(
                        enabled = !running,
                        onClick = { launchJob("embed") { runEmbedTokensTest(context, it) } },
                    ) { Text("Test embeds") }
                    OutlinedButton(
                        enabled = !running,
                        onClick = { launchJob("dec_insp") { runInspectDecoder(context, it) } },
                    ) { Text("Inspect decoder") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !running,
                        onClick = { launchJob("dec_test") { runDecoderTextTest(context, it) } },
                    ) { Text("Test decoder (text-only)") }
                }
            }

            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (line in log) {
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full M4b.3 pipeline: record → preprocess → audio encode → scatter into prompt
 * embeddings → decoder prefill → greedy decode until EOS → detokenise.
 *
 * Session lifecycle is staggered to stay under the device's RAM budget:
 *   1. audio encoder loaded and closed before embed_tokens even opens
 *   2. embed_tokens kept open for the whole decode loop (one call per new token)
 *   3. decoder kept open for the whole decode loop
 * Peak simultaneous: embed_tokens (~1.5 GB) + decoder (~1.4 GB).
 */
private suspend fun runPipeline(context: Context, log: (String) -> Unit) {
    val manifest = ModelManifest.load(context, ModelBundle.Gemma)
    val storage = ModelStorage(context, ModelBundle.Gemma)
    if (!storage.isInstalled(manifest)) {
        log("Model not installed.")
        return
    }

    val audioEncoderFile = storage.fileFor(
        manifest.files.first { it.path == "onnx/audio_encoder_q4f16.onnx" }
    )
    val embedFile = storage.fileFor(
        manifest.files.first { it.path == "onnx/embed_tokens_q4f16.onnx" }
    )
    val decoderFile = storage.fileFor(
        manifest.files.first { it.path == "onnx/decoder_model_merged_q4f16.onnx" }
    )
    val tokenizerFile = storage.fileFor(manifest.files.first { it.path == "tokenizer.json" })

    val totalT0 = SystemClock.elapsedRealtime()

    log("1. Recording 5 s…")
    val pcm = recordPcm(durationSec = 5)
    log("   ${pcm.size} samples")

    log("")
    log("2. Preprocessing…")
    val (mel, tMel) = timed { AudioPreprocessor().process(pcm) }
    log("   ${mel.numFrames} frames in $tMel ms")

    log("")
    log("3. Audio encoder (open → encode → close)…")
    var audioEp = ""
    val (audioFeatures, tEnc) = timed {
        AudioEncoderSession(audioEncoderFile).use {
            audioEp = it.executionProvider
            it.encode(mel)
        }
    }
    val numAudioTokens = audioFeatures.shape[0].toInt()
    log("   EP: $audioEp")
    log("   ${audioFeatures.shape.toList()} in $tEnc ms (→ $numAudioTokens audio tokens)")

    log("")
    log("4. Loading tokenizer…")
    val (tok, tTok) = timed {
        withContext(Dispatchers.IO) { GemmaTokenizer.load(tokenizerFile) }
    }
    log("   $tTok ms")

    log("")
    log("5. Rendering + tokenising prompt…")
    val promptText = AsrPrompt.render(numAudioTokens, language = "en-US")
    val (ids, tEncIds) = timed { tok.encodeWithSpecials(promptText) }
    val audioPositions = ids.count { it == AsrPrompt.AUDIO_TOKEN_ID }
    log("   prompt tokens: ${ids.size}  (audio placeholders: $audioPositions) in $tEncIds ms")
    require(audioPositions == numAudioTokens) {
        "audio placeholders $audioPositions != audio tokens $numAudioTokens"
    }

    log("")
    log("6. Embedding prompt (open → embed → close)…")
    var embedEp = ""
    val embeds = EmbedTokensSession(embedFile).use {
        embedEp = it.executionProvider
        it.embed(ids)
    }
    log("   EP: $embedEp")
    log("   inputs_embeds: ${embeds.inputsEmbeds.size} floats")

    // Sanity: magnitudes of text embeddings vs audio features
    val textEmbedsMin = embeds.inputsEmbeds.minOrNull() ?: 0f
    val textEmbedsMax = embeds.inputsEmbeds.maxOrNull() ?: 0f
    val audioMin = audioFeatures.data.minOrNull() ?: 0f
    val audioMax = audioFeatures.data.maxOrNull() ?: 0f
    log("   text embed range  [% .3f, % .3f]".format(textEmbedsMin, textEmbedsMax))
    log("   audio feat range  [% .3f, % .3f]".format(audioMin, audioMax))

    // Last ~12 prompt tokens with decoded strings — verifies the turn prefix is intact
    log("   prompt tail (last 12 tokens):")
    val tailStart = (ids.size - 12).coerceAtLeast(0)
    for (i in tailStart until ids.size) {
        val piece = tok.decode(intArrayOf(ids[i]), skipSpecialTokens = false)
            .replace("\n", "\\n")
        log("     [$i] ${ids[i]} '$piece'")
    }

    AudioScatter.scatter(
        inputsEmbeds = embeds.inputsEmbeds,
        inputIds = ids,
        audioFeatures = audioFeatures.data,
        audioTokenId = AsrPrompt.AUDIO_TOKEN_ID,
    )
    log("   scattered $numAudioTokens audio rows into prompt embeddings")

    log("")
    log("7. Loading decoder…")
    val (decoder, tDecLoad) = timed {
        withContext(Dispatchers.IO) { DecoderSession(decoderFile) }
    }
    log("   EP: ${decoder.executionProvider}")
    log("   $tDecLoad ms")

    val transcript: String
    try {
        log("")
        log("8. Prefill…")
        val (prefillLogits, tPrefill) = timed { decoder.prefill(embeds) }
        log("   ${ids.size} prompt tokens in $tPrefill ms (${tPrefill / ids.size} ms/token)")

        // Top-5 predictions for the first generated token
        val top5 = topK(prefillLogits, 5)
        log("   top-5 predictions:")
        for ((tokId, tokLogit) in top5) {
            val piece = tok.decode(intArrayOf(tokId), skipSpecialTokens = false).replace("\n", "\\n")
            log("     %-7d %7.2f  '%s'".format(tokId, tokLogit, piece))
        }
        var nextId = top5.first().first

        log("")
        log("9. Decoding until EOS (cap 80)…")
        log("   (embed_tokens is re-opened briefly per step — keeps RAM pressure low)")
        val generated = ArrayList<Int>()
        val eosSet = AsrPrompt.EOS_IDS.toSet()
        var decodeMs = 0L
        for (step in 0 until 80) {
            if (nextId in eosSet) {
                log("   EOS id=$nextId after ${generated.size} tokens")
                break
            }
            generated.add(nextId)
            val stepEmbeds = EmbedTokensSession(embedFile).use {
                it.embed(intArrayOf(nextId))
            }
            val t0 = SystemClock.elapsedRealtime()
            val stepLogits = decoder.decodeStep(stepEmbeds)
            decodeMs += SystemClock.elapsedRealtime() - t0
            nextId = argmax(stepLogits)
        }
        transcript = tok.decode(generated.toIntArray())
        log("   total decode: $decodeMs ms for ${generated.size} tokens " +
            "(${if (generated.isNotEmpty()) decodeMs / generated.size else 0} ms/token)")
    } finally {
        decoder.close()
    }

    val totalMs = SystemClock.elapsedRealtime() - totalT0
    log("")
    log("=== TRANSCRIPT ===")
    log(transcript.trim().ifBlank { "(empty)" })
    log("")
    log("Total wall time: ${totalMs / 1000.0} s")
}

private suspend fun runTokenizerTest(context: Context, log: (String) -> Unit) {
    log("1. Checking model…")
    val manifest = ModelManifest.load(context, ModelBundle.Gemma)
    val storage = ModelStorage(context, ModelBundle.Gemma)
    if (!storage.isInstalled(manifest)) {
        log("   Model not installed.")
        return
    }
    val tokenizerFile = storage.fileFor(manifest.files.first { it.path == "tokenizer.json" })
    log("   ok: ${tokenizerFile.absolutePath} (${tokenizerFile.length()} bytes)")

    log("")
    log("2. Loading tokenizer…")
    val (tok, tLoad) = timed {
        withContext(Dispatchers.IO) { GemmaTokenizer.load(tokenizerFile) }
    }
    log("   loaded in $tLoad ms, vocab size ${tok.vocabSize()}")
    log("   audio_token_id lookup: ${tok.idOf("<|audio|>")}")
    log("   bos lookup: ${tok.idOf("<bos>")}, eos lookup: ${tok.idOf("<eos>")}")

    log("")
    log("3. Encoding 'Hello world' (no specials)…")
    val helloIds = tok.encode("Hello world")
    log("   ids = ${helloIds.toList()}")
    val helloBack = tok.decode(helloIds)
    log("   roundtrip: '$helloBack'")

    log("")
    log("4. Encoding a chunk with specials (<|audio|> placeholder)…")
    val withSpec = tok.encodeWithSpecials(
        "<bos>Transcribe the following speech in English: <|audio|> End."
    )
    log("   length = ${withSpec.size}")
    log("   first 20 = ${withSpec.take(20)}")
    log("   contains <|audio|> id? ${tok.idOf("<|audio|>") in withSpec.toList()}")

    log("")
    log("5. Decoding specials-stripped…")
    val back = tok.decode(withSpec, skipSpecialTokens = true)
    log("   '$back'")

    log("")
    log("6. Byte-fallback sanity (non-ASCII)…")
    val unicodeIds = tok.encode("こんにちは")
    log("   ids len=${unicodeIds.size}")
    val unicodeBack = tok.decode(unicodeIds)
    log("   roundtrip: '$unicodeBack'")
}

private suspend fun runEmbedTokensTest(context: Context, log: (String) -> Unit) {
    log("1. Checking model…")
    val manifest = ModelManifest.load(context, ModelBundle.Gemma)
    val storage = ModelStorage(context, ModelBundle.Gemma)
    if (!storage.isInstalled(manifest)) {
        log("   Model not installed.")
        return
    }
    val modelFile = storage.fileFor(
        manifest.files.first { it.path == "onnx/embed_tokens_q4f16.onnx" }
    )
    log("   ok: ${modelFile.absolutePath}")
    log("   file size: ${"%.1f".format(modelFile.length() / 1_048_576.0)} MiB")

    log("")
    log("2. Loading embed_tokens session…")
    val (session, tLoad) = timed {
        withContext(Dispatchers.IO) { EmbedTokensSession(modelFile) }
    }
    log("   loaded in $tLoad ms")
    log("   inputs:  ${session.inputSummary}")
    log("   outputs: ${session.outputSummary}")

    try {
        log("")
        log("3. Embedding [2, 4215, 17038, 506, 2269] (bos + 'Transcribe the following')…")
        val testIds = intArrayOf(2, 4215, 17038, 506, 2269)
        val (res, tRun) = timed { session.embed(testIds) }
        log("   seq=${res.seqLen} in $tRun ms")
        log("   inputs_embeds.size = ${res.inputsEmbeds.size}")
        log("   per_layer_inputs.size = ${res.perLayerInputs.size}")
        val embedsMin = res.inputsEmbeds.minOrNull() ?: 0f
        val embedsMax = res.inputsEmbeds.maxOrNull() ?: 0f
        log("   embeds min/max = %.4f / %.4f".format(embedsMin, embedsMax))
        log("   embeds first 8 = ${res.inputsEmbeds.take(8).map { "%.4f".format(it) }}")
        val perLayerMin = res.perLayerInputs.minOrNull() ?: 0f
        val perLayerMax = res.perLayerInputs.maxOrNull() ?: 0f
        log("   per_layer min/max = %.4f / %.4f".format(perLayerMin, perLayerMax))
    } finally {
        session.close()
    }
}

private suspend fun runInspectDecoder(context: Context, log: (String) -> Unit) {
    log("1. Checking model…")
    val manifest = ModelManifest.load(context, ModelBundle.Gemma)
    val storage = ModelStorage(context, ModelBundle.Gemma)
    if (!storage.isInstalled(manifest)) {
        log("   Model not installed.")
        return
    }
    val modelFile = storage.fileFor(
        manifest.files.first { it.path == "onnx/decoder_model_merged_q4f16.onnx" }
    )
    log("   ok: ${modelFile.absolutePath}")
    log("   file size: ${"%.1f".format(modelFile.length() / 1_048_576.0)} MiB")

    log("")
    log("2. Loading decoder session (this is the ~1.4 GB merged decoder)…")
    val (session, tLoad) = timed {
        withContext(Dispatchers.IO) { DecoderSession(modelFile) }
    }
    log("   loaded in $tLoad ms")

    try {
        log("")
        log("3. Inputs (${session.inputSummary.size}):")
        for ((name, info) in session.inputSummary.entries.sortedBy { tensorSortKey(it.key) }) {
            log("   ${compactTensorLine(name, info)}")
        }
        log("")
        log("4. Outputs (${session.outputSummary.size}):")
        for ((name, info) in session.outputSummary.entries.sortedBy { tensorSortKey(it.key) }) {
            log("   ${compactTensorLine(name, info)}")
        }
    } finally {
        session.close()
    }
}

/**
 * Compacts `TensorInfo(javaType=FLOAT16,...,shape=[-1, 1, -1, 256],dimNames=[...])` into
 * `fp16 [-1,1,-1,256]` so all 35+ tensor lines fit on a phone screen.
 */
private fun compactTensorLine(name: String, info: String): String {
    val dtype = Regex("javaType=(\\w+)").find(info)?.groupValues?.get(1)?.lowercase() ?: "?"
    val shape = Regex("shape=\\[([^\\]]*)\\]").find(info)?.groupValues?.get(1)
        ?.replace(" ", "") ?: "?"
    val typeShort = when (dtype) {
        "float" -> "f32"
        "float16" -> "f16"
        "int64" -> "i64"
        "int32" -> "i32"
        "bool" -> "b"
        "uint8" -> "u8"
        else -> dtype
    }
    return "$name: $typeShort [$shape]"
}

/** Sort so past_key_values.0, 1, 2, ... come in numeric order (not 0, 1, 10, 11, ...). */
private fun tensorSortKey(name: String): String {
    val regex = Regex("(.*?)(\\d+)(.*)")
    val m = regex.find(name) ?: return name
    val (prefix, num, suffix) = m.destructured
    return "%s%05d%s".format(prefix, num.toInt(), suffix)
}

/**
 * Text-only sanity check for the decoder. Embeds a short prompt via embed_tokens,
 * closes embed_tokens, opens the decoder, prefills with the prompt, runs up to 8
 * greedy decode steps or stops on EOS, then detokenises the generated IDs.
 *
 * This exercises everything M4b.2 introduces — EmbedTokensSession's two outputs,
 * DecoderSession's KV cache, f16 logits + Fp16 converter — without the audio path.
 * If this produces a coherent continuation, we know the heavy lifting is correct
 * and can wire the audio path on top next.
 */
private suspend fun runDecoderTextTest(context: Context, log: (String) -> Unit) {
    val manifest = ModelManifest.load(context, ModelBundle.Gemma)
    val storage = ModelStorage(context, ModelBundle.Gemma)
    if (!storage.isInstalled(manifest)) {
        log("Model not installed.")
        return
    }

    val tokenizerFile = storage.fileFor(manifest.files.first { it.path == "tokenizer.json" })
    val embedFile = storage.fileFor(
        manifest.files.first { it.path == "onnx/embed_tokens_q4f16.onnx" }
    )
    val decoderFile = storage.fileFor(
        manifest.files.first { it.path == "onnx/decoder_model_merged_q4f16.onnx" }
    )

    log("1. Loading tokenizer…")
    val (tok, tTok) = timed {
        withContext(Dispatchers.IO) { GemmaTokenizer.load(tokenizerFile) }
    }
    log("   $tTok ms")

    val prompt = "<bos>The capital of France is"
    log("")
    log("2. Encoding prompt: $prompt")
    val ids = tok.encodeWithSpecials(prompt)
    log("   ids=${ids.toList()} (len=${ids.size})")

    log("")
    log("3. Loading embed_tokens…")
    val (embedSession, tLoadEmbed) = timed {
        withContext(Dispatchers.IO) { EmbedTokensSession(embedFile) }
    }
    log("   $tLoadEmbed ms")

    val embeds = try {
        val (r, t) = timed { embedSession.embed(ids) }
        log("   embed: seq=${r.seqLen} in $t ms")
        r
    } finally {
        embedSession.close()
        log("   embed_tokens session closed")
    }

    log("")
    log("4. Loading decoder…")
    val (decoder, tLoadDec) = timed {
        withContext(Dispatchers.IO) { DecoderSession(decoderFile) }
    }
    log("   $tLoadDec ms")

    try {
        log("")
        log("5. Prefill…")
        val (promptLogits, tPrefill) = timed { decoder.prefill(embeds) }
        log("   logits len=${promptLogits.size} in $tPrefill ms")
        val firstPredictedId = argmax(promptLogits)
        log("   first predicted id = $firstPredictedId ('${tok.decode(intArrayOf(firstPredictedId))}')")

        log("")
        log("6. Decoding up to 8 steps…")
        val generated = ArrayList<Int>()
        var nextId = firstPredictedId
        val stopAt = setOf(1, 106)
        var totalMs = 0L
        for (step in 0 until 8) {
            if (nextId in stopAt) {
                log("   step $step: EOS id=$nextId — stopping")
                break
            }
            generated.add(nextId)
            val (stepEmbeds, _) = timed {
                // re-open embed_tokens briefly for each token
                // to avoid holding two large sessions at once on low-RAM devices
                EmbedTokensSession(embedFile).use { s -> s.embed(intArrayOf(nextId)) }
            }
            val (stepLogits, tStep) = timed { decoder.decodeStep(stepEmbeds) }
            totalMs += tStep
            nextId = argmax(stepLogits)
            log("   step $step: id=${generated.last()} ('${tok.decode(intArrayOf(generated.last()))}') -> next=$nextId in $tStep ms")
        }
        log("")
        log("7. Generated ids: $generated")
        log("   text: '${tok.decode(generated.toIntArray())}'")
        log("   total decode time: $totalMs ms")
    } finally {
        decoder.close()
        log("   decoder session closed")
    }
}

private fun argmax(floats: FloatArray): Int {
    var bestIdx = 0
    var bestVal = Float.NEGATIVE_INFINITY
    for (i in floats.indices) {
        if (floats[i] > bestVal) { bestVal = floats[i]; bestIdx = i }
    }
    return bestIdx
}

/** Returns the top-k (id, logit) pairs, largest first. */
private fun topK(logits: FloatArray, k: Int): List<Pair<Int, Float>> {
    val best = ArrayList<Pair<Int, Float>>(k + 1)
    for (i in logits.indices) {
        val v = logits[i]
        if (best.size < k) {
            best.add(i to v)
            best.sortByDescending { it.second }
        } else if (v > best.last().second) {
            best[best.size - 1] = i to v
            best.sortByDescending { it.second }
        }
    }
    return best
}

private suspend fun recordPcm(durationSec: Int): ShortArray {
    val capture = AudioCapture(maxDurationSec = durationSec)
    return withContext(Dispatchers.IO) {
        capture.start(this)
        val terminal = capture.state.first {
            it is AudioCapture.State.Done || it is AudioCapture.State.Error
        }
        when (terminal) {
            is AudioCapture.State.Done -> terminal.pcm
            is AudioCapture.State.Error -> throw terminal.cause
            else -> error("unreachable")
        }
    }
}

private fun micGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

private inline fun <T> timed(block: () -> T): Pair<T, Long> {
    val t0 = SystemClock.elapsedRealtime()
    val r = block()
    return r to (SystemClock.elapsedRealtime() - t0)
}

private fun saveLog(context: Context, usage: String, lines: List<String>): java.io.File {
    val dir = java.io.File(context.getExternalFilesDir(null), "logs")
    dir.mkdirs()
    val file = java.io.File(dir, "log_$usage.txt")
    file.writeText(lines.joinToString("\n"))
    return file
}
