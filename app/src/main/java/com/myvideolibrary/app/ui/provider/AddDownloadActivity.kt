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

        binding.resolveButton.setOnClickListener {
            viewModel.resolve(binding.urlInput.text?.toString().orEmpty())
        }
        binding.pasteButton.setOnClickListener { pasteFromClipboard() }
        binding.openBrowserButton.setOnClickListener {
            val url = binding.urlInput.text?.toString()?.trim().orEmpty()
            if (url.startsWith("http")) {
                startActivity(com.myvideolibrary.app.ui.browser.BrowserActivity.intent(this, url))
            }
        }
        binding.downloadButton.setOnClickListener { anchor ->
            DownloadKindDialog.show(anchor) { kind -> viewModel.download(kind) }
        }

        observe()

        // Auto-detect a link: a shared URL wins; otherwise sniff the clipboard.
        val shared = readSharedUrl()
        if (!shared.isNullOrBlank()) {
            binding.urlInput.setText(shared)
            viewModel.resolve(shared)
        } else {
            autoDetectFromClipboard()
        }
    }

    /** If the clipboard holds a supported link, fill it and fetch info automatically. */
    private fun autoDetectFromClipboard() {
        val text = clipboardText() ?: return
        val link = extractSupportedLink(text) ?: return
        binding.urlInput.setText(link)
        viewModel.resolve(link)
    }

    private fun clipboardText(): String? {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
    }

    /** Pulls the first tiktok/youtube URL out of arbitrary text (e.g. a shared caption). */
    private fun extractSupportedLink(text: String): String? {
        val token = text.split(Regex("\\s+")).firstOrNull { candidate ->
            val c = candidate.lowercase()
            (c.startsWith("http")) && (
                c.contains("tiktok.com") || c.contains("vm.tiktok") || c.contains("vt.tiktok") ||
                    c.contains("youtube.com") || c.contains("youtu.be") ||
                    c.contains("instagram.com") || c.contains("instagr.am") ||
                    c.contains("snapchat.com")
                )
        }
        return token
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

        // Offer the browser fallback for Instagram/Snapchat (login-walled), and for
        // any link whose extraction failed — the sniffing browser often still gets it.
        val url = binding.urlInput.text?.toString()?.lowercase().orEmpty()
        val socialLink = url.contains("instagram.com") || url.contains("instagr.am") ||
            url.contains("snapchat.com")
        binding.openBrowserButton.isVisible =
            state.resolved == null && (socialLink || state.errorMessage != null)

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
