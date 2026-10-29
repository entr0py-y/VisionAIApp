package com.visionaid.app.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

object SilentCameraCapture {

    suspend fun captureFrame(context: Context, lifecycleOwner: LifecycleOwner): String? = suspendCancellableCoroutine { continuation ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

                val executor = ContextCompat.getMainExecutor(context)
                imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()

                            // Compress Bitmap to max 1024px
                            val originalBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            val scaledBitmap = if (originalBitmap != null && (originalBitmap.width > 1024 || originalBitmap.height > 1024)) {
                                val scale = 1024f / Math.max(originalBitmap.width, originalBitmap.height)
                                Bitmap.createScaledBitmap(
                                    originalBitmap,
                                    (originalBitmap.width * scale).toInt(),
                                    (originalBitmap.height * scale).toInt(),
                                    true
                                )
                            } else originalBitmap

                            val base64 = if (scaledBitmap != null) {
                                val outputStream = ByteArrayOutputStream()
                                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                                Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
                            } else null

                            cameraProvider.unbindAll()
                            if (continuation.isActive) continuation.resume(base64)
                        } catch (e: Exception) {
                            Log.e("SilentCameraCapture", "Error encoding camera frame", e)
                            cameraProvider.unbindAll()
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("SilentCameraCapture", "Error capturing photo", exception)
                        cameraProvider.unbindAll()
                        if (continuation.isActive) continuation.resume(null)
                    }
                })
            } catch (e: Exception) {
                Log.e("SilentCameraCapture", "CameraX initialization failed", e)
                if (continuation.isActive) continuation.resume(null)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
