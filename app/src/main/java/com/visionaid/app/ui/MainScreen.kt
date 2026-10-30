package com.visionaid.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.visionaid.app.ui.auth.AuthModal
import com.visionaid.app.ui.chat.AiChatScreen
import com.visionaid.app.ui.chat.ChatEvent
import com.visionaid.app.ui.chat.ChatViewModel
import com.visionaid.app.ui.family.FamilyScreen
import com.visionaid.app.ui.family.FamilyViewModel
import com.visionaid.app.ui.home.HomeScreen
import com.visionaid.app.ui.home.HomeViewModel
import com.visionaid.app.data.face.FaceRecognitionManager
import com.visionaid.app.ui.face.PeopleScreen
import com.visionaid.app.ui.face.PeopleViewModel
import android.os.Vibrator
import com.visionaid.app.ui.settings.SettingsScreen
import com.visionaid.app.ui.vision.VisionScreen
import com.visionaid.app.ui.vision.VisionViewModel
import com.visionaid.app.util.TtsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: HomeViewModel, 
    chatViewModel: ChatViewModel, 
    visionViewModel: VisionViewModel,
    peopleViewModel: PeopleViewModel,
    familyViewModel: FamilyViewModel,
    ttsManager: TtsManager,
    recognitionManager: FaceRecognitionManager,
    vibrator: Vibrator
) {
    val uiState by viewModel.uiState.collectAsState()
    val chatUiState by chatViewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    var selectedTab by remember { mutableIntStateOf(0) }
    
    // Bottom Sheet States
    var showPeopleSheet by remember { mutableStateOf(false) }
    var showFamilySheet by remember { mutableStateOf(false) }
    var showVisionSheet by remember { mutableStateOf(false) }
    
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Back Handlers: Intercept system Back button / gestures to close open screens cleanly instead of quitting app
    BackHandler(enabled = chatUiState.isOpen) {
        chatViewModel.closeChat()
    }
    BackHandler(enabled = showVisionSheet) {
        showVisionSheet = false
    }
    BackHandler(enabled = showPeopleSheet) {
        showPeopleSheet = false
    }
    BackHandler(enabled = showFamilySheet) {
        showFamilySheet = false
    }
    BackHandler(enabled = uiState.showAuthModal) {
        viewModel.toggleAuthModal()
    }

    // Intercept Chat Events mapping hardware intents to Screens natively!
    LaunchedEffect(Unit) {
        chatViewModel.chatEvents.collect { event ->
            when (event) {
                is ChatEvent.TriggerNavigation -> {
                    ttsManager.speak("Navigation to ${event.destination} requested.")
                }
                else -> {}
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent, 
            bottomBar = {
                val navContainerColor = if (uiState.isDarkTheme) Color(0xFF161616) else Color(0xFFE8E8E3) 
                val navContentColor = if (uiState.isDarkTheme) Color.White else Color.Black
                
                NavigationBar(
                    containerColor = navContainerColor,
                    contentColor = navContentColor
                ) {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                        label = { Text("Home") },
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = navContainerColor,
                            selectedTextColor = navContentColor,
                            indicatorColor = navContentColor,
                            unselectedIconColor = navContentColor.copy(alpha = 0.6f),
                            unselectedTextColor = navContentColor.copy(alpha = 0.6f)
                        )
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = navContainerColor,
                            selectedTextColor = navContentColor,
                            indicatorColor = navContentColor,
                            unselectedIconColor = navContentColor.copy(alpha = 0.6f),
                            unselectedTextColor = navContentColor.copy(alpha = 0.6f)
                        )
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (selectedTab == 0) {
                    HomeScreen(
                        uiState = uiState,
                        onAvatarClick = {
                            if (uiState.currentUser != "User") {
                                viewModel.logout()
                            } else {
                                viewModel.toggleAuthModal()
                            }
                        },
                        onOpenPeople = { showPeopleSheet = true },
                        onOpenFamily = { showFamilySheet = true },
                        onOpenAiChat = { chatViewModel.openChat() } 
                    )
                } else {
                    SettingsScreen(
                        uiState = uiState,
                        onThemeToggle = { viewModel.toggleDarkTheme(it) },
                        onSpeechRateChange = { viewModel.setSpeechRate(it) },
                        onPersonaChange = { viewModel.setPersona(it) },
                        onLogout = { viewModel.logout() },
                        ttsManager = ttsManager
                    )
                }
            }
        }

        // Modals & Dynamic Features Sheets

        if (uiState.showAuthModal) {
            AuthModal(
                uiState = uiState,
                onDismiss = { viewModel.toggleAuthModal() },
                onLogin = { user, pass -> viewModel.login(user, pass) },
                onSignup = { user, pass -> viewModel.signup(user, pass) }
            )
        }
        
        if (showVisionSheet) {
            ModalBottomSheet(onDismissRequest = { showVisionSheet = false }, sheetState = sheetState) {
                VisionScreen(viewModel = visionViewModel)
            }
        }

        if (showPeopleSheet) {
            ModalBottomSheet(onDismissRequest = { showPeopleSheet = false }, sheetState = sheetState) {
                PeopleScreen(
                    viewModel = peopleViewModel,
                    recognitionManager = recognitionManager,
                    ttsManager = ttsManager,
                    vibrator = vibrator,
                    onClose = { showPeopleSheet = false }
                )
            }
        }

        if (showFamilySheet) {
            ModalBottomSheet(onDismissRequest = { showFamilySheet = false }, sheetState = sheetState) {
                FamilyScreen(viewModel = familyViewModel, username = uiState.currentUser)
            }
        }

        // FULL SCREEN AI CHAT PAGE OVERLAY
        if (chatUiState.isOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                AiChatScreen(
                    viewModel = chatViewModel, 
                    onClose = { chatViewModel.closeChat() },
                    isDarkTheme = uiState.isDarkTheme
                )
            }
        }
    }
}
