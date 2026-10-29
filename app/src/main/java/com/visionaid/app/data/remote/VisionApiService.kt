package com.visionaid.app.data.remote

import com.visionaid.app.data.model.*
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface VisionApiService {
    @GET("api/pi/health")
    suspend fun getHealth(): Response<HealthResponse>

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<AuthResponse>

    @POST("api/auth/signup")
    suspend fun signup(@Body body: LoginRequest): Response<AuthResponse>

    @POST("api/ai/chat")
    suspend fun chat(@Body body: ChatRequest): Response<ResponseBody>

    @POST("api/ai/classify")
    suspend fun classify(@Body body: ClassifyRequest): Response<ClassifyResponse>

    @POST("api/pi/trigger-hardware-camera")
    suspend fun triggerHardwareCamera(@Body body: HardwareCameraRequest): Response<HardwareCameraResponse>

    @POST("api/vision")
    suspend fun analyzeVision(@Body body: VisionRequest): Response<VisionResponse>

    @POST("api/navigation/geocode")
    suspend fun geocode(@Body body: GeocodeRequest): Response<GeocodeResponse>

    @GET("api/family/members")
    suspend fun getFamilyMembers(): Response<List<FamilyMember>>

    @POST("api/family/add")
    suspend fun addFamilyMember(@Body body: FamilyMember): Response<Map<String, Any>>

    @POST("api/sos/send")
    suspend fun sendSos(@Body body: SosRequest): Response<Map<String, Any>>
}
