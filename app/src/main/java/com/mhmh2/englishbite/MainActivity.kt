package com.mhmh2.englishbite

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mhmh2.englishbite.ui.CatalogScreen
import com.mhmh2.englishbite.ui.CatalogState
import com.mhmh2.englishbite.ui.CatalogViewModel
import com.mhmh2.englishbite.ui.StudyScreen
import com.mhmh2.englishbite.ui.StudyViewModel
import com.mhmh2.englishbite.ui.UiState
import com.mhmh2.englishbite.ui.theme.EnglishBiteTheme
import com.mhmh2.englishbite.vocab.VocabularyScreen

class MainActivity : ComponentActivity() {
    private val studyViewModel: StudyViewModel by viewModels()
    private val catalogViewModel: CatalogViewModel by viewModels()

    // Read/written from both the Activity's PiP lifecycle callbacks and the Compose tree -
    // a plain mutableStateOf works as the bridge since Compose recomposes on reads of it
    // regardless of where the write came from.
    private var isInPip by mutableStateOf(false)

    // Pressing Home/Recents while a video is playing should enter PiP automatically, matching
    // what every other video app does - not require finding a dedicated button first.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (studyViewModel.uiState.value is UiState.Success) {
            enterPipMode()
        }
    }

    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
    }

    // Real users switch back to the app instead of force-closing and relaunching it, so a
    // catalog fetched only once at process start (CatalogViewModel's init block) would stay
    // stale for as long as the process stays alive - which on a phone can be all day. Refreshing
    // on every resume (silently - see CatalogViewModel.refresh) means newly-processed videos
    // show up without the user having to do anything.
    override fun onResume() {
        super.onResume()
        catalogViewModel.refresh(silent = true)
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way -
            the foreground service for background playback still starts without it; the user
            just won't see its notification, matching what happens if they deny/revoke it later. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Needed on API 33+ for the background-playback foreground service's notification to
        // actually show - asked once up front rather than at the moment a video starts playing,
        // so it doesn't interrupt someone mid-video.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            EnglishBiteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val ingestState by studyViewModel.uiState.collectAsState()
                    var currentVideoTitle by remember { mutableStateOf<String?>(null) }
                    var pendingStartSecond by remember { mutableStateOf<Float?>(null) }
                    var showVocabulary by remember { mutableStateOf(false) }
                    // The mini-player: back (or a swipe-down, from StudyScreen) sets this instead
                    // of tearing the video down, so picking something else off the catalog while
                    // it's playing small is possible - StudyScreen itself keeps running the same
                    // YouTubePlayer instance underneath regardless of this flag; see its own
                    // comment on why the AndroidView call site can't be branched on it directly.
                    var isMinimized by remember { mutableStateOf(false) }
                    // Hoisted above the when() so it survives being navigated away from and
                    // back to (a StudyScreen visit removes CatalogScreen from composition
                    // entirely, so state remember'd inside it - like a LazyListState created
                    // there - does not survive; this one, above the when, does).
                    val catalogListState = rememberLazyListState()
                    // Also hoisted: auto-advance needs "what's the next video after this one"
                    // even while StudyScreen (not CatalogScreen) is the thing on screen.
                    val catalogState by catalogViewModel.state.collectAsState()

                    fun playVideo(videoId: String, title: String, startSecond: Float? = null) {
                        currentVideoTitle = title
                        pendingStartSecond = startSecond
                        isMinimized = false // a freshly-picked video always opens full, even if
                                            // something else was playing minimized a moment ago
                        studyViewModel.submitUrl("https://www.youtube.com/watch?v=$videoId")
                    }

                    fun playNextAfter(videoId: String) {
                        val items = (catalogState as? CatalogState.Loaded)?.items ?: return
                        val nextIndex = items.indexOfFirst { it.video_id == videoId } + 1
                        items.getOrNull(nextIndex)?.let { playVideo(it.video_id, it.title) }
                    }

                    val current = ingestState
                    Box(Modifier.fillMaxSize()) {
                        // Catalog/vocab sits underneath whenever there's no video loaded at all,
                        // or the loaded one has been minimized - not an else-branch of the
                        // Success check below, since both can be true/visible at once.
                        if (current !is UiState.Success || isMinimized) {
                            if (showVocabulary) {
                                BackHandler { showVocabulary = false }
                                VocabularyScreen(
                                    onBack = { showVocabulary = false },
                                    onSelect = { item ->
                                        showVocabulary = false
                                        playVideo(item.videoId, item.videoTitle, item.sentenceStart.toFloat())
                                    }
                                )
                            } else {
                                val channelFilter by catalogViewModel.channelFilter.collectAsState()
                                val categoryFilter by catalogViewModel.categoryFilter.collectAsState()
                                val sort by catalogViewModel.sort.collectAsState()
                                val searchQuery by catalogViewModel.searchQuery.collectAsState()
                                CatalogScreen(
                                    state = catalogState,
                                    channelFilter = channelFilter,
                                    categoryFilter = categoryFilter,
                                    sort = sort,
                                    searchQuery = searchQuery,
                                    listState = catalogListState,
                                    onChannelFilterChange = catalogViewModel::setChannelFilter,
                                    onCategoryFilterChange = catalogViewModel::setCategoryFilter,
                                    onSortChange = catalogViewModel::setSort,
                                    onSearchQueryChange = catalogViewModel::setSearchQuery,
                                    onSelect = { item -> playVideo(item.video_id, item.title) },
                                    onOpenVocabulary = { showVocabulary = true }
                                )
                            }
                        }

                        // Keyed on video_id: auto-advance (or picking a new video while one is
                        // already minimized) moves straight from one Success state to another
                        // (same UiState subtype, different video) - without this key, Compose
                        // treats that as the same StudyScreen instance and never re-runs the
                        // YouTubePlayerView's factory, so the player would just keep showing
                        // whatever video it first loaded.
                        if (current is UiState.Success) {
                            key(current.result.video_id) {
                                StudyScreen(
                                    result = current.result,
                                    videoTitle = currentVideoTitle,
                                    startSecond = pendingStartSecond,
                                    isInPip = isInPip,
                                    onVideoEnded = { playNextAfter(current.result.video_id) },
                                    isMinimized = isMinimized,
                                    onMinimize = { isMinimized = true },
                                    onExpand = { isMinimized = false },
                                    onClose = {
                                        isMinimized = false
                                        studyViewModel.reset()
                                    },
                                    modifier = if (isMinimized) Modifier.align(Alignment.BottomCenter) else Modifier
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
