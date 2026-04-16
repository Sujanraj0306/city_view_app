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
