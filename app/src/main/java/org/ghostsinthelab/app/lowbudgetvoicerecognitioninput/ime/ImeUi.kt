package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.ime

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.audio.AudioCapture

private const val SAMPLE_RATE = 16_000

@Composable
fun ImeUi() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasMic by remember { mutableStateOf(checkMic(context)) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) hasMic = checkMic(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        if (!hasMic) {
            MicPermissionPrompt(onOpenSettings = { openAppDetails(context) })
        } else {
            PushToTalkPanel()
        }
    }
}

@Composable
private fun MicPermissionPrompt(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            "Microphone access is needed to transcribe.",
            style = MaterialTheme.typography.bodyLarge,
        )
        FilledTonalButton(onClick = onOpenSettings) { Text("Open app settings") }
    }
}

@Composable
private fun PushToTalkPanel() {
    val capture = remember { AudioCapture() }
    val state by capture.state.collectAsStateWithLifecycle()
    val amplitude by capture.amplitude.collectAsStateWithLifecycle()
    val samples by capture.samplesRead.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val recording = state is AudioCapture.State.Recording
    val seconds = samples.toDouble() / SAMPLE_RATE

    val statusText = when (val s = state) {
        AudioCapture.State.Idle -> "Hold the button to talk"
        AudioCapture.State.Recording -> "Listening… %.1fs".format(seconds)
        is AudioCapture.State.Done -> {
            val durSec = s.pcm.size.toDouble() / SAMPLE_RATE
            val tail = if (s.truncated) " (capped at 30 s)" else ""
            "Captured ${s.pcm.size} samples (%.2f s)$tail".format(durSec)
        }
        is AudioCapture.State.Error -> "Mic error: ${s.cause.message}"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(statusText, style = MaterialTheme.typography.bodyMedium)

        LinearProgressIndicator(
            progress = { (amplitude * 5f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(
                    if (recording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            capture.start(scope)
                            try {
                                awaitRelease()
                            } finally {
                                capture.stop()
                            }
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (recording) "REC" else "HOLD",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }

        Spacer(Modifier.weight(1f))
    }
}

private fun checkMic(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

private fun openAppDetails(context: Context) {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
