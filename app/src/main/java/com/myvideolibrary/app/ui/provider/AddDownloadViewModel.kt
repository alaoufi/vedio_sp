package com.myvideolibrary.app.ui.provider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.download.DownloadManager
import com.myvideolibrary.app.provider.ProviderRegistry
import com.myvideolibrary.app.provider.model.ProviderErrorType
import com.myvideolibrary.app.provider.model.ProviderException
import com.myvideolibrary.app.provider.model.ResolvedVideo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddDownloadUiState(
    val resolving: Boolean = false,
    val resolved: ResolvedVideo? = null,
    val errorType: ProviderErrorType? = null,
    val errorMessage: String? = null,
    val enqueued: Boolean = false
)

@HiltViewModel
class AddDownloadViewModel @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _state = MutableStateFlow(AddDownloadUiState())
    val state: StateFlow<AddDownloadUiState> = _state.asStateFlow()

    fun resolve(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return

        val provider = providerRegistry.providerForUrl(trimmed)
        if (provider == null) {
            _state.value = AddDownloadUiState(
                errorType = ProviderErrorType.UNSUPPORTED,
                errorMessage = "Unsupported link"
            )
            return
        }

        _state.value = AddDownloadUiState(resolving = true)
        viewModelScope.launch {
            try {
                val resolved = provider.resolve(trimmed)
                _state.value = AddDownloadUiState(resolved = resolved)
            } catch (e: ProviderException) {
                _state.value = AddDownloadUiState(
                    errorType = e.type,
                    errorMessage = e.message
                )
            } catch (e: Throwable) {
                // Catch Throwable (not just Exception) so an Error from an extractor
                // shows a message instead of crashing the app. Surface the real cause.
                _state.value = AddDownloadUiState(
                    errorType = ProviderErrorType.UNKNOWN,
                    errorMessage = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                )
            }
        }
    }

    fun download() {
        val resolved = _state.value.resolved ?: return
        viewModelScope.launch {
            try {
                downloadManager.enqueue(
                    title = resolved.title,
                    source = resolved.source.id,
                    sourceUrl = resolved.sourceUrl,
                    directUrl = resolved.directUrl,
                    audioUrl = resolved.audioUrl,
                    thumbnailUrl = resolved.thumbnailUrl
                )
                _state.value = _state.value.copy(enqueued = true)
            } catch (e: Throwable) {
                _state.value = _state.value.copy(
                    errorType = ProviderErrorType.UNKNOWN,
                    errorMessage = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
                )
            }
        }
    }

    fun reset() {
        _state.value = AddDownloadUiState()
    }
}
