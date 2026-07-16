package com.myvideolibrary.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.download.DownloadManager
import com.myvideolibrary.app.provider.ProviderRegistry
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ProviderSearchItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val source: VideoSource = VideoSource.YOUTUBE,
    val loading: Boolean = false,
    val results: List<ProviderSearchItem> = emptyList(),
    val searchSupported: Boolean = true,
    val error: String? = null,
    val message: String? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    fun setSource(source: VideoSource) {
        // Both TikTok (via resolver) and YouTube (via NewPipe) support keyword search.
        _state.value = _state.value.copy(
            source = source,
            searchSupported = true,
            results = emptyList(),
            error = null
        )
    }

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val source = _state.value.source

        // If the user pasted a link, resolve it directly regardless of source.
        if (q.startsWith("http")) {
            downloadLink(q)
            return
        }

        val provider = providerRegistry.providerForSource(source) ?: return
        _state.value = _state.value.copy(loading = true, error = null, results = emptyList())
        viewModelScope.launch {
            try {
                val results = provider.search(q)
                _state.value = _state.value.copy(loading = false, results = results)
            } catch (e: ProviderException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                )
            }
        }
    }

    fun downloadItem(item: ProviderSearchItem) {
        // Search results that already carry a direct URL (TikTok) enqueue instantly.
        val direct = item.directUrl
        if (direct != null) {
            _state.value = _state.value.copy(loading = true, error = null)
            viewModelScope.launch {
                try {
                    downloadManager.enqueue(
                        title = item.title,
                        source = item.source.id,
                        sourceUrl = item.url,
                        directUrl = direct,
                        thumbnailUrl = item.thumbnailUrl
                    )
                    _state.value = _state.value.copy(loading = false, message = "queued")
                } catch (e: Throwable) {
                    _state.value = _state.value.copy(
                        loading = false,
                        error = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                    )
                }
            }
        } else {
            downloadLink(item.url)
        }
    }

    private fun downloadLink(url: String) {
        val provider = providerRegistry.providerForUrl(url)
        if (provider == null) {
            _state.value = _state.value.copy(error = "Unsupported link")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val resolved = provider.resolve(url)
                downloadManager.enqueue(
                    title = resolved.title,
                    source = resolved.source.id,
                    sourceUrl = resolved.sourceUrl,
                    directUrl = resolved.directUrl,
                    audioUrl = resolved.audioUrl,
                    thumbnailUrl = resolved.thumbnailUrl
                )
                _state.value = _state.value.copy(loading = false, message = "queued")
            } catch (e: ProviderException) {
                _state.value = _state.value.copy(loading = false, error = e.message)
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                )
            }
        }
    }

    fun consumeMessage() { _state.value = _state.value.copy(message = null) }
}
