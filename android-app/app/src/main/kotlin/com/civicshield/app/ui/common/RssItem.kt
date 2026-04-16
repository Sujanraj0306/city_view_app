package com.civicshield.app.ui.common

/**
 * One row parsed out of GET /rss. Everything beyond the basic RSS 2.0 tags
 * (category, enclosure, custom fields) is best-effort — older backend builds
 * emit only title/description/pubDate/guid/link and those still render fine.
 */
data class RssItem(
    val guid: String,
    val title: String,
    val description: String,
    val pubDate: String,
    val link: String?,
    val caseId: String? = null,
    val type: String? = null,        // "helmet" | "pothole"
    val status: String? = null,      // "pending" | "verified" | "in_progress" | "completed"
    val imageUrl: String? = null,
    val address: String? = null,
)
