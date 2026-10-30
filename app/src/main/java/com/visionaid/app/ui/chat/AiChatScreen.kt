package com.visionaid.app.ui.chat

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visionaid.app.util.SilentCameraManager
import kotlinx.coroutines.launch

@Composable
fun AiChatScreen(
    viewModel: ChatViewModel, 
    onClose: () -> Unit,
    isDarkTheme: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }

    // Dock background colors: #E8E8E3 (Light) / #161616 (Dark)
    val dockColor = if (isDarkTheme) Color(0xFF161616) else Color(0xFFE8E8E3)
    val inputBarColor = if (isDarkTheme) Color(0xFF252525) else Color(0xFFFFFFFF)
    val contentColor = if (isDarkTheme) Color.White else Color.Black

    // Observe Chat Events for Silent CameraX Frame Capture
    LaunchedEffect(Unit) {
        viewModel.chatEvents.collect { event ->
            when (event) {
                is ChatEvent.TriggerPhoneCamera -> {
                    coroutineScope.launch {
                        val base64Frame = SilentCameraManager.captureFrameToTempFile(context, lifecycleOwner)
                        if (!base64Frame.isNullOrBlank()) {
                            viewModel.analyzeLocalPhoneImage(base64Frame, event.prompt)
                        } else {
                            viewModel.sendMessage(event.prompt)
                        }
                    }
                }
                else -> {}
            }
        }
    }

    // Smooth Auto-scroll on new message
    LaunchedEffect(uiState.messages.size, uiState.isProcessing) {
        if (uiState.messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }

    // Exact Insets Calculation: Flush right above soft keyboard when open, or above gesture bar when closed
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = if (imeBottom > 0.dp) imeBottom else navBottom

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        // Header with smooth Back button ripple
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Vision AID Assistant", 
                fontSize = 22.sp, 
                fontWeight = FontWeight.Bold, 
                color = MaterialTheme.colorScheme.onBackground
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onBackground)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))

        // Message List with Item Animations
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uiState.messages) { message ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(animationSpec = tween(250)) + slideInVertically(
                        initialOffsetY = { 40 },
                        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy)
                    )
                ) {
                    MessageBubble(message)
                }
            }
            if (uiState.isProcessing) {
                item {
                    TypingIndicator()
                }
            }
        }

        // Bottom Message Bar: Flush on top of soft keyboard or gesture bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = bottomPadding),
            color = dockColor,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Camera Button (Prioritizes ESP32-CAM via Local Hub, fallback to phone camera)
                IconButton(
                    onClick = {
                        viewModel.triggerCameraDirectly()
                    }
                ) {
                    Icon(
                        Icons.Default.CameraAlt, 
                        contentDescription = "Camera Scan", 
                        tint = contentColor
                    )
                }

                // Text Input Bar
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Ask something...", color = contentColor.copy(alpha = 0.5f)) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = inputBarColor,
                        unfocusedContainerColor = inputBarColor,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = contentColor,
                        unfocusedTextColor = contentColor
                    ),
                    maxLines = 3
                )

                // Mic Button (Inline with glowing pulsing red ring when recording)
                val isRecording = uiState.isRecording
                val infiniteTransition = rememberInfiniteTransition()
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = if (isRecording) 1.2f else 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "mic_pulse"
                )

                IconButton(
                    onClick = {
                        if (uiState.isRecording) {
                            viewModel.stopListening()
                        } else {
                            viewModel.startListening(context)
                        }
                    },
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = if (isRecording) pulseScale else 1f
                            scaleY = if (isRecording) pulseScale else 1f
                        }
                        .background(
                            color = if (isRecording) Color(0xFFE53935) else Color.Transparent,
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        Icons.Default.Mic, 
                        contentDescription = "Voice Input", 
                        tint = if (isRecording) Color.White else contentColor
                    )
                }

                // Send Button with Vision Query Interception
                IconButton(
                    onClick = {
                        val textToSend = inputText.trim()
                        inputText = ""
                        viewModel.sendMessage(textToSend)
                    },
                    enabled = inputText.isNotBlank() && !uiState.isProcessing
                ) {
                    Icon(
                        Icons.Default.Send, 
                        contentDescription = "Send",
                        tint = if (inputText.isNotBlank()) contentColor else contentColor.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.sender == "user"
    
    // Robust Base64 Image Decoder into Bitmap
    val bitmap = remember(message.imageUrl) {
        if (!message.imageUrl.isNullOrBlank()) {
            try {
                val raw = message.imageUrl
                val cleanB64 = if (raw.contains(",")) raw.substringAfter(",") else raw
                val bytes = Base64.decode(cleanB64.trim(), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        } else null
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 290.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 20.dp,
                        topEnd = 20.dp,
                        bottomStart = if (isUser) 20.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 20.dp
                    )
                )
                .background(if (isUser) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Analyzed image frame",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .padding(bottom = 8.dp)
                )
            }
            Text(
                text = message.text,
                color = if (isUser) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                lineHeight = 22.sp
            )
        }
    }
}

@Composable
fun TypingIndicator() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        val infiniteTransition = rememberInfiniteTransition()
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "typing"
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)))
                }
            }
        }
    }
}
