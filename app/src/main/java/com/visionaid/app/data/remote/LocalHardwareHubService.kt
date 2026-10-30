package com.visionaid.app.data.remote

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer

sealed class HardwareIntakeEvent {
    data class PcmAudioReceived(val pcmData: ByteArray) : HardwareIntakeEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as PcmAudioReceived
            return pcmData.contentEquals(other.pcmData)
        }
        override fun hashCode(): Int = pcmData.contentHashCode()
    }
    data class CameraFrameReceived(val base64Jpeg: String) : HardwareIntakeEvent()
    object MicPing : HardwareIntakeEvent()
    object CamPing : HardwareIntakeEvent()
    data class SensorTelemetry(val distanceCm: Float?, val pirMotion: Boolean?) : HardwareIntakeEvent()
}

class LocalHardwareHubService : Service() {

    private var webSocketServer: HardwareWebSocketServer? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    companion object {
        private var instance: LocalHardwareHubService? = null
        private val _hardwareEvents = MutableSharedFlow<HardwareIntakeEvent>()
        val hardwareEvents: SharedFlow<HardwareIntakeEvent> = _hardwareEvents.asSharedFlow()

        fun isCameraConnected(): Boolean = (instance?.webSocketServer?.connections?.size ?: 0) > 0

        fun triggerCameraCapture() {
            instance?.webSocketServer?.broadcast("CAPTURE_NOW")
            Log.d("LocalHardwareHub", "Broadcasted CAPTURE_NOW to ESP32-CAM")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundService()

        try {
            webSocketServer = HardwareWebSocketServer(InetSocketAddress("0.0.0.0", 8888)) { event ->
                scope.launch {
                    _hardwareEvents.emit(event)
                }
            }
            webSocketServer?.isReuseAddr = true
            webSocketServer?.start()
            Log.d("LocalHardwareHub", "Embedded WebSocket Server started on port 8888")
        } catch (e: Exception) {
            Log.e("LocalHardwareHub", "Failed to start WebSocket Server", e)
        }
    }

    private fun startForegroundService() {
        val channelId = "vision_aid_hardware_hub"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Hardware Connection Hub",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.stat_notify_sync)
            .setContentTitle("Vision AID Hub")
            .setContentText("Listening for ESP32 devices on port 8888")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            webSocketServer?.stop()
        } catch (e: Exception) {
            Log.e("LocalHardwareHub", "Error stopping WebSocket Server", e)
        }
        instance = null
    }

    class HardwareWebSocketServer(
        address: InetSocketAddress,
        private val onEvent: (HardwareIntakeEvent) -> Unit
    ) : WebSocketServer(address) {

        private val audioBuffer = ByteArrayOutputStream()
        private var isRecording = false

        private val camFrameBuffer = ByteArrayOutputStream()
        private var isReceivingCamImage = false

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            Log.d("LocalHardwareHub", "ESP32 Connected: ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            Log.d("LocalHardwareHub", "ESP32 Disconnected: ${conn.remoteSocketAddress}")
            isRecording = false
            audioBuffer.reset()
            isReceivingCamImage = false
            camFrameBuffer.reset()
        }

        override fun onMessage(conn: WebSocket, message: String) {
            when {
                message == "START" -> {
                    isRecording = true
                    audioBuffer.reset()
                    Log.d("LocalHardwareHub", "MIC started recording PCM stream")
                    onEvent(HardwareIntakeEvent.MicPing)
                }
                message == "STOP" -> {
                    isRecording = false
                    Log.d("LocalHardwareHub", "MIC stopped recording, size: ${audioBuffer.size()} bytes")
                    if (audioBuffer.size() > 0) {
                        onEvent(HardwareIntakeEvent.PcmAudioReceived(audioBuffer.toByteArray()))
                        audioBuffer.reset()
                    }
                    onEvent(HardwareIntakeEvent.MicPing)
                }
                message.contains("ESP32_MIC") || message.contains("MIC_PING") -> {
                    onEvent(HardwareIntakeEvent.MicPing)
                }
                message.contains("ESP32_CAM") -> {
                    onEvent(HardwareIntakeEvent.CamPing)
                }
                message.contains("CAM_IMAGE_END") -> {
                    isReceivingCamImage = false
                    if (camFrameBuffer.size() > 0) {
                        val base64 = Base64.encodeToString(camFrameBuffer.toByteArray(), Base64.NO_WRAP)
                        onEvent(HardwareIntakeEvent.CameraFrameReceived("data:image/jpeg;base64,$base64"))
                        camFrameBuffer.reset()
                        Log.d("LocalHardwareHub", "Assembled complete ESP32-CAM frame")
                    }
                    onEvent(HardwareIntakeEvent.CamPing)
                }
                message.contains("CAM_IMAGE") -> {
                    isReceivingCamImage = true
                    camFrameBuffer.reset()
                    onEvent(HardwareIntakeEvent.CamPing)
                }
                message.contains("\"type\":\"sensors\"") || message.contains("\"type\": \"sensors\"") -> {
                    try {
                        val json = JSONObject(message)
                        val u = if (json.has("u")) {
                            val rawU = json.optDouble("u", -1.0)
                            if (rawU > 0) rawU.toFloat() else null
                        } else null
                        val p = json.optInt("p", 0) == 1
                        onEvent(HardwareIntakeEvent.SensorTelemetry(u, p))
                        onEvent(HardwareIntakeEvent.MicPing)
                    } catch (e: Exception) {
                        Log.e("LocalHardwareHub", "Error parsing sensor JSON", e)
                    }
                }
                else -> {
                    Log.d("LocalHardwareHub", "Received text: $message")
                }
            }
        }

        override fun onMessage(conn: WebSocket, message: ByteBuffer) {
            val bytes = ByteArray(message.remaining())
            message.get(bytes)
            if (isRecording) {
                audioBuffer.write(bytes)
            } else if (isReceivingCamImage) {
                camFrameBuffer.write(bytes)
            }
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            Log.e("LocalHardwareHub", "WebSocket error: ${ex.message}")
        }

        override fun onStart() {
            Log.d("LocalHardwareHub", "Server thread started successfully")
        }
    }
}
