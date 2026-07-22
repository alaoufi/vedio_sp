package com.myvideolibrary.app.ui.search

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.databinding.ActivitySearchBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private val viewModel: SearchViewModel by viewModels()
    private lateinit var adapter: SearchResultAdapter

    /** Results currently on screen, used to build a swipe-able player queue. */
    private var currentItems: List<com.myvideolibrary.app.provider.model.ProviderSearchItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = SearchResultAdapter(
            onPlay = ::playStreamQueue,
            onSaveLink = viewModel::saveLink,
            onDownload = ::chooseDownloadKind
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        // Source selection.
        binding.sourceToggle.check(R.id.sourceYoutube)
        binding.sourceToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                viewModel.setSource(
                    when (checkedId) {
                        R.id.sourceTiktok -> VideoSource.TIKTOK
                        R.id.sourceInstagram -> VideoSource.INSTAGRAM
                        R.id.sourceSnapchat -> VideoSource.SNAPCHAT
                        else -> VideoSource.YOUTUBE
                    }
                )
            }
        }

        binding.searchButton.setOnClickListener { submit() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }
        binding.downloadBanner.setOnClickListener {
            startActivity(
                android.content.Intent(
                    this, com.myvideolibrary.app.ui.downloads.DownloadsActivity::class.java
                )
            )
        }

        observe()
        observeDownloads()
    }

    /** Mirrors the library's live download indicator so progress is visible here too. */
    private fun observeDownloads() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeDownloads.collectLatest { active ->
                    val show = active.isNotEmpty()
                    binding.downloadBanner.isVisible = show
                    if (show) {
                        val downloading = active.firstOrNull {
                            it.status ==
                                com.myvideolibrary.app.data.model.DownloadStatus.DOWNLOADING.id
                        }
                        val percent = downloading?.progress ?: 0
                        binding.downloadBannerText.text =
                            getString(R.string.downloading_banner, active.size, percent)
                        val bar = binding.downloadBannerProgress
                        if (downloading == null) {
                            bar.isIndeterminate = true
                        } else {
                            bar.isIndeterminate = false
                            bar.setProgressCompat(percent, true)
                        }
                    }
                }
            }
        }
    }

    private fun submit() {
        viewModel.search(binding.searchInput.text?.toString().orEmpty())
    }

    private var styledSource: VideoSource? = null

    /** Mimics each platform's look: YouTube = landscape list, TikTok = 9:16 grid. */
    private fun applyStyle(source: VideoSource) {
        if (styledSource == source) return
        styledSource = source
        when (source) {
            VideoSource.TIKTOK, VideoSource.INSTAGRAM, VideoSource.SNAPCHAT -> {
                adapter.style = SearchResultAdapter.Style.PORTRAIT
                binding.recyclerView.layoutManager =
                    androidx.recyclerview.widget.GridLayoutManager(this, 3)
            }
            else -> {
                // YouTube: a 2-column grid of 16:9 cards, mirroring the YouTube app.
                adapter.style = SearchResultAdapter.Style.GRID
                binding.recyclerView.layoutManager =
                    androidx.recyclerview.widget.GridLayoutManager(this, 2)
            }
        }
    }

    /** Plays a result as a stream, with the whole result list as a swipe-able queue. */
    private fun playStreamQueue(item: com.myvideolibrary.app.provider.model.ProviderSearchItem) {
        val list = currentItems
        if (list.size > 1) {
            val index = list.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
            startActivity(
                com.myvideolibrary.app.ui.player.PlayerActivity.streamPlaylistIntent(
                    this,
                    list.map { it.url }.toTypedArray(),
                    list.map { it.title }.toTypedArray(),
                    index
                )
            )
        } else {
            startActivity(
                com.myvideolibrary.app.ui.player.PlayerActivity.streamIntent(this, item.url, item.title)
            )
        }
    }

    private fun chooseDownloadKind(
        item: com.myvideolibrary.app.provider.model.ProviderSearchItem,
        anchor: android.view.View
    ) {
        com.myvideolibrary.app.ui.provider.DownloadKindDialog.show(anchor) { kind ->
            viewModel.downloadItem(item, kind)
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    binding.progressBar.isVisible = state.loading
                    applyStyle(state.source)
                    currentItems = state.results
                    adapter.submitList(state.results)

                    // Link-based sources (TikTok/Instagram/Snapchat): hint the user
                    // to paste a link and switch the field to URL mode.
                    binding.hintText.isVisible = !state.searchSupported
                    binding.hintText.setText(R.string.link_only_hint)
                    binding.searchInput.hint = getString(
                        if (state.searchSupported) R.string.search_hint else R.string.paste_url_hint
                    )

                    binding.errorText.isVisible = state.error != null
                    binding.errorText.text = state.error

                    state.message?.let {
                        Toast.makeText(this@SearchActivity, R.string.download_started, Toast.LENGTH_LONG).show()
                        viewModel.consumeMessage()
                    }

                    if (state.savedLink) {
                        Toast.makeText(this@SearchActivity, R.string.link_saved, Toast.LENGTH_SHORT).show()
                        viewModel.consumeSavedLink()
                    }

                    state.streamRequest?.let { req ->
                        startActivity(
                            com.myvideolibrary.app.ui.player.PlayerActivity.streamIntent(
                                this@SearchActivity, req.sourceUrl, req.title
                            )
                        )
                        viewModel.consumeStreamRequest()
                    }
                }
            }
        }
    }
}
