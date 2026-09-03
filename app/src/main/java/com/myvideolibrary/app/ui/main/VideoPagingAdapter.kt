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
    /** Column count of the staggered grid; used to size cards by aspect ratio. */
    private var spanCount: Int = 3

    /** Names (normalised) of protected categories rendered with a blurred cover. */
    private var obscuredCategories: Set<String> = emptySet()

    fun setObscuredCategories(cats: Set<String>) {
        if (cats != obscuredCategories) {
            obscuredCategories = cats
            notifyDataSetChanged()
        }
    }

    fun setSpanCount(count: Int) {
        val c = count.coerceAtLeast(1)
        if (c != spanCount) {
            spanCount = c
            notifyDataSetChanged()
        }
    }

    /**
     * The thumbnail ImageView for a grid row (null for list rows), so the host can
     * cycle animated-preview frames onto the centred card. [peekAt] exposes the
     * paged item for a position without forcing a load.
     */
    fun previewImageFor(holder: RecyclerView.ViewHolder): android.widget.ImageView? =
        (holder as? GridViewHolder)?.thumbnailView

    fun peekAt(position: Int): VideoEntity? =
        if (position in 0 until itemCount) peek(position) else null

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
        /** Thumbnail target the auto-preview cycles frames onto. */
        val thumbnailView: android.widget.ImageView get() = binding.thumbnail

        fun bind(video: VideoEntity) {
            sizeThumbnail(binding.thumbnail, video)
            val obscured = isObscured(video)
            binding.title.text =
                if (obscured) binding.root.context.getString(R.string.private_video_label)
                else video.title
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
            // While obscured, hide every cue that could reveal the clip.
            binding.favoriteIcon.isVisible = !obscured
            binding.lockIcon.isVisible = obscured
            binding.linkBadge.isVisible = video.isLinkOnly && !obscured
            binding.duration.isVisible = video.duration > 0 && !obscured
            if (obscured) binding.category.isVisible = false else bindCategory(binding.category, video)
            if (obscured) binding.watchProgress.isVisible = false
            else bindWatchProgress(binding.watchProgress, video)
            loadThumbnail(binding.thumbnail, video, obscured)
            if (!obscured) tintCover(binding.infoStrip, video)
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
            val obscured = isObscured(video)
            binding.title.text =
                if (obscured) binding.root.context.getString(R.string.private_video_label)
                else video.title
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
            binding.lockIcon.isVisible = obscured
            binding.linkBadge.isVisible = video.isLinkOnly && !obscured
            binding.duration.isVisible = video.duration > 0 && !obscured
            binding.size.isVisible = !video.isLinkOnly && !obscured
            binding.favoriteIcon.isVisible = !obscured
            if (obscured) binding.category.isVisible = false else bindCategory(binding.category, video)
            if (obscured) binding.watchProgress.isVisible = false
            else bindWatchProgress(binding.watchProgress, video)
            if (!obscured) com.myvideolibrary.app.util.CoverTint.apply(
                binding.rowContent, video.thumbnailPath ?: video.localPath, video.id,
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT
            )
            binding.menuButton.setOnClickListener { onMenu(video, it) }
            loadThumbnail(binding.thumbnail, video, obscured)
            binding.selectionOverlay.isVisible = selectionMode && video.id in selectedIds
            binding.root.setOnClickListener { onClick(video) }
            binding.root.setOnLongClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onLongClick(video); true
            }
        }
    }

    /**
     * Thin resume bar along the bottom of the thumbnail showing how far the clip
     * has been watched. Hidden for saved links and for clips that were never
     * started or are effectively finished (>98%), so a finished clip looks clean.
     */
    private fun bindWatchProgress(
        bar: com.google.android.material.progressindicator.LinearProgressIndicator,
        video: VideoEntity
    ) {
        val pct = if (!video.isLinkOnly && video.duration > 0) {
            (video.lastPlayedPosition * 1000 / video.duration).toInt().coerceIn(0, 1000)
        } else 0
        if (pct in 1..979) {
            bar.progress = pct
            bar.isVisible = true
        } else {
            bar.isVisible = false
        }
    }

    /**
     * Sizes a grid thumbnail to the clip's real aspect ratio so the grid staggers
     * (tall portrait clips, short landscape ones) instead of forcing one height.
     * The column width is estimated from the screen and span count; the ratio is
     * clamped so extreme shapes stay reasonable, and unknown dimensions fall back
     * to 16:9. Runs every bind so recycled cards get the right height.
     */
    private fun sizeThumbnail(imageView: android.widget.ImageView, video: VideoEntity) {
        val dm = imageView.resources.displayMetrics
        val cardMarginPx = 6 * dm.density // 3dp each side of the card
        val colWidth = (dm.widthPixels / spanCount - cardMarginPx).coerceAtLeast(1f)
        val ratio = if (video.width > 0 && video.height > 0) {
            video.height.toFloat() / video.width
        } else {
            9f / 16f
        }
        val height = (colWidth * ratio).coerceIn(colWidth * 0.56f, colWidth * 1.9f)
        val lp = imageView.layoutParams
        val target = height.toInt()
        if (lp.height != target) {
            lp.height = target
            imageView.layoutParams = lp
        }
    }

    /** Shows the clip's category as a chip, hidden when it has none. */
    private fun bindCategory(textView: android.widget.TextView, video: VideoEntity) {
        val label = video.category?.takeIf { it.isNotBlank() }
        textView.text = label
        textView.isVisible = label != null
    }

    /**
     * Tints the info strip under a grid card with a colour pulled from the
     * thumbnail, so each cover feels cohesive. A tiny 64px bitmap is decoded and
     * run through Palette off the main thread; the result is applied as a subtle
     * top-down gradient fading into the card surface.
     *
     * Palette runs asynchronously and rows are recycled, so the strip is tagged
     * with the video id and the callback bails if the row has since been rebound
     * — this prevents a late colour landing on the wrong card.
     */
    private fun tintCover(strip: android.widget.LinearLayout, video: VideoEntity) {
        com.myvideolibrary.app.util.CoverTint.apply(
            strip, video.thumbnailPath ?: video.localPath, video.id
        )
    }

    private fun loadThumbnail(
        imageView: android.widget.ImageView,
        video: VideoEntity,
        obscured: Boolean = false
    ) {
        if (obscured) {
            // OBSCURED mode: show the real cover heavily blurred, kept at the same
            // size, so the clip is unrecognisable until the category is unlocked.
            imageView.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            Glide.with(imageView)
                .load(video.thumbnailPath ?: video.localPath)
                .placeholder(R.drawable.ic_private_cover)
                .error(R.drawable.ic_private_cover)
                .transform(
                    com.bumptech.glide.load.resource.bitmap.CenterCrop(),
                    com.myvideolibrary.app.util.BlurCoverTransformation()
                )
                .into(imageView)
            return
        }
        val model: Any = video.thumbnailPath ?: video.localPath
        Glide.with(imageView)
            .load(model)
            .placeholder(R.drawable.ic_video_placeholder)
            .error(R.drawable.ic_video_placeholder)
            .centerCrop()
            .into(imageView)
    }

    /**
     * True when a clip's cover/title must stay hidden: it belongs to a
     * password-protected category that hasn't been unlocked this session.
     */
    private fun isObscured(video: VideoEntity): Boolean {
        // Per-clip obscure: this individual clip is marked and not unlocked this session.
        if (video.isPrivate &&
            !com.myvideolibrary.app.security.ObscuredClipsSession.isUnlocked()
        ) return true
        // Category obscure: the clip is in a protected/blurred category not yet unlocked.
        val cat = video.category?.trim()?.lowercase() ?: return false
        return cat in obscuredCategories &&
            !com.myvideolibrary.app.security.ProtectedCategoriesSession.isUnlocked(video.category)
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
