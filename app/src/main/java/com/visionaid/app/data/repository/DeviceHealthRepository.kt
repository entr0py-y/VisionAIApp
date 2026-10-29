package com.visionaid.app.data.repository

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

    fun startPolling() {
        if (isPolling) return
        isPolling = true
        scope.launch {
            while (isActive && isPolling) {
                try {
                    val response = apiService.getHealth()
                    if (response.isSuccessful) {
                        _healthState.value = response.body()
                    } else {
                        Log.e("HealthRepo", "Error fetching health: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.e("HealthRepo", "Exception fetching health", e)
                }
                delay(3000)
            }
        }
    }

    fun stopPolling() {
        isPolling = false
    }
}
