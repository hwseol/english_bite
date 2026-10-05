package com.mhmh2.englishbite.data

import com.mhmh2.englishbite.BuildConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
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

    @POST("telemetry/crash")
    suspend fun reportCrash(@Body report: CrashReport)
}

object ApiClient {
    // The AWS EC2 instance's raw IP has no name of its own to put a real TLS cert on, so it's
    // addressed through sslip.io instead - a free service that resolves
    // "<ip-with-dashes>.sslip.io" straight back to that IP with no registration needed. nginx
    // in front of the API (see /etc/nginx/conf.d/englishbite.conf on the server) terminates a
    // real Let's Encrypt certificate for that hostname and proxies through to the API on 8000.
    // The address is per build type (debug -> dev server, release -> production, see
    // build.gradle.kts), and release builds let docs/app-config.json override it - see
    // RemoteConfig - so this constant is only the fallback.
    private val BASE_URL = BuildConfig.DEFAULT_API_BASE

    /** Stamps every request with the app's versionCode (so the server can tell old builds
     * apart) and, if the remote config names a different API address, re-points the request
     * there - Retrofit itself keeps the built-in base URL. */
    private class AppInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val builder = request.newBuilder()
                .header("X-App-Version", BuildConfig.VERSION_CODE.toString())
            RemoteConfig.currentApiBase()?.let { base ->
                builder.url(request.url.newBuilder().scheme(base.scheme).host(base.host).port(base.port).build())
            }
            return chain.proceed(builder.build())
        }
    }

    private val retrofit: Retrofit by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(AppInterceptor())
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
    }

    val ingestApi: IngestApi by lazy { retrofit.create(IngestApi::class.java) }
    val authApi: AuthApi by lazy { retrofit.create(AuthApi::class.java) }
}
