package com.visionaid.app.ui.chat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import com.visionaid.app.data.model.ChatMessageItem
import com.visionaid.app.data.model.ChatRequest
import com.visionaid.app.data.model.HardwareCameraRequest
import com.visionaid.app.data.model.VisionRequest
import com.visionaid.app.data.remote.HardwareIntakeEvent
import com.visionaid.app.data.remote.LocalHardwareHubService
import com.visionaid.app.data.remote.SseGhostClickService
import com.visionaid.app.data.remote.SseHardwareEvent
import com.visionaid.app.data.remote.VisionApiService
import com.visionaid.app.data.repository.DeviceHealthRepository
import com.visionaid.app.domain.router.IntentAction
import com.visionaid.app.domain.router.RouterEngine
import com.visionaid.app.util.TtsManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.UUID

sealed class ChatEvent {
    data class TriggerNavigation(val destination: String) : ChatEvent()
    data class TriggerPhoneCamera(val prompt: String) : ChatEvent()
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: String, // "user" or "ai"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUrl: String? = null
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isRecording: Boolean = false,
    val isProcessing: Boolean = false,
    val isOpen: Boolean = false
)

class ChatViewModel(
    private val apiService: VisionApiService,
    private val sseService: SseGhostClickService,
    private val ttsManager: TtsManager,
    private val vibrator: Vibrator,
    private val routerEngine: RouterEngine,
    private val healthRepo: DeviceHealthRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val _chatEvents = MutableSharedFlow<ChatEvent>()
    val chatEvents: SharedFlow<ChatEvent> = _chatEvents.asSharedFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var lastProcessedTranscript: String = ""
    private var lastProcessedTime: Long = 0L
    private var lastAudioUploadTime: Long = 0L

    init {
        observeSseEvents()
        observeHardwareRecordingState()
        observeHardwareHubEvents()
    }

    private fun observeHardwareHubEvents() {
        viewModelScope.launch {
            LocalHardwareHubService.hardwareEvents.collect { event ->
                when (event) {
                    is HardwareIntakeEvent.MicPing -> {
                        healthRepo?.markMicOnline()
                    }
                    is HardwareIntakeEvent.CamPing -> {
                        healthRepo?.markCamOnline()
                    }
                    is HardwareIntakeEvent.SensorTelemetry -> {
                        healthRepo?.updateSensors(event.distanceCm, event.pirMotion)
                    }
                    is HardwareIntakeEvent.PcmAudioReceived -> {
                        healthRepo?.markMicOnline()
                        handleLocalHardwareAudio(event.pcmData)
                    }
                    is HardwareIntakeEvent.CameraFrameReceived -> {
                        healthRepo?.markCamOnline()
                    }
                }
            }
        }
    }

    private fun handleLocalHardwareAudio(pcmData: ByteArray) {
        if (pcmData.size < 12000) {
            Log.d("ChatViewModel", "Ignored short hardware audio: ${pcmData.size} bytes")
            return
        }
        val now = System.currentTimeMillis()
        if (_uiState.value.isProcessing || (now - lastAudioUploadTime < 2000L)) {
            Log.d("ChatViewModel", "Ignored concurrent or rapid hardware audio upload (cooldown active)")
            return
        }
        lastAudioUploadTime = now

        _uiState.update { it.copy(isOpen = true, isRecording = false, isProcessing = true) }
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
        ttsManager.stop()
        viewModelScope.launch {
            try {
                val mediaType = "application/octet-stream".toMediaTypeOrNull()
                val requestBody = pcmData.toRequestBody(mediaType)
                val response = apiService.uploadAudio("application/octet-stream", requestBody)
                if (response.isSuccessful && response.body() != null) {
                    try {
                        val rawStr = response.body()!!.string()
                        val json = JsonParser.parseString(rawStr).asJsonObject
                        val text = json.get("text")?.asString
                        val transcript = json.get("transcript")?.asString
                        if (!transcript.isNullOrBlank() && !text.isNullOrBlank()) {
                            handleAudioResult(transcript, text)
                        } else {
                            _uiState.update { it.copy(isProcessing = false) }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Error parsing upload response", e)
                        _uiState.update { it.copy(isProcessing = false) }
                    }
                } else {
                    Log.e("ChatViewModel", "Hardware audio upload failed: ${response.code()}")
                    _uiState.update { it.copy(isProcessing = false) }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Exception uploading hardware audio", e)
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    private fun observeHardwareRecordingState() {
        viewModelScope.launch {
            sseService.isHardwareRecording.collect { recording ->
                _uiState.update { it.copy(isRecording = recording) }
            }
        }
    }

    private fun observeSseEvents() {
        viewModelScope.launch {
            sseService.events.collect { event ->
                when (event) {
                    is SseHardwareEvent.HardwareBtnTouched -> {
                        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                        ttsManager.stop()
                        _uiState.update { it.copy(isOpen = true, isRecording = true) }
                    }
                    is SseHardwareEvent.HardwareBtnReleased -> {
                        _uiState.update { it.copy(isRecording = false, isProcessing = true) }
                    }
                    is SseHardwareEvent.AudioResult -> {
                        handleAudioResult(event.transcript, event.text)
                    }
                }
            }
        }
    }

    private suspend fun handleAudioResult(transcript: String, pregenText: String) {
        val now = System.currentTimeMillis()
        if (transcript.equals(lastProcessedTranscript, ignoreCase = true) && (now - lastProcessedTime) < 3000L) {
            return
        }
        lastProcessedTranscript = transcript
        lastProcessedTime = now

        addMessage(ChatMessage(sender = "user", text = transcript))
        
        routerEngine.dispatch(transcript, pregenText) { action ->
            when (action) {
                is IntentAction.GeneralChat -> {
                    if (action.pregenResponse.isNullOrBlank()) {
                        sendMessageInternal(transcript)
                    } else {
                        addMessage(ChatMessage(sender = "ai", text = action.pregenResponse))
                        ttsManager.speak(action.pregenResponse)
                        _uiState.update { it.copy(isProcessing = false) }
                    }
                }
                is IntentAction.Vision -> {
                    handleVisionInChat(action.text)
                }
                is IntentAction.Navigation -> {
                    Log.d("ChatViewModel", "Routed to Navigation: ${action.destination}")
                    _uiState.update { it.copy(isProcessing = false) }
                    _chatEvents.emit(ChatEvent.TriggerNavigation(action.destination))
                }
                is IntentAction.LocationInfo -> {
                    Log.d("ChatViewModel", "Routed to LocationInfo")
                    _uiState.update { it.copy(isProcessing = false) }
                }
                is IntentAction.PlaceSearch -> {
                    Log.d("ChatViewModel", "Routed to PlaceSearch")
                    _uiState.update { it.copy(isProcessing = false) }
                }
            }
        }
    }

    fun openChat() {
        _uiState.update { it.copy(isOpen = true) }
    }

    fun closeChat() {
        stopListening()
        _uiState.update { it.copy(isOpen = false) }
    }

    fun addMessage(message: ChatMessage) {
        _uiState.update { it.copy(messages = it.messages + message) }
    }

    fun setProcessing(processing: Boolean) {
        _uiState.update { it.copy(isProcessing = processing) }
    }

    fun startListening(context: Context) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e("ChatViewModel", "Speech recognition not available on device")
            return
        }
        ttsManager.stop()
        _uiState.update { it.copy(isRecording = true) }

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    _uiState.update { it.copy(isRecording = false, isProcessing = true) }
                }

                override fun onError(error: Int) {
                    Log.e("ChatViewModel", "Speech recognizer error: $error")
                    _uiState.update { it.copy(isRecording = false, isProcessing = false) }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        sendMessage(text)
                    } else {
                        _uiState.update { it.copy(isProcessing = false) }
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        _uiState.update { it.copy(isRecording = false) }
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error stopping speech recognizer", e)
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        addMessage(ChatMessage(sender = "user", text = text))
        _uiState.update { it.copy(isProcessing = true) }
        
        viewModelScope.launch {
            routerEngine.dispatch(text) { action ->
                when (action) {
                    is IntentAction.Vision -> {
                        handleVisionInChat(action.text)
                    }
                    is IntentAction.Navigation -> {
                        _uiState.update { it.copy(isProcessing = false) }
                        _chatEvents.emit(ChatEvent.TriggerNavigation(action.destination))
                    }
                    else -> {
                        sendMessageInternal(text)
                    }
                }
            }
        }
    }

    fun triggerCameraDirectly() {
        viewModelScope.launch {
            handleVisionInChat("Describe what is in front of me.")
        }
    }

    private suspend fun handleVisionInChat(prompt: String) {
        _uiState.update { it.copy(isProcessing = true) }
        val isCamOnline = LocalHardwareHubService.isCameraConnected() || (healthRepo?.healthState?.value?.camOnline == true)

        // Priority 1: Local ESP32-CAM if online
        if (isCamOnline) {
            try {
                Log.d("ChatViewModel", "ESP32-CAM is online. Requesting local hardware camera frame...")
                val localFrame = LocalHardwareHubService.captureImageFromCamera(8000)

                if (!localFrame.isNullOrBlank()) {
                    Log.d("ChatViewModel", "Received ESP32-CAM frame. Analyzing with Vision AI...")
                    analyzeLocalPhoneImage(localFrame, prompt)
                    return
                } else {
                    Log.w("ChatViewModel", "Timeout waiting for local ESP32-CAM frame, trying remote fallback...")
                }
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Local ESP32-CAM trigger failed, falling back...", e)
            }

            // Fallback to cloud trigger if available
            try {
                val response = withTimeoutOrNull(10000) {
                    apiService.triggerHardwareCamera(HardwareCameraRequest(prompt))
                }
                if (response != null && response.isSuccessful && response.body() != null && !response.body()?.description.isNullOrBlank()) {
                    val body = response.body()!!
                    val desc = body.description ?: "Captured scene image from ESP32-CAM."
                    val imageUrl = body.image?.let { img ->
                        if (img.startsWith("data:")) img else "data:image/jpeg;base64,$img"
                    }
                    addMessage(ChatMessage(sender = "ai", text = desc, imageUrl = imageUrl))
                    ttsManager.speak(desc)
                    _uiState.update { it.copy(isProcessing = false) }
                    return
                }
            } catch (e: Exception) {
                Log.w("ChatViewModel", "Remote ESP32-CAM trigger failed", e)
            }
        }

        // Priority 2 / Fallback: Silent Phone Camera Frame Capture
        Log.d("ChatViewModel", "Falling back to phone camera capture...")
        _uiState.update { it.copy(isProcessing = false) }
        _chatEvents.emit(ChatEvent.TriggerPhoneCamera(prompt))
    }

    fun analyzeLocalPhoneImage(base64: String, prompt: String = "Describe what is in front of me") {
        val dataUri = if (base64.startsWith("data:")) base64 else "data:image/jpeg;base64,$base64"
        _uiState.update { it.copy(isProcessing = true) }

        viewModelScope.launch {
            try {
                val response = apiService.analyzeVision(
                    VisionRequest(image = dataUri, prompt = prompt, source = "phone")
                )
                if (response.isSuccessful && response.body() != null) {
                    val desc = response.body()?.description ?: "No description available."
                    addMessage(ChatMessage(sender = "ai", text = desc, imageUrl = dataUri))
                    ttsManager.speak(desc)
                } else {
                    addMessage(ChatMessage(sender = "ai", text = "Could not analyze the scene."))
                }
            } catch (e: Exception) {
                addMessage(ChatMessage(sender = "ai", text = "Network error analyzing image."))
            } finally {
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    private suspend fun sendMessageInternal(text: String) {
        try {
            val history = _uiState.value.messages.map { ChatMessageItem(role = if (it.sender == "user") "user" else "assistant", content = it.text) }
            val req = ChatRequest(message = text, history = history)
            val response = apiService.chat(req)
            if (response.isSuccessful && response.body() != null) {
                val rawStr = response.body()!!.string()
                val aiText = try {
                    val json = JsonParser.parseString(rawStr).asJsonObject
                    json.get("response")?.asString ?: rawStr
                } catch (e: Exception) {
                    rawStr
                }
                addMessage(ChatMessage(sender = "ai", text = aiText))
                ttsManager.speak(aiText)
            } else {
                addMessage(ChatMessage(sender = "ai", text = "Sorry, I couldn't reach the server."))
            }
        } catch (e: Exception) {
            Log.e("ChatViewModel", "Error sending chat message", e)
            addMessage(ChatMessage(sender = "ai", text = "Network error occurred."))
        } finally {
            _uiState.update { it.copy(isProcessing = false) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            // ignore
        }
    }
}

class ChatViewModelFactory(
    private val apiService: VisionApiService,
    private val sseService: SseGhostClickService,
    private val ttsManager: TtsManager,
    private val vibrator: Vibrator,
    private val routerEngine: RouterEngine,
    private val healthRepo: DeviceHealthRepository? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(apiService, sseService, ttsManager, vibrator, routerEngine, healthRepo) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
