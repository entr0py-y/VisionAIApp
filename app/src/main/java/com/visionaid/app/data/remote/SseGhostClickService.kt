package com.visionaid.app.data.remote

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.math.pow

sealed class SseHardwareEvent {
    object HardwareBtnTouched : SseHardwareEvent()
    object HardwareBtnReleased : SseHardwareEvent()
    data class AudioResult(val transcript: String, val text: String) : SseHardwareEvent()
}

class SseGhostClickService {

    private val _events = MutableSharedFlow<SseHardwareEvent>()
    val events: SharedFlow<SseHardwareEvent> = _events.asSharedFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var eventSource: EventSource? = null
    private val sseUrl: String
        get() = NetworkClient.baseUrl + "api/stream"

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var retryCount = 0

    fun start() {
        if (_isConnected.value) return
        connect()
    }

    private fun connect() {
        Log.d("SseGhostClick", "Connecting to SSE stream at $sseUrl...")
        val request = Request.Builder().url(sseUrl).build()
        val factory = EventSources.createFactory(NetworkClient.sseClient)
        
        eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d("SseGhostClick", "SSE Connection opened successfully!")
                _isConnected.value = true
                retryCount = 0
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                scope.launch {
                    Log.d("SseGhostClick", "SSE Event received: $type, data: $data")
                    try {
                        val json = JsonParser.parseString(data).asJsonObject
                        val eventType = json.get("event")?.asString ?: type
                        when (eventType) {
                            "HARDWARE_BTN_TOUCHED" -> _events.emit(SseHardwareEvent.HardwareBtnTouched)
                            "HARDWARE_BTN_RELEASED" -> _events.emit(SseHardwareEvent.HardwareBtnReleased)
                            "AUDIO_RESULT" -> {
                                val dataObj = json.getAsJsonObject("data")
                                val transcript = dataObj?.get("transcript")?.asString ?: ""
                                val text = dataObj?.get("text")?.asString ?: ""
                                _events.emit(SseHardwareEvent.AudioResult(transcript, text))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("SseGhostClick", "Error parsing SSE JSON payload: $data", e)
                    }
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d("SseGhostClick", "SSE Connection closed")
                _isConnected.value = false
                scheduleReconnect()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.e("SseGhostClick", "SSE Connection failed", t)
                _isConnected.value = false
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        eventSource?.cancel()
        eventSource = null
        
        val backoffTime = (2.0.pow(retryCount).toLong() * 1000).coerceAtMost(30000)
        retryCount++
        Log.d("SseGhostClick", "Scheduling reconnect in $backoffTime ms")
        
        scope.launch {
            delay(backoffTime)
            connect()
        }
    }

    fun stop() {
        eventSource?.cancel()
        _isConnected.value = false
    }
}
