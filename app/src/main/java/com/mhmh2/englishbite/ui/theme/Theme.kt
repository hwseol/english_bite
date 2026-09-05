package com.mhmh2.englishbite.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// A fixed brand identity, not Android's per-device Material You dynamic color - streaming
// apps (Netflix, Tving, Naver TV, ...) all commit to one designed look regardless of the
// user's wallpaper, which is what actually reads as "professional" rather than "default
// Compose starter project."
private val EnglishBiteColorScheme = darkColorScheme(
    primary = BiteCoral,
    onPrimary = Ink900,
    primaryContainer = BiteCoralDim,
    onPrimaryContainer = TextPrimary,
    secondary = TextSecondary,
    onSecondary = Ink900,
    background = Ink900,
    onBackground = TextPrimary,
    surface = Ink800,
    onSurface = TextPrimary,
    surfaceVariant = Ink700,
    onSurfaceVariant = TextSecondary,
    outline = Ink600,
    error = ErrorRed,
    onError = Ink900
)

@Composable
fun EnglishBiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EnglishBiteColorScheme,
        typography = Typography,
        content = content
    )
}
