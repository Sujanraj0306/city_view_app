package com.civicshield.app.ui.user

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.civicshield.app.R
import com.civicshield.app.data.model.CaseAdminItem
import com.civicshield.app.databinding.ItemUserCaseBinding

/**
 * Gson bypasses Kotlin's null-checks via reflection, so any non-null field in
 * [CaseAdminItem] can still come through as null from the backend. Every access
 * here is defensive so one malformed row can never crash the whole list.
 */
class UserCaseAdapter : ListAdapter<CaseAdminItem, UserCaseAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemUserCaseBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val binding: ItemUserCaseBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CaseAdminItem) {
            val ctx = binding.root.context

            val safeType = (item.type ?: "").ifBlank { "unknown" }
            binding.tvType.text = safeType.replaceFirstChar { it.uppercase() }
            binding.tvType.backgroundTintList = ColorStateList.valueOf(typeColor(safeType))

            val safeId = (item.id ?: "").take(8).ifBlank { "——" }
            binding.tvCaseId.text = ctx.getString(R.string.case_id_short, safeId)

            binding.tvLocation.text = ctx.getString(
                R.string.loc_format, item.latitude ?: 0.0, item.longitude ?: 0.0
            )

            binding.tvDescription.text =
                (item.aiDescription ?: item.userDescription ?: "").ifBlank { "—" }

            val safeStatus = (item.status ?: "pending")
            binding.tvStatus.text = safeStatus.replace('_', ' ')
            binding.tvStatus.backgroundTintList = ColorStateList.valueOf(statusColor(safeStatus))
        }

        private fun typeColor(type: String): Int = when (type) {
            "helmet" -> Color.parseColor("#E53935")
            "pothole" -> Color.parseColor("#FB8C00")
            else -> Color.GRAY
        }

        private fun statusColor(status: String): Int = when (status) {
            "pending" -> Color.parseColor("#9E9E9E")
            "verified" -> Color.parseColor("#1976D2")
            "in_progress" -> Color.parseColor("#F57C00")
            "completed" -> Color.parseColor("#388E3C")
            else -> Color.GRAY
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CaseAdminItem>() {
            override fun areItemsTheSame(old: CaseAdminItem, new: CaseAdminItem) = old.id == new.id
            override fun areContentsTheSame(old: CaseAdminItem, new: CaseAdminItem) = old == new
        }
    }
}
