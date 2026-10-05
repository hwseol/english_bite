package com.mhmh2.englishbite.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST

interface AuthApi {
    @POST("auth/signup")
    suspend fun signup(@Body request: SignupRequest): AuthResponse

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): AuthResponse

    @POST("auth/change-password")
    suspend fun changePassword(@Body request: ChangePasswordRequest): AuthResponse

    @POST("auth/delete")
    suspend fun deleteAccount(@Body request: LoginRequest)

    @GET("auth/me")
    suspend fun me(@Header("Authorization") token: String): UserProfile
}
