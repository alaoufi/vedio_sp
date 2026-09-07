package com.myvideolibrary.app.ui.youtube

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.DownloadKind
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.download.DownloadManager
import com.myvideolibrary.app.provider.ProviderRegistry
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ProviderVideoDetail
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val loading: Boolean = false,
    val detail: ProviderVideoDetail? = null,
    val error: String? = null,
    val message: String? = null,
    val savedLink: Boolean = false
)

/** Backs the YouTube-style detail page: loads details + related, plays/downloads. */
@HiltViewModel
class YouTubeDetailViewModel @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val downloadManager: DownloadManager,
    private val videoRepository: VideoRepository
) : ViewModel() {

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private var url: String? = null

    /** Loads (or reloads) the detail for [videoUrl]; no-op if already loaded. */
    fun load(videoUrl: String) {
        if (url == videoUrl && _state.value.detail != null) return
        url = videoUrl
        val provider = providerRegistry.providerForSource(VideoSource.YOUTUBE) ?: return
        _state.value = _state.value.copy(loading = true, error = null, detail = null)
        viewModelScope.launch {
            try {
                val detail = provider.details(videoUrl)
                _state.value = if (detail == null) {
                    _state.value.copy(loading = false, error = "unavailable")
                } else {
                    _state.value.copy(loading = false, detail = detail)
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

    fun retry() {
        val u = url ?: return
        url = null
        load(u)
    }

    /**
     * Resolves a directly-playable stream for inline playback (progressive, or an
     * HLS manifest for live). Runs the provider's own IO work; null on failure.
     */
    suspend fun resolveStreamSource(): com.myvideolibrary.app.provider.model.StreamSource? {
        val u = url ?: return null
        val provider = providerRegistry.providerForUrl(u) ?: return null
        return runCatching { provider.resolveStream(u) }.getOrNull()
    }

    /** Resolves the current video and enqueues a download. */
    fun download(kind: DownloadKind = DownloadKind.FULL) {
        val u = url ?: return
        val provider = providerRegistry.providerForUrl(u) ?: run {
            _state.value = _state.value.copy(error = "Unsupported link")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val resolved = provider.resolve(u)
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

    /** Saves a link-only library entry that streams on demand. */
    fun saveLink() {
        val d = _state.value.detail ?: return
        viewModelScope.launch {
            try {
                videoRepository.addVideo(
                    VideoEntity(
                        title = d.title,
                        localPath = "",
                        source = d.source.id,
                        sourceUrl = d.url,
                        thumbnailPath = d.thumbnailUrl,
                        duration = d.durationMs,
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

    /** Downloads a related/up-next item (has its own URL). */
    fun downloadItem(
        item: com.myvideolibrary.app.provider.model.ProviderSearchItem,
        kind: DownloadKind = DownloadKind.FULL
    ) {
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

    /** Saves a related item as a link-only library entry. */
    fun saveLinkItem(item: com.myvideolibrary.app.provider.model.ProviderSearchItem) {
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
