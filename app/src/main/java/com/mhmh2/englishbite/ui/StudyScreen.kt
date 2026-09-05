package com.mhmh2.englishbite.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mhmh2.englishbite.data.Sentence
import com.mhmh2.englishbite.data.VideoResult
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

/** Long sentences would otherwise overflow/clip in the fixed subtitle area - step the type
 * scale down as the sentence gets longer instead of letting it run off the edge. */
@Composable
private fun englishSubtitleStyle(text: String): TextStyle = when {
    text.length > 140 -> MaterialTheme.typography.bodyLarge
    text.length > 90 -> MaterialTheme.typography.titleLarge
    else -> MaterialTheme.typography.headlineMedium
}

@Composable
fun StudyScreen(result: VideoResult, onBack: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val view = LocalView.current

    var currentSecond by remember { mutableFloatStateOf(0f) }
    var isFullscreen by remember { mutableStateOf(false) }

    val currentSentence: Sentence? = remember(currentSecond, result) {
        val t = currentSecond.toDouble()
        result.sentences.firstOrNull { t >= it.start && t < it.end }
    }
    val progress = remember(currentSecond, currentSentence) {
        val s = currentSentence ?: return@remember 0f
        val span = (s.end - s.start).coerceAtLeast(0.001)
        ((currentSecond - s.start) / span).toFloat()
    }

    fun setFullscreen(enabled: Boolean) {
        isFullscreen = enabled
        val act = activity ?: return
        act.requestedOrientation = if (enabled) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
                        // Only suppress end-screen related-video suggestions - leave captions,
                        // annotations, and everything else at YouTube's normal defaults so its
                        // native controls (including the CC toggle) work as expected.
                        val options = IFramePlayerOptions.Builder(ctx)
                            .controls(1)
                            .rel(0)
                            .build()
                        initialize(object : AbstractYouTubePlayerListener() {
                            override fun onReady(youTubePlayer: YouTubePlayer) {
                                youTubePlayer.loadVideo(result.video_id, 0f)
                            }

                            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                                currentSecond = second
                            }
                        }, options)
                    }
                }
            )

            // Top-end, not bottom-end: YouTube's own control bar already occupies the bottom
            // edge, and our icon was getting lost against/behind it there.
            IconButton(
                onClick = { setFullscreen(!isFullscreen) },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = if (isFullscreen) "화면 축소" else "화면 크게",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            if (isFullscreen && currentSentence != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    KaraokeText(
                        text = currentSentence.text,
                        progress = progress,
                        style = englishSubtitleStyle(currentSentence.text),
                        highlightColor = MaterialTheme.colorScheme.primary,
                        baseColor = Color.White
                    )
                    Text(
                        text = currentSentence.ko,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 4.dp)
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
                    Column {
                        KaraokeText(
                            text = currentSentence.text,
                            progress = progress,
                            style = englishSubtitleStyle(currentSentence.text),
                            highlightColor = MaterialTheme.colorScheme.primary,
                            baseColor = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = currentSentence.ko,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp)
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
