package com.myvideolibrary.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val videoRepository: VideoRepository
) : ViewModel() {

    private val _video = MutableStateFlow<VideoEntity?>(null)
    val video: StateFlow<VideoEntity?> = _video.asStateFlow()

    private var loaded = false
    private var countedPlay = false

    fun load(id: Long) {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            _video.value = videoRepository.getVideo(id)
        }
    }

    /** The stored resume position for the current video, in milliseconds. */
    fun resumePosition(): Long = _video.value?.lastPlayedPosition ?: 0L

    /** Persist playback progress; counts a "play" only once per session. */
    fun savePosition(positionMs: Long) {
        val current = _video.value ?: return
        viewModelScope.launch {
            val countAsPlay = !countedPlay
            countedPlay = true
            videoRepository.recordPlayback(current.id, positionMs, countAsPlay)
        }
    }
}
