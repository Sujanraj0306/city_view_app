package com.civicshield.app.data.model

import com.google.gson.annotations.SerializedName

data class LoginRequest(
    val username: String,
    val password: String,
)

data class LoginResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type") val tokenType: String,
    @SerializedName("user_id") val userId: Long,
    val role: String,
)

data class FcmTokenRequest(
    @SerializedName("fcm_token") val fcmToken: String,
)

data class OkResponse(
    val ok: Boolean,
)

data class ReportRequest(
    @SerializedName("image_base64") val imageBase64: String,
    val latitude: Double,
    val longitude: Double,
    val description: String? = null,
)

data class ReportResponse(
    @SerializedName("case_id") val caseId: String,
    val status: String,
    @SerializedName("ai_verified") val aiVerified: Boolean,
    @SerializedName("ai_confidence") val aiConfidence: Double?,
    val label: String?,
    @SerializedName("image_hdfs_path") val imageHdfsPath: String,
)

// ---- Admin ----

data class UserMini(
    val id: Long = 0L,
    val username: String = "",
    val role: String = "",
    val email: String? = null,
)

data class CaseBoundingBox(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val width: Double = 0.0,
    val height: Double = 0.0,
)

data class CaseAnalysis(
    @SerializedName("case_id") val caseId: String = "",
    @SerializedName("scene_description") val sceneDescription: String = "",
    @SerializedName("violation_confirmed") val violationConfirmed: Boolean = false,
    @SerializedName("vehicle_number") val vehicleNumber: String? = null,
    @SerializedName("violation_zone") val violationZone: String = "",
    val severity: String = "medium",
    @SerializedName("bounding_box") val boundingBox: CaseBoundingBox? = null,
    val cached: Boolean = false,
)

data class CaseAddress(
    @SerializedName("case_id") val caseId: String = "",
    @SerializedName("display_name") val displayName: String = "",
)

// ---- Analytics dashboard ----

data class AnalyticsSummary(
    val total: Int = 0,
    val pending: Int = 0,
    val verified: Int = 0,
    @SerializedName("in_progress") val inProgress: Int = 0,
    val completed: Int = 0,
)

data class DailyCountPoint(
    val date: String = "",
    val count: Int = 0,
)

data class DailyCountsResponse(
    val days: Int = 0,
    val points: List<DailyCountPoint> = emptyList(),
)

data class TopZonePoint(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val total: Int = 0,
    @SerializedName("helmet_count") val helmetCount: Int = 0,
    @SerializedName("pothole_count") val potholeCount: Int = 0,
)

data class TopZonesResponse(
    val limit: Int = 0,
    val zones: List<TopZonePoint> = emptyList(),
)

data class ResolutionTrendPoint(
    @SerializedName("week_start") val weekStart: String = "",
    @SerializedName("avg_days") val avgDays: Double = 0.0,
    @SerializedName("resolved_count") val resolvedCount: Int = 0,
)

data class ResolutionTrendResponse(
    val weeks: Int = 0,
    val points: List<ResolutionTrendPoint> = emptyList(),
)

data class RecentActivityItem(
    @SerializedName("case_id") val caseId: String = "",
    @SerializedName("case_type") val caseType: String = "",
    @SerializedName("old_status") val oldStatus: String? = null,
    @SerializedName("new_status") val newStatus: String = "",
    @SerializedName("changed_at") val changedAt: String = "",
    val username: String? = null,
)

data class RecentActivityResponse(
    val items: List<RecentActivityItem> = emptyList(),
)

data class CaseAdminItem(
    val id: String = "",
    val type: String = "",
    @SerializedName("user_description") val userDescription: String = "",
    @SerializedName("ai_description") val aiDescription: String? = null,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    @SerializedName("image_hdfs_path") val imageHdfsPath: String? = null,
    @SerializedName("ai_verified") val aiVerified: Boolean = false,
    @SerializedName("ai_confidence") val aiConfidence: Double? = null,
    val status: String = "pending",
    @SerializedName("created_at") val createdAt: String = "",
    val user: UserMini = UserMini(),
)

data class PagedCases(
    val items: List<CaseAdminItem>,
    val total: Int,
    val page: Int,
    val limit: Int,
    val pages: Int,
)

data class CaseStatusUpdate(val status: String)

data class CaseStatusUpdateResponse(
    @SerializedName("case_id") val caseId: String,
    val status: String,
    @SerializedName("push_sent") val pushSent: Boolean,
)

// ---- Analytics (GeoJSON) ----

data class ZoneGeometry(val type: String, val coordinates: List<Double>)

data class ZoneProperties(
    @SerializedName("helmet_count") val helmetCount: Int,
    @SerializedName("pothole_count") val potholeCount: Int,
    val total: Int,
)

data class ZoneFeatureDto(
    val type: String,
    val geometry: ZoneGeometry,
    val properties: ZoneProperties,
)

data class ZoneCollection(
    val type: String,
    val features: List<ZoneFeatureDto>,
)

// ---- Conversational agent ----

data class AgentChatRequest(
    @SerializedName("user_id") val userId: String,
    @SerializedName("session_id") val sessionId: String,
    val message: String,
    @SerializedName("image_base64") val imageBase64: String? = null,
)

data class AgentChatResponse(
    val reply: String = "",
    val action: String? = null,
    val data: Map<String, Any?> = emptyMap(),
)
