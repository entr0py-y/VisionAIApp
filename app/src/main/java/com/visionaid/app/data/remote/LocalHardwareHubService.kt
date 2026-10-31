package com.visionaid.app.data.remote

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
import com.visionaid.app.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
        private val _hardwareEvents = MutableSharedFlow<HardwareIntakeEvent>(
            replay = 1,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        val hardwareEvents: SharedFlow<HardwareIntakeEvent> = _hardwareEvents.asSharedFlow()

        private var pendingCameraCapture: CompletableDeferred<String?>? = null

        private var lastCamPingTimestamp: Long = 0L
        private var lastMicPingTimestamp: Long = 0L

        var latestCameraFrame: String? = null
            private set
        var latestFrameTimestamp: Long = 0L
            private set

        fun isCameraConnected(): Boolean {
            val server = instance?.webSocketServer
            val hasCamConn = server?.camConnection != null && server.camConnection?.isOpen == true
            val hasRecentPing = (System.currentTimeMillis() - lastCamPingTimestamp) < 15000L
            val hasRecentFrame = (System.currentTimeMillis() - latestFrameTimestamp) < 30000L
            val hasActiveSockets = server?.connections?.isNotEmpty() == true
            return hasCamConn || hasRecentPing || (hasActiveSockets && hasRecentFrame)
        }

        fun triggerCameraCapture() {
            val server = instance?.webSocketServer
            if (server?.camConnection != null && server.camConnection?.isOpen == true) {
                server.camConnection?.send("CAPTURE_NOW")
                Log.d("LocalHardwareHub", "Sent CAPTURE_NOW directly to ESP32-CAM")
            } else {
                server?.broadcast("CAPTURE_NOW")
                Log.d("LocalHardwareHub", "Broadcasted CAPTURE_NOW to all clients")
            }
        }

        suspend fun captureImageFromCamera(timeoutMs: Long = 10000): String? {
            val server = instance?.webSocketServer
            val now = System.currentTimeMillis()

            // Ultra-fast path: If a fresh ESP32-CAM frame arrived within the last 3.5 seconds, use it immediately!
            if (!latestCameraFrame.isNullOrBlank() && (now - latestFrameTimestamp) < 3500L) {
                Log.d("LocalHardwareHub", "Returning fresh cached ESP32-CAM frame (${now - latestFrameTimestamp}ms old)")
                return latestCameraFrame
            }

            val hasConnections = isCameraConnected()
            if (!hasConnections) {
                Log.w("LocalHardwareHub", "No camera connected to capture frame")
                // Check if we have a slightly older cached frame (within 15s)
                if (!latestCameraFrame.isNullOrBlank() && (now - latestFrameTimestamp) < 15000L) {
                    Log.i("LocalHardwareHub", "Using cached frame from recently seen camera (${now - latestFrameTimestamp}ms old)")
                    return latestCameraFrame
                }
                return null
            }

            val deferred = CompletableDeferred<String?>()
            pendingCameraCapture = deferred

            triggerCameraCapture()

            return try {
                val frame = withTimeout(timeoutMs) {
                    deferred.await()
                }
                if (!frame.isNullOrBlank()) {
                    frame
                } else if (!latestCameraFrame.isNullOrBlank() && (System.currentTimeMillis() - latestFrameTimestamp) < 25000L) {
                    Log.w("LocalHardwareHub", "Capture was empty, falling back to recent cached frame")
                    latestCameraFrame
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.w("LocalHardwareHub", "captureImageFromCamera timed out after ${timeoutMs}ms", e)
                // If live capture timed out, return recent cached frame if available
                if (!latestCameraFrame.isNullOrBlank() && (System.currentTimeMillis() - latestFrameTimestamp) < 25000L) {
                    Log.i("LocalHardwareHub", "Falling back to recent cached ESP32-CAM frame after timeout (${System.currentTimeMillis() - latestFrameTimestamp}ms old)")
                    latestCameraFrame
                } else {
                    null
                }
            } finally {
                if (pendingCameraCapture === deferred) {
                    pendingCameraCapture = null
                }
            }
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
            .setSmallIcon(android.R.drawable.stat_notify_sync)
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

        var camConnection: WebSocket? = null
        var micConnection: WebSocket? = null

        private val audioBuffer = ByteArrayOutputStream()
        private var isRecording = false
        private var lastAudioDispatchTime: Long = 0L

        private val camFrameBuffer = ByteArrayOutputStream()
        private var isReceivingCamImage = false

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            Log.d("LocalHardwareHub", "ESP32 Connected: ${conn.remoteSocketAddress}")
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            Log.d("LocalHardwareHub", "ESP32 Disconnected: ${conn.remoteSocketAddress}")
            if (conn == camConnection) {
                camConnection = null
                isReceivingCamImage = false
                camFrameBuffer.reset()
            }
            if (conn == micConnection) {
                micConnection = null
                isRecording = false
                audioBuffer.reset()
            }
        }

        override fun onMessage(conn: WebSocket, message: String) {
            when {
                message == "START" -> {
                    micConnection = conn
                    isRecording = true
                    audioBuffer.reset()
                    Log.d("LocalHardwareHub", "MIC started recording PCM stream")
                    onEvent(HardwareIntakeEvent.MicPing)
                }
                message == "STOP" -> {
                    micConnection = conn
                    isRecording = false
                    val size = audioBuffer.size()
                    Log.d("LocalHardwareHub", "MIC stopped recording, size: $size bytes")
                    val now = System.currentTimeMillis()
                    if (size >= 12000 && (now - lastAudioDispatchTime) > 1500L) {
                        lastAudioDispatchTime = now
                        onEvent(HardwareIntakeEvent.PcmAudioReceived(audioBuffer.toByteArray()))
                    } else if (size < 12000) {
                        Log.d("LocalHardwareHub", "Discarded short audio buffer ($size bytes < 12000 bytes)")
                    } else {
                        Log.d("LocalHardwareHub", "Ignored rapid audio dispatch — cooldown active")
                    }
                    audioBuffer.reset()
                    onEvent(HardwareIntakeEvent.MicPing)
                }
                message.contains("ESP32_MIC") || message.contains("MIC_PING") -> {
                    micConnection = conn
                    lastMicPingTimestamp = System.currentTimeMillis()
                    onEvent(HardwareIntakeEvent.MicPing)
                }
                message.contains("ESP32_CAM") -> {
                    camConnection = conn
                    lastCamPingTimestamp = System.currentTimeMillis()
                    onEvent(HardwareIntakeEvent.CamPing)
                }
                message.contains("CAM_IMAGE_END") -> {
                    isReceivingCamImage = false
                    camConnection = conn
                    lastCamPingTimestamp = System.currentTimeMillis()
                    if (camFrameBuffer.size() > 0) {
                        val base64 = Base64.encodeToString(camFrameBuffer.toByteArray(), Base64.NO_WRAP)
                        val dataUri = "data:image/jpeg;base64,$base64"
                        latestCameraFrame = dataUri
                        latestFrameTimestamp = System.currentTimeMillis()
                        onEvent(HardwareIntakeEvent.CameraFrameReceived(dataUri))
                        pendingCameraCapture?.complete(dataUri)
                        camFrameBuffer.reset()
                        Log.d("LocalHardwareHub", "Assembled complete ESP32-CAM frame: ${base64.length} chars")
                    } else {
                        pendingCameraCapture?.complete(null)
                    }
                    onEvent(HardwareIntakeEvent.CamPing)
                }
                message.contains("CAM_IMAGE") -> {
                    isReceivingCamImage = true
                    camConnection = conn
                    lastCamPingTimestamp = System.currentTimeMillis()
                    camFrameBuffer.reset()
                    Log.d("LocalHardwareHub", "Starting CAM_IMAGE reception...")
                    onEvent(HardwareIntakeEvent.CamPing)
                }
                message.contains("CAM_ERROR") -> {
                    Log.w("LocalHardwareHub", "ESP32-CAM reported camera error: $message")
                    pendingCameraCapture?.complete(null)
                    onEvent(HardwareIntakeEvent.CamPing)
                }
                message.contains("sensors") -> {
                    micConnection = conn
                    lastMicPingTimestamp = System.currentTimeMillis()
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
            if (conn == camConnection) {
                camFrameBuffer.write(bytes)
            } else if (conn == micConnection) {
                audioBuffer.write(bytes)
            } else {
                if (isReceivingCamImage && !isRecording) {
                    camConnection = conn
                    camFrameBuffer.write(bytes)
                } else if (isRecording) {
                    micConnection = conn
                    audioBuffer.write(bytes)
                }
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
