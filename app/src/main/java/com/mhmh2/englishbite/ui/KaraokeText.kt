package com.mhmh2.englishbite.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.mhmh2.englishbite.data.Word

/**
 * Renders [words] with each one colored as already-spoken once playback passes its own real
 * start timestamp - these come straight from YouTube's own per-word caption alignment (see
 * pipeline.py's fetch_caption_words), not an estimate, so pacing that isn't flat and even
 * (interviews, fast talkers) tracks as accurately as the source captions do.
 *
 * [onTap] and [onLongPressWord] share one gesture detector rather than a plain .clickable()
 * modifier from the caller, since figuring out which word a long-press landed on needs the
 * Text's own layout (via onTextLayout) - a caller-side clickable() has no way to do that.
 */
@Composable
fun KaraokeText(
    words: List<Word>,
    currentSecond: Double,
    style: TextStyle,
    highlightColor: Color,
    modifier: Modifier = Modifier,
    baseColor: Color = LocalContentColor.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onTap: (() -> Unit)? = null,
    onLongPressWord: ((Word) -> Unit)? = null
) {
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    // Each word's [start, end) character range in the annotated string built below - a plain
    // space joins words, so every range after the first starts one character past the previous
    // one's end.
    val wordRanges = remember(words) {
        var pos = 0
        words.map { word ->
            val range = pos until (pos + word.text.length)
            pos = range.last + 2 // the word itself, plus the joining space
            range
        }
    }

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

    Text(
        text = annotated,
        style = style,
        modifier = modifier.then(
            if (onTap != null || onLongPressWord != null) {
                Modifier.pointerInput(words) {
                    detectTapGestures(
                        onTap = { onTap?.invoke() },
                        onLongPress = { position ->
                            val layout = layoutResult ?: return@detectTapGestures
                            val charIndex = layout.getOffsetForPosition(position)
                            val wordIndex = wordRanges.indexOfFirst { charIndex in it }
                            if (wordIndex >= 0) onLongPressWord?.invoke(words[wordIndex])
                        }
                    )
                }
            } else {
                Modifier
            }
        ),
        maxLines = maxLines,
        overflow = overflow,
        onTextLayout = { layoutResult = it }
    )
}
