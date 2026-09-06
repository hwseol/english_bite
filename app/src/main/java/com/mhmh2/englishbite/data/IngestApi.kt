package com.mhmh2.englishbite.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface IngestApi {
    @POST("videos")
    suspend fun ingestVideo(@Body request: IngestRequest): IngestResponse

    @GET("videos/{videoId}")
    suspend fun getVideo(@Path("videoId") videoId: String): IngestResponse

    @GET("catalog")
    suspend fun getCatalog(@Query("channel") channel: String? = null): List<CatalogItem>
}

object ApiClient {
    // TEMPORARY: a Cloudflare quick tunnel (`cloudflared tunnel --url http://localhost:8000`)
    // pointed at the backend running on the dev machine. This URL is random and changes every
    // time the tunnel is restarted - swap it here when that happens. Once real hosting (AWS,
    // pending account verification) is up, replace this with that stable URL.
    private const val BASE_URL = "https://those-bits-amp-achieving.trycloudflare.com/"

    val ingestApi: IngestApi by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            // Every request (submit or poll) now returns almost immediately - the actual
            // translation work happens server-side in a background thread, so there's no
            // reason for any single call to hold a connection open for minutes anymore.
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IngestApi::class.java)
    }
}
