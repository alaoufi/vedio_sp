package com.myvideolibrary.app.ui.reorder

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.databinding.ItemReorderBinding
import java.util.Collections

/**
 * A plain (non-paged) list of clips for the drag-to-arrange screen. Holds its own
 * mutable order so [ItemTouchHelper][androidx.recyclerview.widget.ItemTouchHelper]
 * can move rows smoothly; [currentOrder] returns the arrangement to persist.
 */
class ReorderAdapter(
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit
) : RecyclerView.Adapter<ReorderAdapter.VH>() {

    private val items = mutableListOf<VideoEntity>()

    fun submit(list: List<VideoEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /** The current top-first order after any drags. */
    fun currentOrder(): List<VideoEntity> = items.toList()

    /** Moves an item within the backing list and animates the change. */
    fun onItemMove(from: Int, to: Int) {
        if (from < to) {
            for (i in from until to) Collections.swap(items, i, i + 1)
        } else {
            for (i in from downTo to + 1) Collections.swap(items, i, i - 1)
        }
        notifyItemMoved(from, to)
    }

    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemReorderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(private val binding: ItemReorderBinding) : RecyclerView.ViewHolder(binding.root) {
        @SuppressLint("ClickableViewAccessibility")
        fun bind(video: VideoEntity) {
            binding.title.text = video.title
            Glide.with(binding.thumbnail)
                .load(video.thumbnailPath ?: video.localPath)
                .placeholder(R.drawable.ic_video_placeholder)
                .centerCrop()
                .into(binding.thumbnail)
            // Press the handle to begin a drag.
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDrag(this)
                false
            }
        }
    }
}
