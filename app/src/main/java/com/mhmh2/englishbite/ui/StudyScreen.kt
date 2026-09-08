package com.mhmh2.englishbite.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mhmh2.englishbite.data.Idiom
import com.mhmh2.englishbite.data.Sentence
import com.mhmh2.englishbite.data.VideoResult
import com.mhmh2.englishbite.data.Word
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
private fun IdiomBadge(idiom: Idiom, expanded: Boolean, onToggle: () -> Unit, onDarkBackground: Boolean) {
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
                Text(
                    text = idiom.note_ko,
                    style = MaterialTheme.typography.bodySmall,
                    color = noteColor,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString("${idiom.phrase}\n${idiom.note_ko}")) },
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

/** Tapping the line itself also repeats it (seeks back to its start) - on top of the explicit
 * prev/repeat/next controls below, since the whole point is not needing to aim for a small
 * button when the sentence you just heard is still in your ear. */
@Composable
private fun RepeatableSentence(
    words: List<Word>,
    currentSecond: Double,
    style: TextStyle,
    highlightColor: Color,
    baseColor: Color,
    onRepeat: () -> Unit
) {
    KaraokeText(
        words = words,
        currentSecond = currentSecond,
        style = style,
        highlightColor = highlightColor,
        baseColor = baseColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRepeat)
    )
}

/** Explicit transport controls for stepping between sentences - previous/repeat/next, each
 * seeking the video and keeping it playing. More discoverable and easier to hit than relying
 * on tapping text alone, especially for "go back one" which has no text of its own to tap. */
@Composable
private fun SentenceNavControls(
    onPrevious: () -> Unit,
    onRepeat: () -> Unit,
    onNext: () -> Unit,
    onDarkBackground: Boolean
) {
    // A classic media-transport layout - prev / (bigger, accented) replay / next - rather than
    // three identical flat icons floating with no visual hierarchy or sense of being buttons.
    val secondaryBg = if (onDarkBackground) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
    val secondaryTint = if (onDarkBackground) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(top = 10.dp)
    ) {
        IconButton(
            onClick = onPrevious,
            modifier = Modifier.background(secondaryBg, CircleShape).size(38.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "이전 문장",
                tint = secondaryTint,
                modifier = Modifier.size(22.dp)
            )
        }
        IconButton(
            onClick = onRepeat,
            modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Replay,
                contentDescription = "이 문장 다시 듣기",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(24.dp)
            )
        }
        IconButton(
            onClick = onNext,
            modifier = Modifier.background(secondaryBg, CircleShape).size(38.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "다음 문장",
                tint = secondaryTint,
                modifier = Modifier.size(22.dp)
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
    contentColor: Color = Color.White
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(containerColor)
            .clickable(onClick = onCycle)
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

@Composable
fun StudyScreen(result: VideoResult, onBack: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val view = LocalView.current

    var currentSecond by remember { mutableFloatStateOf(0f) }
    var isFullscreen by remember { mutableStateOf(false) }
    var youTubePlayer by remember { mutableStateOf<YouTubePlayer?>(null) }
    var speedIndex by remember { mutableStateOf(PLAYBACK_RATES.indexOfFirst { it.first == 1f }) }

    // The last sentence to have started, not "the sentence whose own [start, end) contains
    // now" - a strict end-time cutoff left the subtitle blank for a visibly distracting beat
    // in any gap between sentences (a pause, or just the boundary between two adjacent ones).
    // Keeping the previous one on screen until the next one actually starts reads much better.
    val currentIndex: Int = remember(currentSecond, result) {
        val t = currentSecond.toDouble()
        result.sentences.indexOfLast { t >= it.start }
    }
    val currentSentence: Sentence? = currentIndex.takeIf { it >= 0 }?.let { result.sentences[it] }
    val currentIdiom: Idiom? = remember(currentIndex, result) {
        if (currentIndex < 0) null else result.idioms.firstOrNull { it.sentence_index == currentIndex + 1 }
    }
    var idiomExpanded by remember(currentIdiom?.sentence_index) { mutableStateOf(false) }

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
    fun repeatCurrentSentence() = seekToSentence(currentIndex)
    fun previousSentence() = seekToSentence(currentIndex - 1)
    fun nextSentence() = seekToSentence(currentIndex + 1)

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

    BackHandler {
        if (isFullscreen) setFullscreen(false) else onBack()
    }

    // Collapses to zero automatically once the bars are hidden in fullscreen, and gives the
    // video/subtitles a bit of breathing room under the status bar otherwise - the previous
    // Scaffold-based padding was computed but never actually applied to this screen.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Box(
            modifier = if (isFullscreen) {
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
                modifier = if (isFullscreen) {
                    Modifier.fillMaxHeight().aspectRatio(16f / 9f)
                } else {
                    Modifier.fillMaxSize()
                },
                factory = { ctx ->
                    YouTubePlayerView(ctx).apply {
                        lifecycleOwner.lifecycle.addObserver(this)
                        enableAutomaticInitialization = false
                        // ccLoadPolicy(0) asks YouTube not to default captions on - we already
                        // show our own English+Korean subtitles, so its native captions on top
                        // were a second, redundant subtitle line stacked on the video itself.
                        // This is a request, not a hard override: a video whose uploader forced
                        // captions on for that specific video can still show them regardless.
                        val options = IFramePlayerOptions.Builder(ctx)
                            .controls(1)
                            .rel(0)
                            .ccLoadPolicy(0)
                            .build()
                        initialize(object : AbstractYouTubePlayerListener() {
                            override fun onReady(player: YouTubePlayer) {
                                youTubePlayer = player
                                player.loadVideo(result.video_id, 0f)
                            }

                            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                                currentSecond = second
                            }
                        }, options)
                    }
                }
            )

            // Only overlaid on the video itself in fullscreen, where it takes up the whole
            // screen and there's nowhere else to put them. In the small embedded view this
            // same corner is where YouTube draws its own controls, and the overlay was
            // intercepting taps meant for those - see the control row below the video instead.
            if (isFullscreen) {
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

            if (isFullscreen && currentSentence != null) {
                val styles = subtitleStyles(currentSentence.text, currentSentence.ko)
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    currentIdiom?.let { idiom ->
                        IdiomBadge(idiom, idiomExpanded, ::toggleIdiom, onDarkBackground = true)
                    }
                    RepeatableSentence(
                        words = displayWords,
                        currentSecond = currentSecond.toDouble(),
                        style = styles.english,
                        highlightColor = MaterialTheme.colorScheme.primary,
                        baseColor = Color.White,
                        onRepeat = ::repeatCurrentSentence
                    )
                    Text(
                        text = currentSentence.ko,
                        style = styles.korean,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    SentenceNavControls(
                        onPrevious = ::previousSentence,
                        onRepeat = ::repeatCurrentSentence,
                        onNext = ::nextSentence,
                        onDarkBackground = true
                    )
                }
            }
        }

        if (!isFullscreen) {
            // Kept off the video surface itself here (unlike in fullscreen) so it doesn't sit
            // on top of - and block taps on - YouTube's own controls in the small embedded view.
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                PlaybackSpeedButton(
                    speedIndex = speedIndex,
                    onCycle = ::cyclePlaybackSpeed,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { setFullscreen(true) },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "화면 크게",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (!isFullscreen) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                if (currentSentence != null) {
                    val styles = subtitleStyles(currentSentence.text, currentSentence.ko)
                    Column {
                        currentIdiom?.let { idiom ->
                            IdiomBadge(idiom, idiomExpanded, ::toggleIdiom, onDarkBackground = false)
                        }
                        RepeatableSentence(
                            words = displayWords,
                            currentSecond = currentSecond.toDouble(),
                            style = styles.english,
                            highlightColor = MaterialTheme.colorScheme.primary,
                            baseColor = MaterialTheme.colorScheme.onSurface,
                            onRepeat = ::repeatCurrentSentence
                        )
                        Text(
                            text = currentSentence.ko,
                            style = styles.korean,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        SentenceNavControls(
                            onPrevious = ::previousSentence,
                            onRepeat = ::repeatCurrentSentence,
                            onNext = ::nextSentence,
                            onDarkBackground = false
                        )
                    }
                } else {
                    Text(
                        "재생하면 영문 자막이 여기 표시됩니다",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
