package com.myvideolibrary.app.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.databinding.ItemContinueBinding
import com.myvideolibrary.app.util.Formatters

/** Horizontal "Continue watching" row: thumbnail + resume progress, tap to resume. */
class ContinueAdapter(
    private val onClick: (VideoEntity) -> Unit
) : ListAdapter<VideoEntity, ContinueAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemContinueBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemContinueBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(video: VideoEntity) {
            binding.title.text = video.title
            binding.duration.text = Formatters.duration(video.duration)
            val pct = if (video.duration > 0) {
                (video.lastPlayedPosition * 1000 / video.duration).toInt().coerceIn(0, 1000)
            } else 0
            binding.progress.progress = pct
            Glide.with(binding.thumbnail)
                .load(video.thumbnailPath)
                .placeholder(R.drawable.ic_video_placeholder)
                .centerCrop()
                .into(binding.thumbnail)
            binding.root.setOnClickListener { onClick(video) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoEntity>() {
            override fun areItemsTheSame(a: VideoEntity, b: VideoEntity) = a.id == b.id
            override fun areContentsTheSame(a: VideoEntity, b: VideoEntity) = a == b
        }
    }
}
