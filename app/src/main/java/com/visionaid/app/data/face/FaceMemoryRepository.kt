package com.visionaid.app.data.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class FaceMemoryRepository(
    context: Context,
    private val recognitionManager: FaceRecognitionManager
) {
    private val tag = "FaceMemoryRepo"
    private val prefs = context.getSharedPreferences("visionaid_face_memory", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _profiles = MutableStateFlow<List<FaceProfile>>(emptyList())
    val profiles: StateFlow<List<FaceProfile>> = _profiles.asStateFlow()

    // ML Kit Face Detector with 5% minFaceSize to capture faces at distance
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.05f)
            .build()
    )

    init {
        loadProfiles()
    }

    private fun loadProfiles() {
        val json = prefs.getString("saved_profiles", null)
        if (!json.isNullOrBlank()) {
            try {
                val type = object : TypeToken<List<FaceProfile>>() {}.type
                val list: List<FaceProfile> = gson.fromJson(json, type) ?: emptyList()
                _profiles.value = list
                Log.i(tag, "Loaded ${list.size} face profiles from memory.")
            } catch (e: Exception) {
                Log.e(tag, "Failed to parse profiles: ${e.message}")
            }
        }
    }

    private fun saveProfiles(list: List<FaceProfile>) {
        _profiles.value = list
        val json = gson.toJson(list)
        prefs.edit().putString("saved_profiles", json).apply()
    }

    fun addProfile(name: String, embedding: FloatArray, faceBitmap: Bitmap?): Boolean {
        val current = _profiles.value.toMutableList()
        if (current.size >= 5) {
            Log.w(tag, "Max profile capacity reached (5). Cannot add more.")
            return false
        }

        val base64Thumb = faceBitmap?.let { bmp ->
            try {
                val thumb = Bitmap.createScaledBitmap(bmp, 120, 120, true)
                val out = ByteArrayOutputStream()
                thumb.compress(Bitmap.CompressFormat.JPEG, 85, out)
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } catch (e: Exception) {
                null
            }
        }

        val profile = FaceProfile(
            name = name.trim(),
            embedding = embedding.toList(),
            photoBase64 = base64Thumb
        )
        current.add(profile)
        saveProfiles(current)
        Log.i(tag, "Enrolled face: ${profile.name} (Total: ${current.size}/5)")
        return true
    }

    fun deleteProfile(id: String) {
        val updated = _profiles.value.filter { it.id != id }
        saveProfiles(updated)
        Log.i(tag, "Deleted profile ID: $id (Remaining: ${updated.size})")
    }

    /**
     * Given any frame (from ESP32-CAM or phone camera), detects faces across all orientations
     * and attempts to recognize known people from memory.
     */
    suspend fun recognizeFaceInBitmap(bitmap: Bitmap): Pair<FaceProfile, Float>? = withContext(Dispatchers.Default) {
        val saved = _profiles.value
        Log.i(tag, "recognizeFaceInBitmap: incoming bitmap ${bitmap.width}x${bitmap.height}, enrolled profiles: ${saved.size}")
        if (saved.isEmpty()) {
            Log.w(tag, "No profiles enrolled in memory!")
            return@withContext null
        }

        // Try detecting faces at 0 degrees, then 90, 270 if needed (for rotated camera mounts)
        var faces = detectFacesInBitmap(bitmap, 0)
        var workingBitmap = bitmap

        if (faces.isEmpty()) {
            Log.d(tag, "No face at 0 deg, trying 90 deg rotation...")
            val rotated90 = rotateBitmap(bitmap, 90f)
            faces = detectFacesInBitmap(rotated90, 0)
            if (faces.isNotEmpty()) {
                workingBitmap = rotated90
            } else {
                Log.d(tag, "No face at 90 deg, trying 270 deg rotation...")
                val rotated270 = rotateBitmap(bitmap, 270f)
                faces = detectFacesInBitmap(rotated270, 0)
                if (faces.isNotEmpty()) {
                    workingBitmap = rotated270
                }
            }
        }

        Log.i(tag, "Face detection completed: found ${faces.size} faces")
        if (faces.isEmpty()) return@withContext null

        // Pick the largest / most prominent face
        val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return@withContext null
        val cropped = cropFaceBitmap(workingBitmap, primaryFace.boundingBox) ?: return@withContext null

        val embedding = recognitionManager.extractEmbedding(cropped) ?: run {
            Log.w(tag, "Failed to extract embedding from cropped face")
            return@withContext null
        }

        var bestMatch: FaceProfile? = null
        var highestScore = 0f

        // Threshold of 0.58f for cross-device camera invariance
        val matchThreshold = 0.58f

        for (profile in saved) {
            val score = recognitionManager.cosineSimilarity(embedding, profile.embedding.toFloatArray())
            Log.i(tag, "Comparing against '${profile.name}': similarity score = $score (threshold = $matchThreshold)")
            if (score > highestScore) {
                highestScore = score
                if (score >= matchThreshold) {
                    bestMatch = profile
                }
            }
        }

        if (bestMatch != null) {
            Log.i(tag, "MATCH CONFIRMED: ${bestMatch.name} with score $highestScore")
            Pair(bestMatch, highestScore)
        } else {
            Log.i(tag, "No match above threshold $matchThreshold. Highest was: $highestScore")
            null
        }
    }

    private suspend fun detectFacesInBitmap(bitmap: Bitmap, rotationDegrees: Int): List<Face> = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromBitmap(bitmap, rotationDegrees)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (cont.isActive) cont.resume(faces)
                }
                .addOnFailureListener { e ->
                    Log.w(tag, "MLKit face detection error: ${e.message}")
                    if (cont.isActive) cont.resume(emptyList())
                }
        } catch (e: Exception) {
            Log.e(tag, "Error processing image for face detection: ${e.message}")
            if (cont.isActive) cont.resume(emptyList())
        }
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun cropFaceBitmap(source: Bitmap, box: Rect): Bitmap? {
        return try {
            val marginX = (box.width() * 0.15f).toInt()
            val marginY = (box.height() * 0.15f).toInt()

            val left = (box.left - marginX).coerceAtLeast(0)
            val top = (box.top - marginY).coerceAtLeast(0)
            val right = (box.right + marginX).coerceAtMost(source.width)
            val bottom = (box.bottom + marginY).coerceAtMost(source.height)

            val width = right - left
            val height = bottom - top

            if (width > 20 && height > 20) {
                Bitmap.createBitmap(source, left, top, width, height)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to crop face: ${e.message}")
            null
        }
    }
}
