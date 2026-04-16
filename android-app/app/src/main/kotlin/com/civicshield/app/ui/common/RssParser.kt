package com.civicshield.app.ui.common

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RssParser {

    /** Parse an RSS 2.0 document into RssItem rows. Swallows malformed entries gracefully. */
    fun parse(xml: String): List<RssItem> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val items = mutableListOf<RssItem>()
        var insideItem = false
        var currentTag: String? = null

        var title = ""
        var description = ""
        var pubDate = ""
        var guid = ""
        var link: String? = null
        var category: String? = null
        var caseId: String? = null
        var statusField: String? = null
        var imageUrl: String? = null
        var address: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "item") {
                        insideItem = true
                        title = ""; description = ""; pubDate = ""; guid = ""; link = null
                        category = null; caseId = null; statusField = null
                        imageUrl = null; address = null
                    } else if (insideItem && currentTag == "enclosure") {
                        // <enclosure url="..." type="..." length="..."/>
                        val url = parser.getAttributeValue(null, "url")
                        if (!url.isNullOrBlank()) imageUrl = url
                    }
                }
                XmlPullParser.TEXT -> {
                    if (insideItem) {
                        val text = parser.text ?: ""
                        when (currentTag) {
                            "title" -> title = text
                            "description" -> description = text
                            "pubDate" -> pubDate = text
                            "guid" -> guid = text
                            "link" -> link = text
                            "category" -> category = text
                            "caseId" -> caseId = text
                            "status" -> statusField = text
                            "imageUrl" -> if (imageUrl.isNullOrBlank()) imageUrl = text
                            "address" -> address = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item" && insideItem) {
                        items.add(
                            RssItem(
                                guid = guid.ifEmpty { "${System.currentTimeMillis()}-${items.size}" },
                                title = title,
                                description = description,
                                pubDate = pubDate,
                                link = link,
                                caseId = caseId,
                                type = category?.lowercase(),
                                status = statusField?.lowercase(),
                                imageUrl = imageUrl,
                                address = address,
                            )
                        )
                        insideItem = false
                    }
                    currentTag = null
                }
            }
            event = parser.next()
        }

        return items
    }

    /** Turn an RFC 2822 pubDate into a more readable "16 Apr 2026, 06:21". Falls back to raw string. */
    fun formatPubDate(raw: String): String {
        if (raw.isBlank()) return ""
        return try {
            val parsed: Date = RFC_2822.parse(raw) ?: return raw
            OUT.format(parsed)
        } catch (_: Exception) {
            raw
        }
    }

    /** "2 hours ago", "Yesterday", "3 days ago", or a fallback date for older items. */
    fun relativePubDate(raw: String): String {
        if (raw.isBlank()) return ""
        val parsed: Date = try {
            RFC_2822.parse(raw) ?: return formatPubDate(raw)
        } catch (_: Exception) {
            return formatPubDate(raw)
        }
        val nowMs = System.currentTimeMillis()
        val diffMs = nowMs - parsed.time
        if (diffMs < 0) return formatPubDate(raw)
        val secs = diffMs / 1_000
        val mins = secs / 60
        val hours = mins / 60
        val days = hours / 24
        return when {
            secs < 45 -> "just now"
            secs < 90 -> "a minute ago"
            mins < 45 -> "$mins minutes ago"
            mins < 90 -> "an hour ago"
            hours < 24 -> "$hours hours ago"
            days < 2 -> "Yesterday"
            days < 7 -> "$days days ago"
            else -> formatPubDate(raw)
        }
    }

    private val RFC_2822 = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
    private val OUT = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
}
