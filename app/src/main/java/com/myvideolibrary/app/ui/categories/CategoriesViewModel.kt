package com.myvideolibrary.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.repository.SettingsRepository
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.util.CategoryOrder
import com.myvideolibrary.app.util.CategorySecurity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A category plus its visibility and password state, for the management screen. */
data class CategoryItem(
    val name: String,
    val hidden: Boolean,
    val hasPassword: Boolean
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    /** Categories in the user's saved display order, with visibility/password state. */
    val categories: StateFlow<List<CategoryItem>> = combine(
        videoRepository.observeCategories(),
        settingsRepository.observeSettings()
    ) { present, settings ->
        CategoryOrder.apply(present, settings.categoryOrder).map { name ->
            CategoryItem(
                name = name,
                hidden = CategorySecurity.isHidden(settings.hiddenCategories, name),
                hasPassword = CategorySecurity.hasPassword(settings.categoryPasswords, name)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Creates a new (empty) category by persisting its name in the order list. */
    fun add(name: String) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isEmpty()) return@launch
        if (categories.value.any { it.name.equals(clean, ignoreCase = true) }) return@launch
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
        settingsRepository.update {
            it.copy(
                categoryOrder = CategoryOrder.serialize(order),
                hiddenCategories = CategorySecurity.renameHidden(it.hiddenCategories, oldName, clean),
                categoryPasswords = CategorySecurity.renamePassword(it.categoryPasswords, oldName, clean)
            )
        }
    }

    fun delete(name: String) = viewModelScope.launch {
        videoRepository.deleteCategory(name)
        val current = settingsRepository.getSettings()
        val order = CategoryOrder.parse(current.categoryOrder).filter { it != name }
        val (hidden, passwords) =
            CategorySecurity.removeAll(current.hiddenCategories, current.categoryPasswords, name)
        settingsRepository.update {
            it.copy(
                categoryOrder = CategoryOrder.serialize(order),
                hiddenCategories = hidden,
                categoryPasswords = passwords
            )
        }
    }

    /** Show/hide a category: hidden categories are kept out of the library view. */
    fun setHidden(name: String, hidden: Boolean) = viewModelScope.launch {
        settingsRepository.update {
            it.copy(hiddenCategories = CategorySecurity.toggleHidden(it.hiddenCategories, name, hidden))
        }
    }

    /** Sets ([password] != null) or clears a category's password protection. */
    fun setPassword(name: String, password: String?) = viewModelScope.launch {
        settingsRepository.update {
            it.copy(categoryPasswords = CategorySecurity.setPassword(it.categoryPasswords, name, password))
        }
    }

    /** Verifies a password before opening a protected category's contents. */
    suspend fun verifyPassword(name: String, password: String): Boolean {
        val settings = settingsRepository.getSettings()
        return CategorySecurity.verify(settings.categoryPasswords, name, password)
    }

    /** Persists a new display order after a drag-and-drop reorder. */
    fun saveOrder(order: List<String>) = viewModelScope.launch {
        settingsRepository.update { it.copy(categoryOrder = CategoryOrder.serialize(order)) }
    }
}
