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
            binding.tvType.text = item.type.replaceFirstChar { it.uppercase() }
            binding.tvType.backgroundTintList = ColorStateList.valueOf(
                when (item.type) {
                    "helmet" -> Color.parseColor("#E53935")
                    "pothole" -> Color.parseColor("#FB8C00")
                    else -> Color.GRAY
                }
            )
            binding.tvCaseId.text = ctx.getString(R.string.case_id_short, item.id.take(8))
            binding.tvLocation.text = ctx.getString(
                R.string.loc_format, item.latitude, item.longitude
            )
            binding.tvDescription.text =
                (item.aiDescription ?: item.userDescription).ifBlank { "—" }

            binding.tvStatus.text = item.status.replace('_', ' ')
            binding.tvStatus.backgroundTintList = ColorStateList.valueOf(statusColor(item.status))
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
