package com.civicshield.app.ui.admin

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.civicshield.app.R
import com.civicshield.app.data.model.CaseAdminItem
import com.civicshield.app.databinding.ItemCaseBinding

class CaseAdapter(
    private val onStatusChanged: (caseId: String, newStatus: String) -> Unit,
    private val onRowClick: (CaseAdminItem) -> Unit = {},
) : ListAdapter<CaseAdminItem, CaseAdapter.CaseVH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CaseVH {
        val binding = ItemCaseBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return CaseVH(binding)
    }

    override fun onBindViewHolder(holder: CaseVH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CaseVH(
        private val binding: ItemCaseBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        private var boundItem: CaseAdminItem? = null

        init {
            val spinnerAdapter = ArrayAdapter(
                binding.root.context,
                android.R.layout.simple_spinner_item,
                STATUSES,
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            binding.spinnerStatus.adapter = spinnerAdapter

            binding.root.setOnClickListener {
                boundItem?.let(onRowClick)
            }

            binding.spinnerStatus.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                        parent: AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long,
                    ) {
                        val item = boundItem ?: return
                        val newStatus = STATUSES[position]
                        if (item.status != newStatus) {
                            onStatusChanged(item.id, newStatus)
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                }
        }

        fun bind(item: CaseAdminItem) {
            boundItem = null // suppress spurious callback during setSelection
            binding.tvCaseId.text = binding.root.context.getString(
                R.string.case_id_short, item.id.take(8)
            )
            binding.tvLocation.text = binding.root.context.getString(
                R.string.loc_format, item.latitude, item.longitude
            )
            binding.tvType.text = item.type.replaceFirstChar { it.uppercase() }
            val chipColor = when (item.type) {
                "helmet" -> Color.parseColor("#E53935")
                "pothole" -> Color.parseColor("#FB8C00")
                else -> Color.GRAY
            }
            binding.tvType.backgroundTintList =
                android.content.res.ColorStateList.valueOf(chipColor)

            binding.ivAiVerified.isVisible = item.aiVerified
            binding.tvAiLabel.text = if (item.aiVerified) {
                binding.root.context.getString(
                    R.string.ai_verified_label,
                    (item.aiConfidence ?: 0.0) * 100,
                )
            } else {
                binding.root.context.getString(R.string.ai_unverified_label)
            }
            binding.tvAiLabel.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (item.aiVerified) android.R.color.holo_green_dark
                    else android.R.color.darker_gray,
                )
            )

            val statusIndex = STATUSES.indexOf(item.status).coerceAtLeast(0)
            binding.spinnerStatus.setSelection(statusIndex, false)

            boundItem = item
        }
    }

    companion object {
        val STATUSES = listOf("pending", "verified", "in_progress", "completed")

        private val DIFF = object : DiffUtil.ItemCallback<CaseAdminItem>() {
            override fun areItemsTheSame(old: CaseAdminItem, new: CaseAdminItem) = old.id == new.id
            override fun areContentsTheSame(old: CaseAdminItem, new: CaseAdminItem) = old == new
        }
    }
}
