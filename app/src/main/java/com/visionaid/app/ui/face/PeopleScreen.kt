package com.visionaid.app.ui.face

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Vibrator
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visionaid.app.data.face.FaceProfile
import com.visionaid.app.data.face.FaceRecognitionManager
import com.visionaid.app.ui.theme.DangerColor
import com.visionaid.app.ui.theme.LightMutedText
import com.visionaid.app.util.TtsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleScreen(
    viewModel: PeopleViewModel,
    recognitionManager: FaceRecognitionManager,
    ttsManager: TtsManager,
    vibrator: Vibrator,
    onClose: () -> Unit = {}
) {
    val profiles by viewModel.profiles.collectAsState()

    var showNameDialog by remember { mutableStateOf(false) }
    var candidateName by remember { mutableStateOf("") }
    var activeEnrollmentName by remember { mutableStateOf<String?>(null) }

    // If an enrollment is active, show the full-screen CameraX Face Enrollment scanner
    if (activeEnrollmentName != null) {
        FaceEnrollmentScreen(
            personName = activeEnrollmentName!!,
            repository = viewModel.repository,
            recognitionManager = recognitionManager,
            ttsManager = ttsManager,
            vibrator = vibrator,
            onEnrolled = {
                activeEnrollmentName = null
            },
            onDismiss = {
                activeEnrollmentName = null
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Screen Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Known People",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${profiles.size} of 5 people memorized",
                    fontSize = 14.sp,
                    color = LightMutedText
                )
            }

            // Memory Capacity Badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (profiles.size >= 5) DangerColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "${profiles.size}/5 Slots",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (profiles.size >= 5) DangerColor else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // Add Person Action Card
        Button(
            onClick = {
                if (profiles.size >= 5) {
                    ttsManager.speak("Memory is full. Delete an existing person to add a new one.")
                } else {
                    candidateName = ""
                    showNameDialog = true
                }
            },
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Icon(Icons.Default.PersonAdd, contentDescription = "Add Person", tint = Color.White)
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Add Person",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        // Feature Explanation Card
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = "AI Memory",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "When your wearable camera detects these faces, it announces their name instantly through voice.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    lineHeight = 18.sp
                )
            }
        }

        // Profiles List or Empty State
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Face,
                        contentDescription = "Empty",
                        modifier = Modifier.size(64.dp),
                        tint = LightMutedText.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No Faces Memorized Yet",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap 'Add Person' to scan a family member or friend using the camera frame.",
                        fontSize = 14.sp,
                        color = LightMutedText,
                        lineHeight = 20.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        onDelete = { viewModel.deleteProfile(profile.id, profile.name) }
                    )
                }
            }
        }
    }

    // Name Prompt Dialog
    if (showNameDialog) {
        AlertDialog(
            onDismissRequest = { showNameDialog = false },
            title = {
                Text("Add New Person", fontWeight = FontWeight.Bold)
            },
            text = {
                Column {
                    Text(
                        "Enter the person's name (e.g. Mom, Dad, Sarah). The camera will open to scan their face.",
                        fontSize = 14.sp,
                        color = LightMutedText
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = candidateName,
                        onValueChange = { candidateName = it },
                        label = { Text("Person's Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (candidateName.isNotBlank()) {
                            val name = candidateName.trim()
                            showNameDialog = false
                            activeEnrollmentName = name
                        }
                    },
                    enabled = candidateName.isNotBlank()
                ) {
                    Text("Open Camera & Scan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNameDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ProfileCard(profile: FaceProfile, onDelete: () -> Unit) {
    val bitmap = remember(profile.photoBase64) {
        profile.photoBase64?.let {
            try {
                val bytes = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (e: Exception) {
                null
            }
        }
    }

    val dateFormatted = remember(profile.enrolledAt) {
        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        sdf.format(Date(profile.enrolledAt))
    }

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Avatar Thumbnail or Fallback Icon
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = profile.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = profile.name,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = profile.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Enrolled $dateFormatted",
                        fontSize = 13.sp,
                        color = LightMutedText
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = "Delete",
                    tint = DangerColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}
