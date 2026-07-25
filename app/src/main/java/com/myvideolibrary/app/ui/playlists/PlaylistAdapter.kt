package com.myvideolibrary.app.ui.playlists

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.PlaylistWithCount
import com.myvideolibrary.app.databinding.ItemPlaylistBinding

class PlaylistAdapter(
    private val onOpen: (PlaylistWithCount) -> Unit,
    private val onMenu: (PlaylistWithCount, View) -> Unit
) : ListAdapter<PlaylistWithCount, PlaylistAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: PlaylistWithCount) {
            binding.name.text = item.name
            binding.count.text = binding.root.context.resources
                .getQuantityString(R.plurals.playlist_video_count, item.videoCount, item.videoCount)
            binding.root.setOnClickListener { onOpen(item) }
            binding.menuButton.setOnClickListener { onMenu(item, it) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<PlaylistWithCount>() {
            override fun areItemsTheSame(a: PlaylistWithCount, b: PlaylistWithCount) = a.id == b.id
            override fun areContentsTheSame(a: PlaylistWithCount, b: PlaylistWithCount) = a == b
        }
    }
}
