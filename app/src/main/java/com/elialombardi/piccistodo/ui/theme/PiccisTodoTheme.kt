package com.elialombardi.piccistodo.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightWorkspace = lightColorScheme(
    primary = Color(0xFF006B5E), onPrimary = Color.White,
    primaryContainer = Color(0xFFB5F2DF), onPrimaryContainer = Color(0xFF00382F),
    secondary = Color(0xFF4A635A), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD4E8DE), onSecondaryContainer = Color(0xFF243E34),
    tertiary = Color(0xFF49617E), tertiaryContainer = Color(0xFFD1E4FF), onTertiaryContainer = Color(0xFF18324E),
    background = Color(0xFFF5FAF7), onBackground = Color(0xFF17201C),
    surface = Color(0xFFF5FAF7), onSurface = Color(0xFF17201C),
    surfaceVariant = Color(0xFFDCE6DF), onSurfaceVariant = Color(0xFF46534C),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFEDF4EF),
    surfaceContainer = Color(0xFFE7EFE9), surfaceContainerHigh = Color(0xFFE1EAE3), surfaceContainerHighest = Color(0xFFDBE4DD),
    outline = Color(0xFF738078), outlineVariant = Color(0xFFC5D1C8), surfaceTint = Color(0xFF006B5E)
)
private val DarkWorkspace = darkColorScheme(
    primary = Color(0xFF7BD9C0), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005145), onPrimaryContainer = Color(0xFFB5F2DF),
    secondary = Color(0xFFB8CCC1), onSecondary = Color(0xFF243E34),
    secondaryContainer = Color(0xFF344C42), onSecondaryContainer = Color(0xFFD4E8DE),
    tertiary = Color(0xFFAFC9EB), tertiaryContainer = Color(0xFF304966), onTertiaryContainer = Color(0xFFD1E4FF),
    background = Color(0xFF0E1713), onBackground = Color(0xFFDEE8E0),
    surface = Color(0xFF0E1713), onSurface = Color(0xFFDEE8E0),
    surfaceVariant = Color(0xFF3D4A42), onSurfaceVariant = Color(0xFFBFCBC2),
    surfaceContainerLowest = Color(0xFF09120E), surfaceContainerLow = Color(0xFF17211B),
    surfaceContainer = Color(0xFF1B251F), surfaceContainerHigh = Color(0xFF263029), surfaceContainerHighest = Color(0xFF303B33),
    outline = Color(0xFF89968D), outlineVariant = Color(0xFF3D4A42), surfaceTint = Color(0xFF7BD9C0)
)

@Composable
fun PiccisTodoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (darkTheme) DarkWorkspace else LightWorkspace

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(32.dp)),
        content = content
    )
}