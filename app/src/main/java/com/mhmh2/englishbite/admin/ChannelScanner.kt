package com.mhmh2.englishbite.admin

import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import kotlin.math.min

/** Mirrors backend/translate/catalog.py's CHANNELS/WINDOW_SECONDS/MAX_DURATION_SECONDS/
 * RECENT_CHECK_COUNT constants - this runs the same discovery step the server used to run
 * itself via yt-dlp, just from the admin's phone instead, since YouTube blocks the server's
 * (cloud, datacenter) IP but not a normal residential/mobile one. */
object ChannelScanner {
    val CHANNELS = mapOf(
        "CNN" to "https://www.youtube.com/@CNN",
        "BBC News" to "https://www.youtube.com/@BBCNews",
        "Bloomberg" to "https://www.youtube.com/@markets",
        "The Economist" to "https://www.youtube.com/@TheEconomist",
        "Fox Business" to "https://www.youtube.com/@FoxBusiness",
    )

    private const val MAX_DURATION_SECONDS = 20 * 60
    private const val RECENT_CHECK_COUNT = 40
    private const val WINDOW_SECONDS = 24 * 60 * 60L

    data class ScannedVideo(
        val videoId: String,
        val title: String,
        val channel: String,
        val thumbnailUrl: String?,
        val viewCount: Long,
        val durationSeconds: Int,
        val uploadDate: String,
        val timestamp: Long,
    )

    private var initialized = false

    @Synchronized
    private fun ensureInit() {
        if (!initialized) {
            NewPipe.init(NewPipeDownloader.instance())
            initialized = true
        }
    }

    /** Scans every configured channel's most recent uploads and returns the ones from the
     * last 24h that are short enough for the app to use - same filtering catalog.py did. */
    fun scanAll(onProgress: (String) -> Unit = {}): List<ScannedVideo> {
        ensureInit()
        val cutoff = System.currentTimeMillis() / 1000 - WINDOW_SECONDS
        val results = mutableListOf<ScannedVideo>()

        for ((channelName, channelUrl) in CHANNELS) {
            onProgress("[$channelName] 최근 영상 확인 중...")
            try {
                results += scanChannel(channelName, channelUrl, cutoff, onProgress)
            } catch (e: Exception) {
                onProgress("[$channelName] 스캔 실패: ${e.message}")
            }
        }
        return results
    }

    private fun scanChannel(
        channelName: String,
        channelUrl: String,
        cutoff: Long,
        onProgress: (String) -> Unit,
    ): List<ScannedVideo> {
        val service = ServiceList.YouTube
        val tabExtractor = service.getChannelTabExtractorFromId(
            service.getChannelExtractor(channelUrl).also { it.fetchPage() }.id,
            ChannelTabs.VIDEOS,
        )
        tabExtractor.fetchPage()

        val items = tabExtractor.initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .let { it.subList(0, min(it.size, RECENT_CHECK_COUNT)) }

        val found = mutableListOf<ScannedVideo>()
        for (item in items) {
            val uploadInstant = item.uploadDate?.instant
            if (uploadInstant == null) {
                continue // can't place it in the 24h window without a real timestamp - skip
            }
            val timestamp = uploadInstant.epochSecond
            if (timestamp < cutoff) {
                break // channel tabs list newest-first, so nothing after this is in-window either
            }
            val duration = item.duration.toInt()
            if (duration > MAX_DURATION_SECONDS || duration <= 0) {
                continue
            }
            val videoId = extractVideoId(item.url) ?: continue
            found += ScannedVideo(
                videoId = videoId,
                title = item.name,
                channel = channelName,
                thumbnailUrl = item.thumbnails.maxByOrNull { it.height }?.url,
                viewCount = item.viewCount.coerceAtLeast(0),
                durationSeconds = duration,
                uploadDate = uploadInstant.toString(),
                timestamp = timestamp,
            )
            onProgress("[$channelName] 발견: ${item.name}")
        }
        return found
    }

    private fun extractVideoId(url: String): String? =
        Regex("[?&]v=([^&]+)").find(url)?.groupValues?.get(1)
            ?: Regex("youtu\\.be/([^?&]+)").find(url)?.groupValues?.get(1)
}
