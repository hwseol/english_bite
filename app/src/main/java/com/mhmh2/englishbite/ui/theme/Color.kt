package com.mhmh2.englishbite.ui.theme

import androidx.compose.ui.graphics.Color

// Brand accent - a warm coral ("bite" into something appetizing), distinct from the
// red/black palettes of Netflix/YouTube while sitting in the same energetic family.
val BiteCoral = Color(0xFFFF6B4A)
val BiteCoralDim = Color(0xFFCC5539)

// Dark, streaming-app-style neutrals. Slightly lifted off pure black (like Netflix/YouTube
// dark surfaces) so text and card edges stay readable.
val Ink900 = Color(0xFF0E0E12)
val Ink800 = Color(0xFF1A1A20)
val Ink700 = Color(0xFF26262E)
val Ink600 = Color(0xFF3A3A44)

val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFFA8A8B3)

val ErrorRed = Color(0xFFFF5449)

// Karaoke word-highlight color - kept separate from the brand accent (BiteCoral) rather than
// reusing it: a cool blue reads more clearly as "spoken so far" against both the dark video
// scrim and the app's own dark surfaces than the warm coral did, and doesn't visually compete
// with the idiom badges/buttons that already use the accent color for something else.
val KaraokeHighlightBlue = Color(0xFF4FA8FF)
