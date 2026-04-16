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
    val id: Long,
    val username: String,
    val role: String,
)

data class CaseAdminItem(
    val id: String,
    val type: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    @SerializedName("image_hdfs_path") val imageHdfsPath: String?,
    @SerializedName("ai_verified") val aiVerified: Boolean,
    @SerializedName("ai_confidence") val aiConfidence: Double?,
    val status: String,
    @SerializedName("created_at") val createdAt: String,
    val user: UserMini,
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
