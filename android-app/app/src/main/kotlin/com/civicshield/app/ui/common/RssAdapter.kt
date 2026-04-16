package com.civicshield.app.ui.common

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.civicshield.app.BuildConfig
import com.civicshield.app.databinding.ItemRssBinding

class RssAdapter : ListAdapter<RssItem, RssAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRssBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    class VH(private val binding: ItemRssBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RssItem) {
            binding.tvTitle.text = item.title.ifBlank { "Case update" }

            val location = item.address?.takeIf { it.isNotBlank() } ?: item.description
            binding.tvLocation.isVisible = location.isNotBlank()
            binding.tvLocation.text = location

            val status = (item.status ?: "").ifBlank { "—" }
            binding.tvStatus.text = status.replace('_', ' ')
            binding.tvStatus.backgroundTintList =
                ColorStateList.valueOf(statusColor(status))

            binding.tvRelativeTime.text = RssParser.relativePubDate(item.pubDate)

            binding.vAccent.setBackgroundColor(accentColor(item.type, item.status))

            loadThumbnail(item)
        }

        private fun loadThumbnail(item: RssItem) {
            val url = item.imageUrl?.takeIf { it.isNotBlank() }
                ?: item.caseId?.let { "${BuildConfig.BASE_URL.trimEnd('/')}/cases/$it/image" }
                ?: run {
                    binding.ivThumb.setImageDrawable(null)
                    binding.thumbShimmer.stopShimmer()
                    binding.thumbShimmer.isVisible = false
                    return
                }

            binding.thumbShimmer.isVisible = true
            binding.thumbShimmer.startShimmer()

            val density = binding.root.resources.displayMetrics.density
            val radiusPx = (10 * density).toInt()

            Glide.with(binding.root)
                .load(url)
                .apply(RequestOptions().transform(RoundedCorners(radiusPx)))
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean,
                    ): Boolean {
                        binding.thumbShimmer.stopShimmer()
                        binding.thumbShimmer.isVisible = false
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean,
                    ): Boolean {
                        binding.thumbShimmer.stopShimmer()
                        binding.thumbShimmer.isVisible = false
                        return false
                    }
                })
                .into(binding.ivThumb)
        }

        private fun accentColor(type: String?, status: String?): Int = when {
            status == "completed" -> Color.parseColor("#43A047")
            type == "helmet" -> Color.parseColor("#E53935")
            type == "pothole" -> Color.parseColor("#FB8C00")
            else -> Color.parseColor("#9E9E9E")
        }

        private fun statusColor(status: String): Int = when (status) {
            "pending" -> Color.parseColor("#FBC02D")
            "verified" -> Color.parseColor("#1976D2")
            "in_progress" -> Color.parseColor("#F57C00")
            "completed" -> Color.parseColor("#43A047")
            else -> Color.parseColor("#9E9E9E")
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RssItem>() {
            override fun areItemsTheSame(old: RssItem, new: RssItem) = old.guid == new.guid
            override fun areContentsTheSame(old: RssItem, new: RssItem) = old == new
        }
    }
}
