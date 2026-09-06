package com.myvideolibrary.app.ui.youtube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.DownloadKind
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.download.DownloadManager
import com.myvideolibrary.app.provider.ProviderRegistry
import com.myvideolibrary.app.provider.model.ProviderChannelPage
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ProviderSearchItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChannelUiState(
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val header: ProviderChannelPage? = null,
    val items: List<ProviderSearchItem> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val savedLink: Boolean = false
)

/** Backs the channel-browsing page: a uploader's videos with infinite scroll. */
@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val downloadManager: DownloadManager,
    private val videoRepository: VideoRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ChannelUiState())
    val state: StateFlow<ChannelUiState> = _state.asStateFlow()

    private var channelUrl: String? = null
    private var continuation: Any? = null

    fun load(url: String) {
        if (channelUrl == url && _state.value.header != null) return
        channelUrl = url
        continuation = null
        val provider = providerRegistry.providerForSource(VideoSource.YOUTUBE) ?: return
        _state.value = _state.value.copy(loading = true, error = null, items = emptyList(), header = null)
        viewModelScope.launch {
            try {
                val page = provider.channel(url)
                if (page == null) {
                    _state.value = _state.value.copy(loading = false, error = "unavailable")
                } else {
                    continuation = page.continuation
                    _state.value = _state.value.copy(
                        loading = false,
                        header = page,
                        items = page.items,
                        canLoadMore = page.continuation != null
                    )
                }
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

    fun loadMore() {
        val url = channelUrl ?: return
        val cont = continuation ?: return
        val s = _state.value
        if (s.loading || s.loadingMore) return
        val provider = providerRegistry.providerForSource(VideoSource.YOUTUBE) ?: return
        _state.value = s.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val page = provider.channelMore(url, cont)
                continuation = page?.continuation
                _state.value = _state.value.copy(
                    loadingMore = false,
                    items = _state.value.items + (page?.items ?: emptyList()),
                    canLoadMore = page?.continuation != null
                )
            } catch (e: Throwable) {
                continuation = null
                _state.value = _state.value.copy(loadingMore = false, canLoadMore = false)
            }
        }
    }

    fun downloadItem(item: ProviderSearchItem, kind: DownloadKind = DownloadKind.FULL) {
        val provider = providerRegistry.providerForUrl(item.url) ?: run {
            _state.value = _state.value.copy(error = "Unsupported link")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val resolved = provider.resolve(item.url)
                downloadManager.enqueueResolved(resolved, kind)
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

    fun saveLinkItem(item: ProviderSearchItem) {
        viewModelScope.launch {
            try {
                videoRepository.addVideo(
                    VideoEntity(
                        title = item.title,
                        localPath = "",
                        source = item.source.id,
                        sourceUrl = item.url,
                        thumbnailPath = item.thumbnailUrl,
                        duration = item.durationMs,
                        createdDate = System.currentTimeMillis(),
                        isLinkOnly = true
                    )
                )
                _state.value = _state.value.copy(savedLink = true)
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    error = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                )
            }
        }
    }

    fun consumeMessage() { _state.value = _state.value.copy(message = null) }
    fun consumeSavedLink() { _state.value = _state.value.copy(savedLink = false) }
}
