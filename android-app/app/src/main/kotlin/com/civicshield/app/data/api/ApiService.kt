package com.civicshield.app.data.api

import com.civicshield.app.data.model.CaseAdminItem
import com.civicshield.app.data.model.CaseStatusUpdate
import com.civicshield.app.data.model.CaseStatusUpdateResponse
import com.civicshield.app.data.model.FcmTokenRequest
import com.civicshield.app.data.model.LoginRequest
import com.civicshield.app.data.model.LoginResponse
import com.civicshield.app.data.model.OkResponse
import com.civicshield.app.data.model.PagedCases
import com.civicshield.app.data.model.ReportRequest
import com.civicshield.app.data.model.ReportResponse
import com.civicshield.app.data.model.ZoneCollection
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): LoginResponse

    @POST("auth/fcm-token")
    suspend fun saveFcmToken(@Body request: FcmTokenRequest): OkResponse

    @POST("report/helmet")
    suspend fun reportHelmet(@Body request: ReportRequest): ReportResponse

    @POST("report/pothole")
    suspend fun reportPothole(@Body request: ReportRequest): ReportResponse

    @GET("admin/cases")
    suspend fun listAdminCases(
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 50,
        @Query("type") type: String? = null,
        @Query("status") status: String? = null,
    ): PagedCases

    @PATCH("admin/cases/{id}/status")
    suspend fun updateCaseStatus(
        @Path("id") id: String,
        @Body body: CaseStatusUpdate,
    ): CaseStatusUpdateResponse

    @GET("analytics/zones")
    suspend fun getAnalyticsZones(): ZoneCollection

    @GET("cases")
    suspend fun listMyCases(
        @Query("user_id") userId: String = "me",
    ): List<CaseAdminItem>

    @GET("rss")
    suspend fun getRssFeed(): okhttp3.ResponseBody
}
