package com.visionaid.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    background = DarkBackgroundStart, // Standard prop; will be masked by gradient modifier in shell
    surface = DarkSurface,
    onBackground = DarkPrimaryText,
    onSurface = DarkPrimaryText,
    primary = SuccessColor,
    error = DangerColor,
    secondary = DarkMutedText
)

private val LightColorScheme = lightColorScheme(
    background = LightBackground,
    surface = LightSurface,
    onBackground = LightPrimaryText,
    onSurface = LightPrimaryText,
    primary = SuccessColor,
    error = DangerColor,
    secondary = LightMutedText
)

@Composable
fun VisionAIDTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = Shapes,
        typography = Typography
    ) {
        // Enforce the 1:1 Design System requirements for backgrounds
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (darkTheme) {
                        Modifier.background(
                            brush = Brush.verticalGradient(
                                colors = listOf(DarkBackgroundStart, DarkBackgroundEnd)
                            )
                        )
                    } else {
                        Modifier.background(colorScheme.background)
                    }
                )
        ) {
            content()
        }
    }
}
