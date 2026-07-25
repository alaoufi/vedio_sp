package com.myvideolibrary.app.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.local.dao.PlaylistDao
import com.myvideolibrary.app.data.local.entity.PlaylistEntity
import com.myvideolibrary.app.data.local.entity.PlaylistWithCount
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistDao: PlaylistDao
) : ViewModel() {

    val playlists: StateFlow<List<PlaylistWithCount>> =
        playlistDao.observePlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun create(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            playlistDao.insert(PlaylistEntity(name = trimmed, createdDate = System.currentTimeMillis()))
        }
    }

    fun rename(id: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { playlistDao.rename(id, trimmed) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            playlistDao.clearVideos(id)
            playlistDao.delete(id)
        }
    }
}
