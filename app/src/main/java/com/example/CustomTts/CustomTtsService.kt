package com.example.CustomTts

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.util.Base64
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.CustomTts.data.ApiStyles
import com.example.CustomTts.data.PrefKeys
import com.example.CustomTts.data.settingsDataStore
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class CustomTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "CustomTtsService"
        // JSON keys commonly used by APIs that wrap base64 audio in a JSON response
        private val JSON_AUDIO_KEYS = listOf("audio_data", "audio_content", "audioContent", "audio", "data", "b64_audio")
    }

    private data class WavHeaderInfo(
        val sampleRate: Int,
        val numChannels: Short,
        val bitsPerSample: Short,
        val audioFormatCode: Short,
        val androidEncoding: Int,
        val dataOffset: Int,
        val dataSize: Int
    )

    private fun parseWavHeader(wavBytes: ByteArray): WavHeaderInfo? {
        if (wavBytes.size < 44) {
            Log.e(TAG, "WAV data too short for header: ${wavBytes.size} bytes")
            return null
        }

        val buffer = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)

        try {
            val riffChunkId = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            if (riffChunkId != "RIFF") {
                Log.e(TAG, "Invalid WAV: Missing 'RIFF' chunk ID.")
                return null
            }

            buffer.position(8)
            val format = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            if (format != "WAVE") {
                Log.e(TAG, "Invalid WAV: Missing 'WAVE' format.")
                return null
            }

            buffer.position(12)
            val fmtChunkId = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            if (fmtChunkId != "fmt ") {
                Log.e(TAG, "Invalid WAV: Missing 'fmt ' sub-chunk ID.")
                return null
            }

            val fmtChunkSize = buffer.int
            val audioFormat = buffer.short
            val numChannels = buffer.short
            val sampleRate = buffer.int
            @Suppress("UNUSED_VARIABLE") val byteRate = buffer.int
            @Suppress("UNUSED_VARIABLE") val blockAlign = buffer.short
            val bitsPerSample = buffer.short

            Log.d(TAG, "WAV Header: Format=$audioFormat, Channels=$numChannels, Rate=$sampleRate, Bits=$bitsPerSample, FmtChunkSize=$fmtChunkSize")

            val androidEncoding = when {
                audioFormat == 1.toShort() && bitsPerSample == 16.toShort() -> AudioFormat.ENCODING_PCM_16BIT
                audioFormat == 3.toShort() && bitsPerSample == 32.toShort() -> AudioFormat.ENCODING_PCM_FLOAT
                else -> {
                    Log.e(TAG, "Unsupported WAV format: AudioFormat=$audioFormat, BitsPerSample=$bitsPerSample. Only 16-bit Int (1) or 32-bit Float (3) PCM supported.")
                    return null
                }
            }

            // Find the 'data' sub-chunk, skipping any other chunks
            buffer.position(20 + fmtChunkSize)
            var dataChunkId = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            while (dataChunkId != "data" && buffer.remaining() >= 8) {
                Log.d(TAG, "Skipping chunk '$dataChunkId'")
                val chunkSize = buffer.int
                if (buffer.remaining() < chunkSize + 4) break
                buffer.position(buffer.position() + chunkSize)
                if (buffer.remaining() < 4) break
                dataChunkId = ByteArray(4).apply { buffer.get(this) }.toString(Charsets.US_ASCII)
            }

            if (dataChunkId != "data") {
                Log.e(TAG, "Invalid WAV: Missing 'data' sub-chunk ID.")
                return null
            }

            val rawDataSize = buffer.int
            val dataOffset = buffer.position()
            // Some backends use 0xFFFFFFFF (reads as -1) for unknown/streaming size — fall back to actual bytes
            val dataSize = if (rawDataSize <= 0) wavBytes.size - dataOffset else rawDataSize

            if (wavBytes.size < dataOffset + dataSize) {
                Log.e(TAG, "WAV data shorter than expected by header. Expected >= ${dataOffset + dataSize}, got ${wavBytes.size}")
                return null
            }

            return WavHeaderInfo(
                sampleRate = sampleRate,
                numChannels = numChannels,
                bitsPerSample = bitsPerSample,
                audioFormatCode = audioFormat,
                androidEncoding = androidEncoding,
                dataOffset = dataOffset,
                dataSize = dataSize
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing WAV header", e)
            return null
        }
    }

    private lateinit var httpClient: HttpClient
    private lateinit var serviceScope: CoroutineScope

    // Currently running synthesis job, so onStop() can cancel it
    @Volatile
    private var currentJob: Job? = null

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        httpClient = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 120000
                connectTimeoutMillis = 15000
                socketTimeoutMillis = 120000
            }
        }
        Log.i(TAG, "Service Created, HttpClient and Scope initialized.")
    }

    override fun onDestroy() {
        Log.d(TAG, "Service Destroyed")
        if (::httpClient.isInitialized) {
            httpClient.close()
            Log.i(TAG, "HttpClient closed.")
        }
        if (::serviceScope.isInitialized) {
            serviceScope.cancel()
            Log.i(TAG, "Coroutine Scope cancelled.")
        }
        super.onDestroy()
    }

    //--------------------------------------------------------------------------
    // Abstract TTS methods
    //--------------------------------------------------------------------------

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        Log.d(TAG, "onIsLanguageAvailable: lang=$lang, country=$country, variant=$variant")
        // The backend decides what it supports — accept any language so the engine
        // can be used with arbitrary endpoints.
        return if (lang.isNullOrBlank()) TextToSpeech.LANG_NOT_SUPPORTED
        else TextToSpeech.LANG_COUNTRY_AVAILABLE
    }

    override fun onGetLanguage(): Array<String> {
        Log.d(TAG, "onGetLanguage called")
        return arrayOf("eng", "USA", "")
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        val result = onIsLanguageAvailable(lang, country, variant)
        Log.d(TAG, "onLoadLanguage for $lang-$country-$variant: Result=$result")
        return result
    }

    override fun onStop() {
        Log.d(TAG, "onStop called — cancelling current synthesis job")
        currentJob?.cancel()
        currentJob = null
    }

    //--------------------------------------------------------------------------
    // Synthesis
    //--------------------------------------------------------------------------

    override fun onSynthesizeText(request: SynthesisRequest?, callback: SynthesisCallback?) {
        if (request == null || callback == null) {
            Log.e(TAG, "onSynthesizeText: Request or Callback is null")
            return
        }

        val text = request.charSequenceText?.toString() ?: ""
        val androidRate = request.speechRate.toFloat().coerceIn(20f, 300f)
        val speed = androidRate / 100.0f

        Log.d(TAG, "Synthesize request received: text='${text.take(50)}...', rate=$androidRate")

        if (text.isBlank()) {
            Log.w(TAG, "Text to synthesize is blank.")
            try {
                callback.start(16000, AudioFormat.ENCODING_PCM_16BIT, 1)
                callback.done()
            } catch (e: Exception) { Log.e(TAG, "Error completing callback for blank text", e) }
            return
        }

        val job = serviceScope.launch {
            try {
                // --- Read settings ---
                val currentSettings = applicationContext.settingsDataStore.data.first()
                val backendUrl = currentSettings[PrefKeys.BACKEND_URL] ?: ""
                val apiKey = currentSettings[PrefKeys.API_KEY] ?: ""
                val apiModel = currentSettings[PrefKeys.TTS_MODEL] ?: ""
                val apiVoice = currentSettings[PrefKeys.TTS_VOICE] ?: ""
                val requestedFormat = currentSettings[PrefKeys.RESPONSE_FORMAT] ?: "mp3"
                val apiStyle = currentSettings[PrefKeys.API_STYLE] ?: ApiStyles.OPENAI
                val language = currentSettings[PrefKeys.LANGUAGE] ?: ""
                val autoSave = currentSettings[PrefKeys.AUTO_SAVE] ?: false
                val saveFolderUri = currentSettings[PrefKeys.SAVE_FOLDER_URI] ?: ""

                if (backendUrl.isBlank()) {
                    Log.e(TAG, "Backend URL is missing in settings.")
                    callback.error(TextToSpeech.ERROR_SERVICE)
                    return@launch
                }
                Log.d(TAG, "Settings: URL=$backendUrl, Style=$apiStyle, Model='$apiModel', Voice='$apiVoice', Format=$requestedFormat, Lang='$language', AutoSave=$autoSave")

                // --- Network request ---
                val payload = buildTtsRequestBody(
                    style = apiStyle,
                    text = text,
                    model = apiModel,
                    voice = apiVoice,
                    format = requestedFormat,
                    language = language,
                    speed = speed
                )
                val response: HttpResponse = httpClient.post(backendUrl) {
                    if (apiKey.isNotBlank()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Json)
                    setBody(payload)
                }
                Log.d(TAG, "Backend response status: ${response.status}")

                if (!response.status.isSuccess()) {
                    val errorBody = try { response.bodyAsText() } catch (e: Exception) { "Could not read error body: ${e.message}" }
                    Log.e(TAG, "Backend request failed: Status=${response.status}, Body='${errorBody.take(500)}'")
                    callback.error(TextToSpeech.ERROR_NETWORK)
                    return@launch
                }

                var audioBytes = response.readBytes()
                Log.d(TAG, "Received ${audioBytes.size} bytes (Content-Type: ${response.contentType()})")

                if (audioBytes.isEmpty()) {
                    Log.e(TAG, "Backend returned empty body.")
                    callback.error(TextToSpeech.ERROR_SERVICE)
                    return@launch
                }

                // Some APIs (e.g. Speechify) wrap base64 audio inside a JSON response
                if (looksLikeJson(response.contentType(), audioBytes)) {
                    val extracted = extractAudioFromJson(audioBytes)
                    if (extracted == null) {
                        Log.e(TAG, "JSON response did not contain a recognizable audio field.")
                        callback.error(TextToSpeech.ERROR_SERVICE)
                        return@launch
                    }
                    audioBytes = extracted
                    Log.d(TAG, "Extracted ${audioBytes.size} audio bytes from JSON response.")
                }

                // Determine actual container from magic bytes; fall back to requested format
                val detectedFormat = sniffAudioFormat(audioBytes) ?: requestedFormat.lowercase()
                Log.d(TAG, "Detected audio format: $detectedFormat (requested: $requestedFormat)")

                // --- Auto-save the generation before playback ---
                if (autoSave) {
                    try {
                        saveGeneration(audioBytes, detectedFormat, saveFolderUri)
                    } catch (e: Exception) {
                        Log.e(TAG, "Auto-save failed (continuing with playback)", e)
                    }
                }

                // --- Decode & stream to the TTS callback ---
                val ok = when (detectedFormat) {
                    "wav" -> streamWav(audioBytes, callback)
                    "pcm" -> streamRawPcm(audioBytes, callback)
                    else -> decodeCompressedAndStream(audioBytes, callback) // mp3, ogg/opus, aac, flac, ...
                }
                if (ok) {
                    callback.done()
                    Log.i(TAG, "Synthesis completed ($detectedFormat).")
                } else {
                    Log.e(TAG, "Audio processing failed for format '$detectedFormat'.")
                    callback.error(TextToSpeech.ERROR_OUTPUT)
                }

            } catch (e: CancellationException) {
                Log.i(TAG, "Synthesis cancelled.")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Exception during network request or processing", e)
                val errorCode = when (e) {
                    is io.ktor.client.plugins.ClientRequestException -> TextToSpeech.ERROR_NETWORK
                    is io.ktor.client.plugins.ServerResponseException -> TextToSpeech.ERROR_NETWORK
                    is io.ktor.client.plugins.RedirectResponseException -> TextToSpeech.ERROR_NETWORK
                    is java.net.UnknownHostException -> TextToSpeech.ERROR_NETWORK
                    is java.net.ConnectException -> TextToSpeech.ERROR_NETWORK_TIMEOUT
                    is java.net.SocketTimeoutException -> TextToSpeech.ERROR_NETWORK_TIMEOUT
                    is java.io.IOException -> TextToSpeech.ERROR_NETWORK
                    is kotlinx.serialization.SerializationException -> TextToSpeech.ERROR_INVALID_REQUEST
                    else -> TextToSpeech.ERROR_SERVICE
                }
                try {
                    callback.error(errorCode)
                } catch (callbackError: Exception) {
                    Log.e(TAG, "Error reporting error code $errorCode to callback", callbackError)
                }
            }
        }
        currentJob = job

        // onSynthesizeText must block until synthesis finishes — the framework
        // calls it on a dedicated thread and considers the request done on return.
        runBlocking { job.join() }
    }

    //--------------------------------------------------------------------------
    // Response helpers
    //--------------------------------------------------------------------------

    private fun looksLikeJson(contentType: ContentType?, bytes: ByteArray): Boolean {
        if (contentType?.match(ContentType.Application.Json) == true) return true
        val firstChar = bytes.firstOrNull { it != ' '.code.toByte() && it != '\n'.code.toByte() && it != '\r'.code.toByte() && it != '\t'.code.toByte() }
        return firstChar == '{'.code.toByte()
    }

    /** Finds a base64 audio string in a JSON response (searching nested objects) and decodes it. */
    private fun extractAudioFromJson(bytes: ByteArray): ByteArray? {
        return try {
            val root = jsonParser.parseToJsonElement(bytes.toString(Charsets.UTF_8))
            val base64 = findAudioString(root) ?: return null
            // Strip optional data-URI prefix ("data:audio/mp3;base64,....")
            val cleaned = base64.substringAfter("base64,", base64).trim()
            Base64.decode(cleaned, Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract audio from JSON response", e)
            null
        }
    }

    private fun findAudioString(element: JsonElement): String? {
        if (element !is JsonObject) return null
        for (key in JSON_AUDIO_KEYS) {
            val value = element[key]
            if (value is JsonPrimitive && value.isString && value.content.length > 100) {
                return value.content
            }
        }
        for ((_, value) in element) {
            if (value is JsonObject) {
                findAudioString(value)?.let { return it }
            }
        }
        return null
    }

    /** Detects the audio container from magic bytes. Returns null if unknown. */
    private fun sniffAudioFormat(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        fun ascii(offset: Int, len: Int) = bytes.copyOfRange(offset, offset + len).toString(Charsets.US_ASCII)
        return when {
            ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE" -> "wav"
            ascii(0, 3) == "ID3" -> "mp3"
            bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            ascii(0, 4) == "OggS" -> "ogg"
            ascii(0, 4) == "fLaC" -> "flac"
            ascii(4, 4) == "ftyp" -> "m4a"
            else -> null
        }
    }

    //--------------------------------------------------------------------------
    // Audio streaming
    //--------------------------------------------------------------------------

    private fun streamChunks(callback: SynthesisCallback, bytes: ByteArray, offset: Int, length: Int): Boolean {
        val maxChunk = callback.maxBufferSize.let { if (it > 0) it else 8192 }
        var streamed = 0
        while (streamed < length) {
            val chunkSize = minOf(length - streamed, maxChunk)
            if (callback.audioAvailable(bytes, offset + streamed, chunkSize) == TextToSpeech.ERROR) {
                Log.e(TAG, "audioAvailable failed")
                return false
            }
            streamed += chunkSize
        }
        return true
    }

    private fun streamRawPcm(audioBytes: ByteArray, callback: SynthesisCallback): Boolean {
        Log.d(TAG, "Processing as raw PCM (assuming 24kHz, 16-bit, mono)...")
        val startResult = callback.start(24000, AudioFormat.ENCODING_PCM_16BIT, 1)
        if (startResult == TextToSpeech.ERROR) {
            Log.e(TAG, "PCM start failed")
            return false
        }
        return streamChunks(callback, audioBytes, 0, audioBytes.size)
    }

    private fun streamWav(audioBytes: ByteArray, callback: SynthesisCallback): Boolean {
        Log.d(TAG, "Processing as WAV...")
        val wavInfo = parseWavHeader(audioBytes)
        if (wavInfo == null) {
            Log.w(TAG, "Failed to parse WAV header — trying MediaCodec decode as fallback.")
            return decodeCompressedAndStream(audioBytes, callback)
        }
        val startResult = callback.start(wavInfo.sampleRate, wavInfo.androidEncoding, wavInfo.numChannels.toInt())
        if (startResult == TextToSpeech.ERROR) {
            Log.e(TAG, "WAV start failed!")
            return false
        }
        val available = minOf(wavInfo.dataSize, audioBytes.size - wavInfo.dataOffset)
        return streamChunks(callback, audioBytes, wavInfo.dataOffset, available)
    }

    /**
     * Decodes compressed audio (MP3, OGG/Opus, AAC, FLAC, ...) to 16-bit PCM using
     * the platform MediaCodec and streams it to the TTS callback.
     */
    private fun decodeCompressedAndStream(audioBytes: ByteArray, callback: SynthesisCallback): Boolean {
        val tempFile = File.createTempFile("tts_audio", ".bin", cacheDir)
        var codec: MediaCodec? = null
        val extractor = MediaExtractor()
        try {
            tempFile.writeBytes(audioBytes)
            extractor.setDataSource(tempFile.absolutePath)

            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = f
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                Log.e(TAG, "No audio track found in response data.")
                return false
            }
            extractor.selectTrack(trackIndex)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            Log.d(TAG, "Decoding '$mime' via MediaCodec...")

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            var callbackStarted = false
            var inputDone = false
            var outputDone = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex >= 0 -> {
                        if (!callbackStarted) {
                            val outFormat = codec.outputFormat
                            val sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            Log.d(TAG, "Decoded PCM: rate=$sampleRate, channels=$channels")
                            if (callback.start(sampleRate, AudioFormat.ENCODING_PCM_16BIT, channels) == TextToSpeech.ERROR) {
                                Log.e(TAG, "callback.start failed for decoded audio")
                                return false
                            }
                            callbackStarted = true
                        }
                        if (bufferInfo.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            val pcmChunk = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.get(pcmChunk)
                            if (!streamChunks(callback, pcmChunk, 0, pcmChunk.size)) {
                                return false
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Decoder output format changed: ${codec.outputFormat}")
                    }
                }
            }

            if (!callbackStarted) {
                Log.e(TAG, "Decoder produced no audio output.")
                return false
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "MediaCodec decoding failed", e)
            return false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            tempFile.delete()
        }
    }

    //--------------------------------------------------------------------------
    // Auto-save
    //--------------------------------------------------------------------------

    private fun saveGeneration(audioBytes: ByteArray, format: String, folderUriString: String) {
        val extension = when (format) {
            "ogg" -> "ogg"
            "opus" -> "ogg"
            "pcm" -> "pcm"
            else -> format
        }
        val fileName = "tts_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".$extension"
        val mimeType = when (extension) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "m4a", "aac" -> "audio/mp4"
            else -> "application/octet-stream"
        }

        if (folderUriString.isNotBlank()) {
            val treeUri = Uri.parse(folderUriString)
            val dir = DocumentFile.fromTreeUri(applicationContext, treeUri)
            if (dir != null && dir.canWrite()) {
                val file = dir.createFile(mimeType, fileName)
                if (file != null) {
                    contentResolver.openOutputStream(file.uri)?.use { it.write(audioBytes) }
                    Log.i(TAG, "Saved generation to ${file.uri} (${audioBytes.size} bytes)")
                    return
                }
            }
            Log.w(TAG, "Could not write to chosen folder ($folderUriString), falling back to app storage.")
        }

        // Fallback: app-specific external storage (visible via file managers under
        // Android/data/<package>/files/generations)
        val dir = File(getExternalFilesDir(null), "generations").apply { mkdirs() }
        val outFile = File(dir, fileName)
        outFile.writeBytes(audioBytes)
        Log.i(TAG, "Saved generation to ${outFile.absolutePath} (${audioBytes.size} bytes)")
    }
}
