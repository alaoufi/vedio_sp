package com.myvideolibrary.app.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.LibraryViewMode
import com.myvideolibrary.app.databinding.ItemVideoGridBinding
import com.myvideolibrary.app.databinding.ItemVideoListBinding
import com.myvideolibrary.app.util.Formatters

/**
 * Paging adapter for the library. Renders either a grid card or a list row based
 * on [viewMode], and highlights items while multi-select is active.
 */
class VideoPagingAdapter(
    private var viewMode: LibraryViewMode,
    private val onClick: (VideoEntity) -> Unit,
    private val onLongClick: (VideoEntity) -> Unit,
    private val onMenu: (VideoEntity, android.view.View) -> Unit,
    private val onFavorite: (VideoEntity) -> Unit = {}
) : PagingDataAdapter<VideoEntity, RecyclerView.ViewHolder>(DIFF) {

    private var selectedIds: Set<Long> = emptySet()
    private var selectionMode: Boolean = false

    fun setViewMode(mode: LibraryViewMode) {
        if (mode != viewMode) {
            viewMode = mode
            notifyDataSetChanged()
        }
    }

    fun setSelection(mode: Boolean, ids: Set<Long>) {
        selectionMode = mode
        selectedIds = ids
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (viewMode) {
        LibraryViewMode.GRID -> TYPE_GRID
        LibraryViewMode.LIST -> TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_GRID) {
            GridViewHolder(ItemVideoGridBinding.inflate(inflater, parent, false))
        } else {
            ListViewHolder(ItemVideoListBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val video = getItem(position) ?: return
        when (holder) {
            is GridViewHolder -> holder.bind(video)
            is ListViewHolder -> holder.bind(video)
        }
    }

    inner class GridViewHolder(
        private val binding: ItemVideoGridBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(video: VideoEntity) {
            binding.title.text = video.title
            binding.duration.text = Formatters.duration(video.duration)
            // Always-visible heart toggle: filled red when favourite, outline when
            // not — the obvious one-tap way to add or remove a favourite.
            binding.favoriteIcon.setImageResource(
                if (video.isFavorite) com.myvideolibrary.app.R.drawable.ic_favorite
                else com.myvideolibrary.app.R.drawable.ic_favorite_border
            )
            binding.favoriteIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (video.isFavorite) android.graphics.Color.parseColor("#E53935")
                else android.graphics.Color.WHITE
            )
            binding.favoriteIcon.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                onFavorite(video)
            }
            binding.lockIcon.isVisible = video.isLocked
            binding.linkBadge.isVisible = video.isLinkOnly
            // A saved link has no duration until downloaded.
            binding.duration.isVisible = video.duration > 0
            bindCategory(binding.category, video)
            loadThumbnail(binding.thumbnail, video)
            binding.selectionOverlay.isVisible = selectionMode && video.id in selectedIds
            binding.menuButton.setOnClickListener { onMenu(video, it) }
            binding.root.setOnClickListener { onClick(video) }
            binding.root.setOnLongClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongClick(video); true
            }
        }
    }

    inner class ListViewHolder(
        private val binding: ItemVideoListBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(video: VideoEntity) {
            binding.title.text = video.title
            binding.duration.text = Formatters.duration(video.duration)
            binding.size.text = Formatters.fileSize(video.fileSize)
            binding.quality.text = video.quality ?: "—"
            // Always-visible heart toggle: filled red when favourite, outline when
            // not — the obvious one-tap way to add or remove a favourite.
            binding.favoriteIcon.setImageResource(
                if (video.isFavorite) com.myvideolibrary.app.R.drawable.ic_favorite
                else com.myvideolibrary.app.R.drawable.ic_favorite_border
            )
            binding.favoriteIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                if (video.isFavorite) android.graphics.Color.parseColor("#E53935")
                else android.graphics.Color.WHITE
            )
            binding.favoriteIcon.setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                onFavorite(video)
            }
            binding.lockIcon.isVisible = video.isLocked
            binding.linkBadge.isVisible = video.isLinkOnly
            binding.duration.isVisible = video.duration > 0
            binding.size.isVisible = !video.isLinkOnly
            bindCategory(binding.category, video)
            binding.menuButton.setOnClickListener { onMenu(video, it) }
            loadThumbnail(binding.thumbnail, video)
            binding.selectionOverlay.isVisible = selectionMode && video.id in selectedIds
            binding.root.setOnClickListener { onClick(video) }
            binding.root.setOnLongClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongClick(video); true
            }
        }
    }

    /** Shows the clip's category as a chip, hidden when it has none. */
    private fun bindCategory(textView: android.widget.TextView, video: VideoEntity) {
        val label = video.category?.takeIf { it.isNotBlank() }
        textView.text = label
        textView.isVisible = label != null
    }

    private fun loadThumbnail(
        imageView: android.widget.ImageView,
        video: VideoEntity
    ) {
        val model: Any = video.thumbnailPath ?: video.localPath
        Glide.with(imageView)
            .load(model)
            .placeholder(R.drawable.ic_video_placeholder)
            .error(R.drawable.ic_video_placeholder)
            .centerCrop()
            .into(imageView)
    }

    companion object {
        private const val TYPE_GRID = 0
        private const val TYPE_LIST = 1

        private val DIFF = object : DiffUtil.ItemCallback<VideoEntity>() {
            override fun areItemsTheSame(a: VideoEntity, b: VideoEntity) = a.id == b.id
            override fun areContentsTheSame(a: VideoEntity, b: VideoEntity) = a == b
        }
    }
}
