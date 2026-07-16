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

    /** Creates a new (empty) category by persisting its name in the order list. */
    fun add(name: String) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isEmpty()) return@launch
        if (categories.value.any { it.equals(clean, ignoreCase = true) }) return@launch
        val current = settingsRepository.getSettings()
        val order = CategoryOrder.parse(current.categoryOrder)
        settingsRepository.update {
            it.copy(categoryOrder = CategoryOrder.serialize(order + clean))
        }
    }

    fun rename(oldName: String, newName: String) = viewModelScope.launch {
        val clean = newName.trim()
        if (clean.isEmpty() || clean == oldName) return@launch
        videoRepository.renameCategory(oldName, clean)
        val current = settingsRepository.getSettings()
        val parsed = CategoryOrder.parse(current.categoryOrder)
        // Replace in place, or add if the category wasn't tracked in the order yet.
        val order = if (parsed.contains(oldName)) {
            parsed.map { if (it == oldName) clean else it }
        } else {
            parsed + clean
        }.distinct()
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
