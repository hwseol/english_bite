package com.mhmh2.englishbite.admin

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.util.concurrent.TimeUnit

/** Downloads just the audio track for one video - all the server-side pipeline needs (Whisper
 * doesn't touch video frames), which also keeps the admin's mobile data usage down compared to
 * a full video download. */
object AudioExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    fun downloadAudio(context: Context, videoId: String): File {
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=$videoId")
        // Prefer the lowest-bitrate direct-URL stream: Whisper transcribes 16kHz mono
        // internally anyway, so a high-bitrate download would just waste the admin's mobile
        // data for no accuracy gain. DASH-manifest-only streams (isUrl() == false) aren't
        // simple range-downloadable, so they're excluded.
        val stream = info.audioStreams
            .filter { it.isUrl }
            .minByOrNull { if (it.averageBitrate > 0) it.averageBitrate else Int.MAX_VALUE }
            ?: throw IllegalStateException("오디오 스트림을 찾을 수 없어요")

        val outFile = File(context.cacheDir, "admin_audio_$videoId.${stream.format?.suffix ?: "audio"}")
        val request = Request.Builder().url(stream.content).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("오디오 다운로드 실패: HTTP ${response.code}")
            }
            outFile.outputStream().use { out ->
                response.body?.byteStream()?.copyTo(out)
                    ?: throw IllegalStateException("오디오 응답이 비어 있어요")
            }
        }
        return outFile
    }
}
