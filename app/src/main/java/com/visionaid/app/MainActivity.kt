package com.visionaid.app

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.google.android.gms.location.LocationServices
import com.visionaid.app.data.remote.NetworkClient
import com.visionaid.app.data.remote.SseGhostClickService
import com.visionaid.app.data.repository.DeviceHealthRepository
import com.visionaid.app.domain.router.RouterEngine
import com.visionaid.app.ui.MainScreen
import com.visionaid.app.ui.chat.ChatViewModel
import com.visionaid.app.ui.chat.ChatViewModelFactory
import com.visionaid.app.ui.family.FamilyViewModel
import com.visionaid.app.ui.family.FamilyViewModelFactory
import com.visionaid.app.ui.home.HomeViewModel
import com.visionaid.app.ui.home.HomeViewModelFactory
import com.visionaid.app.ui.mobility.MobilityViewModel
import com.visionaid.app.ui.mobility.MobilityViewModelFactory
import com.visionaid.app.ui.theme.VisionAIDTheme
import com.visionaid.app.ui.vision.VisionViewModel
import com.visionaid.app.ui.vision.VisionViewModelFactory
import com.visionaid.app.util.TtsManager

class MainActivity : ComponentActivity() {

    private lateinit var ttsManager: TtsManager
    
    // Core Dependencies
    private val sseService = SseGhostClickService()
    private val healthRepo = DeviceHealthRepository(NetworkClient.apiService)
    private val routerEngine = RouterEngine(NetworkClient.apiService)

    // ViewModels generated dynamically tracking process dependencies
    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModelFactory(healthRepo, NetworkClient.apiService, sseService)
    }

    private val visionViewModel: VisionViewModel by viewModels {
        VisionViewModelFactory(NetworkClient.apiService, ttsManager)
    }

    private val mobilityViewModel: MobilityViewModel by viewModels {
        MobilityViewModelFactory(
            NetworkClient.apiService, 
            ttsManager, 
            LocationServices.getFusedLocationProviderClient(this)
        )
    }

    private val familyViewModel: FamilyViewModel by viewModels {
        val vibrator = getVibratorInstance()
        FamilyViewModelFactory(
            NetworkClient.apiService, 
            ttsManager, 
            LocationServices.getFusedLocationProviderClient(this),
            vibrator
        )
    }

    private val chatViewModel: ChatViewModel by viewModels {
        ChatViewModelFactory(
            apiService = NetworkClient.apiService,
            sseService = sseService,
            ttsManager = ttsManager,
            vibrator = getVibratorInstance(),
            routerEngine = routerEngine
        )
    }

    private fun getVibratorInstance(): Vibrator {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        ttsManager = TtsManager(this)

        // Runtime Permissions Request
        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            // Handle permission results if needed
        }

        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())

        setContent {
            val uiState by homeViewModel.uiState.collectAsState()
            
            VisionAIDTheme(darkTheme = uiState.isDarkTheme) {
                MainScreen(
                    viewModel = homeViewModel, 
                    chatViewModel = chatViewModel,
                    visionViewModel = visionViewModel,
                    mobilityViewModel = mobilityViewModel,
                    familyViewModel = familyViewModel,
                    ttsManager = ttsManager
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
    }
}
