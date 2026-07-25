package com.myvideolibrary.app.ui.playlists

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.databinding.ItemPlaylistVideoBinding
import com.myvideolibrary.app.util.Formatters

class PlaylistVideoAdapter(
    private val onPlay: (VideoEntity) -> Unit,
    private val onMenu: (VideoEntity, View) -> Unit
) : ListAdapter<VideoEntity, PlaylistVideoAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPlaylistVideoBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemPlaylistVideoBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(video: VideoEntity) {
            binding.title.text = video.title
            binding.duration.text = Formatters.duration(video.duration)
            Glide.with(binding.thumbnail)
                .load(video.thumbnailPath)
                .placeholder(R.drawable.ic_video_placeholder)
                .centerCrop()
                .into(binding.thumbnail)
            binding.root.setOnClickListener { onPlay(video) }
            binding.menuButton.setOnClickListener { onMenu(video, it) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoEntity>() {
            override fun areItemsTheSame(a: VideoEntity, b: VideoEntity) = a.id == b.id
            override fun areContentsTheSame(a: VideoEntity, b: VideoEntity) = a == b
        }
    }
}
