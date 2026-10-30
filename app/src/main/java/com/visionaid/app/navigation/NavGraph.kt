package com.visionaid.app.navigation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.visionaid.app.ui.MainScreen
import com.visionaid.app.ui.chat.AiChatScreen
import com.visionaid.app.ui.chat.ChatViewModel
import com.visionaid.app.ui.family.FamilyScreen
import com.visionaid.app.ui.family.FamilyViewModel
import com.visionaid.app.ui.home.HomeViewModel
import com.visionaid.app.ui.mobility.MobilityScreen
import com.visionaid.app.ui.mobility.MobilityViewModel
import com.visionaid.app.ui.vision.VisionScreen
import com.visionaid.app.ui.vision.VisionViewModel
import com.visionaid.app.util.TtsManager

@Composable
fun AppNavGraph(
    navController: NavHostController,
    homeViewModel: HomeViewModel,
    chatViewModel: ChatViewModel,
    visionViewModel: VisionViewModel,
    mobilityViewModel: MobilityViewModel,
    familyViewModel: FamilyViewModel,
    ttsManager: TtsManager
) {
    NavHost(
        navController = navController,
        startDestination = "main",
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(380, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(280))
        },
        exitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> -fullWidth / 4 },
                animationSpec = tween(380, easing = FastOutSlowInEasing)
            ) + scaleOut(
                targetScale = 0.95f,
                animationSpec = tween(380, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(250))
        },
        popEnterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> -fullWidth / 4 },
                animationSpec = tween(380, easing = FastOutSlowInEasing)
            ) + scaleIn(
                initialScale = 0.95f,
                animationSpec = tween(380, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(280))
        },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(380, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(250))
        }
    ) {
        composable("main") {
            MainScreen(
                viewModel = homeViewModel,
                chatViewModel = chatViewModel,
                visionViewModel = visionViewModel,
                mobilityViewModel = mobilityViewModel,
                familyViewModel = familyViewModel,
                ttsManager = ttsManager,
                onNavigateToChat = { navController.navigate("chat") }
            )
        }
        composable("chat") {
            AiChatScreen(
                viewModel = chatViewModel,
                onClose = { navController.popBackStack() },
                isDarkTheme = homeViewModel.uiState.value.isDarkTheme
            )
        }
        composable("vision") {
            VisionScreen(viewModel = visionViewModel)
        }
        composable("mobility") {
            MobilityScreen(viewModel = mobilityViewModel)
        }
        composable("family") {
            FamilyScreen(viewModel = familyViewModel, username = homeViewModel.uiState.value.currentUser)
        }
    }
}
