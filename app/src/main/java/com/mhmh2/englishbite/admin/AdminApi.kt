package com.mhmh2.englishbite.admin

import com.mhmh2.englishbite.data.IngestResponse
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.util.concurrent.TimeUnit

interface AdminApi {
    @Multipart
    @POST("admin/ingest")
    suspend fun ingest(
        @Header("X-Admin-Token") adminToken: String,
        @Part("video_id") videoId: okhttp3.RequestBody,
        @Part("title") title: okhttp3.RequestBody,
        @Part("channel") channel: okhttp3.RequestBody,
        @Part("thumbnail") thumbnail: okhttp3.RequestBody?,
        @Part("view_count") viewCount: okhttp3.RequestBody,
        @Part("duration") duration: okhttp3.RequestBody,
        @Part("upload_date") uploadDate: okhttp3.RequestBody,
        @Part("timestamp") timestamp: okhttp3.RequestBody,
        @Part audio: MultipartBody.Part,
    ): IngestResponse
}

object AdminApiClient {
    // Same backend the app itself talks to (see IngestApi.kt) - the admin endpoint lives on
    // the same FastAPI server, just under a separate /admin/ prefix.
    private const val BASE_URL = "http://13.218.170.114:8000/"

    val adminApi: AdminApi by lazy {
        // Uploading an audio file over mobile data can take a while - the default 10s
        // read/write timeouts (fine for the small JSON calls in IngestApi.kt) aren't enough.
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AdminApi::class.java)
    }
}
