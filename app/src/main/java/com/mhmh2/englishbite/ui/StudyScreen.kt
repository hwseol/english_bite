package com.mhmh2.englishbite.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mhmh2.englishbite.data.Sentence
import com.mhmh2.englishbite.data.VideoResult
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

@Composable
fun StudyScreen(result: VideoResult, onBack: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentSecond by remember { mutableFloatStateOf(0f) }
    var isExpanded by remember { mutableStateOf(false) }

    val currentSentence: Sentence? = remember(currentSecond, result) {
        val t = currentSecond.toDouble()
        result.sentences.firstOrNull { t >= it.start && t < it.end }
    }

    val progress = remember(currentSecond, currentSentence) {
        val s = currentSentence ?: return@remember 0f
        val span = (s.end - s.start).coerceAtLeast(0.001)
        ((currentSecond - s.start) / span).toFloat()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = if (isExpanded) {
                Modifier.fillMaxWidth().fillMaxHeight(0.75f)
            } else {
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            },
            factory = { context ->
                YouTubePlayerView(context).apply {
                    lifecycleOwner.lifecycle.addObserver(this)
                    enableAutomaticInitialization = false
                    // Trim YouTube's own chrome down to just playback controls: no end-screen
                    // suggested videos, no click-through annotations, captions off by default
                    // (we render our own).
                    val options = IFramePlayerOptions.Builder(context)
                        .controls(1)
                        .rel(0)
                        .ivLoadPolicy(3)
                        .ccLoadPolicy(0)
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

        TextButton(onClick = { isExpanded = !isExpanded }) {
            Text(if (isExpanded) "화면 작게" else "화면 크게")
        }

        if (!isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            ) {
                if (currentSentence != null) {
                    Column {
                        KaraokeText(
                            text = currentSentence.text,
                            progress = progress,
                            style = MaterialTheme.typography.headlineMedium,
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

            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 8.dp)
            ) {
                Text("다른 영상 입력")
            }
        }
    }
}
