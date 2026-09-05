package com.mhmh2.englishbite.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle

/**
 * Renders [text] word-by-word, coloring everything up to [progress] (0f..1f, position within
 * the sentence's time window) as already-spoken. Word-level only - we don't have per-word
 * timestamps from the backend, so word boundaries are estimated by character-position share
 * of the sentence, which is close enough for a karaoke-style following aid.
 */
@Composable
fun KaraokeText(
    text: String,
    progress: Float,
    style: TextStyle,
    highlightColor: Color,
    modifier: Modifier = Modifier,
    baseColor: Color = LocalContentColor.current,
    maxLines: Int = Int.MAX_VALUE
) {
    val words = remember(text) { text.split(" ") }
    val totalChars = text.length.coerceAtLeast(1)
    val highlightChars = (progress.coerceIn(0f, 1f) * totalChars)

    val annotated = remember(words, highlightChars) {
        buildAnnotatedString {
            var consumed = 0
            words.forEachIndexed { index, word ->
                val wordEnd = consumed + word.length
                val spoken = wordEnd <= highlightChars
                withStyle(SpanStyle(color = if (spoken) highlightColor else baseColor)) {
                    append(word)
                }
                if (index != words.lastIndex) append(" ")
                consumed = wordEnd + 1
            }
        }
    }

    Text(
        text = annotated,
        style = style,
        modifier = modifier,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}
