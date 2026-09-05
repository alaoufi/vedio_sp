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
    val sortOrder: SortOrder = SortOrder.LAST_PLAYED_DESC,
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
    /** When true, the grid is showing in-progress "Continue watching" clips. */
    val continueOnly: Boolean = false,
    /** Selected tags; empty means no tag filter. */
    val tagFilters: Set<String> = emptySet(),
    val videoCount: Int = 0,
    val totalSize: Long = 0,
    val folders: List<FolderEntity> = emptyList(),
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    /** Category names (normalised) whose covers are blurred (OBSCURED mode). */
    val obscuredCategories: Set<String> = emptySet(),
    /** Category names (normalised) that require a password to open (VISIBLE ∪ OBSCURED). */
    val lockedCategories: Set<String> = emptySet(),
    /** Raw "name\thash\tmode" password store, for verifying a category unlock. */
    val categoryPasswordsRaw: String? = null,
    /** SHA-256 hash of the shared password that reveals per-clip obscured items. */
    val obscurePasswordHash: String? = null
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
    private val savedSearchDao: com.myvideolibrary.app.data.local.dao.SavedSearchDao,
    @dagger.hilt.android.qualifiers.ApplicationContext
    private val appContext: android.content.Context,
    private val storageManager: com.myvideolibrary.app.util.StorageManager,
    private val thumbnailGenerator: com.myvideolibrary.app.util.ThumbnailGenerator
) : ViewModel() {

    /** One-shot: number of device items imported (null when consumed). */
    private val _importResult = MutableStateFlow<Int?>(null)
    val importResult: StateFlow<Int?> = _importResult.asStateFlow()
    fun consumeImportResult() { _importResult.value = null }

    /**
     * Imports images/videos the user picked from the device. Each file is COPIED into
     * app storage (the picker's content URIs aren't durable), so the clip plays
     * offline and is included in backups. New rows appear in the library automatically.
     */
    fun importDeviceMedia(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var count = 0
            for (uri in uris) {
                if (runCatching { importOneUri(uri) }.getOrDefault(false)) count++
            }
            _importResult.value = count
        }
    }

    private suspend fun importOneUri(uri: android.net.Uri): Boolean {
        val resolver = appContext.contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val type = when {
            mime.startsWith("image/") -> com.myvideolibrary.app.data.model.MediaType.IMAGE
            mime.startsWith("audio/") -> com.myvideolibrary.app.data.model.MediaType.AUDIO
            else -> com.myvideolibrary.app.data.model.MediaType.VIDEO
        }
        val ext = when (type) {
            com.myvideolibrary.app.data.model.MediaType.IMAGE ->
                mime.substringAfter('/', "jpg").substringBefore(';')
                    .ifBlank { "jpg" }.let { if (it == "jpeg") "jpg" else it }
            com.myvideolibrary.app.data.model.MediaType.AUDIO -> "m4a"
            else -> "mp4"
        }
        val dest = storageManager.newVideoFile(ext)
        val copied = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
        if (!copied || dest.length() == 0L) { runCatching { dest.delete() }; return false }

        val meta = if (type == com.myvideolibrary.app.data.model.MediaType.IMAGE) null
        else runCatching { thumbnailGenerator.readMetadata(dest.absolutePath) }.getOrNull()
        val thumb = when (type) {
            com.myvideolibrary.app.data.model.MediaType.IMAGE -> dest.absolutePath
            com.myvideolibrary.app.data.model.MediaType.AUDIO -> null
            else -> runCatching { thumbnailGenerator.generateThumbnail(dest.absolutePath) }.getOrNull()
        }
        val title = (queryDisplayName(uri) ?: dest.name).substringBeforeLast('.').ifBlank { "media" }
        videoRepository.addVideo(
            VideoEntity(
                title = title,
                thumbnailPath = thumb,
                localPath = dest.absolutePath,
                source = com.myvideolibrary.app.data.model.VideoSource.LOCAL_IMPORT.id,
                mediaType = type.id,
                duration = meta?.durationMs ?: 0,
                fileSize = dest.length(),
                width = meta?.width ?: 0,
                height = meta?.height ?: 0,
                quality = meta?.qualityLabel,
                createdDate = System.currentTimeMillis(),
                contentHash = "${dest.length()}_${meta?.durationMs ?: 0}"
            )
        )
        return true
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        appContext.contentResolver.query(
            uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

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
    private val _continueOnly = MutableStateFlow(false)

    /** Merged source + protected + category + type + tags (+ continue), one flow. */
    private val extraFilters = combine(
        combine(
            _sourceFilters, _protectedMode, _categoryFilters, _mediaTypeFilters, _tagFilters
        ) { sources, protectedMode, categories, mediaTypes, tags ->
            ExtraFilters(sources, protectedMode, categories, mediaTypes, tags)
        },
        _continueOnly
    ) { extra, continueOnly -> extra.copy(continueOnly = continueOnly) }

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
    // Only *hidden* categories are dropped from the library. Password-protected
    // categories now stay visible but with obscured covers (see protectedCategories),
    // so they are no longer excluded here.
    private fun excludedCategories(settings: SettingsEntity): Set<String> =
        com.myvideolibrary.app.util.CategorySecurity.parseHidden(settings.hiddenCategories)

    /** Every protected category name, normalised (trimmed, lower-cased). */
    private fun allProtectedCategories(settings: SettingsEntity): Set<String> =
        com.myvideolibrary.app.util.CategorySecurity.protectedNames(settings.categoryPasswords)
            .map { it.trim().lowercase() }.toSet()

    /** Categories whose covers are blurred (OBSCURED mode), normalised. */
    private fun obscuredCategories(settings: SettingsEntity): Set<String> =
        com.myvideolibrary.app.util.CategorySecurity.namesWithMode(
            settings.categoryPasswords, com.myvideolibrary.app.util.CategoryProtectionMode.OBSCURED
        ).map { it.trim().lowercase() }.toSet()

    /** Categories shown in the library that need a password to open (VISIBLE ∪ OBSCURED). */
    private fun lockedCategories(settings: SettingsEntity): Set<String> {
        val pw = settings.categoryPasswords
        val visible = com.myvideolibrary.app.util.CategorySecurity.namesWithMode(
            pw, com.myvideolibrary.app.util.CategoryProtectionMode.VISIBLE
        )
        val obscured = com.myvideolibrary.app.util.CategorySecurity.namesWithMode(
            pw, com.myvideolibrary.app.util.CategoryProtectionMode.OBSCURED
        )
        return (visible + obscured).map { it.trim().lowercase() }.toSet()
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
            sourceFilters = f.extra.sourceFilters,
            protectedMode = f.extra.protectedMode,
            categoryFilters = f.extra.categories,
            categories = m.categories,
            mediaTypeFilters = f.extra.mediaTypes,
            continueOnly = f.extra.continueOnly,
            tagFilters = f.extra.tags,
            videoCount = m.count,
            totalSize = m.size,
            folders = m.folders,
            selectionMode = selection.active,
            selectedIds = selection.ids,
            obscuredCategories = obscuredCategories(m.settings),
            lockedCategories = lockedCategories(m.settings),
            categoryPasswordsRaw = m.settings.categoryPasswords,
            obscurePasswordHash = m.settings.privateVaultPassword
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
                    continueOnly = extra.continueOnly,
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

    fun setMediaTypeFilters(types: Set<String>) {
        _mediaTypeFilters.value = types
        if (types.isNotEmpty()) _continueOnly.value = false
    }

    /** Continue-watching quick view (mutually exclusive with a media-type filter). */
    fun setContinueOnly(on: Boolean) {
        _continueOnly.value = on
        if (on) _mediaTypeFilters.value = emptySet()
    }

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

    /** Marks a single clip obscured (blurred cover, gated) or clears it. */
    fun setClipObscured(id: Long, obscured: Boolean) = viewModelScope.launch {
        videoRepository.setPrivate(id, obscured)
    }

    /** Stores the shared password (hash) that reveals per-clip obscured items. */
    fun setObscurePassword(hash: String) = viewModelScope.launch {
        settingsRepository.update { it.copy(privateVaultPassword = hash) }
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
        val tags: Set<String>,
        val continueOnly: Boolean = false
    )

    private data class LibraryFilters(
        val search: String,
        val folderId: Long?,
        val favoritesOnly: Boolean,
        val extra: ExtraFilters
    )
}
