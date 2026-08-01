package com.myvideolibrary.app.ui.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.local.entity.DownloadEntity
import com.myvideolibrary.app.data.repository.DownloadRepository
import com.myvideolibrary.app.download.DownloadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val downloadManager: DownloadManager
) : ViewModel() {

    val downloads: StateFlow<List<DownloadEntity>> =
        downloadRepository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun pause(id: Long) = viewModelScope.launch { downloadManager.pause(id) }
    fun resume(id: Long) = viewModelScope.launch { downloadManager.resume(id) }
    fun retry(id: Long) = viewModelScope.launch { downloadManager.retry(id) }
    fun cancel(id: Long) = viewModelScope.launch { downloadManager.cancel(id) }
    fun remove(download: DownloadEntity) = viewModelScope.launch { downloadManager.remove(download) }
    fun clearCompleted() = viewModelScope.launch { downloadRepository.clearCompleted() }
}
