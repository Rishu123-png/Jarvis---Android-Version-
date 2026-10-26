package com.jarvis.assistant.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val JarvisColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = VoidBlack,
    primaryContainer = CyanDeep,
    onPrimaryContainer = IceBlue,
    secondary = IceBlue,
    onSecondary = VoidBlack,
    tertiary = StatusGreen,
    onTertiary = VoidBlack,
    background = VoidBlack,
    onBackground = TextPrimary,
    surface = DeepNavy,
    onSurface = TextPrimary,
    surfaceVariant = PanelNavy,
    onSurfaceVariant = TextSecondary,
    outline = HairLine,
    outlineVariant = HairLine,
    error = AlertRed,
    onError = VoidBlack,
    scrim = Color(0xCC01040A),
)

/**
 * Root theme. The app is dark-only by design — a HUD has one lighting condition — so there
 * is deliberately no light colour scheme.
 */
@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = JarvisColorScheme,
        typography = JarvisTypography,
        shapes = JarvisShapes,
        content = content,
    )
}
