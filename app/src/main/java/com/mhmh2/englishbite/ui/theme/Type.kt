package com.mhmh2.englishbite.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mhmh2.englishbite.R

// Hanken Grotesk - the typeface Stitch's generated design system ("Midnight Executive
// Broadcast") specifies for every text style. Bundled as its two variable-font files (roman +
// italic), each entry below just points at a different weight axis setting of the same file.
// Korean text has no glyphs in this font and falls back to the system's own CJK font per
// character - the same thing that happens to Korean text in Stitch's own HTML mockup, which
// only ever loads Hanken Grotesk too.
@OptIn(ExperimentalTextApi::class)
private fun hankenWeight(weight: FontWeight, style: FontStyle = FontStyle.Normal) = Font(
    resId = if (style == FontStyle.Italic) R.font.hanken_grotesk_italic else R.font.hanken_grotesk,
    weight = weight,
    style = style,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight))
)

val HankenGrotesk = FontFamily(
    hankenWeight(FontWeight.Normal),
    hankenWeight(FontWeight.Medium),
    hankenWeight(FontWeight.SemiBold),
    hankenWeight(FontWeight.Bold),
    hankenWeight(FontWeight.ExtraBold),
    hankenWeight(FontWeight.Normal, FontStyle.Italic),
    hankenWeight(FontWeight.SemiBold, FontStyle.Italic)
)

// Sizes/weights/letter-spacing below follow Stitch's DESIGN.md scale (display-lg down to
// label-sm), mapped onto the Material3 slots this app actually uses - preserving the existing
// relative size ordering (e.g. headlineLarge > headlineSmall > titleLarge > titleMedium >
// bodyMedium > bodySmall, the three-tier ladder StudyScreen's subtitleStyles() picks from by
// sentence length) rather than a strict one-to-one class-name match, since several DESIGN.md
// tiers (the two display sizes, headline-md as a distinct step) have no used slot to land on.
val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.28).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.22).sp
    ),
    titleLarge = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.14.sp
    ),
    bodySmall = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.24.sp
    ),
    labelLarge = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.14.sp
    ),
    labelMedium = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.48.sp
    ),
    labelSmall = TextStyle(
        fontFamily = HankenGrotesk,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp
    )
)
