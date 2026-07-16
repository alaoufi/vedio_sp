package com.myvideolibrary.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.myvideolibrary.app.data.local.entity.FolderEntity
import com.myvideolibrary.app.data.local.entity.SettingsEntity
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.LibraryViewMode
import com.myvideolibrary.app.data.model.SortOrder
import com.myvideolibrary.app.data.model.SourceFilter
import com.myvideolibrary.app.data.repository.FolderRepository
import com.myvideolibrary.app.data.repository.LibraryQuery
import com.myvideolibrary.app.data.repository.SettingsRepository
import com.myvideolibrary.app.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Snapshot of everything the library screen renders. */
data class LibraryUiState(
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val sortOrder: SortOrder = SortOrder.DATE_ADDED_DESC,
    val search: String = "",
    val folderId: Long? = null,
    val favoritesOnly: Boolean = false,
    val sourceFilter: SourceFilter = SourceFilter.ALL,
    val protectedMode: Boolean = false,
    /** Selected category label, or null for "all categories". */
    val categoryFilter: String? = null,
    val categories: List<String> = emptyList(),
    val videoCount: Int = 0,
    val totalSize: Long = 0,
    val folders: List<FolderEntity> = emptyList(),
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val videoRepository: VideoRepository,
    private val folderRepository: FolderRepository,
    private val settingsRepository: SettingsRepository,
    private val downloadRepository: com.myvideolibrary.app.data.repository.DownloadRepository,
    private val downloadManager: com.myvideolibrary.app.download.DownloadManager,
    private val providerRegistry: com.myvideolibrary.app.provider.ProviderRegistry
) : ViewModel() {

    /** Active (waiting/downloading/paused) downloads, for the dashboard banner. */
    val activeDownloads: StateFlow<List<com.myvideolibrary.app.data.local.entity.DownloadEntity>> =
        downloadRepository.observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val queryState = MutableStateFlow(LibraryQuery())

    private val _selection = MutableStateFlow(SelectionState())
    private val _search = MutableStateFlow("")
    private val _folderFilter = MutableStateFlow<Long?>(null)
    private val _favoritesOnly = MutableStateFlow(false)
    private val _sourceFilter = MutableStateFlow(SourceFilter.ALL)
    private val _protectedMode = MutableStateFlow(false)
    private val _categoryFilter = MutableStateFlow<String?>(null)

    /** Merged source + protected + category, kept as one flow for combine arity. */
    private val extraFilters = combine(
        _sourceFilter, _protectedMode, _categoryFilter
    ) { source, protectedMode, category -> ExtraFilters(source, protectedMode, category) }

    /** Paged videos, recomputed whenever the query changes. */
    val videos: Flow<PagingData<VideoEntity>> =
        queryState.flatMapLatest { videoRepository.pagedVideos(it) }
            .cachedIn(viewModelScope)

    // Persisted preferences + aggregate stats.
    private val meta = combine(
        settingsRepository.observeSettings(),
        folderRepository.observeFolders(),
        videoRepository.observeCount(),
        videoRepository.observeTotalSize(),
        videoRepository.observeCategories()
    ) { settings, folders, count, size, categories ->
        val ordered = com.myvideolibrary.app.util.CategoryOrder.apply(categories, settings.categoryOrder)
        LibraryMeta(settings, folders, count, size, ordered)
    }

    // Active filter selections.
    private val filters = combine(
        _search, _folderFilter, _favoritesOnly, extraFilters
    ) { search, folderId, favoritesOnly, extra ->
        LibraryFilters(search, folderId, favoritesOnly, extra)
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        meta, filters, _selection
    ) { m, f, selection ->
        LibraryUiState(
            viewMode = LibraryViewMode.fromId(m.settings.viewMode),
            sortOrder = SortOrder.fromId(m.settings.sortOrder),
            search = f.search,
            folderId = f.folderId,
            favoritesOnly = f.favoritesOnly,
            sourceFilter = f.extra.sourceFilter,
            protectedMode = f.extra.protectedMode,
            categoryFilter = f.extra.category,
            categories = m.categories,
            videoCount = m.count,
            totalSize = m.size,
            folders = m.folders,
            selectionMode = selection.active,
            selectedIds = selection.ids
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    init {
        // Rebuild the paging query whenever any filter or the persisted sort changes.
        viewModelScope.launch {
            combine(
                settingsRepository.observeSettings(),
                _search,
                _folderFilter,
                _favoritesOnly,
                extraFilters
            ) { settings, search, folderId, favoritesOnly, extra ->
                LibraryQuery(
                    search = search,
                    folderId = folderId,
                    favoritesOnly = favoritesOnly,
                    category = extra.category,
                    sourceFilter = extra.sourceFilter,
                    protectedOnly = extra.protectedMode,
                    sortOrder = SortOrder.fromId(settings.sortOrder)
                )
            }.collect { queryState.value = it }
        }
        // Clear a category filter only when the category no longer exists at all
        // (deleted). Added-but-empty categories remain selectable.
        viewModelScope.launch {
            combine(
                videoRepository.observeCategories(),
                settingsRepository.observeSettings()
            ) { present, settings ->
                com.myvideolibrary.app.util.CategoryOrder.apply(present, settings.categoryOrder)
            }.collect { known ->
                val current = _categoryFilter.value
                if (current != null && current !in known) _categoryFilter.value = null
            }
        }
    }

    // ---- Filters ----

    fun setSearch(text: String) { _search.value = text }

    fun setFolderFilter(folderId: Long?) { _folderFilter.value = folderId }

    fun setFavoritesOnly(only: Boolean) { _favoritesOnly.value = only }

    fun setSourceFilter(filter: SourceFilter) { _sourceFilter.value = filter }

    fun setProtectedMode(on: Boolean) { _protectedMode.value = on }

    fun setCategoryFilter(category: String?) { _categoryFilter.value = category }

    fun setSortOrder(order: SortOrder) = viewModelScope.launch {
        settingsRepository.update { it.copy(sortOrder = order.id) }
    }

    fun toggleViewMode() = viewModelScope.launch {
        settingsRepository.update {
            val next = if (LibraryViewMode.fromId(it.viewMode) == LibraryViewMode.GRID) {
                LibraryViewMode.LIST
            } else {
                LibraryViewMode.GRID
            }
            it.copy(viewMode = next.id)
        }
    }

    // ---- Selection ----

    fun enterSelection(id: Long) {
        _selection.update { SelectionState(active = true, ids = setOf(id)) }
    }

    fun toggleSelected(id: Long) {
        _selection.update { current ->
            val ids = if (id in current.ids) current.ids - id else current.ids + id
            if (ids.isEmpty()) SelectionState() else current.copy(active = true, ids = ids)
        }
    }

    fun clearSelection() { _selection.value = SelectionState() }

    // ---- Bulk actions ----

    fun deleteSelected(alsoFiles: Boolean) = viewModelScope.launch {
        val ids = _selection.value.ids.toList()
        if (ids.isNotEmpty()) videoRepository.deleteVideos(ids, alsoFiles)
        clearSelection()
    }

    fun moveSelectedToFolder(folderId: Long?) = viewModelScope.launch {
        val ids = _selection.value.ids.toList()
        if (ids.isNotEmpty()) videoRepository.moveToFolder(ids, folderId)
        clearSelection()
    }

    fun favoriteSelected(favorite: Boolean) = viewModelScope.launch {
        _selection.value.ids.forEach { videoRepository.setFavorite(it, favorite) }
        clearSelection()
    }

    // ---- Single-item actions ----

    fun toggleFavorite(video: VideoEntity) = viewModelScope.launch {
        videoRepository.setFavorite(video.id, !video.isFavorite)
    }

    fun toggleLock(video: VideoEntity) = viewModelScope.launch {
        videoRepository.setLocked(video.id, !video.isLocked)
    }

    fun deleteVideo(id: Long, alsoFile: Boolean) = viewModelScope.launch {
        videoRepository.deleteVideos(listOf(id), alsoFile)
    }

    fun rename(id: Long, title: String) = viewModelScope.launch {
        videoRepository.rename(id, title)
    }

    fun setCategory(id: Long, category: String?) = viewModelScope.launch {
        videoRepository.setCategory(listOf(id), category)
    }

    /** Downloads a saved link on demand, resolving its best quality first. */
    fun downloadLink(video: VideoEntity) = viewModelScope.launch {
        val url = video.sourceUrl ?: return@launch
        val provider = providerRegistry.providerForUrl(url) ?: return@launch
        runCatching {
            val r = provider.resolve(url)
            downloadManager.enqueue(
                title = r.title,
                source = r.source.id,
                sourceUrl = r.sourceUrl,
                directUrl = r.directUrl,
                audioUrl = r.audioUrl,
                thumbnailUrl = r.thumbnailUrl
            )
        }
    }

    fun setCategorySelected(category: String?) = viewModelScope.launch {
        val ids = _selection.value.ids.toList()
        if (ids.isNotEmpty()) videoRepository.setCategory(ids, category)
        clearSelection()
    }

    fun createFolder(name: String) = viewModelScope.launch {
        folderRepository.createFolder(name, null)
    }

    private data class SelectionState(
        val active: Boolean = false,
        val ids: Set<Long> = emptySet()
    )

    private data class LibraryMeta(
        val settings: SettingsEntity,
        val folders: List<FolderEntity>,
        val count: Int,
        val size: Long,
        val categories: List<String>
    )

    private data class ExtraFilters(
        val sourceFilter: SourceFilter,
        val protectedMode: Boolean,
        val category: String?
    )

    private data class LibraryFilters(
        val search: String,
        val folderId: Long?,
        val favoritesOnly: Boolean,
        val extra: ExtraFilters
    )
}
