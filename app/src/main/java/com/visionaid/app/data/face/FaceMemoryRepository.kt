package com.visionaid.app.data.face

import android.content.Context
import android.graphics.Bitmap
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

    // ML Kit Face Detector for static/stream image face extraction
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
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
                Log.d(tag, "Loaded ${list.size} face profiles from memory.")
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

    /**
     * Enrolls a person with their name, embedding vector, and an optional face thumbnail.
     * Enforces a maximum of 5 stored people.
     */
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
     * Given any frame (from ESP32-CAM or phone camera), detects faces and attempts
     * to recognize known people from memory.
     * Returns Pair(MatchedProfile, SimilarityScore) or null if no match / no face found.
     */
    suspend fun recognizeFaceInBitmap(bitmap: Bitmap): Pair<FaceProfile, Float>? = withContext(Dispatchers.Default) {
        val saved = _profiles.value
        if (saved.isEmpty()) return@withContext null

        val faces = detectFacesInBitmap(bitmap)
        if (faces.isEmpty()) return@withContext null

        // Pick the largest / most prominent face
        val primaryFace = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return@withContext null
        val cropped = cropFaceBitmap(bitmap, primaryFace.boundingBox) ?: return@withContext null

        val embedding = recognitionManager.extractEmbedding(cropped) ?: return@withContext null

        var bestMatch: FaceProfile? = null
        var highestScore = 0f

        for (profile in saved) {
            val score = recognitionManager.cosineSimilarity(embedding, profile.embedding.toFloatArray())
            Log.d(tag, "Comparing with ${profile.name}: score = $score")
            if (score > highestScore && score >= FaceRecognitionManager.SIMILARITY_THRESHOLD) {
                highestScore = score
                bestMatch = profile
            }
        }

        if (bestMatch != null) {
            Log.i(tag, "Recognized ${bestMatch.name} with similarity $highestScore")
            Pair(bestMatch, highestScore)
        } else {
            null
        }
    }

    private suspend fun detectFacesInBitmap(bitmap: Bitmap): List<Face> = suspendCancellableCoroutine { cont ->
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (cont.isActive) cont.resume(faces)
                }
                .addOnFailureListener { e ->
                    Log.w(tag, "MLKit face detection failed: ${e.message}")
                    if (cont.isActive) cont.resume(emptyList())
                }
        } catch (e: Exception) {
            Log.e(tag, "Error processing image for face detection: ${e.message}")
            if (cont.isActive) cont.resume(emptyList())
        }
    }

    private fun cropFaceBitmap(source: Bitmap, box: Rect): Bitmap? {
        return try {
            // Add a 15% margin around the face box for better facial context
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
