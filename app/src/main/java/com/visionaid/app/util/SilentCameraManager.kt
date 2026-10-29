package com.visionaid.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.util.Base64
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume

object SilentCameraManager {

    suspend fun captureFrameToTempFile(context: Context, lifecycleOwner: LifecycleOwner): String? = suspendCancellableCoroutine { continuation ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // Ultra-low latency configuration: 640x480 resolution for lightning-fast capture & upload
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetResolution(Size(640, 480))
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

                val tempFile = File.createTempFile("temp_vision_", ".jpg", context.cacheDir)
                val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

                val executor = ContextCompat.getMainExecutor(context)
                imageCapture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        try {
                            val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)

                            // Read EXIF Orientation tag to fix -90 / 270 degree rotation
                            val exif = try {
                                ExifInterface(tempFile.absolutePath)
                            } catch (e: Exception) {
                                null
                            }
                            
                            val orientation = exif?.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_UNDEFINED
                            ) ?: ExifInterface.ORIENTATION_UNDEFINED

                            val rotationAngle = when (orientation) {
                                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                                ExifInterface.ORIENTATION_UNDEFINED -> 90f // Default portrait correction for back camera
                                else -> 0f
                            }

                            val matrix = Matrix()
                            if (rotationAngle != 0f) {
                                matrix.postRotate(rotationAngle)
                            }

                            val rotatedBitmap = if (bitmap != null) {
                                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                            } else null

                            // Scale down to max 640px for ultra-low network latency
                            val maxDim = 640
                            val scaledBitmap = if (rotatedBitmap != null) {
                                val scale = maxDim.toFloat() / Math.max(rotatedBitmap.width, rotatedBitmap.height)
                                if (scale < 1f) {
                                    Bitmap.createScaledBitmap(
                                        rotatedBitmap,
                                        (rotatedBitmap.width * scale).toInt(),
                                        (rotatedBitmap.height * scale).toInt(),
                                        true
                                    )
                                } else rotatedBitmap
                            } else null

                            // Compress at 60% quality -> ultra-compact Base64 payload (~20KB)
                            val base64 = if (scaledBitmap != null) {
                                val outputStream = ByteArrayOutputStream()
                                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
                                Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                            } else null

                            tempFile.delete()
                            cameraProvider.unbindAll()

                            if (continuation.isActive) continuation.resume(base64)
                        } catch (e: Exception) {
                            Log.e("SilentCameraManager", "Error processing temp file image", e)
                            tempFile.delete()
                            cameraProvider.unbindAll()
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("SilentCameraManager", "ImageCapture failed", exception)
                        tempFile.delete()
                        cameraProvider.unbindAll()
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            } catch (e: Exception) {
                Log.e("SilentCameraManager", "CameraX binding error", e)
                if (continuation.isActive) continuation.resume(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
