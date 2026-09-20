package com.mhmh2.englishbite.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mhmh2.englishbite.data.Idiom
import com.mhmh2.englishbite.data.Sentence
import com.mhmh2.englishbite.data.VideoResult
import com.mhmh2.englishbite.data.Word
import com.mhmh2.englishbite.playback.PlaybackForegroundService
import com.mhmh2.englishbite.vocab.SavedIdiomsViewModel
import com.mhmh2.englishbite.ui.theme.BiteBlue
import com.mhmh2.englishbite.ui.theme.KaraokeHighlightBlue
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/** Long sentences would otherwise overflow/clip in the subtitle area. The English and Korean
 * lines were scaled independently before - English shrank with its own length but Korean
 * stayed fixed-size, so a short English sentence with a long Korean translation (or vice
 * versa) could still overflow. Score both together (Korean characters render noticeably wider
 * than Latin ones at the same font size, hence the weight) and scale both from one shared tier. */
private data class SubtitleStyles(val english: TextStyle, val korean: TextStyle)

@Composable
private fun subtitleStyles(english: String, korean: String): SubtitleStyles {
    val weight = english.length + korean.length * 1.6
    return when {
        // Bumped up one tier from the original sizing - backend sentences are now capped at
        // 110 characters (split into pieces if longer, see MAX_SENTENCE_CHARS in pipeline.py),
        // so the smallest tier is rarer than it used to be and can afford to be bigger too.
        weight > 220 -> SubtitleStyles(MaterialTheme.typography.titleLarge, MaterialTheme.typography.bodyMedium)
        weight > 140 -> SubtitleStyles(MaterialTheme.typography.headlineSmall, MaterialTheme.typography.titleMedium)
        else -> SubtitleStyles(MaterialTheme.typography.headlineLarge, MaterialTheme.typography.titleLarge)
    }
}

/** A small tappable pill for the current sentence's idiom/expression, if it has one -
 * at most 1-2 per ~8-sentence stretch of the video, often none. Tapping expands a card
 * with the Korean explanation and a copy-to-clipboard action; tapping again collapses it. */
@Composable
private fun IdiomBadge(
    idiom: Idiom,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDarkBackground: Boolean
) {
    val clipboard = LocalClipboardManager.current
    val pillBg = MaterialTheme.colorScheme.primary.copy(alpha = if (onDarkBackground) 0.28f else 0.14f)
    val noteBg = if (onDarkBackground) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceVariant
    val noteColor = if (onDarkBackground) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = Modifier.padding(bottom = 6.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(pillBg)
                .clickable { onToggle() }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Lightbulb,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = idiom.phrase,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        AnimatedVisibility(visible = expanded) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(noteBg)
                    .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 2.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = idiom.note_ko,
                        style = MaterialTheme.typography.bodySmall,
                        color = noteColor
                    )
                    idiom.example?.let { example ->
                        Text(
                            text = "예문  $example",
                            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                            color = noteColor.copy(alpha = 0.85f),
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                IconButton(
                    onClick = {
                        val text = buildString {
                            append(idiom.phrase).append("\n").append(idiom.note_ko)
                            idiom.example?.let { append("\n예문: ").append(it) }
                        }
                        clipboard.setText(AnnotatedString(text))
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "복사",
                        tint = noteColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

/** A circular icon button with a physical "pressed in" feel - scales down and its shadow
 * flattens while held, rebounding on release - instead of a flat, static filled circle. The
 * kind of tactile micro-interaction big-tech apps (Google, Instagram) bake into every tappable
 * control, that a plain IconButton-on-a-colored-background doesn't have on its own. */
@Composable
private fun DepthIconButton(
    onClick: () -> Unit,
    containerColor: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.87f else 1f, label = "buttonScale")
    val shadowElevation by animateDpAsState(if (pressed) 1.dp else 7.dp, label = "buttonElevation")

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation = shadowElevation, shape = CircleShape, clip = false)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    listOf(containerColor.copy(alpha = (containerColor.alpha + 0.12f).coerceAtMost(1f)), containerColor)
                )
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
        content = { content() }
    )
}

/** Tapping the line itself also repeats it (seeks back to its start) - the whole point is not
 * needing to aim for a small button when the sentence you just heard is still in your ear.
 * Long-pressing a specific word looks it up instead - a plain tap can't tell one word from
 * another, so the two gestures split the same text between "repeat" and "look up". */
@Composable
private fun RepeatableSentence(
    words: List<Word>,
    currentSecond: Double,
    style: TextStyle,
    highlightColor: Color,
    baseColor: Color,
    onRepeat: () -> Unit,
    onLookUpWord: (Word) -> Unit
) {
    KaraokeText(
        words = words,
        currentSecond = currentSecond,
        style = style,
        highlightColor = highlightColor,
        baseColor = baseColor,
        modifier = Modifier.fillMaxWidth(),
        onTap = onRepeat,
        onLongPressWord = onLookUpWord
    )
}

/** Explicit transport controls for stepping between sentences - previous/next, each seeking
 * the video and keeping it playing. More discoverable and easier to hit than relying on tapping
 * text alone, especially for "go back one" which has no text of its own to tap. There used to be
 * a third, center "repeat" button here, but tapping the sentence text itself already does the
 * exact same seek-to-start - a dedicated button for it was pure redundancy. */
@Composable
private fun SentenceNavControls(
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDarkBackground: Boolean,
    modifier: Modifier = Modifier
) {
    // Tinted with the brand blue rather than a neutral gray - these two are the single most-used
    // control on the whole screen (stepping sentence by sentence is the core of how this app is
    // used), so they read as the primary action instead of blending in with secondary ones like
    // fullscreen/PiP.
    val secondaryBg = if (onDarkBackground) BiteBlue.copy(alpha = 0.32f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    val secondaryTint = if (onDarkBackground) Color.White else MaterialTheme.colorScheme.primary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = modifier
    ) {
        DepthIconButton(onClick = onPrevious, containerColor = secondaryBg, size = 42.dp) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "이전 문장",
                tint = secondaryTint,
                modifier = Modifier.size(24.dp)
            )
        }
        DepthIconButton(onClick = onNext, containerColor = secondaryBg, size = 42.dp) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "다음 문장",
                tint = secondaryTint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// Slower only - the point is following speech that's too fast to catch, not speeding
// anything up. Cycling through this list on tap, rather than a menu, since it's just one
// control that needs to be reachable one-handed over a video.
private val PLAYBACK_RATES = listOf(
    0.5f to PlayerConstants.PlaybackRate.RATE_0_5,
    0.75f to PlayerConstants.PlaybackRate.RATE_0_75,
    1f to PlayerConstants.PlaybackRate.RATE_1,
)

private fun formatRate(rate: Float): String {
    val number = if (rate == rate.toInt().toFloat()) rate.toInt().toString() else rate.toString()
    return "${number}x"
}

/** A speed pill (e.g. "1.25x") that cycles to the next rate in PLAYBACK_RATES on tap - some
 * speakers are just fast, and someone re-listening to a line via the repeat controls above
 * might specifically want it slower than the first pass. */
@Composable
private fun PlaybackSpeedButton(
    speedIndex: Int,
    onCycle: () -> Unit,
    containerColor: Color = Color.Black.copy(alpha = 0.5f),
    contentColor: Color = Color.White,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "speedButtonScale")
    val shadowElevation by animateDpAsState(if (pressed) 1.dp else 5.dp, label = "speedButtonElevation")

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation = shadowElevation, shape = RoundedCornerShape(16.dp), clip = false)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    listOf(containerColor.copy(alpha = (containerColor.alpha + 0.12f).coerceAtMost(1f)), containerColor)
                )
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onCycle)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = formatRate(PLAYBACK_RATES[speedIndex].first),
            color = contentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** The compact caption strip beneath the mini-player's video, per the Stitch-generated
 * mini-player layout - a one-line karaoke English snippet plus its Korean translation, so
 * there's still something to read while the video is shrunk into the corner, with a hint to tap
 * or swipe up to return to the full player. */
@Composable
private fun MiniPlayerCaption(
    currentSentence: Sentence?,
    displayWords: List<Word>,
    currentSecond: Double
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                imageVector = Icons.Default.Subtitles,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = "실시간 학습 중",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        if (currentSentence != null) {
            KaraokeText(
                words = displayWords,
                currentSecond = currentSecond,
                style = MaterialTheme.typography.bodySmall,
                highlightColor = KaraokeHighlightBlue,
                baseColor = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = currentSentence.ko,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        } else {
            Text(
                text = "탭하거나 위로 쓸어올려 복귀",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun StudyScreen(
    result: VideoResult,
    videoTitle: String? = null,
    startSecond: Float? = null,
    isInPip: Boolean = false,
    onVideoEnded: () -> Unit = {},
    isMinimized: Boolean = false,
    onMinimize: () -> Unit = {},
    onExpand: () -> Unit = {},
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val view = LocalView.current

    // 140dp read as too small once actually used day to day - 200dp is a noticeably bigger
    // default, and dragging the resize handle (top-left corner of the widget) can size it
    // anywhere from a genuinely small corner peek up to something closer to a small window.
    var miniWidgetWidth by remember { mutableStateOf(200.dp) }
    val miniWidgetWidthRange = 120.dp..300.dp

    var currentSecond by remember { mutableFloatStateOf(0f) }
    var isFullscreen by remember { mutableStateOf(false) }
    var youTubePlayer by remember { mutableStateOf<YouTubePlayer?>(null) }
    var playerViewRef by remember { mutableStateOf<YouTubePlayerView?>(null) }
    // currentSecond starts at 0f before any real onCurrentSecond callback has arrived, which
    // would otherwise immediately match a sentence whose own start is 0 (very common - dialogue
    // beginning right at the first frame) and show its text before the video has even started
    // playing, still on the loading spinner. Tracked separately from currentSecond so a title
    // placeholder can show for that stretch instead.
    var hasStartedPlaying by remember { mutableStateOf(false) }
    var speedIndex by remember { mutableStateOf(PLAYBACK_RATES.indexOfFirst { it.first == 1f }) }

    // Tap-to-play/pause (Netflix/YouTube-style) instead of a dedicated pause button: with
    // YouTube's own control bar hidden (controls(0) below - we show our own subtitle-driven
    // controls instead), tapping the video itself is the one intuitive gesture every video app
    // trains people to expect.
    var isPlaying by remember { mutableStateOf(false) }
    // With YouTube's own control bar hidden, its built-in loading/buffering spinner goes with
    // it - without this, the video area would just sit blank during the initial load or a
    // mid-playback rebuffer with no indication anything is happening.
    var isBuffering by remember { mutableStateOf(false) }
    fun togglePlayback() {
        val player = youTubePlayer ?: return
        if (isPlaying) player.pause() else player.play()
    }

    // Swipe-down-to-minimize's threshold in dp, not a fixed raw-pixel count - on a real
    // high-density phone (Galaxy S23 and up) a fixed pixel count covers a much shorter physical
    // drag than on a lower-density test device, which read as the video minimizing at the
    // slightest touch. An earlier version also shrank/faded the video live as the drag
    // approached this, for visible feedback before the threshold - removed on request: it read
    // as the video sliding/shrinking away rather than a clean, direct switch to the mini-player,
    // so the switch now just happens outright the moment the drag clears the threshold.
    val density = LocalDensity.current
    val minimizeThresholdPx = with(density) { 96.dp.toPx() }

    // The last sentence to have started, not "the sentence whose own [start, end) contains
    // now" - a strict end-time cutoff left the subtitle blank for a visibly distracting beat
    // in any gap between sentences (a pause, or just the boundary between two adjacent ones).
    // Keeping the previous one on screen until the next one actually starts reads much better.
    val naturalIndex: Int = remember(currentSecond, result) {
        val t = currentSecond.toDouble()
        result.sentences.indexOfLast { t >= it.start }
    }
    // currentSecond only updates when the player's onCurrentSecond callback fires, which lags a
    // real seekTo() by up to its own poll interval - naturalIndex above doesn't reflect a seek
    // until that next callback arrives. Pressing "previous" twice quickly used to compute both
    // presses from that same still-stale naturalIndex, so both seeks landed on the identical
    // target instead of stepping back a second time - reported as the button just repeating the
    // current sentence no matter how many times it's pressed. lastCommandedIndex overrides the
    // index immediately on each press (and on the subtitle/idiom this drives) so a second rapid
    // press steps from where the first one just commanded, not from playback's own lagging idea
    // of where it is; it's released back to natural tracking once real playback catches up to it.
    var lastCommandedIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(naturalIndex) {
        val commanded = lastCommandedIndex
        if (commanded != null && naturalIndex >= commanded) {
            lastCommandedIndex = null
        }
    }
    val currentIndex: Int = lastCommandedIndex ?: naturalIndex
    val currentSentence: Sentence? = currentIndex.takeIf { it >= 0 }?.let { result.sentences[it] }
    val currentIdiom: Idiom? = remember(currentIndex, result) {
        if (currentIndex < 0) null else result.idioms.firstOrNull { it.sentence_index == currentIndex + 1 }
    }
    var idiomExpanded by remember(currentIdiom?.sentence_index) { mutableStateOf(false) }

    // Bookmarking is per-sentence, not per-idiom - any sentence can be saved, whether or not it
    // happens to have a flagged idiom; when it does, that idiom's phrase/note/example just ride
    // along on the same bookmark entry (see SavedIdiomsViewModel.toggleSave).
    val savedIdiomsViewModel: SavedIdiomsViewModel = viewModel()
    val savedIdioms by savedIdiomsViewModel.items.collectAsState()
    val currentSentenceSaved = currentSentence != null &&
        savedIdioms.any { it.videoId == result.video_id && it.sentenceStart == currentSentence.start }
    fun toggleSaveCurrentSentence() {
        val sentence = currentSentence ?: return
        savedIdiomsViewModel.toggleSave(
            videoId = result.video_id,
            videoTitle = videoTitle ?: result.video_id,
            sentenceText = sentence.text,
            sentenceKo = sentence.ko,
            sentenceStart = sentence.start,
            phrase = currentIdiom?.phrase,
            noteKo = currentIdiom?.note_ko,
            example = currentIdiom?.example
        )
    }

    // Pause while reading the explanation, resume on closing it - otherwise the video (and
    // the sentence the badge is even about) keeps moving on while it's being read.
    fun toggleIdiom() {
        idiomExpanded = !idiomExpanded
        if (idiomExpanded) youTubePlayer?.pause() else youTubePlayer?.play()
    }

    // Real per-word timestamps come from the backend (YouTube's own ASR alignment) for any
    // freshly-processed video. A sentence cached before that existed has an empty `words` list -
    // approximate evenly by character share for those rather than show no highlight at all.
    val displayWords: List<Word> = remember(currentSentence) {
        val s = currentSentence ?: return@remember emptyList()
        if (!s.words.isNullOrEmpty()) return@remember s.words
        val tokens = s.text.split(" ").filter { it.isNotEmpty() }
        val totalChars = tokens.sumOf { it.length + 1 }.coerceAtLeast(1)
        val span = (s.end - s.start).coerceAtLeast(0.001)
        var consumed = 0
        tokens.map { token ->
            val start = s.start + span * consumed / totalChars
            consumed += token.length + 1
            val end = s.start + span * consumed / totalChars
            Word(token, start, end)
        }
    }

    // Seeking back to a sentence's start and continuing playback - a quick way to hear/read a
    // line again (or step to the one before/after it) without hunting for the timeline
    // scrubber, which is fiddly for a span that might only be a second or two long.
    fun seekToSentence(index: Int) {
        val sentence = result.sentences.getOrNull(index) ?: return
        val player = youTubePlayer ?: return
        player.seekTo(sentence.start.toFloat())
        player.play()
    }
    // Each of these sets lastCommandedIndex before seeking, not after - so if the player's own
    // position hasn't visibly moved yet by the time the next tap lands, that tap still starts
    // counting from the target this one just set, not from the pre-seek position.
    fun repeatCurrentSentence() {
        lastCommandedIndex = currentIndex
        seekToSentence(currentIndex)
    }

    // Long-pressing a word the learner doesn't know opens it in a browser dictionary right
    // away, rather than leaving them to close the app and look it up themselves - the video
    // keeps playing behind it via the foreground service either way. Trailing punctuation
    // (the raw word token can end in a comma/period) would otherwise get searched literally.
    fun lookUpWord(word: Word) {
        val cleaned = word.text.trim { it.isWhitespace() || it in ",.!?;:\"'()" }
        if (cleaned.isBlank()) return
        val uri = Uri.parse("https://en.dict.naver.com/#/search?query=${Uri.encode(cleaned)}")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }
    fun previousSentence() {
        val target = (currentIndex - 1).coerceAtLeast(0)
        lastCommandedIndex = target
        seekToSentence(target)
    }
    fun nextSentence() {
        val target = (currentIndex + 1).coerceAtMost(result.sentences.lastIndex)
        lastCommandedIndex = target
        seekToSentence(target)
    }

    fun cyclePlaybackSpeed() {
        speedIndex = (speedIndex + 1) % PLAYBACK_RATES.size
        youTubePlayer?.setPlaybackRate(PLAYBACK_RATES[speedIndex].second)
    }

    fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        val act = activity ?: return
        act.requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            // UNSPECIFIED just meant "no preference" - if the phone was still physically held
            // sideways, the screen stayed landscape while the layout below switched back to its
            // portrait-shaped formula (fillMaxWidth * 9/16), which overflows a landscape-height
            // screen and gets clipped instead of shrinking. Force portrait so exiting fullscreen
            // always lands back in the layout it's actually built for.
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val controller = WindowCompat.getInsetsController(act.window, view)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            act.window.attributes = act.window.attributes.apply {
                layoutInDisplayCutoutMode = if (enabled) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { setFullscreen(false) }
    }

    // Keeps the process alive (via a foreground service + its mandatory notification) for as
    // long as this screen has ever started playing - not tied to isPlaying/paused so the
    // notification doesn't flicker on every tap-to-pause. Stops the moment the user actually
    // leaves the video, not just backgrounds the app.
    DisposableEffect(hasStartedPlaying) {
        if (hasStartedPlaying) {
            PlaybackForegroundService.start(context, videoTitle ?: result.video_id)
        }
        onDispose { PlaybackForegroundService.stop(context) }
    }

    // Minimized takes priority: back from a minimized mini-player closes it outright rather
    // than trying to re-expand first - the mini-player bar itself (tap to expand) is already
    // right there if that's what was wanted instead.
    BackHandler {
        when {
            isFullscreen -> setFullscreen(false)
            isMinimized -> onClose()
            else -> onMinimize()
        }
    }

    // Real PiP is its own "small" presentation, driven entirely by the OS window - if the
    // in-app widget's small-corner styling stays applied underneath while isInPip is also true
    // (pressing Home while already minimized), the widget shrinks a second time inside the
    // already-tiny PiP frame instead of the PiP frame just showing the plain video. showAsMini
    // is what every visual/structural isMinimized branch below actually keys on, so real PiP
    // always wins and shows the ordinary full-size video - only isMinimized itself (not this)
    // still decides navigation things like what back should do.
    val showAsMini = isMinimized && !isInPip

    // Collapses to zero automatically once the bars are hidden in fullscreen, and gives the
    // video/subtitles a bit of breathing room under the status bar otherwise - the previous
    // Scaffold-based padding was computed but never actually applied to this screen. Minimized
    // mode instead just wraps the small floating widget's own size - fillMaxSize here would cover
    // (and block touches meant for) the catalog it's supposed to be floating over. Its own bottom
    // inset is handled further down via an explicit Spacer instead of a padding modifier here -
    // see that Spacer's comment for why.
    Column(
        modifier = modifier.then(
            if (showAsMini) Modifier.padding(end = 16.dp)
            else Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        )
    ) {
        // Wrapping the video Box in this Row - unconditionally, in every mode - rather than
        // branching the AndroidView itself between a "full" and "mini" composable is what lets
        // the exact same YouTubePlayerView instance (and its live playback) survive minimizing:
        // Compose identifies a composable by its position in this structure, and putting the
        // AndroidView inside two different branches of an if/else would tear down and recreate
        // it - a fresh, reloaded-from-0 player - every time isMinimized flipped.
        Column(
            modifier = if (showAsMini) {
                // A small floating card - the video on top at a user-resizable width, with a
                // compact live-caption footer beneath it (see MiniPlayerCaption below) - both
                // inside the same rounded, shadowed card, per the Stitch-generated mini-player
                // layout (a media surface plus a separate caption strip under it, not just the
                // bare video YouTube's own in-app mini-player shows).
                Modifier
                    .width(miniWidgetWidth)
                    .shadow(elevation = 10.dp, shape = RoundedCornerShape(16.dp), clip = false)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            } else {
                Modifier
            }
        ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = if (showAsMini) Modifier.fillMaxWidth().aspectRatio(16f / 9f) else Modifier
        ) {
        Box(
            modifier = if (showAsMini) {
                Modifier.fillMaxSize().background(Color.Black)
            } else if (isFullscreen || isInPip) {
                Modifier.fillMaxSize().background(Color.Black)
            } else {
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                // Fullscreen on a wider-than-16:9 display (e.g. the S23's ~19.5:9 in landscape)
                // must letterbox by height, not stretch to fill both dimensions - otherwise the
                // video gets cropped/zoomed and, since the crop pushes the player's own control
                // bar past the visible edge, YouTube's controls appear to vanish along with it.
                // In PiP the window itself is already locked to 16:9 (see MainActivity's
                // PictureInPictureParams), so a plain fill is fine there - same for the small
                // fixed-aspect-ratio box the mini-player gives it.
                modifier = if (isInPip || showAsMini) {
                    Modifier.fillMaxSize()
                } else if (isFullscreen) {
                    Modifier.fillMaxHeight().aspectRatio(16f / 9f)
                } else {
                    Modifier.fillMaxSize()
                },
                factory = { ctx ->
                    YouTubePlayerView(ctx).apply {
                        lifecycleOwner.lifecycle.addObserver(this)
                        enableAutomaticInitialization = false
                        // Without this, the player library itself pauses playback the moment
                        // the Activity's lifecycle hits ON_STOP (screen off, or backgrounded) -
                        // the whole point of the foreground service below is to keep playing
                        // through exactly that.
                        enableBackgroundPlayback(true)
                        // controls(0) hides YouTube's own control bar entirely - this app now
                        // drives play/pause itself (tap-to-toggle below) and sentence navigation
                        // via its own controls, so YouTube's bar was a second, mostly-redundant
                        // control surface. ccLoadPolicy(0) asks YouTube not to default captions
                        // on - a request, not a hard override: a video whose uploader forced
                        // captions on for that specific video can still show them regardless.
                        val options = IFramePlayerOptions.Builder(ctx)
                            .controls(0)
                            .rel(0)
                            .ccLoadPolicy(0)
                            .build()
                        initialize(object : AbstractYouTubePlayerListener() {
                            override fun onReady(player: YouTubePlayer) {
                                youTubePlayer = player
                                player.loadVideo(result.video_id, startSecond ?: 0f)
                            }

                            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                                currentSecond = second
                            }

                            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                                if (state == PlayerConstants.PlayerState.PLAYING) {
                                    hasStartedPlaying = true
                                }
                                isPlaying = state == PlayerConstants.PlayerState.PLAYING
                                isBuffering = state == PlayerConstants.PlayerState.BUFFERING
                                if (state == PlayerConstants.PlayerState.ENDED) {
                                    onVideoEnded()
                                }
                            }
                        }, options)
                    }
                },
                // Only captures the view reference for the requestLayout() nudge below.
                update = { view -> playerViewRef = view }
            )

            // Shrinking straight from fullscreen width down to the small corner widget can leave
            // a stray sliver of the WebView's frame duplicated near the top of the screen for a
            // moment - a hardware-layer compositing artifact from the sudden resize. A previous
            // fix forced this view onto a software-rendered layer during that transition, which
            // did clear the sliver on the emulator, but on real hardware (confirmed on a Galaxy
            // S23) forcing software rendering stops the actual video frame from compositing at
            // all - the exact same failure mode already found and fixed once for real PiP's own
            // transition (see git history for the black-PiP-screen fix), just re-introduced here
            // for the widget's own resize instead. A plain requestLayout() is a much smaller
            // hammer - it doesn't fully prevent the sliver, but it doesn't risk the video itself.
            LaunchedEffect(showAsMini) {
                playerViewRef?.requestLayout()
            }

            // A transparent overlay on top of the AndroidView, exactly like the tap-catcher below
            // for the normal/fullscreen views - without one, the raw WebView underneath receives
            // taps and drags directly, and its own native touch handling (deciding whether to
            // scroll/handle them itself) can keep them from ever reaching a clickable() or
            // pointerInput() modifier placed only on the outer Row: tap-to-expand and drag-to-
            // expand both silently did nothing until this was added, confirmed by testing
            // coordinates that were unambiguously over plain video content, not the icon buttons.
            // Also carries the same paused-state scrim as the full view below, for the same
            // reason: YouTube's iframe shows its own promotional overlay (channel branding, a
            // suggested-video card, a share icon) whenever paused, with no way to disable it -
            // the mini widget never had a scrim of its own, so pausing it left that showing.
            // While playing, a much subtler top/bottom gradient (per the Stitch mini-player
            // layout) replaces the plain transparent background instead, just enough to keep the
            // corner icon buttons readable against a bright video frame.
            if (showAsMini) {
                val miniScrimBrush = Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
                    0.45f to Color.Transparent,
                    1f to MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.9f)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (hasStartedPlaying && !isPlaying) {
                                Modifier.background(Color.Black.copy(alpha = 0.94f))
                            } else {
                                Modifier.background(miniScrimBrush)
                            }
                        )
                        .pointerInput(Unit) {
                            var totalDrag = 0f
                            val expandThresholdPx = 40.dp.toPx()
                            detectVerticalDragGestures(
                                onDragStart = { totalDrag = 0f },
                                onDragEnd = {
                                    if (totalDrag <= -expandThresholdPx) onExpand()
                                },
                                onVerticalDrag = { change, dragAmount ->
                                    change.consume()
                                    totalDrag += dragAmount
                                }
                            )
                        }
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onExpand
                        )
                )
            }

            // Tap-anywhere-to-toggle playback, in both the small embedded view and fullscreen -
            // safe to sit right over the video now that YouTube's own control bar is off
            // (controls(0) above), so there's no native touch target underneath left to block.
            // While paused, YouTube's iframe shows its own promotional overlay underneath
            // (channel branding, a suggested-video card, a share icon) - there's no public
            // parameter to turn that off, so a solid scrim on top hides it instead, with our
            // own play icon as the only thing the viewer actually sees.
            if (!isInPip && !isMinimized) {
                val paused = hasStartedPlaying && !isPlaying
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (paused) Color.Black.copy(alpha = 0.94f) else Color.Transparent)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = ::togglePlayback
                        )
                        // Swipe-down-to-minimize (YouTube/Netflix-style) - only in the normal
                        // small view; fullscreen swipe-down is left alone rather than also
                        // trying to minimize straight out of it in the same gesture. A plain
                        // accumulation via a closure var, checked once the gesture ends - no
                        // visual change while dragging, so clearing the threshold switches
                        // straight to the mini-player instead of easing into it.
                        .then(
                            if (!isFullscreen) {
                                Modifier.pointerInput(Unit) {
                                    var totalDrag = 0f
                                    detectVerticalDragGestures(
                                        onDragStart = { totalDrag = 0f },
                                        onDragEnd = {
                                            if (totalDrag >= minimizeThresholdPx) {
                                                onMinimize()
                                            }
                                        },
                                        onVerticalDrag = { change, dragAmount ->
                                            change.consume()
                                            totalDrag = (totalDrag + dragAmount).coerceAtLeast(0f)
                                        }
                                    )
                                }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (!hasStartedPlaying || isBuffering) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                    } else if (paused) {
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.16f), CircleShape)
                                .size(72.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "재생",
                                tint = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }
            }

            // Pause/play and close, overlaid directly on the small floating video itself (top
            // corner, small semi-transparent circles) rather than laid out beside it in a bar -
            // matching YouTube's own in-app mini-player. Button chrome color now follows the
            // theme's surfaceContainerLowest token (per the Stitch mini-player layout) instead of
            // a hardcoded black, so it tracks the rest of the palette if that changes again.
            val miniControlBg = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.8f)
            if (showAsMini) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                ) {
                    IconButton(
                        onClick = ::togglePlayback,
                        modifier = Modifier.size(26.dp).background(miniControlBg, CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "일시정지" else "재생",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(26.dp).background(miniControlBg, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "닫기",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                // A resize handle in the opposite (top-start) corner from pause/close - the
                // widget is anchored bottom-end (see MainActivity), so dragging this corner
                // further away shrinks the anchor point's distance to it, i.e. grows the widget;
                // dragging it toward the anchor shrinks the widget. Width alone drives it since
                // the aspect ratio is fixed, and it's clamped to miniWidgetWidthRange so it can't
                // be dragged down to nothing or up past a small-window-sized ceiling. The
                // open_in_full icon (matching Stitch's mini-player mockup) reads as "tap this to
                // maximize" - reported as "doesn't seem to work" when it only handled drag, so a
                // plain tap now also expands, same as tapping the video itself does; only a real
                // drag (past touch slop, which detectDragGestures already requires before calling
                // onDrag) resizes instead.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .size(26.dp)
                        .background(miniControlBg, CircleShape)
                        .pointerInput(Unit) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                miniWidgetWidth = (miniWidgetWidth - with(density) { dragAmount.x.toDp() })
                                    .coerceIn(miniWidgetWidthRange)
                            }
                        }
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onExpand
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.OpenInFull,
                        contentDescription = "크기 조절",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            // Enter-fullscreen lives on the video itself (bottom-right, the same spot YouTube's
            // own player puts it) rather than down in the control row below the subtitles - it's
            // an action about the video, so reaching it shouldn't require leaving the video. The
            // matching exit button below is already overlaid the same way once inside fullscreen.
            if (!isFullscreen && !isInPip && !isMinimized) {
                IconButton(
                    onClick = { setFullscreen(true) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "화면 크게",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Only overlaid on the video itself in fullscreen, where it takes up the whole
            // screen and there's nowhere else to put them - the small embedded view instead
            // gets the equivalent controls in the row below the video.
            if (isFullscreen && !isInPip) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp)
                ) {
                    PlaybackSpeedButton(speedIndex = speedIndex, onCycle = ::cyclePlaybackSpeed)
                    IconButton(
                        onClick = { setFullscreen(!isFullscreen) },
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            .size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FullscreenExit,
                            contentDescription = "화면 축소",
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            if (isFullscreen && !isInPip && (currentSentence != null || !videoTitle.isNullOrBlank())) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                ) {
                if (hasStartedPlaying && currentSentence != null) {
                    val styles = subtitleStyles(currentSentence.text, currentSentence.ko)
                    currentIdiom?.let { idiom ->
                        IdiomBadge(idiom, idiomExpanded, ::toggleIdiom, onDarkBackground = true)
                    }
                    RepeatableSentence(
                        words = displayWords,
                        currentSecond = currentSecond.toDouble(),
                        style = styles.english,
                        highlightColor = KaraokeHighlightBlue,
                        baseColor = Color.White,
                        onRepeat = ::repeatCurrentSentence,
                        onLookUpWord = ::lookUpWord
                    )
                    // A translucent card around the Korean line, distinct from the plain-on-
                    // scrim English line above it - reported as tiring to read when the two
                    // languages ran together with no separation, just a little vertical gap.
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
                    ) {
                        Text(
                            text = currentSentence.ko,
                            style = styles.korean,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = ::toggleSaveCurrentSentence, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = if (currentSentenceSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = if (currentSentenceSaved) "단어장에서 제거" else "이 문장 저장",
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    SentenceNavControls(
                        onPrevious = ::previousSentence,
                        onNext = ::nextSentence,
                        onDarkBackground = true,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                } else if (!videoTitle.isNullOrBlank()) {
                    Text(
                        text = videoTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2
                    )
                }
                }
            }
        }
        }
        if (showAsMini) {
            MiniPlayerCaption(
                currentSentence = currentSentence,
                displayWords = displayWords,
                currentSecond = currentSecond.toDouble()
            )
        }
        }

        // A padding modifier on this same Column (or on the modifier MainActivity passes in,
        // which carries align(BottomCenter) against its own edge-to-edge Box) measured fine but
        // visibly failed to keep the bar clear of the nav bar on-device - confirmed on the
        // emulator with a 3-button nav bar, and confirmed via an on-screen debug readout that
        // WindowInsets.navigationBars itself WAS reporting the correct non-zero value at this
        // exact point in the tree, so the value was never the problem, only the padding modifier
        // not translating it into actual layout space here. An explicit Spacer sized to that
        // inset is unambiguous - it's real, un-collapsible height in the Column's child order -
        // where a padding modifier on a wrap-content element inside an aligned Box apparently
        // wasn't.
        if (showAsMini) {
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }

        // Hide every custom control/subtitle overlay while in PiP - the floating window is
        // tiny, only shows the video itself, and none of this UI would be usable in it anyway.
        // Minimized has none of this either - the mini-bar row above is the entire UI then.
        if (!isMinimized && !isInPip) {
        if (!isFullscreen) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                if (hasStartedPlaying && currentSentence != null) {
                    val styles = subtitleStyles(currentSentence.text, currentSentence.ko)
                    Column {
                        currentIdiom?.let { idiom ->
                            IdiomBadge(idiom, idiomExpanded, ::toggleIdiom, onDarkBackground = false)
                        }
                        RepeatableSentence(
                            words = displayWords,
                            currentSecond = currentSecond.toDouble(),
                            style = styles.english,
                            highlightColor = KaraokeHighlightBlue,
                            baseColor = MaterialTheme.colorScheme.onSurface,
                            onRepeat = ::repeatCurrentSentence,
                            onLookUpWord = ::lookUpWord
                        )
                        // A tinted card around the Korean line, distinct from the English line
                        // above it - reported as tiring to read when the two languages ran
                        // together with no visual separation, just a little vertical gap.
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .padding(top = 14.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
                        ) {
                            Text(
                                text = currentSentence.ko,
                                style = styles.korean,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = ::toggleSaveCurrentSentence, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector = if (currentSentenceSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                    contentDescription = if (currentSentenceSaved) "단어장에서 제거" else "이 문장 저장",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        // One centered control cluster - speed pill and prev/next grouped
                        // together with a small gap - instead of the speed pill floating alone
                        // on the far left with a wide empty gap before the centered nav buttons.
                        // Reads as one deliberately-designed control bar instead of two unrelated
                        // controls that happened to land in the same row.
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp),
                        ) {
                            Spacer(Modifier.weight(1f))
                            PlaybackSpeedButton(
                                speedIndex = speedIndex,
                                onCycle = ::cyclePlaybackSpeed,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            SentenceNavControls(
                                onPrevious = ::previousSentence,
                                onNext = ::nextSentence,
                                onDarkBackground = false
                            )
                            Spacer(Modifier.weight(1f))
                        }
                    }
                } else {
                    Text(
                        text = videoTitle?.takeIf { it.isNotBlank() } ?: "재생하면 영문 자막이 여기 표시됩니다",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3
                    )
                }
            }
        }
        }
    }
}
