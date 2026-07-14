package com.example.CustomTts

import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.CustomTts.ui.SettingsScreen
import com.example.CustomTts.ui.theme.DummyTTSTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DummyTTSTheme {
                var showSettings by remember { mutableStateOf(false) }

                if (showSettings) {
                    SettingsScreen(
                        onNavigateBack = { showSettings = false }
                    )
                } else {
                    MainScreen(onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current

    var engineStatus by remember { mutableStateOf("Connecting to TTS engine…") }
    var engineReady by remember { mutableStateOf(false) }
    var testText by remember { mutableStateOf("Hello! This is a test of the custom TTS engine.") }
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }

    // Bind a TTS client directly to this app's engine so the pipeline can be
    // tested in-app, independent of the system default engine selection.
    DisposableEffect(Unit) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context, { result ->
            if (result == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        engineStatus = "Speaking…"
                    }

                    override fun onDone(utteranceId: String?) {
                        engineStatus = "Playback finished — engine is working!"
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        engineStatus = "Synthesis error — check backend settings and network"
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        engineStatus = "Synthesis error (code $errorCode) — check backend settings and network"
                    }
                })
                ttsRef.value = tts
                engineReady = true
                engineStatus = "Engine connected — ready to test"
            } else {
                engineStatus = "Engine failed to initialize (code $result)"
            }
        }, context.packageName)
        onDispose {
            tts?.stop()
            tts?.shutdown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.main_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(id = R.string.main_settings_action_description)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(id = R.string.main_screen_text_2),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(id = R.string.main_screen_text_3),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(24.dp))

            // --- In-app engine test ---
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(id = R.string.main_test_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = testText,
                        onValueChange = { testText = it },
                        label = { Text(stringResource(id = R.string.main_test_text_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            engineStatus = "Requesting synthesis…"
                            ttsRef.value?.speak(
                                testText,
                                TextToSpeech.QUEUE_FLUSH,
                                null,
                                "customtts-test"
                            )
                        },
                        enabled = engineReady && testText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(id = R.string.main_test_button))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        engineStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = {
                context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            }) {
                Text(stringResource(id = R.string.main_open_tts_settings))
            }
        }
    }
}
