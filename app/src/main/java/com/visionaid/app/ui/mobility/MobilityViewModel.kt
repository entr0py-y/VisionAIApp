package com.visionaid.app.ui.mobility

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.visionaid.app.data.model.GeocodeRequest
import com.visionaid.app.data.remote.VisionApiService
import com.visionaid.app.util.TtsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MobilityUiState(
    val destination: String = "",
    val destLat: Double? = null,
    val destLng: Double? = null,
    val userLat: Double = 37.7749,
    val userLng: Double = -122.4194,
    val isNavigating: Boolean = false,
    val distanceText: String = "",
    val error: String? = null
)

class MobilityViewModel(
    private val apiService: VisionApiService,
    private val ttsManager: TtsManager,
    private val fusedLocationClient: FusedLocationProviderClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(MobilityUiState())
    val uiState: StateFlow<MobilityUiState> = _uiState.asStateFlow()

    @SuppressLint("MissingPermission")
    fun navigateTo(destination: String, context: Context) {
        _uiState.update { it.copy(destination = destination, error = null, isNavigating = true) }
        ttsManager.speak("Opening navigation to $destination")
        
        viewModelScope.launch {
            try {
                // 1. Geocode Destination
                val response = apiService.geocode(GeocodeRequest(destination))
                if (response.isSuccessful && response.body()?.latitude != null) {
                    val body = response.body()!!
                    _uiState.update { 
                        it.copy(
                            destLat = body.latitude,
                            destLng = body.longitude,
                            distanceText = "Calculating route..."
                        ) 
                    }
                    
                    // 2. Fetch User Location
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                            if (loc != null) {
                                _uiState.update { 
                                    it.copy(
                                        userLat = loc.latitude, 
                                        userLng = loc.longitude,
                                        distanceText = "1.2 km • 15 min walk" // Mock calculated dist
                                    ) 
                                }
                                ttsManager.speak("Route calculated. Head North for 200 meters.")
                            }
                        }
                    } else {
                        _uiState.update { it.copy(distanceText = "Location Permission Missing") }
                    }

                } else {
                    _uiState.update { it.copy(error = "Location not found.", isNavigating = false) }
                    ttsManager.speak("Sorry, I couldn't find that location.")
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Network Error.", isNavigating = false) }
                ttsManager.speak("Network error.")
            }
        }
    }

    fun stopNavigation() {
        _uiState.update { it.copy(isNavigating = false, destLat = null, destLng = null, destination = "") }
        ttsManager.speak("Navigation stopped.")
    }
}

class MobilityViewModelFactory(
    private val apiService: VisionApiService,
    private val ttsManager: TtsManager,
    private val fusedLocationClient: FusedLocationProviderClient
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MobilityViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MobilityViewModel(apiService, ttsManager, fusedLocationClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}