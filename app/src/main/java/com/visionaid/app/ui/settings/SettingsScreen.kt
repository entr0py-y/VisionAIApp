package com.visionaid.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visionaid.app.data.remote.NetworkClient
import com.visionaid.app.ui.home.HomeUiState
import com.visionaid.app.util.TtsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    uiState: HomeUiState,
    onThemeToggle: (Boolean) -> Unit,
    onSpeechRateChange: (Float) -> Unit,
    onPersonaChange: (String) -> Unit,
    onLogout: () -> Unit,
    ttsManager: TtsManager
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("Settings", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)

        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Theme Toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Dark Mode", fontSize = 16.sp)
                    Switch(
                        checked = uiState.isDarkTheme, 
                        onCheckedChange = onThemeToggle,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color.Black,
                            uncheckedThumbColor = Color.Black,
                            uncheckedTrackColor = Color(0xFFE0E0E0)
                        )
                    )
                }
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                // Speech Rate
                Text("Speech Rate: ${String.format("%.1f", uiState.speechRate)}x", fontSize = 16.sp)
                
                val mainSliderColor = if (uiState.isDarkTheme) Color.White else Color.Black
                val inactiveSliderColor = if (uiState.isDarkTheme) Color(0xFF444444) else Color(0xFFE0E0E0)

                Slider(
                    value = uiState.speechRate,
                    onValueChange = { onSpeechRateChange(it) },
                    onValueChangeFinished = {
                        ttsManager.speak("Testing speech rate.")
                    },
                    valueRange = 0.5f..2.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = mainSliderColor,
                        activeTrackColor = mainSliderColor,
                        inactiveTrackColor = inactiveSliderColor
                    )
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

                // Persona Picker
                Text("AI Persona", fontSize = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                val personas = listOf("Vision AID Assistant", "Friendly Companion", "Direct Navigator")
                
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    personas.forEach { persona ->
                        FilterChip(
                            selected = uiState.selectedPersona == persona,
                            onClick = { onPersonaChange(persona) },
                            label = { Text(persona) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (uiState.isDarkTheme) Color.White else Color.Black,
                                selectedLabelColor = if (uiState.isDarkTheme) Color.Black else Color.White
                            )
                        )
                    }
                }
            }
        }

        // Diagnostics
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Diagnostic Status", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Server URL: ${NetworkClient.baseUrl}", fontSize = 14.sp)
                Text("SSE Connection: ${if (uiState.sseConnected) "Connected" else "Waiting..."}", fontSize = 14.sp)
                Text("Ultrasonic: ${uiState.ultrasonicDistance ?: "N/A"} cm", fontSize = 14.sp)
                Text("PIR Motion: ${uiState.pirMotion ?: "N/A"}", fontSize = 14.sp)
            }
        }

        // Account / Logout Card
        Card(
            shape = MaterialTheme.shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Account", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Logged in as: ${uiState.currentUser}", fontSize = 15.sp)
                
                if (uiState.currentUser != "User") {
                    Button(
                        onClick = onLogout,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text("Log Out", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
