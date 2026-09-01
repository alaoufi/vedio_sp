package com.myvideolibrary.app.ui.reorder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.SortOrder
import com.myvideolibrary.app.data.repository.SettingsRepository
import com.myvideolibrary.app.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the drag-to-arrange screen: loads clips in the current custom order and
 * persists a new arrangement, switching the library's sort to CUSTOM so the order
 * is what the user then sees.
 */
@HiltViewModel
class ReorderViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val clips: List<VideoEntity> = emptyList()
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val clips = videoRepository.clipsForReorder(MAX_CLIPS)
            _state.value = State(loading = false, clips = clips)
        }
    }

    /** Saves the given top-first order and makes the library show it (CUSTOM sort). */
    fun save(orderedTopFirst: List<VideoEntity>) = viewModelScope.launch {
        videoRepository.saveCustomOrder(orderedTopFirst)
        settingsRepository.update { it.copy(sortOrder = SortOrder.CUSTOM.id) }
    }

    companion object {
        /** Cap the non-paged list; more than this is impractical to hand-arrange. */
        private const val MAX_CLIPS = 1000
    }
}
