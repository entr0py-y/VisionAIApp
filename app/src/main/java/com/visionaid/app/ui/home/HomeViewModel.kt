package com.visionaid.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.visionaid.app.data.model.LoginRequest
import com.visionaid.app.data.remote.SseGhostClickService
import com.visionaid.app.data.remote.VisionApiService
import com.visionaid.app.data.repository.DeviceHealthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val currentUser: String = "User",
    val isCamOnline: Boolean = false,
    val isMicOnline: Boolean = false,
    val ultrasonicDistance: Float? = null,
    val pirMotion: Boolean? = null,
    val isDarkTheme: Boolean = false, // Default to Light Mode
    val speechRate: Float = 1.0f,
    val selectedPersona: String = "Vision AID Assistant",
    val showAuthModal: Boolean = false,
    val authError: String? = null,
    val isLoading: Boolean = false,
    val sseConnected: Boolean = false
)

class HomeViewModel(
    private val healthRepo: DeviceHealthRepository,
    private val apiService: VisionApiService,
    private val sseService: SseGhostClickService
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        healthRepo.startPolling()
        sseService.start()
        
        // Observe Device Health
        viewModelScope.launch {
            healthRepo.healthState.collect { health ->
                _uiState.update {
                    it.copy(
                        isCamOnline = health?.camOnline ?: false,
                        isMicOnline = health?.micOnline ?: false,
                        ultrasonicDistance = health?.ultrasonicDist,
                        pirMotion = health?.pirMotion
                    )
                }
            }
        }
        
        // Observe Real-Time SSE Connectivity
        viewModelScope.launch {
            sseService.isConnected.collect { connected ->
                _uiState.update { it.copy(sseConnected = connected) }
            }
        }
    }

    fun toggleAuthModal() {
        _uiState.update { it.copy(showAuthModal = !it.showAuthModal, authError = null) }
    }

    fun login(user: String, pass: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, authError = null) }
            try {
                val response = apiService.login(LoginRequest(user, pass))
                if (response.isSuccessful) {
                    val body = response.body()
                    val userName = body?.name ?: body?.user?.username ?: user
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            currentUser = userName,
                            showAuthModal = false
                        ) 
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, authError = "Login Failed") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, authError = "Network error: ${e.localizedMessage}") }
            }
        }
    }

    fun signup(user: String, pass: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, authError = null) }
            try {
                val response = apiService.signup(LoginRequest(user, pass))
                if (response.isSuccessful) {
                    val body = response.body()
                    val userName = body?.name ?: body?.user?.username ?: user
                    _uiState.update { 
                        it.copy(
                            isLoading = false,
                            currentUser = userName,
                            showAuthModal = false
                        ) 
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, authError = "Sign Up Failed") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, authError = "Network error: ${e.localizedMessage}") }
            }
        }
    }

    fun setSpeechRate(rate: Float) {
        _uiState.update { it.copy(speechRate = rate) }
    }

    fun toggleDarkTheme(isDark: Boolean) {
        _uiState.update { it.copy(isDarkTheme = isDark) }
    }

    fun setPersona(persona: String) {
        _uiState.update { it.copy(selectedPersona = persona) }
    }

    fun logout() {
        _uiState.update { it.copy(currentUser = "User") }
    }

    override fun onCleared() {
        super.onCleared()
        healthRepo.stopPolling()
        sseService.stop()
    }
}

class HomeViewModelFactory(
    private val healthRepo: DeviceHealthRepository,
    private val apiService: VisionApiService,
    private val sseService: SseGhostClickService
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(healthRepo, apiService, sseService) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
