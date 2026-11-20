package com.example.gemini.data.remote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.shriram.cinecode.data.remote.CloudBridgeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Live connection state for Myraa (मायरा) Google Gemini Multimodal Live Voice Server.
 */
enum class LiveServerStatus(val displayLabel: String) {
    OFFLINE("Offline"),
    CONNECTING("Connecting…"),
    CONNECTED("Connected"),
    LISTENING("Listening"),
    SPEAKING("Speaking"),
    RECONNECTING("Reconnecting…")
}

/**
 * One live conversation card displayed in MyraaAssistantView.
 */
data class MyraaTranscriptItem(
    val id: String = UUID.randomUUID().toString(),
    val isUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Real-time Google Live Server (Gemini Multimodal Live API BidiGenerateContent WebSocket)
 * client for मायरा (Myraa) Assistant, optimized for 4GB RAM Android devices with:
 * - Lightweight 16kHz PCM16 mic capture & 24kHz PCM16 playback
 * - Instant Barge-in / Interrupt support
 * - Bounded auto-reconnect on network drop
 * - Safe REST fallback when WebSocket live audio is unavailable
 */
class GeminiLiveVoiceClient(private val context: Context) {

    companion object {
        private const val TAG = "MyraaLiveVoice"
        private const val INPUT_SAMPLE_RATE = 16000
        private const val OUTPUT_SAMPLE_RATE = 24000
        private const val MAX_TRANSCRIPTS = 40
        private const val MAX_LOGS = 60
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val BARGE_IN_RMS_THRESHOLD = 0.24f
        private const val BARGE_IN_FRAMES_REQUIRED = 3

        private val LIVE_MODELS = listOf(
            "models/gemini-2.0-flash-exp",
            "models/gemini-2.5-flash-native-audio-preview-09-2025"
        )

        private val REST_FALLBACK_MODELS = listOf(
            "gemini-2.5-flash",
            "gemini-2.0-flash"
        )

        private const val MYRAA_SYSTEM_INSTRUCTION =
            "तुम मायरा (Myraa) हो — CineCode-AI की स्वाभाविक, तेज़ और मददगार हिंदी वॉइस असिस्टेंट। " +
                "हमेशा साफ़, प्राकृतिक और संक्षिप्त हिंदी (या यूज़र की भाषा के अनुसार सरल Hinglish) में उत्तर दो। " +
                "कोडिंग, Android डेवलपमेंट, प्रोजेक्ट स्टेटस और रोज़मर्रा के कामों में सटीक मदद करो। " +
                "आवाज़ में बोलते समय लंबे मार्कडाउन सिंबल से बचो और स्पष्ट रूप से बात करो।"
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val restClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val _isLiveActive = MutableStateFlow(false)
    val isLiveActive: StateFlow<Boolean> = _isLiveActive.asStateFlow()

    private val _liveStatus = MutableStateFlow(LiveServerStatus.OFFLINE)
    val liveStatus: StateFlow<LiveServerStatus> = _liveStatus.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _transcripts = MutableStateFlow<List<MyraaTranscriptItem>>(emptyList())
    val transcripts: StateFlow<List<MyraaTranscriptItem>> = _transcripts.asStateFlow()

    private val _liveSessionLogs = MutableStateFlow<List<String>>(emptyList())
    val liveSessionLogs: StateFlow<List<String>> = _liveSessionLogs.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isSpeakerEnabled = MutableStateFlow(true)
    val isSpeakerEnabled: StateFlow<Boolean> = _isSpeakerEnabled.asStateFlow()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var micJob: Job? = null
    private var playbackJob: Job? = null
    private var reconnectJob: Job? = null

    private val playbackQueue = Channel<ByteArray>(capacity = 32)
    private val setupComplete = AtomicBoolean(false)
    private val intentionalStop = AtomicBoolean(false)
    private var reconnectAttempt = 0
    private var activeModelIndex = 0
    private var currentAssistantBubbleId: String? = null
    private var currentUserBubbleId: String? = null

    fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasApiKey(): Boolean = getStoredApiKey().isNotBlank()

    private fun getStoredApiKey(): String {
        val prefs = appContext.getSharedPreferences("cinecode_cloud_ai", Context.MODE_PRIVATE)
        val direct = prefs.getString("google_api_key", "")?.trim().orEmpty()
        if (direct.isNotBlank()) return direct
        return CloudBridgeStore.apiKeyFlow.value.trim()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun clearTranscripts() {
        _transcripts.value = emptyList()
        currentAssistantBubbleId = null
        currentUserBubbleId = null
    }

    fun toggleMicMute() {
        val muted = !_isMicMuted.value
        _isMicMuted.value = muted
        if (_isLiveActive.value && _liveStatus.value != LiveServerStatus.SPEAKING) {
            _liveStatus.value = if (muted) LiveServerStatus.CONNECTED else LiveServerStatus.LISTENING
        }
    }

    fun toggleSpeakerEnabled() {
        val next = !_isSpeakerEnabled.value
        _isSpeakerEnabled.value = next
        if (!next) {
            interruptPlayback()
        }
    }

    /**
     * Starts a real-time bidirectional Google Live Server audio session.
     * Requires RECORD_AUDIO permission and Google AI Studio API Key.
     */
    fun startRealtimeSession() {
        val apiKey = getStoredApiKey()
        if (apiKey.isBlank()) {
            _errorMessage.value = "Google AI Studio API Key सेट नहीं है। कृपया Settings में API Key दर्ज करें।"
            _liveStatus.value = LiveServerStatus.OFFLINE
            _isLiveActive.value = false
            return
        }
        if (!hasMicPermission()) {
            _errorMessage.value = "मायरा के लिए माइक (Microphone) अनुमति आवश्यक है।"
            _liveStatus.value = LiveServerStatus.OFFLINE
            _isLiveActive.value = false
            return
        }

        intentionalStop.set(false)
        reconnectAttempt = 0
        activeModelIndex = 0
        _errorMessage.value = null
        _isMicMuted.value = false
        _isLiveActive.value = true
        connectWebSocket(apiKey)
    }

    private fun connectWebSocket(apiKey: String) {
        cleanupTransport(keepSessionFlag = true)
        setupComplete.set(false)
        _liveStatus.value = if (reconnectAttempt > 0) LiveServerStatus.RECONNECTING else LiveServerStatus.CONNECTING

        val modelName = LIVE_MODELS[activeModelIndex % LIVE_MODELS.size]
        val wsUrl = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$apiKey"
        appendLog("Connecting Myraa Live ($modelName)...")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                _liveStatus.value = LiveServerStatus.CONNECTED
                appendLog("Live WebSocket connected ($modelName)")
                sendSetupMessage(webSocket, modelName)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerJson(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                handleServerJson(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Live WS closing: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Live WS closed: $code $reason")
                if (!intentionalStop.get() && _isLiveActive.value) {
                    scheduleReconnect(apiKey, "Connection closed ($code): ${reason.ifBlank { "reconnecting" }}")
                } else {
                    _liveStatus.value = LiveServerStatus.OFFLINE
                    _audioLevel.value = 0f
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = t.message ?: response?.message ?: "Network error"
                Log.w(TAG, "Live WS failure: $msg")
                if (!intentionalStop.get() && _isLiveActive.value) {
                    scheduleReconnect(apiKey, msg)
                } else {
                    _liveStatus.value = LiveServerStatus.OFFLINE
                    _isLiveActive.value = false
                    _audioLevel.value = 0f
                }
            }
        })
    }

    private fun sendSetupMessage(ws: WebSocket, modelName: String) {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", modelName)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Aoede")
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", MYRAA_SYSTEM_INSTRUCTION)))
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
            })
        }
        ws.send(setup.toString())
    }

    private fun handleServerJson(raw: String) {
        try {
            val json = JSONObject(raw)

            if (json.has("setupComplete")) {
                if (setupComplete.compareAndSet(false, true)) {
                    _liveStatus.value = if (_isMicMuted.value) LiveServerStatus.CONNECTED else LiveServerStatus.LISTENING
                    appendLog("Myraa Live ready 🟢")
                    startPlaybackLoop()
                    startMicStreaming()
                }
                return
            }

            val serverContent = json.optJSONObject("serverContent") ?: return

            if (serverContent.optBoolean("interrupted", false)) {
                interruptPlayback()
                currentAssistantBubbleId = null
                return
            }

            // Live user speech transcription from Google Live Server
            serverContent.optJSONObject("inputTranscription")
                ?.optString("text", "")
                ?.takeIf { it.isNotBlank() }
                ?.let { chunk ->
                    appendOrUpdateTranscript(isUser = true, delta = chunk)
                }

            // Live Myraa speech transcription from Google Live Server
            serverContent.optJSONObject("outputTranscription")
                ?.optString("text", "")
                ?.takeIf { it.isNotBlank() }
                ?.let { chunk ->
                    appendOrUpdateTranscript(isUser = false, delta = chunk)
                }

            val modelTurn = serverContent.optJSONObject("modelTurn")
            val parts = modelTurn?.optJSONArray("parts")
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    val text = part.optString("text", "")
                    if (text.isNotBlank()) {
                        appendOrUpdateTranscript(isUser = false, delta = text)
                    }
                    val inlineData = part.optJSONObject("inlineData")
                    val mime = inlineData?.optString("mimeType", "") ?: ""
                    val b64 = inlineData?.optString("data", "") ?: ""
                    if (b64.isNotBlank() && mime.startsWith("audio/")) {
                        val pcm = Base64.decode(b64, Base64.DEFAULT)
                        if (pcm.isNotEmpty() && _isSpeakerEnabled.value) {
                            if (_liveStatus.value != LiveServerStatus.SPEAKING) {
                                _liveStatus.value = LiveServerStatus.SPEAKING
                            }
                            if (!playbackQueue.trySend(pcm).isSuccess) {
                                // Drop oldest frame on 4GB RAM devices to prevent memory/latency buildup
                                playbackQueue.tryReceive()
                                playbackQueue.trySend(pcm)
                            }
                        }
                    }
                }
            }

            if (serverContent.optBoolean("turnComplete", false)) {
                currentAssistantBubbleId = null
                currentUserBubbleId = null
                if (_isLiveActive.value && setupComplete.get()) {
                    _liveStatus.value = if (_isMicMuted.value) LiveServerStatus.CONNECTED else LiveServerStatus.LISTENING
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing Live Server message: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startMicStreaming() {
        micJob?.cancel()
        if (!hasMicPermission()) return

        micJob = scope.launch {
            val minBuf = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(3200)
            // Keep capture buffer light (~100ms chunks at 16kHz 16-bit mono = 3200 bytes)
            val chunkBytes = 3200
            val bufferSize = maxOf(minBuf, chunkBytes * 2)

            val recorder = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    INPUT_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord init failed: ${e.message}")
                _errorMessage.value = "माइक शुरू नहीं हो सका: ${e.message}"
                return@launch
            }

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                _errorMessage.value = "ऑडियो रिकॉर्डर उपलब्ध नहीं है।"
                return@launch
            }

            audioRecord = recorder
            val pcmBuffer = ByteArray(chunkBytes)
            var loudFrameStreak = 0

            try {
                recorder.startRecording()
                while (isActive && _isLiveActive.value && setupComplete.get()) {
                    val read = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read <= 0) continue

                    val rms = computeNormalizedRms(pcmBuffer, read)
                    if (_isMicMuted.value) {
                        _audioLevel.value = 0f
                        loudFrameStreak = 0
                        continue
                    }

                    // Smooth waveform level
                    _audioLevel.value = (_audioLevel.value * 0.45f) + (rms * 0.55f)

                    // Barge-in detection: if user speaks loudly while Myraa is speaking, interrupt immediately
                    if (_liveStatus.value == LiveServerStatus.SPEAKING) {
                        if (rms >= BARGE_IN_RMS_THRESHOLD) {
                            loudFrameStreak++
                            if (loudFrameStreak >= BARGE_IN_FRAMES_REQUIRED) {
                                loudFrameStreak = 0
                                interruptPlayback()
                            }
                        } else {
                            loudFrameStreak = 0
                        }
                    } else {
                        loudFrameStreak = 0
                    }

                    val chunk = if (read == pcmBuffer.size) pcmBuffer else pcmBuffer.copyOf(read)
                    val encoded = Base64.encodeToString(chunk, Base64.NO_WRAP)
                    val payload = JSONObject().apply {
                        put("realtimeInput", JSONObject().apply {
                            put("mediaChunks", JSONArray().put(JSONObject().apply {
                                put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
                                put("data", encoded)
                            }))
                        })
                    }
                    webSocket?.send(payload.toString())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mic streaming loop ended: ${e.message}")
            } finally {
                runCatching {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                }
                runCatching { recorder.release() }
                if (audioRecord === recorder) audioRecord = null
            }
        }
    }

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch {
            val minBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4800)

            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(OUTPUT_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, 9600))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack init failed: ${e.message}")
                return@launch
            }

            audioTrack = track
            try {
                track.play()
                for (pcm in playbackQueue) {
                    if (!isActive || !_isLiveActive.value) break
                    if (!_isSpeakerEnabled.value) continue
                    val rms = computeNormalizedRms(pcm, pcm.size)
                    _audioLevel.value = (_audioLevel.value * 0.4f) + (rms * 0.6f)
                    track.write(pcm, 0, pcm.size)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio playback loop ended: ${e.message}")
            } finally {
                runCatching { track.pause() }
                runCatching { track.flush() }
                runCatching { track.release() }
                if (audioTrack === track) audioTrack = null
            }
        }
    }

    /**
     * Immediately halts Myraa's current audio playback (Barge-in / Interrupt).
     */
    fun interruptPlayback() {
        while (playbackQueue.tryReceive().isSuccess) {
            // drain buffered PCM frames
        }
        runCatching {
            audioTrack?.let { track ->
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                    if (_isLiveActive.value && _isSpeakerEnabled.value) {
                        track.play()
                    }
                }
            }
        }
        if (_isLiveActive.value) {
            _liveStatus.value = if (_isMicMuted.value) LiveServerStatus.CONNECTED else LiveServerStatus.LISTENING
        }
        appendLog("Playback interrupted (Barge-in)")
    }

    /**
     * Sends a typed or quick-action prompt to Myraa.
     * Uses the live WebSocket when active; otherwise falls back cleanly to REST.
     */
    fun sendTextTurn(prompt: String) {
        val cleaned = prompt.trim()
        if (cleaned.isEmpty()) return
        currentUserBubbleId = null
        currentAssistantBubbleId = null
        appendTranscript(isUser = true, text = cleaned)

        val ws = webSocket
        if (ws != null && setupComplete.get() && _isLiveActive.value) {
            interruptPlayback()
            val msg = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("parts", JSONArray().put(JSONObject().put("text", cleaned)))
                    }))
                    put("turnComplete", true)
                })
            }
            ws.send(msg.toString())
            appendLog("User (Live Text): $cleaned")
            return
        }

        scope.launch {
            val res = requestRestReply(cleaned)
            res.onSuccess { reply ->
                appendTranscript(isUser = false, text = reply)
            }.onFailure { err ->
                val msg = err.message ?: "Live server unreachable"
                _errorMessage.value = msg
                appendTranscript(isUser = false, text = "माफ़ कीजिए, अभी कनेक्शन में दिक्कत है: $msg")
            }
        }
    }

    private fun scheduleReconnect(apiKey: String, reason: String) {
        if (reconnectJob?.isActive == true) return
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            _liveStatus.value = LiveServerStatus.OFFLINE
            _isLiveActive.value = false
            _audioLevel.value = 0f
            _errorMessage.value = "Google Live Server से कनेक्ट नहीं हो सका ($reason)। कृपया इंटरनेट या API Key जांचें।"
            appendLog("Live reconnect limit reached: $reason")
            return
        }
        reconnectAttempt++
        // Switch fallback model on retry if initial setup never completed
        if (!setupComplete.get()) {
            activeModelIndex = (activeModelIndex + 1) % LIVE_MODELS.size
        }
        _liveStatus.value = LiveServerStatus.RECONNECTING
        appendLog("Reconnecting (${reconnectAttempt}/$MAX_RECONNECT_ATTEMPTS): $reason")
        reconnectJob = scope.launch {
            val backoffMs = 1500L * reconnectAttempt
            delay(backoffMs)
            if (!intentionalStop.get() && _isLiveActive.value) {
                connectWebSocket(apiKey)
            }
        }
    }

    /**
     * Backward-compatible REST conversational turn (used by GeminiLiveAndSearchPanel and CineCodeApiIntegrationSection).
     */
    suspend fun startLiveVoiceSession(spokenInput: String): Result<String> = withContext(Dispatchers.IO) {
        val cleaned = spokenInput.trim()
        if (cleaned.isBlank()) return@withContext Result.failure(IllegalArgumentException("Empty voice input"))

        _isLiveActive.value = true
        _liveStatus.value = LiveServerStatus.CONNECTED
        appendLog("User (Voice): $cleaned")
        appendTranscript(isUser = true, text = cleaned)

        val apiKey = getStoredApiKey()
        if (apiKey.isBlank()) {
            val msg = "मायरा (Myraa): कृपया Settings में अपनी Google AI Studio API Key जोड़ें ताकि रीयल-टाइम Google लाइव वॉइस चालू हो सके।"
            appendLog("Myraa (Live): $msg")
            appendTranscript(isUser = false, text = msg)
            _liveStatus.value = LiveServerStatus.OFFLINE
            return@withContext Result.success(msg)
        }

        val replyResult = requestRestReply(cleaned)
        replyResult.onSuccess { reply ->
            appendLog("Myraa (Live): $reply")
            appendTranscript(isUser = false, text = reply)
            _liveStatus.value = LiveServerStatus.CONNECTED
        }.onFailure { err ->
            _errorMessage.value = err.message
            _liveStatus.value = LiveServerStatus.OFFLINE
        }
        replyResult
    }

    private suspend fun requestRestReply(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getStoredApiKey()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Google AI Studio API Key missing"))
        }

        var lastError: Exception? = null
        for (model in REST_FALLBACK_MODELS) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val jsonBody = JSONObject().apply {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(JSONObject().put("text", MYRAA_SYSTEM_INSTRUCTION)))
                    })
                    put("contents", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                        }
                    ))
                }

                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toString().toRequestBody(jsonMediaType))
                    .header("Content-Type", "application/json")
                    .build()

                restClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = RuntimeException("Live API Error HTTP ${response.code} ($model)")
                        return@use
                    }
                    val bodyStr = response.body?.string() ?: "{}"
                    val json = JSONObject(bodyStr)
                    val candidates = json.optJSONArray("candidates")
                    val text = if (candidates != null && candidates.length() > 0) {
                        candidates.getJSONObject(0)
                            .optJSONObject("content")
                            ?.optJSONArray("parts")
                            ?.optJSONObject(0)
                            ?.optString("text", "")
                            ?.trim()
                            .orEmpty()
                    } else ""
                    if (text.isNotBlank()) {
                        return@withContext Result.success(text)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "REST fallback failed for $model: ${e.message}")
                lastError = e
            }
        }
        Result.failure(lastError ?: RuntimeException("Google Live Server से उत्तर प्राप्त नहीं हुआ।"))
    }

    fun stopLiveSession() {
        intentionalStop.set(true)
        reconnectJob?.cancel()
        reconnectJob = null
        cleanupTransport(keepSessionFlag = false)
        _isLiveActive.value = false
        _liveStatus.value = LiveServerStatus.OFFLINE
        _audioLevel.value = 0f
        currentAssistantBubbleId = null
        currentUserBubbleId = null
        appendLog("--- मायरा Live Session Ended ---")
    }

    private fun cleanupTransport(keepSessionFlag: Boolean) {
        setupComplete.set(false)
        micJob?.cancel()
        micJob = null
        playbackJob?.cancel()
        playbackJob = null
        while (playbackQueue.tryReceive().isSuccess) {
            // drain
        }
        runCatching { webSocket?.close(1000, "Session stopped") }
        runCatching { webSocket?.cancel() }
        webSocket = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        if (!keepSessionFlag) {
            _isLiveActive.value = false
        }
    }

    private fun appendTranscript(isUser: Boolean, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val item = MyraaTranscriptItem(isUser = isUser, text = clean)
        _transcripts.value = (_transcripts.value + item).takeLast(MAX_TRANSCRIPTS)
    }

    private fun appendOrUpdateTranscript(isUser: Boolean, delta: String) {
        if (delta.isBlank()) return
        val currentList = _transcripts.value
        val activeId = if (isUser) currentUserBubbleId else currentAssistantBubbleId
        if (activeId != null) {
            val idx = currentList.indexOfLast { it.id == activeId }
            if (idx >= 0) {
                val existing = currentList[idx]
                val sep = if (existing.text.endsWith(" ") || delta.startsWith(" ")) "" else " "
                val updated = existing.copy(text = (existing.text + sep + delta.trim()).trim())
                val nextList = currentList.toMutableList()
                nextList[idx] = updated
                _transcripts.value = nextList
                return
            }
        }
        val item = MyraaTranscriptItem(isUser = isUser, text = delta.trim())
        if (isUser) currentUserBubbleId = item.id else currentAssistantBubbleId = item.id
        _transcripts.value = (currentList + item).takeLast(MAX_TRANSCRIPTS)
    }

    private fun appendLog(entry: String) {
        _liveSessionLogs.value = (_liveSessionLogs.value + entry).takeLast(MAX_LOGS)
    }

    private fun computeNormalizedRms(pcmBytes: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sumSq = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < length) {
            val low = pcmBytes[i].toInt() and 0xFF
            val high = pcmBytes[i + 1].toInt()
            val sample = (high shl 8) or low
            val normalized = sample / 32768.0
            sumSq += normalized * normalized
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        val rms = sqrt(sumSq / samples).toFloat()
        return min(1f, rms * 3.6f)
    }
}
