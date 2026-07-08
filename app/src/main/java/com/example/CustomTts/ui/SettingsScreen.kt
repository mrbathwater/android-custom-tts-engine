package com.example.CustomTts.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import com.example.CustomTts.R
import com.example.CustomTts.data.ApiStyles
import com.example.CustomTts.data.PrefKeys
import com.example.CustomTts.data.settingsDataStore
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    var urlState by remember { mutableStateOf("") }
    var apiKeyState by remember { mutableStateOf("") }
    var modelState by remember { mutableStateOf("") }
    var voiceState by remember { mutableStateOf("") }
    var formatState by remember { mutableStateOf("") }
    var styleState by remember { mutableStateOf(ApiStyles.OPENAI) }
    var languageState by remember { mutableStateOf("") }
    var autoSaveState by remember { mutableStateOf(false) }
    var saveFolderState by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val supportedFormats = listOf("mp3", "wav", "opus", "ogg", "flac", "aac", "pcm")

    // Folder picker for auto-save destination
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.e("SettingsScreen", "Failed to persist folder permission", e)
            }
            saveFolderState = uri.toString()
            scope.launch {
                context.settingsDataStore.edit { it[PrefKeys.SAVE_FOLDER_URI] = uri.toString() }
            }
        }
    }

    LaunchedEffect(Unit) {
        isLoading = true
        context.settingsDataStore.data.firstOrNull()?.let { prefs ->
            urlState = prefs[PrefKeys.BACKEND_URL] ?: ""
            apiKeyState = prefs[PrefKeys.API_KEY] ?: ""
            modelState = prefs[PrefKeys.TTS_MODEL] ?: ""
            voiceState = prefs[PrefKeys.TTS_VOICE] ?: ""
            formatState = prefs[PrefKeys.RESPONSE_FORMAT] ?: "mp3"
            styleState = prefs[PrefKeys.API_STYLE] ?: ApiStyles.OPENAI
            languageState = prefs[PrefKeys.LANGUAGE] ?: ""
            autoSaveState = prefs[PrefKeys.AUTO_SAVE] ?: false
            saveFolderState = prefs[PrefKeys.SAVE_FOLDER_URI] ?: ""
        } ?: run {
            formatState = "mp3"
        }
        isLoading = false
    }

    data class Preset(
        val label: String,
        val url: String,
        val style: String,
        val model: String,
        val voice: String,
        val format: String,
        val language: String
    )
    val presets = listOf(
        Preset("OpenAI", "https://api.openai.com/v1/audio/speech", ApiStyles.OPENAI, "tts-1", "alloy", "mp3", ""),
        Preset("Speechify", "https://api.speechify.ai/v1/audio/speech", ApiStyles.SPEECHIFY, "simba-3.2", "harper_32", "mp3", ""),
        Preset("xAI", "https://api.x.ai/v1/tts", ApiStyles.XAI, "", "eve", "mp3", "en")
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(id = R.string.settings_back_description))
                    }
                }
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(id = R.string.settings_instruction),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))

                Text("Presets", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { preset ->
                        OutlinedButton(onClick = {
                            urlState = preset.url
                            styleState = preset.style
                            modelState = preset.model
                            voiceState = preset.voice
                            formatState = preset.format
                            languageState = preset.language
                        }) {
                            Text(preset.label)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // --- API request style ---
                var styleExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = styleExpanded,
                    onExpandedChange = { styleExpanded = !styleExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = ApiStyles.label(styleState),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(id = R.string.settings_label_api_style)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = styleExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = styleExpanded,
                        onDismissRequest = { styleExpanded = false }
                    ) {
                        ApiStyles.ALL.forEach { style ->
                            DropdownMenuItem(
                                text = { Text(ApiStyles.label(style)) },
                                onClick = {
                                    styleState = style
                                    styleExpanded = false
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = urlState,
                    onValueChange = { urlState = it },
                    label = { Text(stringResource(id = R.string.settings_label_url)) },
                    placeholder = { Text(stringResource(id = R.string.settings_placeholder_url)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = apiKeyState,
                    onValueChange = { apiKeyState = it },
                    label = { Text(stringResource(id = R.string.settings_label_api_key)) },
                    placeholder = { Text(stringResource(id = R.string.settings_placeholder_api_key)) },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = modelState,
                    onValueChange = { modelState = it },
                    label = { Text(stringResource(id = R.string.settings_label_model)) },
                    placeholder = { Text(stringResource(id = R.string.settings_placeholder_model)) },
                    supportingText = { Text(stringResource(id = R.string.settings_hint_model_optional)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = voiceState,
                    onValueChange = { voiceState = it },
                    label = { Text(stringResource(id = R.string.settings_label_voice)) },
                    placeholder = { Text(stringResource(id = R.string.settings_placeholder_voice)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = languageState,
                    onValueChange = { languageState = it },
                    label = { Text(stringResource(id = R.string.settings_label_language)) },
                    placeholder = { Text(stringResource(id = R.string.settings_placeholder_language)) },
                    supportingText = { Text(stringResource(id = R.string.settings_hint_language)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))

                // --- Response format dropdown ---
                var formatExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = formatExpanded,
                    onExpandedChange = { formatExpanded = !formatExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = formatState,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(id = R.string.settings_label_response_format)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = formatExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = formatExpanded,
                        onDismissRequest = { formatExpanded = false }
                    ) {
                        supportedFormats.forEach { selectionOption ->
                            DropdownMenuItem(
                                text = { Text(selectionOption) },
                                onClick = {
                                    formatState = selectionOption
                                    formatExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // --- Auto-save section ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(id = R.string.settings_label_auto_save),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            stringResource(id = R.string.settings_hint_auto_save),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoSaveState,
                        onCheckedChange = { autoSaveState = it }
                    )
                }
                if (autoSaveState) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(id = R.string.settings_button_pick_folder))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (saveFolderState.isNotBlank()) {
                            val decoded = Uri.parse(saveFolderState).lastPathSegment ?: saveFolderState
                            stringResource(id = R.string.settings_current_folder, decoded)
                        } else {
                            stringResource(id = R.string.settings_no_folder)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        scope.launch {
                            val validationErrorMsg = context.getString(R.string.settings_snackbar_validation_error)
                            val savedMsg = context.getString(R.string.settings_snackbar_saved)
                            val errorMsg = context.getString(R.string.settings_snackbar_save_error)

                            try {
                                val urlToSave = urlState.trim()
                                // Only the URL is required — model, voice, language and key
                                // are optional so arbitrary endpoints (e.g. xAI) work.
                                if (urlToSave.isBlank()) {
                                    snackbarHostState.showSnackbar(validationErrorMsg)
                                    return@launch
                                }

                                context.settingsDataStore.edit { settings ->
                                    settings[PrefKeys.BACKEND_URL] = urlToSave
                                    settings[PrefKeys.API_KEY] = apiKeyState
                                    settings[PrefKeys.TTS_MODEL] = modelState.trim()
                                    settings[PrefKeys.TTS_VOICE] = voiceState.trim()
                                    settings[PrefKeys.RESPONSE_FORMAT] = formatState
                                    settings[PrefKeys.API_STYLE] = styleState
                                    settings[PrefKeys.LANGUAGE] = languageState.trim()
                                    settings[PrefKeys.AUTO_SAVE] = autoSaveState
                                    settings[PrefKeys.SAVE_FOLDER_URI] = saveFolderState
                                }

                                Log.i("SettingsScreen", "Settings saved!")
                                snackbarHostState.showSnackbar(savedMsg)

                            } catch (e: Exception) {
                                Log.e("SettingsScreen", "Failed to save settings", e)
                                snackbarHostState.showSnackbar(errorMsg)
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(id = R.string.settings_button_save))
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
