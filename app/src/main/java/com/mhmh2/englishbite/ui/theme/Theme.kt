package com.mhmh2.englishbite.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Stitch-generated "Midnight Executive Broadcast" tonal palette - a fixed dark scheme (still
// not Android's per-device Material You dynamic color), but now Stitch's own Material3 tonal
// ramp rather than the hand-picked BiteBlue/Ink palette it replaced. See Color.kt for the raw
// values this was generated from.
private val EnglishBiteColorScheme = darkColorScheme(
    primary = BiteBlue,
    onPrimary = OnBiteBlue,
    primaryContainer = BiteBluePressed,
    onPrimaryContainer = OnBiteBluePressed,
    inversePrimary = InversePrimary,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = TertiaryError,
    onTertiary = OnTertiaryError,
    tertiaryContainer = TertiaryErrorContainer,
    onTertiaryContainer = OnTertiaryErrorContainer,
    background = Ink800,
    onBackground = TextPrimary,
    surface = Ink800,
    onSurface = TextPrimary,
    surfaceVariant = Ink600,
    onSurfaceVariant = TextSecondary,
    surfaceDim = Ink800,
    surfaceBright = SurfaceBright,
    surfaceContainerLowest = Ink900,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = Ink700,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = Ink600,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    outline = Outline,
    outlineVariant = OutlineVariant,
    error = ErrorRed,
    onError = OnErrorRed,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer
)

@Composable
fun EnglishBiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EnglishBiteColorScheme,
        typography = Typography,
        content = content
    )
}
