package org.ghostsinthelab.app.lowbudgetvoicerecognitioninput

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.asr.AsrDebugActivity
import org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.model.ModelDownloadController
import org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.model.Preflight
import org.ghostsinthelab.app.lowbudgetvoicerecognitioninput.ui.theme.LowBudgetVoiceRecognitionInputTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LowBudgetVoiceRecognitionInputTheme {
                SettingsScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    var imeEnabled by remember { mutableStateOf(isImeEnabled(context)) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) imeEnabled = isImeEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusBanner(enabled = imeEnabled)
            StepCard(
                title = stringResource(R.string.settings_step_enable_title),
                body = stringResource(R.string.settings_step_enable_body),
                action = stringResource(R.string.settings_step_enable_action),
                onAction = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                },
            )
            StepCard(
                title = stringResource(R.string.settings_step_pick_title),
                body = stringResource(R.string.settings_step_pick_body),
                action = stringResource(R.string.settings_step_pick_action),
                onAction = {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                            as InputMethodManager
                    imm.showInputMethodPicker()
                },
            )
            ModelDownloadCard()
            DebugCard()
        }
    }
}

@Composable
private fun DebugCard() {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Debug",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Standalone pipeline test — records 5 s, computes log-mel, " +
                    "runs the Gemma audio encoder, prints output shape and timings.",
                style = MaterialTheme.typography.bodySmall,
            )
            FilledTonalButton(
                onClick = {
                    context.startActivity(Intent(context, AsrDebugActivity::class.java))
                },
                modifier = Modifier.align(Alignment.End),
            ) { Text("Open ASR debug") }
        }
    }
}

@Composable
private fun StatusBanner(enabled: Boolean) {
    val color = if (enabled) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.errorContainer
    val text = stringResource(
        if (enabled) R.string.settings_status_enabled else R.string.settings_status_disabled
    )
    Surface(
        color = color,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(text, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun StepCard(
    title: String,
    body: String,
    action: String?,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            if (action != null) {
                Spacer(Modifier.height(4.dp))
                FilledTonalButton(
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.End),
                ) { Text(action) }
            }
        }
    }
}

@Composable
private fun ModelDownloadCard() {
    val context = LocalContext.current
    val controller = remember { ModelDownloadController(context.applicationContext) }
    val state by controller.observe()
        .collectAsStateWithLifecycle(initialValue = ModelDownloadController.State.NotStarted)
    val preflight = remember(state) {
        val raw = Preflight.check(context, controller.manifest, controller.storage())
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable && raw is Preflight.Result.Block) {
            Preflight.Result.Warn("[debug override] ${raw.message}")
        } else raw
    }
    val totalRam = remember { Preflight.totalRamBytes(context) }
    val freeDisk = remember(state) { controller.storage().freeBytes() }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notification is best-effort; worker still runs without it */ }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "3. Download voice model",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Gemma 4 E2B (q4f16) — about ${formatBytes(controller.manifest.totalBytes)} " +
                    "downloaded once on Wi-Fi recommended.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Device: ${formatBytes(totalRam)} RAM • ${formatBytes(freeDisk)} free",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (val p = preflight) {
                is Preflight.Result.Block -> Banner(p.message, MaterialTheme.colorScheme.errorContainer)
                is Preflight.Result.Warn -> Banner(p.message, MaterialTheme.colorScheme.tertiaryContainer)
                Preflight.Result.Ok -> Unit
            }

            when (val s = state) {
                ModelDownloadController.State.NotStarted -> {
                    FilledTonalButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            controller.enqueue()
                        },
                        enabled = preflight !is Preflight.Result.Block,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Download model") }
                }

                is ModelDownloadController.State.Running -> {
                    val progress = if (s.total > 0) s.bytes.toFloat() / s.total else 0f
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatBytes(s.bytes)} / ${formatBytes(s.total)} • " +
                            "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    s.currentFile?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = { controller.cancel() },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Cancel") }
                }

                ModelDownloadController.State.Installed -> {
                    Banner(
                        "Model installed (" +
                            "${formatBytes(controller.storage().installedBytes(controller.manifest))}" +
                            " on disk)",
                        MaterialTheme.colorScheme.primaryContainer,
                    )
                    OutlinedButton(
                        onClick = { controller.deleteInstalled() },
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Delete model") }
                }

                is ModelDownloadController.State.Error -> {
                    Banner(s.message, MaterialTheme.colorScheme.errorContainer)
                    FilledTonalButton(
                        onClick = { controller.enqueue() },
                        enabled = preflight !is Preflight.Result.Block,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Retry") }
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Surface(
        color = color,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private fun isImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val pkg = context.packageName
    return imm.enabledInputMethodList.any { it.packageName == pkg }
}
