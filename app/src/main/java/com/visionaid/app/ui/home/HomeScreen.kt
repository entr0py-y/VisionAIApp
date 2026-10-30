package com.visionaid.app.ui.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visionaid.app.ui.theme.LightMutedText
import com.visionaid.app.ui.theme.SuccessColor

fun Modifier.bounceClick(
    onClick: () -> Unit
): Modifier = composed {
    var isPressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "card_bounce"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    isPressed = true
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    tryAwaitRelease()
                    isPressed = false
                },
                onTap = {
                    onClick()
                }
            )
        }
}

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onAvatarClick: () -> Unit,
    onOpenMobility: () -> Unit,
    onOpenFamily: () -> Unit,
    onOpenAiChat: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        HomeHeader(username = uiState.currentUser, isDarkTheme = uiState.isDarkTheme, onAvatarClick = onAvatarClick)
        WidgetsRow(uiState = uiState)
        DeviceStatusSection(isCamOnline = uiState.isCamOnline, isMicOnline = uiState.isMicOnline)
        FeaturesRow(onOpenMobility, onOpenFamily, onOpenAiChat)
    }
}

@Composable
fun HomeHeader(username: String, isDarkTheme: Boolean, onAvatarClick: () -> Unit) {
    val headerBg = if (isDarkTheme) Color.White else Color(0xFF1A1A1A)
    val headerText = if (isDarkTheme) Color(0xFF1A1A1A) else Color.White
    val isLoggedIn = username != "User"
    
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = headerBg),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Hello,", color = headerText.copy(alpha = 0.7f), fontSize = 16.sp)
                Text(username, color = headerText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!isLoggedIn) {
                    Button(
                        onClick = onAvatarClick,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDarkTheme) Color(0xFF1A1A1A) else Color.White,
                            contentColor = if (isDarkTheme) Color.White else Color(0xFF1A1A1A)
                        ),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Login", fontWeight = FontWeight.Bold)
                    }
                } else {
                    IconButton(
                        onClick = onAvatarClick,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(headerText.copy(alpha = 0.1f))
                    ) {
                        Icon(Icons.Default.Person, contentDescription = "Profile", tint = headerText)
                    }
                }
            }
        }
    }
}

@Composable
fun WidgetsRow(uiState: HomeUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status Widget
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.weight(1f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Default.Wifi, contentDescription = "Connection", tint = SuccessColor)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Connected", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Online", color = LightMutedText, fontSize = 14.sp)
            }
        }

        // Sensors Widget
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.weight(1f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Default.Sensors, contentDescription = "Sensors", tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Active", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                val distText = uiState.ultrasonicDistance?.let { "Dist: ${it}cm" } ?: "Clear"
                val motionText = if (uiState.pirMotion == true) " • Motion!" else ""
                Text("$distText$motionText", color = LightMutedText, fontSize = 14.sp)
            }
        }
    }
}

@Composable
fun DeviceStatusSection(isCamOnline: Boolean, isMicOnline: Boolean) {
    Column {
        Text("Devices", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LightMutedText)
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                DeviceRow("ESP32-CAM", Icons.Default.CameraAlt, isCamOnline)
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = LightMutedText.copy(alpha = 0.2f))
                DeviceRow("ESP32-MIC", Icons.Default.Mic, isMicOnline)
            }
        }
    }
}

@Composable
fun DeviceRow(name: String, icon: ImageVector, isOnline: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = name, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(name, fontWeight = FontWeight.Medium, fontSize = 16.sp)
        }
        PulsingDot(isOnline = isOnline)
    }
}

@Composable
fun PulsingDot(isOnline: Boolean) {
    val targetColor = if (isOnline) SuccessColor else Color(0xFF8E8E93)
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isOnline) 0.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(targetColor.copy(alpha = alpha))
    )
}

@Composable
fun FeaturesRow(onOpenMobility: () -> Unit, onOpenFamily: () -> Unit, onOpenAiChat: () -> Unit) {
    Column {
        Text("Features", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = LightMutedText)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FeatureActionCard("Mobility", Icons.Default.Explore, onOpenMobility, Modifier.weight(1f))
            FeatureActionCard("Family", Icons.Default.Favorite, onOpenFamily, Modifier.weight(1f))
            FeatureActionCard("AI Chat", Icons.Default.Chat, onOpenAiChat, Modifier.weight(1f))
        }
    }
}

@Composable
fun FeatureActionCard(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier.bounceClick(onClick)
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = title, tint = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}
