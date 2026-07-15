package com.myvideolibrary.app.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ItemSearchResultBinding
import com.myvideolibrary.app.provider.model.ProviderSearchItem
import com.myvideolibrary.app.util.Formatters

class SearchResultAdapter(
    private val onDownload: (ProviderSearchItem) -> Unit
) : ListAdapter<ProviderSearchItem, SearchResultAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSearchResultBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ProviderSearchItem) {
            binding.title.text = item.title
            binding.meta.text = listOfNotNull(
                item.author,
                item.durationMs.takeIf { it > 0 }?.let { Formatters.duration(it) }
            ).joinToString(" · ")
            Glide.with(binding.thumbnail)
                .load(item.thumbnailUrl)
                .placeholder(R.drawable.ic_video_placeholder)
                .centerCrop()
                .into(binding.thumbnail)
            binding.downloadButton.setOnClickListener { onDownload(item) }
            binding.root.setOnClickListener { onDownload(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ProviderSearchItem>() {
            override fun areItemsTheSame(a: ProviderSearchItem, b: ProviderSearchItem) =
                a.url == b.url
            override fun areContentsTheSame(a: ProviderSearchItem, b: ProviderSearchItem) = a == b
        }
    }
}
