package com.mhmh2.englishbite

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import com.mhmh2.englishbite.ui.CatalogScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EnglishBiteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val ingestState by studyViewModel.uiState.collectAsState()
                    var currentVideoTitle by remember { mutableStateOf<String?>(null) }
                    var pendingStartSecond by remember { mutableStateOf<Float?>(null) }
                    var showVocabulary by remember { mutableStateOf(false) }
                    // Hoisted above the when() so it survives being navigated away from and
                    // back to (a StudyScreen visit removes CatalogScreen from composition
                    // entirely, so state remember'd inside it - like a LazyListState created
                    // there - does not survive; this one, above the when, does).
                    val catalogListState = rememberLazyListState()

                    when (val current = ingestState) {
                        is UiState.Success -> StudyScreen(
                            result = current.result,
                            onBack = { studyViewModel.reset() },
                            videoTitle = currentVideoTitle,
                            startSecond = pendingStartSecond,
                            isInPip = isInPip,
                            onRequestPip = ::enterPipMode
                        )

                        else -> {
                            if (showVocabulary) {
                                BackHandler { showVocabulary = false }
                                VocabularyScreen(
                                    onBack = { showVocabulary = false },
                                    onSelect = { item ->
                                        showVocabulary = false
                                        currentVideoTitle = item.videoTitle
                                        pendingStartSecond = item.sentenceStart.toFloat()
                                        studyViewModel.submitUrl("https://www.youtube.com/watch?v=${item.videoId}")
                                    }
                                )
                            } else {
                                val catalogState by catalogViewModel.state.collectAsState()
                                val channelFilter by catalogViewModel.channelFilter.collectAsState()
                                val sort by catalogViewModel.sort.collectAsState()
                                CatalogScreen(
                                    state = catalogState,
                                    channelFilter = channelFilter,
                                    sort = sort,
                                    listState = catalogListState,
                                    onChannelFilterChange = catalogViewModel::setChannelFilter,
                                    onSortChange = catalogViewModel::setSort,
                                    onSelect = { item ->
                                        currentVideoTitle = item.title
                                        pendingStartSecond = null
                                        studyViewModel.submitUrl("https://www.youtube.com/watch?v=${item.video_id}")
                                    },
                                    onOpenVocabulary = { showVocabulary = true }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
