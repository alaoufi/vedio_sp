package com.myvideolibrary.app.ui.duplicates

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

/**
 * Backs the Duplicates screen. Groups videos that share a content fingerprint,
 * keeps the oldest of each group, and can delete the extra copies to reclaim
 * space (app-owned files only — imported MediaStore references are never touched).
 */
@HiltViewModel
class DuplicatesViewModel @Inject constructor(
    private val videoRepository: VideoRepository
) : ViewModel() {

    /** One set of duplicates: the copy we keep and the extras that can go. */
    data class Group(val keep: VideoEntity, val extras: List<VideoEntity>) {
        val copies: Int get() = extras.size + 1
        val reclaimable: Long get() = extras.sumOf { it.fileSize }
    }

    data class State(
        val loading: Boolean = true,
        val groups: List<Group> = emptyList()
    ) {
        val extrasCount: Int get() = groups.sumOf { it.extras.size }
        val totalReclaimable: Long get() = groups.sumOf { it.reclaimable }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        // findDuplicates() returns each group oldest-first, so first() is the keeper.
        val groups = videoRepository.findDuplicates().mapNotNull { list ->
            if (list.size < 2) null else Group(keep = list.first(), extras = list.drop(1))
        }
        _state.value = State(loading = false, groups = groups)
    }

    fun removeExtras(group: Group) = viewModelScope.launch {
        videoRepository.deleteVideos(group.extras.map { it.id }, alsoDeleteFiles = true)
        load()
    }

    fun removeAllExtras() = viewModelScope.launch {
        val ids = _state.value.groups.flatMap { g -> g.extras.map { it.id } }
        if (ids.isNotEmpty()) videoRepository.deleteVideos(ids, alsoDeleteFiles = true)
        load()
    }
}
