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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = SearchResultAdapter(
            onPlay = viewModel::play,
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
                    if (checkedId == R.id.sourceTiktok) VideoSource.TIKTOK else VideoSource.YOUTUBE
                )
            }
        }

        binding.searchButton.setOnClickListener { submit() }
        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false
        }

        observe()
    }

    private fun submit() {
        viewModel.search(binding.searchInput.text?.toString().orEmpty())
    }

    private fun chooseDownloadKind(item: com.myvideolibrary.app.provider.model.ProviderSearchItem) {
        com.myvideolibrary.app.ui.provider.DownloadKindDialog.show(this) { kind ->
            viewModel.downloadItem(item, kind)
        }
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    binding.progressBar.isVisible = state.loading
                    adapter.submitList(state.results)

                    // TikTok = link-based; hint the user and change the field mode.
                    binding.hintText.isVisible = !state.searchSupported
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
