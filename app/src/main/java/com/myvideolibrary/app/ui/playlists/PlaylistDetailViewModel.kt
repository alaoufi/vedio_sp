package com.myvideolibrary.app.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.local.dao.PlaylistDao
import com.myvideolibrary.app.data.local.entity.VideoEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val playlistDao: PlaylistDao
) : ViewModel() {

    private val playlistId = MutableStateFlow(-1L)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val videos: StateFlow<List<VideoEntity>> =
        playlistId.flatMapLatest { id ->
            if (id > 0) playlistDao.observeVideos(id)
            else kotlinx.coroutines.flow.flowOf(emptyList())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setPlaylist(id: Long) { playlistId.value = id }

    fun removeVideo(videoId: Long) {
        val id = playlistId.value
        if (id > 0) viewModelScope.launch { playlistDao.removeVideo(id, videoId) }
    }
}
