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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    enabled = !running,
                    onClick = {
                        if (!hasMic) {
                            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@FilledTonalButton
                        }
                        scope.launch {
                            running = true
                            log.clear()
                            try {
                                runPipeline(context) { log.add(it) }
                            } catch (t: Throwable) {
                                log.add("ERROR: ${t.javaClass.simpleName}: ${t.message}")
                            } finally {
                                running = false
                            }
                        }
                    },
                ) {
                    Text(
                        when {
                            !hasMic -> "Grant mic"
                            running -> "Running…"
                            else -> "Run pipeline (5 s record)"
                        }
                    )
                }
                OutlinedButton(
                    enabled = !running,
                    onClick = {
                        scope.launch {
                            running = true
                            log.clear()
                            try {
                                runTokenizerTest(context) { log.add(it) }
                            } catch (t: Throwable) {
                                log.add("ERROR: ${t.javaClass.simpleName}: ${t.message}")
                            } finally {
                                running = false
                            }
                        }
                    },
                ) { Text("Test tokenizer") }
                OutlinedButton(
                    enabled = !running,
                    onClick = { log.clear() },
                ) { Text("Clear") }
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

private suspend fun runPipeline(context: Context, log: (String) -> Unit) {
    log("1. Checking model…")
    val manifest = ModelManifest.load(context)
    val storage = ModelStorage(context)
    if (!storage.isInstalled(manifest)) {
        log("   Model not installed. Download it from the settings screen first.")
        return
    }
    val modelFile = storage.fileFor(
        manifest.files.first { it.path == "onnx/audio_encoder_q4f16.onnx" }
    )
    log("   ok: ${modelFile.absolutePath}")

    log("")
    log("2. Recording 5 s…")
    val pcm = recordPcm(durationSec = 5)
    log("   got ${pcm.size} samples (${"%.2f".format(pcm.size / 16_000.0)} s)")

    log("")
    log("3. Preprocessing…")
    val (mel, tMel) = timed { AudioPreprocessor().process(pcm) }
    log("   ${mel.numFrames} frames × ${mel.numBins} bins in $tMel ms")
    log("   mel[0][0..3] = ${mel.data.take(4).map { "%.3f".format(it) }}")

    log("")
    log("4. Loading audio encoder…")
    val (session, tLoad) = timed { AudioEncoderSession(modelFile) }
    log("   loaded in $tLoad ms")
    log("   inputs:  ${session.inputSummary}")
    log("   outputs: ${session.outputSummary}")

    try {
        log("")
        log("5. Running encoder…")
        val (res, tRun) = timed { session.encode(mel) }
        log("   output '${res.outputName}' shape ${res.shape.toList()} in $tRun ms")
        val min = res.data.minOrNull() ?: 0f
        val max = res.data.maxOrNull() ?: 0f
        log("   min/max = %.4f / %.4f".format(min, max))
        log("   first 8 = ${res.data.take(8).map { "%.4f".format(it) }}")
    } finally {
        session.close()
    }
}

private suspend fun runTokenizerTest(context: Context, log: (String) -> Unit) {
    log("1. Checking model…")
    val manifest = ModelManifest.load(context)
    val storage = ModelStorage(context)
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
