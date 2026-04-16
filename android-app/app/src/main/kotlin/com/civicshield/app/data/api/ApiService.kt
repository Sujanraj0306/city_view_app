package com.civicshield.app.data.api

import com.civicshield.app.data.model.FcmTokenRequest
import com.civicshield.app.data.model.LoginRequest
import com.civicshield.app.data.model.LoginResponse
import com.civicshield.app.data.model.OkResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("auth/fcm-token")
    suspend fun saveFcmToken(@Body request: FcmTokenRequest): OkResponse
}
