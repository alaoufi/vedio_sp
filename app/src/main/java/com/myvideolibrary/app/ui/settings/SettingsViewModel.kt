package com.myvideolibrary.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.myvideolibrary.app.data.backup.BackupManager
import com.myvideolibrary.app.data.model.AppTheme
import com.myvideolibrary.app.data.repository.SettingsRepository
import com.myvideolibrary.app.security.SecurityManager
import com.myvideolibrary.app.util.StorageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val theme: AppTheme = AppTheme.SYSTEM,
    val wifiOnly: Boolean = true,
    val maxConcurrent: Int = 2,
    val appLockEnabled: Boolean = false,
    val hasPin: Boolean = false,
    val biometricEnabled: Boolean = false,
    val preventScreenshots: Boolean = true,
    val hideInRecents: Boolean = true,
    val storageUsed: Long = 0,
    /** User-chosen SAF tree URI for a copy of finished downloads; null = app storage only. */
    val saveLocation: String? = null,
    val endOfClipAction: com.myvideolibrary.app.data.model.EndOfClipAction =
        com.myvideolibrary.app.data.model.EndOfClipAction.NEXT,
    val message: String? = null,
    val exportedFile: java.io.File? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val settingsRepository: SettingsRepository,
    private val securityManager: SecurityManager,
    private val themeManager: ThemeManager,
    private val storageManager: StorageManager,
    private val backupManager: BackupManager
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val settings = settingsRepository.getSettings()
            val used = withContext(Dispatchers.IO) { storageManager.usedBytes() }
            _state.value = SettingsUiState(
                theme = themeManager.theme,
                wifiOnly = settings.wifiOnlyDownloads,
                maxConcurrent = settings.maxConcurrentDownloads,
                appLockEnabled = securityManager.appLockEnabled,
                hasPin = securityManager.hasPin,
                biometricEnabled = securityManager.biometricEnabled,
                preventScreenshots = securityManager.preventScreenshots,
                hideInRecents = securityManager.hideInRecents,
                storageUsed = used,
                saveLocation = settings.storagePath,
                endOfClipAction = com.myvideolibrary.app.data.model.EndOfClipAction
                    .fromId(settings.endOfClipAction)
            )
        }
    }

    fun setEndOfClipAction(action: com.myvideolibrary.app.data.model.EndOfClipAction) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(endOfClipAction = action.id) }
        }
        _state.value = _state.value.copy(endOfClipAction = action)
    }

    fun setSaveLocation(treeUri: String?) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(storagePath = treeUri) }
            _state.value = _state.value.copy(saveLocation = treeUri)
        }
    }

    fun setTheme(theme: AppTheme) {
        themeManager.theme = theme
        viewModelScope.launch { settingsRepository.update { it.copy(theme = theme.id) } }
        _state.value = _state.value.copy(theme = theme)
    }

    fun setWifiOnly(value: Boolean) {
        viewModelScope.launch { settingsRepository.update { it.copy(wifiOnlyDownloads = value) } }
        _state.value = _state.value.copy(wifiOnly = value)
    }

    fun setMaxConcurrent(value: Int) {
        val clamped = value.coerceIn(1, 5)
        viewModelScope.launch {
            settingsRepository.update { it.copy(maxConcurrentDownloads = clamped) }
        }
        _state.value = _state.value.copy(maxConcurrent = clamped)
    }

    // ---- Security ----

    fun setPin(pin: String) {
        securityManager.setPin(pin)
        securityManager.appLockEnabled = true
        refresh()
    }

    fun disableLock() {
        securityManager.clearPin()
        refresh()
    }

    fun setBiometric(enabled: Boolean) {
        securityManager.biometricEnabled = enabled
        _state.value = _state.value.copy(biometricEnabled = enabled)
    }

    fun setPreventScreenshots(enabled: Boolean) {
        securityManager.preventScreenshots = enabled
        _state.value = _state.value.copy(preventScreenshots = enabled)
    }

    fun setHideInRecents(enabled: Boolean) {
        securityManager.hideInRecents = enabled
        _state.value = _state.value.copy(hideInRecents = enabled)
    }

    // ---- Maintenance ----

    fun clearCache() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Glide.get(context).clearDiskCache() }
            Glide.get(context).clearMemory()
            postMessage(context.getString(com.myvideolibrary.app.R.string.cache_cleared))
        }
    }

    fun export() {
        viewModelScope.launch {
            try {
                val file = backupManager.export()
                _state.value = _state.value.copy(
                    message = context.getString(
                        com.myvideolibrary.app.R.string.backup_exported, file.name
                    ),
                    exportedFile = file
                )
            } catch (e: Exception) {
                postMessage(e.message ?: "Backup failed")
            }
        }
    }

    fun restore(uri: Uri) {
        viewModelScope.launch {
            try {
                val count = backupManager.restore(uri)
                postMessage(context.getString(
                    com.myvideolibrary.app.R.string.backup_restored, count
                ))
                refresh()
            } catch (e: Exception) {
                postMessage(context.getString(com.myvideolibrary.app.R.string.backup_restore_failed))
            }
        }
    }

    fun consumeMessage() { _state.value = _state.value.copy(message = null) }

    fun consumeExportedFile() { _state.value = _state.value.copy(exportedFile = null) }

    private fun postMessage(text: String) {
        _state.value = _state.value.copy(message = text)
    }
}
