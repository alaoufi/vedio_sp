package com.myvideolibrary.app.ui.provider

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ActivityAddDownloadBinding
import com.myvideolibrary.app.provider.model.ProviderErrorType
import com.myvideolibrary.app.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AddDownloadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddDownloadBinding
    private val viewModel: AddDownloadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddDownloadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Pre-fill from a shared link if the activity was launched via ACTION_SEND.
        readSharedUrl()?.let { binding.urlInput.setText(it) }

        binding.resolveButton.setOnClickListener {
            viewModel.resolve(binding.urlInput.text?.toString().orEmpty())
        }
        binding.pasteButton.setOnClickListener { pasteFromClipboard() }
        binding.downloadButton.setOnClickListener { viewModel.download() }

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { render(it) }
            }
        }
    }

    private fun render(state: AddDownloadUiState) {
        binding.progressBar.isVisible = state.resolving
        binding.resolveButton.isEnabled = !state.resolving

        binding.previewCard.isVisible = state.resolved != null
        binding.downloadButton.isEnabled = state.resolved != null
        state.resolved?.let { video ->
            binding.previewTitle.text = video.title
            binding.previewMeta.text = listOfNotNull(
                video.author,
                video.quality,
                video.durationMs.takeIf { it > 0 }?.let { Formatters.duration(it) }
            ).joinToString(" · ")
            Glide.with(this)
                .load(video.thumbnailUrl)
                .placeholder(R.drawable.ic_video_placeholder)
                .into(binding.previewThumbnail)
        }

        binding.errorText.isVisible = state.errorMessage != null && state.resolved == null
        binding.errorText.text = state.errorType?.let { messageFor(it, state.errorMessage) }

        if (state.enqueued) {
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun messageFor(type: ProviderErrorType, fallback: String?): String = when (type) {
        ProviderErrorType.INVALID_LINK -> getString(R.string.error_invalid_link)
        ProviderErrorType.PRIVATE_CONTENT -> getString(R.string.error_private)
        ProviderErrorType.NOT_FOUND -> getString(R.string.error_not_found)
        ProviderErrorType.UNSUPPORTED -> getString(R.string.error_unsupported)
        ProviderErrorType.NETWORK -> getString(R.string.error_network)
        ProviderErrorType.EXTRACTION_FAILED -> getString(R.string.error_extraction)
        ProviderErrorType.UNKNOWN -> fallback ?: getString(R.string.error_unknown)
    }

    private fun pasteFromClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (!text.isNullOrBlank()) {
            binding.urlInput.setText(text)
            viewModel.resolve(text)
        }
    }

    private fun readSharedUrl(): String? =
        if (intent?.action == android.content.Intent.ACTION_SEND) {
            intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        } else {
            null
        }

    companion object {
        fun intent(context: Context) = android.content.Intent(context, AddDownloadActivity::class.java)
    }
}
