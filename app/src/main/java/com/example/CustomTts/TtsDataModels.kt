package com.example.CustomTts

import com.example.CustomTts.data.ApiStyles
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the JSON request body for the configured API style.
 * Blank optional fields (model, format, language, ...) are omitted entirely so the
 * request works with APIs that reject unknown/empty fields (e.g. xAI has no model).
 */
fun buildTtsRequestBody(
    style: String,
    text: String,
    model: String,
    voice: String,
    format: String,
    language: String,
    speed: Float
): JsonObject = buildJsonObject {
    when (style) {
        ApiStyles.SPEECHIFY -> {
            // https://docs.speechify.ai — { input, voice_id, audio_format, model }
            put("input", text)
            if (voice.isNotBlank()) put("voice_id", voice)
            if (format.isNotBlank()) put("audio_format", format)
            if (model.isNotBlank()) put("model", model)
        }
        ApiStyles.XAI -> {
            // https://api.x.ai/v1/tts — { text, voice_id, language }
            put("text", text)
            if (voice.isNotBlank()) put("voice_id", voice)
            if (language.isNotBlank()) put("language", language)
            if (model.isNotBlank()) put("model", model)
        }
        else -> {
            // OpenAI-compatible — { model, input, voice, response_format, speed }
            put("input", text)
            if (model.isNotBlank()) put("model", model)
            if (voice.isNotBlank()) put("voice", voice)
            if (format.isNotBlank()) put("response_format", format)
            put("speed", speed)
        }
    }
}
