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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.bumptech.glide.Glide
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ActivityYoutubeDetailBinding
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

    /** Inline player state (YouTube-style playback inside this page). */
    private var player: ExoPlayer? = null
    private var playerStarted = false
    private var posterLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityYoutubeDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE)
        if (!title.isNullOrBlank()) binding.title.text = title
        // Show the poster and page immediately (fast transition) — details fill in after.
        binding.scroll.isVisible = true
        loadPoster(intent.getStringExtra(EXTRA_THUMB))

        relatedAdapter = SearchResultAdapter(
            onPlay = { item -> start(this, item.url, item.title, item.thumbnailUrl) },
            onSaveLink = { item -> viewModel.saveLinkItem(item) },
            onDownload = { item, anchor ->
                DownloadKindDialog.show(anchor) { kind -> viewModel.downloadItem(item, kind) }
            }
        )
        relatedAdapter.style = SearchResultAdapter.Style.LIST
        binding.relatedRecycler.adapter = relatedAdapter

        binding.description.setOnClickListener { toggleDescription() }
        binding.playOverlay.setOnClickListener { startInlinePlayback() }
        binding.btnPlay.setOnClickListener { startInlinePlayback() }
        binding.fullscreenButton.setOnClickListener { openFullscreen() }
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
        // Only a subtle top spinner while details load; the page is already visible.
        binding.progress.isVisible = state.loading && state.detail == null
        val detail = state.detail
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
        // Load the detail thumbnail only if we didn't already show one from the intent
        // (avoids a flash), and never while the inline player is on screen.
        if (!posterLoaded && !playerStarted) loadPoster(detail.thumbnailUrl)

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
        cleanDate(detail.uploadDate)
    ).joinToString(" · ")

    /**
     * Some backends give a relative date ("3 years ago"); others an ISO timestamp
     * ("2020-03-20T13:50:27-07:00") whose time part just clutters an RTL line — so
     * keep only the calendar day from an ISO value, and pass anything else through.
     */
    private fun cleanDate(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return if (s.length >= 10 && s[4] == '-' && s[7] == '-') s.substring(0, 10) else s
    }

    private fun toggleDescription() {
        descriptionExpanded = !descriptionExpanded
        binding.description.maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2
    }

    private fun loadPoster(thumbUrl: String?) {
        if (thumbUrl.isNullOrBlank()) return
        posterLoaded = true
        Glide.with(this)
            .load(thumbUrl)
            .placeholder(R.drawable.ic_video_placeholder)
            .centerCrop()
            .into(binding.thumbnail)
    }

    /** Starts inline playback in the poster area, like YouTube's mini player. */
    private fun startInlinePlayback() {
        if (playerStarted || url.isBlank()) return
        playerStarted = true
        binding.playOverlay.isVisible = false
        binding.playerLoading.isVisible = true
        lifecycleScope.launch {
            val streamUrl = viewModel.resolveStreamUrl()
            if (streamUrl.isNullOrBlank()) {
                binding.playerLoading.isVisible = false
                binding.playOverlay.isVisible = true
                playerStarted = false
                android.widget.Toast.makeText(
                    this@YouTubeDetailActivity, R.string.yt_play_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            binding.thumbnail.isVisible = false
            binding.playerView.isVisible = true
            binding.fullscreenButton.isVisible = true
            preparePlayer(streamUrl)
        }
    }

    private fun preparePlayer(streamUrl: String) {
        // Start playing after ~0.5s buffered (not the default 2.5s) for a fast start.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                500,
                1000
            )
            .build()
        val exo = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        player = exo
        binding.playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(streamUrl))
        exo.playWhenReady = true
        exo.prepare()
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                binding.playerLoading.isVisible = playbackState == Player.STATE_BUFFERING
            }
        })
    }

    /** Optional enlarge: hand off to the full-screen player. */
    private fun openFullscreen() {
        val title = viewModel.state.value.detail?.title ?: binding.title.text?.toString().orEmpty()
        startActivity(PlayerActivity.streamIntent(this, url, title))
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        binding.playerView.player = null
        playerStarted = false
        // Return to the poster state so replay works when coming back.
        binding.playerView.isVisible = false
        binding.fullscreenButton.isVisible = false
        binding.playerLoading.isVisible = false
        binding.thumbnail.isVisible = true
        binding.playOverlay.isVisible = true
    }

    override fun onStop() {
        super.onStop()
        // Free the codec when leaving (also when opening full-screen), avoiding two players.
        releasePlayer()
    }

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_THUMB = "extra_thumb"

        fun intent(context: Context, url: String, title: String?, thumbnailUrl: String? = null): Intent =
            Intent(context, YouTubeDetailActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_THUMB, thumbnailUrl)

        fun start(context: Context, url: String, title: String?, thumbnailUrl: String? = null) {
            context.startActivity(intent(context, url, title, thumbnailUrl))
        }
    }
}
