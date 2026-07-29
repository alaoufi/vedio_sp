package com.myvideolibrary.app.ui.duplicates

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ItemDuplicateGroupBinding
import com.myvideolibrary.app.util.Formatters

/** One row per duplicate group: the kept copy + how many extras can be removed. */
class DuplicatesAdapter(
    private val onRemove: (DuplicatesViewModel.Group) -> Unit
) : ListAdapter<DuplicatesViewModel.Group, DuplicatesAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDuplicateGroupBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemDuplicateGroupBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(group: DuplicatesViewModel.Group) {
            val ctx = binding.root.context
            binding.title.text = group.keep.title
            binding.subtitle.text = ctx.getString(
                R.string.dup_group_subtitle,
                group.copies,
                Formatters.fileSize(group.reclaimable)
            )
            Glide.with(binding.thumbnail)
                .load(group.keep.thumbnailPath ?: group.keep.localPath)
                .placeholder(R.drawable.ic_video_placeholder)
                .centerCrop()
                .into(binding.thumbnail)
            binding.removeButton.setOnClickListener { onRemove(group) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DuplicatesViewModel.Group>() {
            override fun areItemsTheSame(
                a: DuplicatesViewModel.Group, b: DuplicatesViewModel.Group
            ) = a.keep.id == b.keep.id

            override fun areContentsTheSame(
                a: DuplicatesViewModel.Group, b: DuplicatesViewModel.Group
            ) = a == b
        }
    }
}
