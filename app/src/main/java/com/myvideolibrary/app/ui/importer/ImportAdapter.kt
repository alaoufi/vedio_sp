package com.myvideolibrary.app.ui.importer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ItemImportBinding
import com.myvideolibrary.app.util.Formatters
import com.myvideolibrary.app.util.ScannedVideo

class ImportAdapter(
    private val onToggle: (String) -> Unit
) : ListAdapter<ImportAdapter.Row, ImportAdapter.VH>(DIFF) {

    data class Row(val video: ScannedVideo, val selected: Boolean)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemImportBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemImportBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row) {
            val v = row.video
            binding.title.text = v.displayName
            binding.meta.text = binding.root.context.getString(
                R.string.import_item_meta,
                Formatters.duration(v.durationMs),
                Formatters.fileSize(v.sizeBytes)
            )
            binding.checkbox.setOnCheckedChangeListener(null)
            binding.checkbox.isChecked = row.selected
            Glide.with(binding.thumbnail)
                .load(v.contentUri)
                .placeholder(R.drawable.ic_video_placeholder)
                .centerCrop()
                .into(binding.thumbnail)

            binding.root.setOnClickListener { onToggle(v.contentUri) }
            binding.checkbox.setOnClickListener { onToggle(v.contentUri) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) =
                a.video.contentUri == b.video.contentUri

            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }
    }
}
