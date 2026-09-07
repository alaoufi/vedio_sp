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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ActivityChannelBinding
import com.myvideolibrary.app.ui.provider.DownloadKindDialog
import com.myvideolibrary.app.ui.search.SearchResultAdapter
import com.myvideolibrary.app.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Channel-browsing page: a uploader's videos with infinite scroll + local subscribe. */
@AndroidEntryPoint
class ChannelActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChannelBinding
    private val viewModel: ChannelViewModel by viewModels()
    private lateinit var adapter: SearchResultAdapter

    private var channelUrl: String = ""
    private var channelName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        channelUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        channelName = intent.getStringExtra(EXTRA_NAME)
        channelName?.let { binding.channelName.text = it }

        adapter = SearchResultAdapter(
            onPlay = { item -> YouTubeDetailActivity.start(this, item.url, item.title, item.thumbnailUrl) },
            onSaveLink = { item -> viewModel.saveLinkItem(item) },
            onDownload = { item, anchor ->
                DownloadKindDialog.show(anchor) { kind -> viewModel.downloadItem(item, kind) }
            }
        )
        adapter.style = SearchResultAdapter.Style.LIST
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.itemCount > 0 && lm.findLastVisibleItemPosition() >= lm.itemCount - 4) {
                    viewModel.loadMore()
                }
            }
        })

        renderSubscribe()
        binding.subscribeButton.setOnClickListener {
            LocalSubscriptions.toggle(this, channelUrl, channelName)
            renderSubscribe()
        }

        if (channelUrl.isNotBlank()) viewModel.load(channelUrl)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest(::render)
            }
        }
    }

    private fun render(state: ChannelUiState) {
        binding.progress.isVisible = state.loading && state.items.isEmpty()
        binding.errorText.isVisible = state.error != null && state.items.isEmpty()
        state.error?.let { if (state.items.isEmpty()) binding.errorText.text = it }

        state.header?.let { header ->
            if (!header.name.isNullOrBlank()) {
                binding.channelName.text = header.name
                channelName = header.name
            }
            binding.channelSubs.isVisible = header.subscriberCount >= 0
            if (header.subscriberCount >= 0) {
                binding.channelSubs.text =
                    getString(R.string.yt_subscribers, Formatters.count(header.subscriberCount))
            }
            Glide.with(this)
                .load(header.avatarUrl)
                .placeholder(R.drawable.ic_video_placeholder)
                .circleCrop()
                .into(binding.channelAvatar)
        }

        adapter.submitList(state.items)

        state.message?.let {
            android.widget.Toast.makeText(this, R.string.download_started, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeMessage()
        }
        if (state.savedLink) {
            android.widget.Toast.makeText(this, R.string.link_saved, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.consumeSavedLink()
        }
    }

    private fun renderSubscribe() {
        val subscribed = LocalSubscriptions.isSubscribed(this, channelUrl)
        binding.subscribeButton.setText(
            if (subscribed) R.string.yt_subscribed else R.string.yt_subscribe
        )
    }

    companion object {
        private const val EXTRA_URL = "extra_url"
        private const val EXTRA_NAME = "extra_name"

        fun intent(context: Context, channelUrl: String, name: String?): Intent =
            Intent(context, ChannelActivity::class.java)
                .putExtra(EXTRA_URL, channelUrl)
                .putExtra(EXTRA_NAME, name)
    }
}
