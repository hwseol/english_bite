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
    // The AWS EC2 instance now hosts the backend directly (plain HTTP - see
    // network_security_config.xml for the cleartext exception; no domain name yet for a real
    // TLS cert). Stable IP, no dev machine or tunnel needed anymore.
    private const val BASE_URL = "http://13.218.170.114:8000/"

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
