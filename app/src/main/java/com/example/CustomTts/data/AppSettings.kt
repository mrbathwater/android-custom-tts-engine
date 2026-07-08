package com.example.CustomTts.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

// DataStore instance
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "tts_settings")

object PrefKeys {
    // Backend endpoint URL
    val BACKEND_URL = stringPreferencesKey("backend_url")

    // API key (optional)
    val API_KEY = stringPreferencesKey("api_key")

    // Model name (optional — some APIs like xAI don't use one)
    val TTS_MODEL = stringPreferencesKey("tts_model")

    // Voice name / voice_id
    val TTS_VOICE = stringPreferencesKey("tts_voice")

    // Requested response/audio format (mp3, wav, opus, pcm, ...)
    val RESPONSE_FORMAT = stringPreferencesKey("response_format")

    // Request body style: "openai", "speechify" or "xai"
    val API_STYLE = stringPreferencesKey("api_style")

    // Language code sent with xAI-style requests (optional)
    val LANGUAGE = stringPreferencesKey("language")

    // Automatically save each generation as an audio file
    val AUTO_SAVE = booleanPreferencesKey("auto_save")

    // SAF tree URI of the folder generations are saved to
    val SAVE_FOLDER_URI = stringPreferencesKey("save_folder_uri")
}

object ApiStyles {
    const val OPENAI = "openai"
    const val SPEECHIFY = "speechify"
    const val XAI = "xai"

    val ALL = listOf(OPENAI, SPEECHIFY, XAI)

    fun label(style: String): String = when (style) {
        OPENAI -> "OpenAI (input/voice/model)"
        SPEECHIFY -> "Speechify (input/voice_id/audio_format)"
        XAI -> "xAI (text/voice_id/language)"
        else -> style
    }
}
