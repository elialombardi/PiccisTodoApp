package com.elialombardi.piccistodo.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// We force a dark color scheme to match the website
private val AppColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = DarkTealBackground,
    primaryContainer = CyanPrimaryContainer,
    onPrimaryContainer = TextWhite,

    background = DarkTealBackground,
    onBackground = TextWhite,

    surface = DarkTealSurface,
    onSurface = TextWhite,
    surfaceVariant = LightTealSurface,
    onSurfaceVariant = TextWhite,
)

@Composable
fun PiccisTodoTheme(
    darkTheme: Boolean = true, // Force dark theme
    dynamicColor: Boolean = false, // Disable dynamic colors so your brand colors stick
    content: @Composable () -> Unit
) {
    val colorScheme = AppColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Make status bar match the background
            window.statusBarColor = colorScheme.background.toArgb()
            // Make status bar icons light
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography, // Keep your default typography
        content = content
    )
}