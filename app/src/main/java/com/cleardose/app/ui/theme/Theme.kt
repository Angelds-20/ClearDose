package com.cleardose.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val HUDColorScheme = lightColorScheme(
    primary = NeonCyan,
    secondary = NeonBlue,
    tertiary = NeonGreen,
    background = DeepBlack,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Charcoal,
    onBackground = Charcoal,
    onSurface = Charcoal,
    error = NeonRed
)

@Composable
fun ClearDoseTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DeepBlack.toArgb()
            window.navigationBarColor = DeepBlack.toArgb()
            val controller = WindowCompat.getInsetsController(window, view)
            // Enable dark icons on the status bar and navigation bar (since the background is light)
            controller.isAppearanceLightStatusBars = true
            controller.isAppearanceLightNavigationBars = true
        }
    }

    MaterialTheme(
        colorScheme = HUDColorScheme,
        typography = Typography,
        content = content
    )
}
