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
import androidx.compose.ui.text.withStyle
import com.mhmh2.englishbite.data.Word

/**
 * Renders [words] with each one colored as already-spoken once playback passes its own real
 * start timestamp - these come straight from YouTube's own per-word caption alignment (see
 * pipeline.py's fetch_caption_words), not an estimate, so pacing that isn't flat and even
 * (interviews, fast talkers) tracks as accurately as the source captions do.
 */
@Composable
fun KaraokeText(
    words: List<Word>,
    currentSecond: Double,
    style: TextStyle,
    highlightColor: Color,
    modifier: Modifier = Modifier,
    baseColor: Color = LocalContentColor.current
) {
    val annotated = remember(words, currentSecond) {
        buildAnnotatedString {
            words.forEachIndexed { index, word ->
                val spoken = currentSecond >= word.start
                withStyle(SpanStyle(color = if (spoken) highlightColor else baseColor)) {
                    append(word.text)
                }
                if (index != words.lastIndex) append(" ")
            }
        }
    }

    Text(text = annotated, style = style, modifier = modifier)
}
