package com.myvideolibrary.app.ui.youtube

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bumptech.glide.Glide
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ActivityYoutubeDetailBinding
import com.myvideolibrary.app.provider.model.ProviderSearchItem
import com.myvideolibrary.app.provider.model.ProviderVideoDetail
import com.myvideolibrary.app.ui.player.PlayerActivity
import com.myvideolibrary.app.ui.provider.DownloadKindDialog
import com.myvideolibrary.app.ui.search.SearchResultAdapter
import com.myvideolibrary.app.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * YouTube-style detail page: poster + play, title/meta, channel (tap to browse),
 * play / download / save actions, an expandable description, and a related
 * ("up next") list. Tapping a related item opens its own detail page.
 */
@AndroidEntryPoint
class YouTubeDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityYoutubeDetailBinding
    private val viewModel: YouTubeDetailViewModel by viewModels()
    private lateinit var relatedAdapter: SearchResultAdapter

    private var url: String = ""
    private var descriptionExpanded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityYoutubeDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE)
        if (!title.isNullOrBlank()) binding.title.text = title

        relatedAdapter = SearchResultAdapter(
            onPlay = { item -> start(this, item.url, item.title); finishAfterOpening() },
            onSaveLink = { item -> viewModel.saveLinkItem(item) },
            onDownload = { item, anchor ->
                DownloadKindDialog.show(anchor) { kind -> viewModel.downloadItem(item, kind) }
            }
        )
        relatedAdapter.style = SearchResultAdapter.Style.LIST
        binding.relatedRecycler.adapter = relatedAdapter

        binding.description.setOnClickListener { toggleDescription() }
        binding.playOverlay.setOnClickListener { playCurrent() }
        binding.btnPlay.setOnClickListener { playCurrent() }
        binding.btnDownload.setOnClickListener {
            DownloadKindDialog.show(binding.btnDownload) { kind -> viewModel.download(kind) }
        }
        binding.btnSaveLink.setOnClickListener { viewModel.saveLink() }

        if (url.isNotBlank()) viewModel.load(url)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest(::render)
            }
        }
    }

    private fun render(state: DetailUiState) {
        binding.progress.isVisible = state.loading && state.detail == null
        val detail = state.detail
        binding.scroll.isVisible = detail != null
        binding.errorText.isVisible = state.error != null && detail == null
        state.error?.let { if (detail == null) binding.errorText.text = it }

        detail?.let(::bind)

        state.message?.let {
            android.widget.Toast.makeText(this, R.string.download_started, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
        if (state.savedLink) {
            android.widget.Toast.makeText(this, R.string.link_saved, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeSavedLink()
        }
    }

    private fun bind(detail: ProviderVideoDetail) {
        binding.title.text = detail.title
        binding.meta.text = metaLine(detail)
        Glide.with(this)
            .load(detail.thumbnailUrl)
            .placeholder(R.drawable.ic_video_placeholder)
            .centerCrop()
            .into(binding.thumbnail)

        binding.channelRow.isVisible = !detail.author.isNullOrBlank()
        binding.channelName.text = detail.author.orEmpty()
        binding.channelSubs.text = if (detail.subscriberCount >= 0) {
            getString(R.string.yt_subscribers, Formatters.count(detail.subscriberCount))
        } else ""
        binding.channelSubs.isVisible = detail.subscriberCount >= 0
        Glide.with(this)
            .load(detail.channelAvatarUrl)
            .placeholder(R.drawable.ic_video_placeholder)
            .circleCrop()
            .into(binding.channelAvatar)
        val channelUrl = detail.channelUrl
        binding.channelRow.setOnClickListener {
            if (!channelUrl.isNullOrBlank()) {
                startActivity(ChannelActivity.intent(this, channelUrl, detail.author))
            }
        }

        binding.description.isVisible = !detail.description.isNullOrBlank()
        binding.description.text = detail.description.orEmpty()

        binding.relatedHeader.isVisible = detail.related.isNotEmpty()
        relatedAdapter.submitList(detail.related)
    }

    private fun metaLine(detail: ProviderVideoDetail): String = listOfNotNull(
        detail.viewCount.takeIf { it >= 0 }?.let { getString(R.string.yt_views, Formatters.count(it)) },
        detail.uploadDate?.takeIf { it.isNotBlank() }
    ).joinToString(" · ")

    private fun toggleDescription() {
        descriptionExpanded = !descriptionExpanded
        binding.description.maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3
    }

    private fun playCurrent() {
        val detail = viewModel.state.value.detail
        val title = detail?.title ?: binding.title.text?.toString().orEmpty()
        startActivity(PlayerActivity.streamIntent(this, url, title))
    }

    /** After opening another detail page from a related tap, close this one is optional. */
    private fun finishAfterOpening() { /* keep back-stack: do nothing */ }

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"

        fun intent(context: Context, url: String, title: String?): Intent =
            Intent(context, YouTubeDetailActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)

        fun start(context: Context, url: String, title: String?) {
            context.startActivity(intent(context, url, title))
        }
    }
}
