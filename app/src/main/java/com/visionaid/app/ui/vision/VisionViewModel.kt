package com.visionaid.app.ui.vision

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.visionaid.app.data.model.HardwareCameraRequest
import com.visionaid.app.data.model.VisionRequest
import com.visionaid.app.data.remote.LocalHardwareHubService
import com.visionaid.app.data.remote.VisionApiService
import com.visionaid.app.util.TtsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VisionUiState(
    val description: String? = null,
    val imageB64: String? = null,
    val qrUrl: String? = null,
    val isLoading: Boolean = false,
    val launchUrlEvent: String? = null
)

class VisionViewModel(
    private val apiService: VisionApiService,
    private val ttsManager: TtsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(VisionUiState())
    val uiState: StateFlow<VisionUiState> = _uiState.asStateFlow()

    fun triggerHardwareCamera(prompt: String = "") {
        _uiState.update { it.copy(isLoading = true, description = null, imageB64 = null, qrUrl = null) }
        viewModelScope.launch {
            try {
                // Priority 1: Check Local Phone Hardware Hub
                if (LocalHardwareHubService.isCameraConnected()) {
                    val localFrame = LocalHardwareHubService.captureImageFromCamera(8000)
                    if (!localFrame.isNullOrBlank()) {
                        val pureB64 = if (localFrame.startsWith("data:")) localFrame.substringAfter(",") else localFrame
                        analyzeLocalImage(pureB64, prompt)
                        return@launch
                    }
                }

                // Priority 2: Remote backend fallback
                val response = apiService.triggerHardwareCamera(HardwareCameraRequest(prompt))
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            description = body.description,
                            imageB64 = body.image,
                            qrUrl = body.qrUrl
                        )
                    }
                    
                    body.description?.let { desc -> ttsManager.speak(desc) }
                    
                    if (!body.qrUrl.isNullOrBlank()) {
                        delay(1500) // Let TTS start
                        _uiState.update { it.copy(launchUrlEvent = body.qrUrl) }
                    }
                    return@launch
                } else {
                    _uiState.update { it.copy(isLoading = false, description = "Failed to capture image from ESP32-CAM.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, description = "Network Error.") }
            }
        }
    }

    fun analyzeLocalImage(base64Image: String, prompt: String = "") {
        _uiState.update { it.copy(isLoading = true, description = null, imageB64 = base64Image, qrUrl = null) }
        viewModelScope.launch {
            try {
                val dataUri = if (base64Image.startsWith("data:")) base64Image else "data:image/jpeg;base64,$base64Image"
                val response = apiService.analyzeVision(VisionRequest(image = dataUri, prompt = prompt))
                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            description = body.description,
                            qrUrl = body.qrUrl
                        )
                    }
                    
                    body.description?.let { desc -> ttsManager.speak(desc) }
                    
                    if (!body.qrUrl.isNullOrBlank()) {
                        delay(1500) // Let TTS start
                        _uiState.update { it.copy(launchUrlEvent = body.qrUrl) }
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, description = "Failed to analyze image.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, description = "Network Error.") }
            }
        }
    }

    fun clearUrlEvent() {
        _uiState.update { it.copy(launchUrlEvent = null) }
    }

    fun replayAudio() {
        _uiState.value.description?.let { ttsManager.speak(it) }
    }
}

class VisionViewModelFactory(
    private val apiService: VisionApiService,
    private val ttsManager: TtsManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VisionViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return VisionViewModel(apiService, ttsManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
