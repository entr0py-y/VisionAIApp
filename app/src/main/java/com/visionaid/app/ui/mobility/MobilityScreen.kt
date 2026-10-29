package com.visionaid.app.ui.mobility

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import com.visionaid.app.ui.theme.Shapes
import com.visionaid.app.ui.theme.DangerColor

@Composable
fun MobilityScreen(viewModel: MobilityViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var searchInput by remember { mutableStateOf("") }

    val userLatLng = LatLng(uiState.userLat, uiState.userLng)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(userLatLng, 15f)
    }

    // Sync camera to destination if navigating
    LaunchedEffect(uiState.destLat, uiState.destLng) {
        if (uiState.destLat != null && uiState.destLng != null) {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(LatLng(uiState.destLat!!, uiState.destLng!!), 15f)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = true)
        ) {
            if (uiState.destLat != null && uiState.destLng != null) {
                Marker(
                    state = MarkerState(position = LatLng(uiState.destLat!!, uiState.destLng!!)),
                    title = uiState.destination
                )
            }
        }

        // Top Search Bar
        Card(
            shape = Shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopCenter)
        ) {
            OutlinedTextField(
                value = searchInput,
                onValueChange = { searchInput = it },
                placeholder = { Text("Where to?") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { 
                        if (searchInput.isNotBlank()) viewModel.navigateTo(searchInput, context) 
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = Color.Transparent
                )
            )
        }

        // Bottom Navigation Overlay
        if (uiState.isNavigating) {
            Card(
                shape = Shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 24.dp)
                    .align(Alignment.BottomCenter)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(uiState.destination, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(uiState.distanceText, fontSize = 16.sp, color = MaterialTheme.colorScheme.secondary)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Button(
                        onClick = { viewModel.stopNavigation() },
                        colors = ButtonDefaults.buttonColors(containerColor = DangerColor),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Stop", tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Stop Navigation", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
