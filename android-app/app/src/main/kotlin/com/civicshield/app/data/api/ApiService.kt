package com.civicshield.app.data.api

import com.civicshield.app.data.model.FcmTokenRequest
import com.civicshield.app.data.model.LoginRequest
import com.civicshield.app.data.model.LoginResponse
import com.civicshield.app.data.model.OkResponse
import com.civicshield.app.data.model.ReportRequest
import com.civicshield.app.data.model.ReportResponse
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("auth/fcm-token")
    suspend fun saveFcmToken(@Body request: FcmTokenRequest): OkResponse

    @POST("report/helmet")
    suspend fun reportHelmet(@Body request: ReportRequest): ReportResponse

    @POST("report/pothole")
    suspend fun reportPothole(@Body request: ReportRequest): ReportResponse
}
