package com.myvideolibrary.app.ui.downloads

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.DownloadEntity
import com.myvideolibrary.app.data.model.DownloadStatus
import com.myvideolibrary.app.databinding.ItemDownloadBinding
import com.myvideolibrary.app.util.Formatters

/** Renders a download row with contextual actions based on its status. */
class DownloadsAdapter(
    private val onPause: (Long) -> Unit,
    private val onResume: (Long) -> Unit,
    private val onRetry: (Long) -> Unit,
    private val onCancel: (Long) -> Unit,
    private val onRemove: (DownloadEntity) -> Unit
) : ListAdapter<DownloadEntity, DownloadsAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(private val binding: ItemDownloadBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadEntity) {
            val ctx = binding.root.context
            val status = DownloadStatus.fromId(item.status)
            binding.title.text = item.title

            // A download with no advertised size shows a value-less bar; the
            // processing phase reports real percent (e.g. muxing) so it's determinate.
            val indeterminate = status == DownloadStatus.DOWNLOADING && item.totalBytes <= 0
            binding.progressBar.isIndeterminate = indeterminate
            if (!indeterminate) binding.progressBar.progress = item.progress

            binding.statusText.text = when (status) {
                DownloadStatus.WAITING -> item.errorMessage ?: ctx.getString(R.string.status_waiting)
                DownloadStatus.DOWNLOADING -> ctx.getString(
                    R.string.status_downloading,
                    item.progress,
                    Formatters.speed(item.downloadSpeed)
                )
                DownloadStatus.PROCESSING -> ctx.getString(R.string.status_processing)
                DownloadStatus.PAUSED -> ctx.getString(R.string.status_paused)
                DownloadStatus.COMPLETED -> ctx.getString(
                    R.string.status_completed, Formatters.fileSize(item.totalBytes)
                )
                DownloadStatus.FAILED -> item.errorMessage
                    ?: ctx.getString(R.string.status_failed)
                DownloadStatus.CANCELED -> ctx.getString(R.string.status_canceled)
            }

            binding.progressBar.isVisible = status == DownloadStatus.DOWNLOADING ||
                status == DownloadStatus.WAITING || status == DownloadStatus.PAUSED ||
                status == DownloadStatus.PROCESSING

            // Action button visibility per state. Processing is a brief, on-device
            // finishing step (mux/thumbnail) that isn't cleanly interruptible, so it
            // shows no buttons — just the indeterminate bar.
            binding.pauseButton.isVisible = status == DownloadStatus.DOWNLOADING
            // Resume also re-kicks a stuck "Waiting" job with the current settings.
            binding.resumeButton.isVisible = status == DownloadStatus.PAUSED ||
                status == DownloadStatus.WAITING
            binding.retryButton.isVisible = status == DownloadStatus.FAILED ||
                status == DownloadStatus.CANCELED
            binding.cancelButton.isVisible = status == DownloadStatus.DOWNLOADING ||
                status == DownloadStatus.WAITING || status == DownloadStatus.PAUSED
            binding.removeButton.isVisible = status == DownloadStatus.COMPLETED ||
                status == DownloadStatus.FAILED || status == DownloadStatus.CANCELED

            binding.pauseButton.setOnClickListener { onPause(item.id) }
            binding.resumeButton.setOnClickListener { onResume(item.id) }
            binding.retryButton.setOnClickListener { onRetry(item.id) }
            binding.cancelButton.setOnClickListener { onCancel(item.id) }
            binding.removeButton.setOnClickListener { onRemove(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DownloadEntity>() {
            override fun areItemsTheSame(a: DownloadEntity, b: DownloadEntity) = a.id == b.id
            override fun areContentsTheSame(a: DownloadEntity, b: DownloadEntity) = a == b
        }
    }
}
