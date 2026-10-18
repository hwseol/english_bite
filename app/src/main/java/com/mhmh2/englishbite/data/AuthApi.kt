package com.mhmh2.englishbite.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface AuthApi {
    @GET("auth/config")
    suspend fun config(): AuthConfig

    @POST("auth/email/send")
    suspend fun sendCode(@Body request: EmailCodeRequest)

    @POST("auth/signup")
    suspend fun signup(@Body request: SignupRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @POST("auth/google")
    suspend fun google(@Body request: GoogleLoginRequest): AuthResponse

    @POST("auth/reset/confirm")
    suspend fun resetConfirm(@Body request: ResetConfirmRequest): AuthResponse

    @POST("auth/change-password")
    suspend fun changePassword(@Body request: ChangePasswordRequest): AuthResponse

    @POST("auth/delete")
    suspend fun deleteAccount(@Body request: LoginRequest)

    /** For an account without a password (Google): deleted on the session alone. */
    @POST("auth/delete-me")
    suspend fun deleteMe(@Header("Authorization") token: String, @Body request: DeleteMeRequest)

    @GET("auth/me")
    suspend fun me(@Header("Authorization") token: String): UserProfile
}
