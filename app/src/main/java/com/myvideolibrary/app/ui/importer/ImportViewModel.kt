package com.myvideolibrary.app.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.util.MediaStoreScanner
import com.myvideolibrary.app.util.ScannedVideo
import com.myvideolibrary.app.util.ThumbnailGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ImportUiState(
    val loading: Boolean = false,
    val importing: Boolean = false,
    val items: List<ScannedVideo> = emptyList(),
    val selected: Set<String> = emptySet(),
    val importedCount: Int = 0,
    val done: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val scanner: MediaStoreScanner,
    private val thumbnailGenerator: ThumbnailGenerator,
    private val videoRepository: VideoRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun scan() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val existing = HashSet<String>()
                val scanned = scanner.scanDeviceVideos().filter { candidate ->
                    // Hide items already in the library.
                    val already = videoRepository.existsByPath(candidate.contentUri)
                    if (already) existing.add(candidate.contentUri)
                    !already
                }
                _state.update { it.copy(loading = false, items = scanned) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Scan failed") }
            }
        }
    }

    fun toggle(uri: String) {
        _state.update { current ->
            val selected = if (uri in current.selected) {
                current.selected - uri
            } else {
                current.selected + uri
            }
            current.copy(selected = selected)
        }
    }

    fun selectAll() {
        _state.update { it.copy(selected = it.items.map { v -> v.contentUri }.toSet()) }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    fun importSelected() {
        val current = _state.value
        val chosen = current.items.filter { it.contentUri in current.selected }
        if (chosen.isEmpty()) return

        _state.update { it.copy(importing = true) }
        viewModelScope.launch {
            var count = 0
            for (item in chosen) {
                val ok = runCatching { importOne(item) }.getOrDefault(false)
                if (ok) count++
            }
            _state.update {
                it.copy(importing = false, importedCount = count, done = true)
            }
        }
    }

    private suspend fun importOne(item: ScannedVideo): Boolean = withContext(Dispatchers.IO) {
        if (videoRepository.existsByPath(item.contentUri)) return@withContext false

        val thumb = thumbnailGenerator.generateThumbnail(item.contentUri)
        val video = VideoEntity(
            title = item.displayName.substringBeforeLast('.'),
            thumbnailPath = thumb,
            localPath = item.contentUri,
            source = VideoSource.LOCAL_IMPORT.id,
            category = item.relativeBucket,
            duration = item.durationMs,
            fileSize = item.sizeBytes,
            quality = qualityFor(item.height),
            width = item.width,
            height = item.height,
            createdDate = System.currentTimeMillis(),
            contentHash = "${item.sizeBytes}_${item.durationMs}"
        )
        videoRepository.addVideo(video)
        true
    }

    private fun qualityFor(height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1080 -> "1080p"
        height >= 720 -> "720p"
        height >= 480 -> "480p"
        height > 0 -> "${height}p"
        else -> "—"
    }
}
