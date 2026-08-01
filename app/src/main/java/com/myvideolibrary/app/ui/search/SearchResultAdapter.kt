package com.myvideolibrary.app.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ItemSearchResultBinding
import com.myvideolibrary.app.databinding.ItemSearchResultGridBinding
import com.myvideolibrary.app.databinding.ItemSearchResultPortraitBinding
import com.myvideolibrary.app.provider.model.ProviderSearchItem
import com.myvideolibrary.app.util.Formatters

class SearchResultAdapter(
    private val onPlay: (ProviderSearchItem) -> Unit,
    private val onSaveLink: (ProviderSearchItem) -> Unit,
    private val onDownload: (ProviderSearchItem, android.view.View) -> Unit
) : ListAdapter<ProviderSearchItem, RecyclerView.ViewHolder>(DIFF) {

    /** How results are laid out — matched to the platform being browsed. */
    enum class Style { LIST, GRID, PORTRAIT }

    /** Landscape list rows (YouTube), landscape cards, or tall 9:16 cards (TikTok). */
    var style: Style = Style.LIST
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun getItemViewType(position: Int): Int = when (style) {
        Style.LIST -> TYPE_LIST
        Style.GRID -> TYPE_GRID
        Style.PORTRAIT -> TYPE_PORTRAIT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_GRID -> GridVH(ItemSearchResultGridBinding.inflate(inflater, parent, false))
            TYPE_PORTRAIT -> PortraitVH(ItemSearchResultPortraitBinding.inflate(inflater, parent, false))
            else -> ListVH(ItemSearchResultBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is ListVH -> holder.bind(item)
            is GridVH -> holder.bind(item)
            is PortraitVH -> holder.bind(item)
        }
    }

    private fun meta(item: ProviderSearchItem): String = listOfNotNull(
        item.author,
        item.durationMs.takeIf { it > 0 }?.let { Formatters.duration(it) }
    ).joinToString(" · ")

    private fun loadThumb(view: android.widget.ImageView, item: ProviderSearchItem) {
        Glide.with(view)
            .load(item.thumbnailUrl)
            .placeholder(R.drawable.ic_video_placeholder)
            .centerCrop()
            .into(view)
    }

    inner class ListVH(private val binding: ItemSearchResultBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ProviderSearchItem) {
            binding.title.text = item.title
            binding.meta.text = meta(item)
            loadThumb(binding.thumbnail, item)
            binding.saveLinkButton.setOnClickListener { onSaveLink(item) }
            binding.downloadButton.setOnClickListener { onDownload(item, it) }
            binding.root.setOnClickListener { onPlay(item) }
        }
    }

    inner class GridVH(private val binding: ItemSearchResultGridBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ProviderSearchItem) {
            binding.title.text = item.title
            binding.meta.text = meta(item)
            loadThumb(binding.thumbnail, item)
            binding.downloadButton.setOnClickListener { onDownload(item, it) }
            binding.root.setOnClickListener { onPlay(item) }
        }
    }

    inner class PortraitVH(private val binding: ItemSearchResultPortraitBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ProviderSearchItem) {
            binding.title.text = item.title
            binding.meta.text = meta(item)
            loadThumb(binding.thumbnail, item)
            binding.downloadButton.setOnClickListener { onDownload(item, it) }
            binding.root.setOnClickListener { onPlay(item) }
        }
    }

    companion object {
        private const val TYPE_LIST = 0
        private const val TYPE_GRID = 1
        private const val TYPE_PORTRAIT = 2

        private val DIFF = object : DiffUtil.ItemCallback<ProviderSearchItem>() {
            override fun areItemsTheSame(a: ProviderSearchItem, b: ProviderSearchItem) =
                a.url == b.url
            override fun areContentsTheSame(a: ProviderSearchItem, b: ProviderSearchItem) = a == b
        }
    }
}
