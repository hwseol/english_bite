package com.mhmh2.englishbite.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface IngestApi {
    @POST("videos")
    suspend fun ingestVideo(@Body request: IngestRequest): VideoResult
}

object ApiClient {
    // 10.0.2.2 is the emulator's alias for the host machine's localhost.
    private const val BASE_URL = "http://10.0.2.2:8000/"

    val ingestApi: IngestApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            // first-time ingest can take ~1-2 minutes (model load + translation)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IngestApi::class.java)
    }
}
