package com.visionaid.app.data.model

import com.google.gson.annotations.SerializedName

data class HealthResponse(
    val micOnline: Boolean,
    val camOnline: Boolean,
    val hardwareMode: String?,
    val ultrasonicDist: Float?,
    val pirMotion: Boolean?
)

data class LoginRequest(
    val username: String,
    val password: String
)

data class UserInfo(
    val username: String,
    val emergencyContacts: List<FamilyMember>? = null
)

data class AuthResponse(
    val success: Boolean?,
    val message: String?,
    val user: UserInfo?,
    val name: String?
)

data class ChatMessageItem(
    val role: String,
    val content: String
)

data class ChatRequest(
    val message: String,
    val persona: String? = null,
    val history: List<ChatMessageItem>? = null,
    val username: String? = null
)

data class ChatResponse(
    val response: String?,
    val model: String?,
    val source: String?
)

data class ClassifyRequest(
    val text: String
)

data class ClassifyResponse(
    val intent: String,
    val destination: String?,
    val source: String?
)

data class HardwareCameraRequest(
    val prompt: String = ""
)

data class HardwareCameraResponse(
    val description: String?,
    val image: String?,
    val qrUrl: String?,
    val source: String?,
    val captureAge: Long?
)

data class VisionRequest(
    val image: String,
    val prompt: String = "",
    val source: String = "phone",
    val username: String? = null
)

data class VisionResponse(
    val description: String?,
    val image: String?,
    val qrUrl: String?,
    val model: String?
)

data class GeocodeRequest(
    val query: String
)

data class GeocodeResponse(
    val latitude: Double?,
    val longitude: Double?,
    val address: String?
)

data class FamilyMember(
    val id: String?,
    val name: String,
    val phone: String
)

data class SosRequest(
    val latitude: Double,
    val longitude: Double,
    val username: String
)

data class SseAudioResultData(
    val transcript: String,
    val text: String
)
