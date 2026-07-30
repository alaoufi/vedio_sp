package com.myvideolibrary.app.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.myvideolibrary.app.data.local.entity.FolderEntity
import com.myvideolibrary.app.data.local.entity.SavedSearchEntity
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
import kotlinx.coroutines.flow.map
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
    /** Selected sources; empty means "all sources". */
    val sourceFilters: Set<SourceFilter> = emptySet(),
    val protectedMode: Boolean = false,
    /** Selected category labels; empty means "all categories". */
    val categoryFilters: Set<String> = emptySet(),
    val categories: List<String> = emptyList(),
    /** Selected media types ("video"/"audio"/"image"); empty means all types. */
    val mediaTypeFilters: Set<String> = emptySet(),
    /** Selected tags; empty means no tag filter. */
    val tagFilters: Set<String> = emptySet(),
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
    private val providerRegistry: com.myvideolibrary.app.provider.ProviderRegistry,
    private val playlistDao: com.myvideolibrary.app.data.local.dao.PlaylistDao,
    private val savedSearchDao: com.myvideolibrary.app.data.local.dao.SavedSearchDao
) : ViewModel() {

    /** Recently-played videos still in progress, for the "Continue watching" row. */
    val continueWatching: StateFlow<List<VideoEntity>> =
        videoRepository.observeRecentlyPlayed(20)
            .map { list ->
                list.filter { v ->
                    v.mediaType != "image" &&
                        v.lastPlayedPosition > 3_000 &&
                        (v.duration <= 0 || v.lastPlayedPosition < v.duration * 95 / 100)
                }.take(10)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** All playlists (with counts), for the "add to playlist" picker. */
    val playlists: StateFlow<List<com.myvideolibrary.app.data.local.entity.PlaylistWithCount>> =
        playlistDao.observePlaylists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Adds a video to an existing playlist at the end. */
    fun addToPlaylist(playlistId: Long, videoId: Long) = viewModelScope.launch {
        val pos = playlistDao.nextPosition(playlistId)
        playlistDao.addVideo(
            com.myvideolibrary.app.data.local.entity.PlaylistVideoEntity(
                playlistId = playlistId, videoId = videoId, position = pos
            )
        )
    }

    /** Creates a new playlist and drops the video into it. */
    fun createPlaylistWith(name: String, videoId: Long) = viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launch
        val id = playlistDao.insert(
            com.myvideolibrary.app.data.local.entity.PlaylistEntity(
                name = trimmed, createdDate = System.currentTimeMillis()
            )
        )
        playlistDao.addVideo(
            com.myvideolibrary.app.data.local.entity.PlaylistVideoEntity(
                playlistId = id, videoId = videoId, position = 0
            )
        )
    }

    /** Active (waiting/downloading/paused) downloads, for the dashboard banner. */
    val activeDownloads: StateFlow<List<com.myvideolibrary.app.data.local.entity.DownloadEntity>> =
        downloadRepository.observeActive()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val queryState = MutableStateFlow(LibraryQuery())

    private val _selection = MutableStateFlow(SelectionState())
    private val _search = MutableStateFlow("")
    private val _folderFilter = MutableStateFlow<Long?>(null)
    private val _favoritesOnly = MutableStateFlow(false)
    private val _sourceFilters = MutableStateFlow<Set<SourceFilter>>(emptySet())
    private val _protectedMode = MutableStateFlow(false)
    private val _categoryFilters = MutableStateFlow<Set<String>>(emptySet())
    private val _mediaTypeFilters = MutableStateFlow<Set<String>>(emptySet())
    private val _tagFilters = MutableStateFlow<Set<String>>(emptySet())

    /** Merged source + protected + category + type + tags, one flow for combine arity. */
    private val extraFilters = combine(
        _sourceFilters, _protectedMode, _categoryFilters, _mediaTypeFilters, _tagFilters
    ) { sources, protectedMode, categories, mediaTypes, tags ->
        ExtraFilters(sources, protectedMode, categories, mediaTypes, tags)
    }

    /** Distinct tags currently in use, for the tag filter picker. */
    val allTags: StateFlow<List<String>> =
        videoRepository.observeTags()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Named filter snapshots the user can re-apply in one tap. */
    val savedSearches: StateFlow<List<SavedSearchEntity>> =
        savedSearchDao.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
        // Hidden and password-protected categories are dropped from the browseable
        // list so their contents never surface in the general library view.
        val excluded = excludedCategories(settings)
        val visible = ordered.filterNot { name ->
            excluded.any { it.equals(name.trim(), ignoreCase = true) }
        }
        LibraryMeta(settings, folders, count, size, visible)
    }

    /** Names of hidden ∪ password-protected categories, from the settings row. */
    private fun excludedCategories(settings: SettingsEntity): Set<String> =
        com.myvideolibrary.app.util.CategorySecurity.parseHidden(settings.hiddenCategories) +
            com.myvideolibrary.app.util.CategorySecurity.protectedNames(settings.categoryPasswords)

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
            sourceFilters = f.extra.sourceFilters,
            protectedMode = f.extra.protectedMode,
            categoryFilters = f.extra.categories,
            categories = m.categories,
            mediaTypeFilters = f.extra.mediaTypes,
            tagFilters = f.extra.tags,
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
                    categories = extra.categories,
                    excludedCategories = excludedCategories(settings),
                    sourceFilters = extra.sourceFilters,
                    protectedOnly = extra.protectedMode,
                    mediaTypes = extra.mediaTypes,
                    tags = extra.tags,
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
                val current = _categoryFilters.value
                val stillPresent = current.filter { it in known }.toSet()
                if (stillPresent.size != current.size) _categoryFilters.value = stillPresent
            }
        }
    }

    // ---- Filters ----

    fun setSearch(text: String) { _search.value = text }

    fun setFolderFilter(folderId: Long?) { _folderFilter.value = folderId }

    fun setFavoritesOnly(only: Boolean) { _favoritesOnly.value = only }

    fun setSourceFilters(filters: Set<SourceFilter>) { _sourceFilters.value = filters }

    fun setProtectedMode(on: Boolean) { _protectedMode.value = on }

    fun setCategoryFilters(categories: Set<String>) { _categoryFilters.value = categories }

    fun setMediaTypeFilters(types: Set<String>) { _mediaTypeFilters.value = types }

    fun setTagFilters(tags: Set<String>) { _tagFilters.value = tags }

    /** Assigns a raw (comma-separated) tag string to a single video. */
    fun setTags(id: Long, rawTags: String?) = viewModelScope.launch {
        videoRepository.setTags(id, rawTags)
    }

    /** Saves the current filter + sort state under [name] (replacing a same-named one). */
    fun saveCurrentSearch(name: String) = viewModelScope.launch {
        val clean = name.trim()
        if (clean.isEmpty()) return@launch
        savedSearchDao.deleteByName(clean)
        savedSearchDao.insert(
            SavedSearchEntity(
                name = clean,
                createdDate = System.currentTimeMillis(),
                search = _search.value.trim().ifEmpty { null },
                favoritesOnly = _favoritesOnly.value,
                protectedMode = _protectedMode.value,
                sources = SavedSearchEntity.join(_sourceFilters.value.map { it.id }),
                categories = SavedSearchEntity.join(_categoryFilters.value),
                mediaTypes = SavedSearchEntity.join(_mediaTypeFilters.value),
                tags = SavedSearchEntity.join(_tagFilters.value),
                sortOrder = uiState.value.sortOrder.id
            )
        )
    }

    /** Re-applies every filter (and sort) captured by a saved search. */
    fun applySavedSearch(entity: SavedSearchEntity) {
        _search.value = entity.search.orEmpty()
        _favoritesOnly.value = entity.favoritesOnly
        _protectedMode.value = entity.protectedMode
        _sourceFilters.value = SavedSearchEntity.split(entity.sources)
            .map { SourceFilter.fromId(it) }.toSet()
        _categoryFilters.value = SavedSearchEntity.split(entity.categories).toSet()
        _mediaTypeFilters.value = SavedSearchEntity.split(entity.mediaTypes).toSet()
        _tagFilters.value = SavedSearchEntity.split(entity.tags).toSet()
        entity.sortOrder?.let { setSortOrder(SortOrder.fromId(it)) }
    }

    fun deleteSavedSearch(id: Long) = viewModelScope.launch {
        savedSearchDao.deleteById(id)
    }

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

    fun updateInfo(id: Long, title: String, description: String?) = viewModelScope.launch {
        videoRepository.updateInfo(id, title, description)
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
            downloadManager.enqueueResolved(r)
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
        val sourceFilters: Set<SourceFilter>,
        val protectedMode: Boolean,
        val categories: Set<String>,
        val mediaTypes: Set<String>,
        val tags: Set<String>
    )

    private data class LibraryFilters(
        val search: String,
        val folderId: Long?,
        val favoritesOnly: Boolean,
        val extra: ExtraFilters
    )
}
