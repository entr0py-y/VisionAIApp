package com.visionaid.app.data.face

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

class FaceRecognitionManager(context: Context) {

    private val tag = "FaceRecognition"
    private var interpreter: Interpreter? = null

    companion object {
        const val INPUT_SIZE = 112
        const val EMBEDDING_SIZE = 192
        const val SIMILARITY_THRESHOLD = 0.68f // Cosine similarity threshold for a match
    }

    init {
        try {
            val modelBuffer = loadModelFile(context, "mobilefacenet.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(4)
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(tag, "MobileFaceNet model loaded successfully.")
        } catch (e: Exception) {
            Log.e(tag, "Failed to load MobileFaceNet model: ${e.message}", e)
        }
    }

    private fun loadModelFile(context: Context, modelName: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Extracts a 192-dimensional normalized facial embedding vector from a cropped face bitmap.
     */
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray? {
        val interp = interpreter ?: run {
            Log.w(tag, "Interpreter not initialized")
            return null
        }

        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
            val byteBuffer = convertBitmapToByteBuffer(scaledBitmap)
            val output = Array(1) { FloatArray(EMBEDDING_SIZE) }

            interp.run(byteBuffer, output)

            // L2-normalize the 192-d vector
            val rawEmbedding = output[0]
            normalizeEmbedding(rawEmbedding)
        } catch (e: Exception) {
            Log.e(tag, "Embedding extraction failed: ${e.message}", e)
            null
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(1 * INPUT_SIZE * INPUT_SIZE * 3 * 4) // 4 bytes per float
        byteBuffer.order(ByteOrder.nativeOrder())

        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        var pixel = 0
        for (i in 0 until INPUT_SIZE) {
            for (j in 0 until INPUT_SIZE) {
                val value = intValues[pixel++]
                // MobileFaceNet standard normalization: (val - 127.5) / 128.0
                val r = (((value shr 16) and 0xFF) - 127.5f) / 128.0f
                val g = (((value shr 8) and 0xFF) - 127.5f) / 128.0f
                val b = ((value and 0xFF) - 127.5f) / 128.0f

                byteBuffer.putFloat(r)
                byteBuffer.putFloat(g)
                byteBuffer.putFloat(b)
            }
        }
        return byteBuffer
    }

    private fun normalizeEmbedding(vector: FloatArray): FloatArray {
        var sum = 0f
        for (v in vector) {
            sum += v * v
        }
        val norm = sqrt(sum)
        if (norm > 0) {
            for (i in vector.indices) {
                vector[i] /= norm
            }
        }
        return vector
    }

    /**
     * Computes the cosine similarity between two normalized 192-d vectors.
     * Returns a value between -1.0 and 1.0 (typically 0.0 to 1.0 for faces).
     * Values >= SIMILARITY_THRESHOLD (0.68) indicate the same person.
     */
    fun cosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        if (v1.size != v2.size) return 0f
        var dot = 0f
        for (i in v1.indices) {
            dot += v1[i] * v2[i]
        }
        return dot
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
