package com.civicshield.app.ui.admin

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.civicshield.app.R
import com.civicshield.app.data.model.RecentActivityItem
import com.civicshield.app.databinding.ItemActivityTimelineBinding
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Timeline feed for admin analytics: each row = one case-status change event.
 * The accent-border color follows the **new** status so completions are green
 * at a glance.
 */
class ActivityTimelineAdapter :
    ListAdapter<RecentActivityItem, ActivityTimelineAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemActivityTimelineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val binding: ItemActivityTimelineBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecentActivityItem) {
            val shortId = item.caseId.take(8).ifBlank { "——" }
            binding.tvCaseLabel.text = "Case $shortId · ${item.caseType}"

            val old = item.oldStatus?.replace('_', ' ')
            val new = item.newStatus.replace('_', ' ')
            binding.tvTransition.text = if (old == null) {
                binding.root.context.getString(R.string.activity_event_created)
            } else {
                binding.root.context.getString(
                    R.string.activity_event_transition, old, new
                )
            }

            binding.tvRelativeTime.text = relativeTime(item.changedAt)

            binding.vAccent.setBackgroundColor(colorForStatus(item.newStatus))
        }

        private fun colorForStatus(status: String): Int = when (status) {
            "pending" -> Color.parseColor("#FBC02D")
            "verified" -> Color.parseColor("#1976D2")
            "in_progress" -> Color.parseColor("#F57C00")
            "completed" -> Color.parseColor("#43A047")
            else -> Color.parseColor("#9E9E9E")
        }

        private fun relativeTime(iso: String): String {
            if (iso.isBlank()) return "—"
            val eventTime = runCatching {
                OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
            }.getOrNull() ?: return iso

            val now = OffsetDateTime.now().atZoneSameInstant(ZoneId.systemDefault())
            val secs = abs(ChronoUnit.SECONDS.between(eventTime, now))
            return when {
                secs < 60 -> "just now"
                secs < 3600 -> "${secs / 60} min ago"
                secs < 86400 -> "${secs / 3600} hr ago"
                secs < 604800 -> "${secs / 86400} d ago"
                else -> eventTime.toLocalDate().toString()
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RecentActivityItem>() {
            override fun areItemsTheSame(
                old: RecentActivityItem,
                new: RecentActivityItem,
            ): Boolean =
                old.caseId == new.caseId && old.changedAt == new.changedAt

            override fun areContentsTheSame(
                old: RecentActivityItem,
                new: RecentActivityItem,
            ): Boolean = old == new
        }
    }
}
