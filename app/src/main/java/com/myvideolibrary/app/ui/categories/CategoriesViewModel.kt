package com.myvideolibrary.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.repository.SettingsRepository
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.util.CategoryOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    /** Categories in the user's saved display order. */
    val categories: StateFlow<List<String>> = combine(
        videoRepository.observeCategories(),
        settingsRepository.observeSettings()
    ) { present, settings ->
        CategoryOrder.apply(present, settings.categoryOrder)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rename(oldName: String, newName: String) = viewModelScope.launch {
        val clean = newName.trim()
        if (clean.isEmpty() || clean == oldName) return@launch
        videoRepository.renameCategory(oldName, clean)
        val current = settingsRepository.getSettings()
        val order = CategoryOrder.parse(current.categoryOrder).map { if (it == oldName) clean else it }
        settingsRepository.update { it.copy(categoryOrder = CategoryOrder.serialize(order)) }
    }

    fun delete(name: String) = viewModelScope.launch {
        videoRepository.deleteCategory(name)
        val current = settingsRepository.getSettings()
        val order = CategoryOrder.parse(current.categoryOrder).filter { it != name }
        settingsRepository.update { it.copy(categoryOrder = CategoryOrder.serialize(order)) }
    }

    /** Persists a new display order after a drag-and-drop reorder. */
    fun saveOrder(order: List<String>) = viewModelScope.launch {
        settingsRepository.update { it.copy(categoryOrder = CategoryOrder.serialize(order)) }
    }
}
