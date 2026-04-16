package com.civicshield.app.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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
            binding.tvTitle.text = item.title
            binding.tvDescription.text = item.description
            binding.tvDate.text = RssParser.formatPubDate(item.pubDate)
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RssItem>() {
            override fun areItemsTheSame(old: RssItem, new: RssItem) = old.guid == new.guid
            override fun areContentsTheSame(old: RssItem, new: RssItem) = old == new
        }
    }
}
