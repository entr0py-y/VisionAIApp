package com.visionaid.app.ui.face

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.visionaid.app.data.face.FaceMemoryRepository
import com.visionaid.app.data.face.FaceRecognitionManager
import com.visionaid.app.util.TtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.abs

enum class AlignmentState {
    NO_FACE,
    TOO_FAR,
    TOO_CLOSE,
    TILTED,
    ALIGNED,
    CAPTURING,
    SUCCESS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceEnrollmentScreen(
    personName: String,
    repository: FaceMemoryRepository,
    recognitionManager: FaceRecognitionManager,
    ttsManager: TtsManager,
    vibrator: Vibrator,
    onEnrolled: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var alignmentState by remember { mutableStateOf(AlignmentState.NO_FACE) }
    var statusMessage by remember { mutableStateOf("Place face inside the frame") }
    var isProcessingCapture by remember { mutableStateOf(false) }

    // TTS guidance throttler
    var lastTtsTimestamp by remember { mutableLongStateOf(0L) }
    fun speakGuidance(text: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (force || now - lastTtsTimestamp > 3000) {
            lastTtsTimestamp = now
            ttsManager.speak(text)
        }
    }

    LaunchedEffect(Unit) {
        speakGuidance("Please position $personName's face inside the oval frame.", force = true)
    }

    val ovalColor by animateColorAsState(
        targetValue = when (alignmentState) {
            AlignmentState.ALIGNED, AlignmentState.CAPTURING, AlignmentState.SUCCESS -> Color(0xFF00E676) // Glowing green
            AlignmentState.TOO_FAR, AlignmentState.TOO_CLOSE, AlignmentState.TILTED -> Color(0xFFFFD600) // Amber/Yellow
            else -> Color(0xFF40C4FF) // Soft cyan
        },
        label = "ovalColor"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // CameraX Preview View
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                val executor = Executors.newSingleThreadExecutor()

                val faceDetector = FaceDetection.getClient(
                    FaceDetectorOptions.Builder()
                        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                        .build()
                )

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    var alignedFramesCount = 0

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { analyzer ->
                            analyzer.setAnalyzer(executor) { imageProxy ->
                                if (isProcessingCapture) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }

                                try {
                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                    val bitmap = imageProxy.toBitmap()
                                    val rotatedBitmap = if (rotation != 0) {
                                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                    } else {
                                        bitmap
                                    }

                                    val inputImage = InputImage.fromBitmap(rotatedBitmap, 0)
                                    faceDetector.process(inputImage)
                                        .addOnSuccessListener { faces ->
                                            if (faces.isEmpty()) {
                                                alignedFramesCount = 0
                                                alignmentState = AlignmentState.NO_FACE
                                                statusMessage = "Looking for face..."
                                            } else {
                                                val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }!!
                                                val imgWidth = rotatedBitmap.width.toFloat()
                                                val imgHeight = rotatedBitmap.height.toFloat()

                                                val box = face.boundingBox
                                                val faceWidthRatio = box.width() / imgWidth
                                                val faceCenterX = box.centerX() / imgWidth
                                                val faceCenterY = box.centerY() / imgHeight

                                                val yaw = face.headEulerAngleY
                                                val pitch = face.headEulerAngleX

                                                val isFacingFront = abs(yaw) < 18f && abs(pitch) < 18f
                                                val isCentered = abs(faceCenterX - 0.5f) < 0.22f && abs(faceCenterY - 0.5f) < 0.22f

                                                if (faceWidthRatio < 0.22f) {
                                                    alignedFramesCount = 0
                                                    alignmentState = AlignmentState.TOO_FAR
                                                    statusMessage = "Move a little closer"
                                                    speakGuidance("Move a little closer")
                                                } else if (faceWidthRatio > 0.75f) {
                                                    alignedFramesCount = 0
                                                    alignmentState = AlignmentState.TOO_CLOSE
                                                    statusMessage = "Move slightly back"
                                                    speakGuidance("Move slightly back")
                                                } else if (!isFacingFront) {
                                                    alignedFramesCount = 0
                                                    alignmentState = AlignmentState.TILTED
                                                    statusMessage = "Look straight ahead"
                                                    speakGuidance("Look straight ahead")
                                                } else if (!isCentered) {
                                                    alignedFramesCount = 0
                                                    alignmentState = AlignmentState.TILTED
                                                    statusMessage = "Center face in the oval"
                                                } else {
                                                    // Face is properly aligned!
                                                    alignedFramesCount++
                                                    if (alignedFramesCount >= 3 && !isProcessingCapture) {
                                                        // Auto-capture triggered!
                                                        isProcessingCapture = true
                                                        alignmentState = AlignmentState.CAPTURING
                                                        statusMessage = "Hold still, capturing..."
                                                        speakGuidance("Hold still, scanning...", force = true)

                                                        coroutineScope.launch {
                                                            // Trigger haptic vibration
                                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                                vibrator.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
                                                            } else {
                                                                @Suppress("DEPRECATION")
                                                                vibrator.vibrate(180)
                                                            }

                                                            // Crop face with padding
                                                            val marginX = (box.width() * 0.15f).toInt()
                                                            val marginY = (box.height() * 0.15f).toInt()
                                                            val left = (box.left - marginX).coerceAtLeast(0)
                                                            val top = (box.top - marginY).coerceAtLeast(0)
                                                            val right = (box.right + marginX).coerceAtMost(rotatedBitmap.width)
                                                            val bottom = (box.bottom + marginY).coerceAtMost(rotatedBitmap.height)

                                                            val croppedFace = Bitmap.createBitmap(
                                                                rotatedBitmap, left, top, right - left, bottom - top
                                                            )

                                                            val embedding = recognitionManager.extractEmbedding(croppedFace)
                                                            if (embedding != null) {
                                                                val success = repository.addProfile(personName, embedding, croppedFace)
                                                                withContext(Dispatchers.Main) {
                                                                    if (success) {
                                                                        alignmentState = AlignmentState.SUCCESS
                                                                        statusMessage = "Enrolled $personName successfully!"
                                                                        speakGuidance("$personName added to memory successfully!", force = true)
                                                                        delay(1200)
                                                                        onEnrolled()
                                                                    } else {
                                                                        statusMessage = "Limit reached (max 5 people)"
                                                                        speakGuidance("Memory full. Maximum 5 people allowed.", force = true)
                                                                        delay(2000)
                                                                        onDismiss()
                                                                    }
                                                                }
                                                            } else {
                                                                isProcessingCapture = false
                                                                alignedFramesCount = 0
                                                            }
                                                        }
                                                    } else {
                                                        alignmentState = AlignmentState.ALIGNED
                                                        statusMessage = "Perfect! Hold still..."
                                                    }
                                                }
                                            }
                                        }
                                        .addOnCompleteListener {
                                            imageProxy.close()
                                        }
                                } catch (e: Exception) {
                                    imageProxy.close()
                                }
                            }
                        }

                    val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
                    } catch (e: Exception) {
                        Log.e("FaceEnrollment", "Camera binding failed: ${e.message}")
                    }
                }, ContextCompat.getMainExecutor(ctx))

                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Oval Viewfinder Mask Overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            val ovalWidth = canvasWidth * 0.72f
            val ovalHeight = canvasHeight * 0.48f
            val ovalLeft = (canvasWidth - ovalWidth) / 2f
            val ovalTop = (canvasHeight - ovalHeight) / 2f - 30.dp.toPx()

            val ovalPath = Path().apply {
                addOval(Rect(Offset(ovalLeft, ovalTop), Size(ovalWidth, ovalHeight)))
            }

            // Darken outside the oval
            clipPath(ovalPath, clipOp = ClipOp.Difference) {
                drawRect(Color.Black.copy(alpha = 0.65f))
            }
        }

        // Oval Guide Border (Drawn centered)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-30).dp)
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.48f)
                .border(
                    width = if (alignmentState == AlignmentState.ALIGNED || alignmentState == AlignmentState.CAPTURING) 4.dp else 2.5.dp,
                    color = ovalColor,
                    shape = RoundedCornerShape(100)
                )
        )

        // Top Navigation Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            Text(
                text = "Enroll: $personName",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            IconButton(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Cameraswitch, contentDescription = "Switch Camera", tint = Color.White)
            }
        }

        // Bottom Status Box
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.8f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (alignmentState == AlignmentState.CAPTURING || isProcessingCapture) {
                        CircularProgressIndicator(
                            color = Color(0xFF00E676),
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Text(
                        text = statusMessage,
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Face must be centered, upright, and well-lit",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
