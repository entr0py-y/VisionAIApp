package com.visionaid.app.ui.family

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.visionaid.app.data.model.FamilyMember
import com.visionaid.app.data.model.SosRequest
import com.visionaid.app.data.remote.VisionApiService
import com.visionaid.app.util.TtsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FamilyUiState(
    val members: List<FamilyMember> = emptyList(),
    val isSosActive: Boolean = false,
    val isLoading: Boolean = false
)

class FamilyViewModel(
    private val apiService: VisionApiService,
    private val ttsManager: TtsManager,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val vibrator: Vibrator
) : ViewModel() {

    private val _uiState = MutableStateFlow(FamilyUiState())
    val uiState: StateFlow<FamilyUiState> = _uiState.asStateFlow()

    init {
        loadMembers()
    }

    fun loadMembers() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val res = apiService.getFamilyMembers()
                if (res.isSuccessful) {
                    _uiState.update { it.copy(members = res.body() ?: emptyList(), isLoading = false) }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun addMember(name: String, phone: String) {
        viewModelScope.launch {
            try {
                apiService.addFamilyMember(FamilyMember(id = null, name = name, phone = phone))
                loadMembers() // refresh
            } catch (e: Exception) {
                // Handle Error
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun sendEmergencySos(context: Context, username: String) {
        if (_uiState.value.isSosActive) return
        _uiState.update { it.copy(isSosActive = true) }
        
        // Haptic Feedback Pattern (SOS)
        val sosPattern = longArrayOf(0, 500, 200, 500, 200, 500, 400, 200, 200, 200)
        vibrator.vibrate(VibrationEffect.createWaveform(sosPattern, -1))
        
        ttsManager.speak("Emergency SOS dispatched with your live location.")

        viewModelScope.launch {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                        if (loc != null) {
                            viewModelScope.launch {
                                apiService.sendSos(SosRequest(loc.latitude, loc.longitude, username))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallback if location fails
            }
            
            // Auto reset UI
            kotlinx.coroutines.delay(5000)
            _uiState.update { it.copy(isSosActive = false) }
        }
    }
}

class FamilyViewModelFactory(
    private val apiService: VisionApiService,
    private val ttsManager: TtsManager,
    private val fusedLocationClient: FusedLocationProviderClient,
    private val vibrator: Vibrator
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FamilyViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FamilyViewModel(apiService, ttsManager, fusedLocationClient, vibrator) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}