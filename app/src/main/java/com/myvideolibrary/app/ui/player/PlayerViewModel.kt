package com.myvideolibrary.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.provider.ProviderRegistry
import com.myvideolibrary.app.provider.model.ProviderException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the player should render, once resolved. */
sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Ready(
        val title: String,
        /** File path, content:// URI, or an http(s) streaming URL. */
        val url: String,
        val resumeMs: Long = 0,
        /** DB id for playback bookkeeping; null for ad-hoc streams. */
        val trackId: Long? = null,
        val streaming: Boolean = false,
        /** True when the track has no video — show cover art, not a black frame. */
        val isAudio: Boolean = false,
        /** Cover image (file path or URL) to display while playing audio. */
        val artwork: String? = null
    ) : PlayerUiState
    data class Error(val message: String?) : PlayerUiState
}

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val providerRegistry: ProviderRegistry
) : ViewModel() {

    private val _state = MutableStateFlow<PlayerUiState>(PlayerUiState.Loading)
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var loaded = false
    private var countedPlay = false

    /** Plays a stored library video; streams from source if it's a saved link. */
    fun loadVideo(id: Long) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            val video = videoRepository.getVideo(id)
            if (video == null) {
                _state.value = PlayerUiState.Error(null)
                return@launch
            }
            if (video.isLinkOnly) {
                val src = video.sourceUrl
                if (src == null) {
                    _state.value = PlayerUiState.Error(null)
                    return@launch
                }
                resolveAndReady(src, video.title, trackId = id)
            } else {
                _state.value = PlayerUiState.Ready(
                    title = video.title,
                    url = video.localPath,
                    resumeMs = video.lastPlayedPosition,
                    trackId = id,
                    streaming = false,
                    isAudio = video.mediaType ==
                        com.myvideolibrary.app.data.model.MediaType.AUDIO.id,
                    artwork = video.thumbnailPath
                )
            }
        }
    }

    /** Plays an ad-hoc preview straight from the platform, no library entry. */
    fun loadStream(sourceUrl: String, title: String) {
        if (loaded) return
        loaded = true
        viewModelScope.launch { resolveAndReady(sourceUrl, title, trackId = null) }
    }

    private suspend fun resolveAndReady(sourceUrl: String, title: String, trackId: Long?) {
        _state.value = PlayerUiState.Loading
        val provider = providerRegistry.providerForUrl(sourceUrl)
        if (provider == null) {
            _state.value = PlayerUiState.Error(null)
            return
        }
        try {
            val stream = provider.resolveStream(sourceUrl)
            _state.value = PlayerUiState.Ready(
                title = stream.title.ifBlank { title },
                url = stream.streamUrl,
                resumeMs = 0,
                trackId = trackId,
                streaming = true
            )
        } catch (e: ProviderException) {
            _state.value = PlayerUiState.Error(e.message)
        } catch (e: Throwable) {
            _state.value = PlayerUiState.Error(e.message)
        }
    }

    /** Persist playback progress for stored, on-disk videos only. */
    fun savePosition(positionMs: Long) {
        val ready = _state.value as? PlayerUiState.Ready ?: return
        val id = ready.trackId ?: return
        // Streaming URLs change every session, so a resume offset is meaningless;
        // only record a play count for those.
        val savePos = if (ready.streaming) 0L else positionMs
        viewModelScope.launch {
            val countAsPlay = !countedPlay
            countedPlay = true
            videoRepository.recordPlayback(id, savePos, countAsPlay)
        }
    }
}
