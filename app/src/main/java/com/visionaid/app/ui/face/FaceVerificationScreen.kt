package com.visionaid.app.ui.face

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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HelpOutline
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
import com.visionaid.app.data.face.FaceMemoryRepository
import com.visionaid.app.data.face.FaceProfile
import com.visionaid.app.util.TtsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FaceVerificationScreen(
    repository: FaceMemoryRepository,
    ttsManager: TtsManager,
    vibrator: Vibrator,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var matchedProfile by remember { mutableStateOf<FaceProfile?>(null) }
    var matchScore by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("Point camera at a face...") }
    var isChecking by remember { mutableStateOf(false) }

    var lastTtsTimestamp by remember { mutableLongStateOf(0L) }
    var lastAnnouncedName by remember { mutableStateOf("") }

    val ovalColor by animateColorAsState(
        targetValue = when {
            matchedProfile != null -> Color(0xFF00E676) // Green match
            statusText.contains("Unknown") -> Color(0xFFFF9100) // Orange unknown
            else -> Color(0xFF40C4FF) // Cyan looking
        },
        label = "verifyOvalColor"
    )

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // CameraX Preview View
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                val executor = Executors.newSingleThreadExecutor()

                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    var lastCheckTime = 0L

                    val imageAnalyzer = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()
                        .also { analyzer ->
                            analyzer.setAnalyzer(executor) { imageProxy ->
                                val now = System.currentTimeMillis()
                                if (isChecking || now - lastCheckTime < 800) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }

                                lastCheckTime = now
                                isChecking = true

                                try {
                                    val rotation = imageProxy.imageInfo.rotationDegrees
                                    val bitmap = imageProxy.toBitmap()
                                    val rotatedBitmap = if (rotation != 0) {
                                        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                    } else {
                                        bitmap
                                    }

                                    coroutineScope.launch {
                                        val match = repository.recognizeFaceInBitmap(rotatedBitmap)
                                        withContext(Dispatchers.Main) {
                                            if (match != null) {
                                                matchedProfile = match.first
                                                matchScore = match.second
                                                statusText = "Matched: ${match.first.name} (${(match.second * 100).toInt()}%)"

                                                val announceNow = System.currentTimeMillis()
                                                if (lastAnnouncedName != match.first.name || announceNow - lastTtsTimestamp > 4000) {
                                                    lastTtsTimestamp = announceNow
                                                    lastAnnouncedName = match.first.name
                                                    ttsManager.speak("${match.first.name} recognized.")
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                        vibrator.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
                                                    }
                                                }
                                            } else {
                                                matchedProfile = null
                                                matchScore = 0f
                                                statusText = "No registered face recognized"
                                            }
                                            isChecking = false
                                        }
                                    }
                                } catch (e: Exception) {
                                    isChecking = false
                                } finally {
                                    imageProxy.close()
                                }
                            }
                        }

                    val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalyzer)
                    } catch (e: Exception) {
                        Log.e("FaceVerify", "Camera binding error", e)
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

            clipPath(ovalPath, clipOp = ClipOp.Difference) {
                drawRect(Color.Black.copy(alpha = 0.60f))
            }
        }

        // Oval Guide Border
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = (-30).dp)
                .fillMaxWidth(0.72f)
                .fillMaxHeight(0.48f)
                .border(
                    width = if (matchedProfile != null) 4.dp else 2.5.dp,
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
                text = "Live Face Test",
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

        // Bottom Result Box
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (matchedProfile != null) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Matched",
                            tint = Color(0xFF00E676),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = matchedProfile!!.name,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Biometric Match: ${(matchScore * 100).toInt()}% Confidence",
                                color = Color(0xFF00E676),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else {
                        Icon(
                            Icons.Default.HelpOutline,
                            contentDescription = "Searching",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = statusText,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Align person's face inside the oval frame",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
