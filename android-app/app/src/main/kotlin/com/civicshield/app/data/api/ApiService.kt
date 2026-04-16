package com.civicshield.app.data.api

import com.civicshield.app.data.model.AgentChatRequest
import com.civicshield.app.data.model.AgentChatResponse
import com.civicshield.app.data.model.AnalyticsSummary
import com.civicshield.app.data.model.CaseAddress
import com.civicshield.app.data.model.CaseAdminItem
import com.civicshield.app.data.model.CaseAnalysis
import com.civicshield.app.data.model.CaseStatusUpdate
import com.civicshield.app.data.model.CaseStatusUpdateResponse
import com.civicshield.app.data.model.DailyCountsResponse
import com.civicshield.app.data.model.FcmTokenRequest
import com.civicshield.app.data.model.RecentActivityResponse
import com.civicshield.app.data.model.ResolutionTrendResponse
import com.civicshield.app.data.model.TopZonesResponse
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

    @POST("cases/{id}/analyze")
    suspend fun analyzeCase(@Path("id") id: String): CaseAnalysis

    @GET("cases/{id}/address")
    suspend fun getCaseAddress(@Path("id") id: String): CaseAddress

    @GET("analytics/zones")
    suspend fun getAnalyticsZones(): ZoneCollection

    @GET("analytics/summary")
    suspend fun getAnalyticsSummary(): AnalyticsSummary

    @GET("analytics/daily")
    suspend fun getAnalyticsDaily(@Query("days") days: Int = 7): DailyCountsResponse

    @GET("analytics/top-zones")
    suspend fun getAnalyticsTopZones(@Query("limit") limit: Int = 5): TopZonesResponse

    @GET("analytics/resolution-trend")
    suspend fun getAnalyticsResolutionTrend(@Query("weeks") weeks: Int = 8): ResolutionTrendResponse

    @GET("analytics/recent-activity")
    suspend fun getAnalyticsRecentActivity(
        @Query("limit") limit: Int = 10,
    ): RecentActivityResponse

    @GET("cases")
    suspend fun listMyCases(
        @Query("user_id") userId: String = "me",
    ): List<CaseAdminItem>

    @GET("rss")
    suspend fun getRssFeed(): okhttp3.ResponseBody

    @POST("agent/chat")
    suspend fun agentChat(@Body request: AgentChatRequest): AgentChatResponse
}
