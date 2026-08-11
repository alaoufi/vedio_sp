package com.myvideolibrary.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.repository.SettingsRepository
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.util.CategoryOrder
import com.myvideolibrary.app.util.CategoryProtectionMode
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
    val hasPassword: Boolean,
    /** How the category behaves when protected; null when it isn't protected. */
    val mode: CategoryProtectionMode? = null
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
            val mode = CategorySecurity.modeOf(settings.categoryPasswords, name)
            CategoryItem(
                name = name,
                hidden = mode == CategoryProtectionMode.HIDDEN ||
                    CategorySecurity.isHidden(settings.hiddenCategories, name),
                hasPassword = mode != null,
                mode = mode
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

    /**
     * Applies an edit from the single edit dialog in one atomic settings write:
     * optional rename plus the section's protection ([mode] + password).
     *
     * @param mode the chosen protection mode, or null when protection is off
     * @param newPassword non-null/blank sets or changes the section password
     * @param clearPassword removes the section protection (overrides [mode]/[newPassword])
     */
    fun applyEdit(
        oldName: String,
        newName: String,
        mode: CategoryProtectionMode?,
        newPassword: String?,
        clearPassword: Boolean
    ) = viewModelScope.launch {
        val finalName = newName.trim().ifEmpty { oldName }
        if (finalName != oldName) videoRepository.renameCategory(oldName, finalName)

        val current = settingsRepository.getSettings()
        val parsed = CategoryOrder.parse(current.categoryOrder)
        val order = when {
            parsed.contains(oldName) -> parsed.map { if (it == oldName) finalName else it }
            !parsed.contains(finalName) -> parsed + finalName
            else -> parsed
        }.distinct()

        // Carry any existing metadata over to the (possibly new) name first.
        var hiddenStr = CategorySecurity.renameHidden(current.hiddenCategories, oldName, finalName)
        var pwStr = CategorySecurity.renamePassword(current.categoryPasswords, oldName, finalName)

        val protect = mode != null && !clearPassword
        pwStr = if (protect) {
            CategorySecurity.setProtection(pwStr, finalName, newPassword, mode!!)
        } else {
            CategorySecurity.removeProtection(pwStr, finalName)
        }
        // Only "hidden" mode drops the category from the library; keep the legacy
        // hiddenCategories list in sync so the library query keeps working.
        hiddenStr = CategorySecurity.toggleHidden(
            hiddenStr, finalName, protect && mode == CategoryProtectionMode.HIDDEN
        )

        settingsRepository.update {
            it.copy(
                categoryOrder = CategoryOrder.serialize(order),
                hiddenCategories = hiddenStr,
                categoryPasswords = pwStr
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

    /**
     * Verifies access to a protected category: its own password, OR the general
     * management password (the master key that opens everything).
     */
    suspend fun verifyPassword(name: String, password: String): Boolean {
        val settings = settingsRepository.getSettings()
        return CategorySecurity.verify(settings.categoryPasswords, name, password) ||
            CategorySecurity.verifyHash(settings.manageCategoriesPassword, password)
    }

    // ---- Management-screen lock ----

    /** Whether the whole management screen is password-protected. */
    suspend fun hasManagePassword(): Boolean =
        !settingsRepository.getSettings().manageCategoriesPassword.isNullOrEmpty()

    suspend fun verifyManagePassword(password: String): Boolean =
        CategorySecurity.verifyHash(
            settingsRepository.getSettings().manageCategoriesPassword, password
        )

    /** Sets (non-blank) or removes (blank/null) the management-screen password. */
    fun setManagePassword(password: String?) = viewModelScope.launch {
        val hash = password?.takeIf { it.isNotBlank() }?.let { CategorySecurity.hashPassword(it) }
        settingsRepository.update { it.copy(manageCategoriesPassword = hash) }
    }

    /** Persists a new display order after a drag-and-drop reorder. */
    fun saveOrder(order: List<String>) = viewModelScope.launch {
        settingsRepository.update { it.copy(categoryOrder = CategoryOrder.serialize(order)) }
    }
}
