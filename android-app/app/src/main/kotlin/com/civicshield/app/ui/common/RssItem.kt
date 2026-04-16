package com.civicshield.app.ui.common

data class RssItem(
    val guid: String,
    val title: String,
    val description: String,
    val pubDate: String,
    val link: String?,
)
