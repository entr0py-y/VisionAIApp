package com.visionaid.app.data.repository

import com.visionaid.app.data.remote.LocalHardwareHubService

import android.util.Log
import com.visionaid.app.data.model.HealthResponse
import com.visionaid.app.data.remote.VisionApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DeviceHealthRepository(private val apiService: VisionApiService) {

    private val _healthState = MutableStateFlow<HealthResponse?>(null)
    val healthState: StateFlow<HealthResponse?> = _healthState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var isPolling = false

    private var lastLocalMicSeen: Long = 0L
    private var lastLocalCamSeen: Long = 0L
    private var latestUltrasonic: Float? = null
    private var latestPir: Boolean? = null
    private var lastRemoteHealth: HealthResponse? = null

    fun markMicOnline() {
        lastLocalMicSeen = System.currentTimeMillis()
        updateConsolidatedHealth()
    }

    fun markCamOnline() {
        lastLocalCamSeen = System.currentTimeMillis()
        updateConsolidatedHealth()
    }

    fun updateSensors(dist: Float?, pir: Boolean?) {
        lastLocalMicSeen = System.currentTimeMillis()
        if (dist != null) latestUltrasonic = dist
        if (pir != null) latestPir = pir
        updateConsolidatedHealth()
    }

    private fun updateConsolidatedHealth() {
        val now = System.currentTimeMillis()
        val localMicOnline = (now - lastLocalMicSeen) < 10000L
        val localCamOnline = (now - lastLocalCamSeen) < 10000L || LocalHardwareHubService.isCameraConnected()

        val isMic = localMicOnline || (lastRemoteHealth?.micOnline == true)
        val isCam = localCamOnline || (lastRemoteHealth?.camOnline == true)

        _healthState.value = HealthResponse(
            micOnline = isMic,
            camOnline = isCam,
            hardwareMode = if (localMicOnline || localCamOnline) "local_hub" else (lastRemoteHealth?.hardwareMode ?: "STANDBY"),
            ultrasonicDist = latestUltrasonic ?: lastRemoteHealth?.ultrasonicDist,
            pirMotion = latestPir ?: lastRemoteHealth?.pirMotion
        )
    }

    fun startPolling() {
        if (isPolling) return
        isPolling = true
        scope.launch {
            while (isActive && isPolling) {
                try {
                    val response = apiService.getHealth()
                    if (response.isSuccessful) {
                        lastRemoteHealth = response.body()
                    } else {
                        Log.e("HealthRepo", "Error fetching health: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.e("HealthRepo", "Exception fetching health", e)
                }
                updateConsolidatedHealth()
                delay(3000)
            }
        }
    }

    fun stopPolling() {
        isPolling = false
    }
}
