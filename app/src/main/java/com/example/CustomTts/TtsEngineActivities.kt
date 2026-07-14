package com.example.CustomTts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech

/**
 * Android's TTS settings fire ACTION_CHECK_TTS_DATA at the engine before using it.
 * Without this activity the "Listen to an example" flow silently aborts.
 */
class CheckVoiceDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().apply {
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES,
                arrayListOf("eng-USA")
            )
            putStringArrayListExtra(
                TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES,
                arrayListOf()
            )
        }
        setResult(TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, result)
        finish()
    }
}

/**
 * Provides the sample text for the system TTS settings "Listen to an example" button.
 */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = Intent().putExtra(
            TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
            "This is an example of speech synthesis from the custom TTS engine."
        )
        setResult(TextToSpeech.LANG_AVAILABLE, result)
        finish()
    }
}
