package com.myvideolibrary.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.data.repository.DownloadRepository
import com.myvideolibrary.app.download.DownloadManager
import com.myvideolibrary.app.provider.ProviderRegistry
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ProviderSearchItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One-shot request to open the streaming player for a search result. */
data class StreamRequest(val sourceUrl: String, val title: String)

data class SearchUiState(
    val source: VideoSource = VideoSource.YOUTUBE,
    val loading: Boolean = false,
    val results: List<ProviderSearchItem> = emptyList(),
    val searchSupported: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val savedLink: Boolean = false,
    val streamRequest: StreamRequest? = null
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val downloadManager: DownloadManager,
    private val videoRepository: VideoRepository,
    downloadRepository: DownloadRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** Active (waiting/downloading) jobs, so the screen can show a live banner. */
    val activeDownloads: StateFlow<List<com.myvideolibrary.app.data.local.entity.DownloadEntity>> =
        downloadRepository.observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSource(source: VideoSource) {
        // TikTok (resolver) and YouTube (NewPipe) support keyword search;
        // Instagram/Snapchat are link-only (paste a public post link).
        val searchable = source == VideoSource.TIKTOK || source == VideoSource.YOUTUBE
        _state.value = _state.value.copy(
            source = source,
            searchSupported = searchable,
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

    fun downloadItem(
        item: ProviderSearchItem,
        kind: com.myvideolibrary.app.data.model.DownloadKind =
            com.myvideolibrary.app.data.model.DownloadKind.FULL
    ) {
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
                        thumbnailUrl = item.thumbnailUrl,
                        kind = kind
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
            downloadLink(item.url, kind)
        }
    }

    /** Preview: open the platform stream in the player without downloading. */
    fun play(item: ProviderSearchItem) {
        _state.value = _state.value.copy(
            streamRequest = StreamRequest(item.url, item.title)
        )
    }

    /** Save a link-only library entry (streams on demand, downloadable later). */
    fun saveLink(item: ProviderSearchItem) {
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

    private fun downloadLink(
        url: String,
        kind: com.myvideolibrary.app.data.model.DownloadKind =
            com.myvideolibrary.app.data.model.DownloadKind.FULL
    ) {
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
                    thumbnailUrl = resolved.thumbnailUrl,
                    kind = kind
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

    fun consumeSavedLink() { _state.value = _state.value.copy(savedLink = false) }

    fun consumeStreamRequest() { _state.value = _state.value.copy(streamRequest = null) }
}
