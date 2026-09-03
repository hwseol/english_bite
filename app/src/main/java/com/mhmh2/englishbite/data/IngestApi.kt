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
    // Talk to the backend over "adb reverse tcp:8000 tcp:8000", which tunnels
    // the device's own localhost:8000 to the host machine's localhost:8000
    // over the USB/adb connection. Works the same way for the emulator and a
    // real device, and sidesteps needing them on the same Wi-Fi/LAN.
    private const val BASE_URL = "http://127.0.0.1:8000/"

    val ingestApi: IngestApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            // first-time ingest can take a few minutes (model load + translation)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
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
