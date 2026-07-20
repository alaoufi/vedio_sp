package com.myvideolibrary.app.ui.main

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.LibraryViewMode
import com.myvideolibrary.app.data.model.SortOrder
import com.myvideolibrary.app.data.model.SourceFilter
import com.myvideolibrary.app.databinding.ActivityMainBinding
import com.myvideolibrary.app.security.SecurityManager
import com.myvideolibrary.app.security.applyScreenshotPolicy
import com.myvideolibrary.app.ui.downloads.DownloadsActivity
import com.myvideolibrary.app.ui.importer.ImportActivity
import com.myvideolibrary.app.ui.player.PlayerActivity
import com.myvideolibrary.app.ui.provider.AddDownloadActivity
import com.myvideolibrary.app.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: LibraryViewModel by viewModels()
    private val youtubeViewModel: com.myvideolibrary.app.ui.search.SearchViewModel by viewModels()

    @javax.inject.Inject lateinit var securityManager: SecurityManager

    private lateinit var adapter: VideoPagingAdapter
    private lateinit var youtubeAdapter: com.myvideolibrary.app.ui.search.SearchResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotPolicy(securityManager)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecycler()
        setupFab()
        setupYouTubeTab()
        observeState()
        observeVideos()
        observeDownloads()
        binding.downloadBanner.setOnClickListener {
            startActivity(Intent(this, DownloadsActivity::class.java))
        }
        requestNotificationPermissionIfNeeded()
    }

    private fun observeDownloads() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.activeDownloads.collectLatest { active ->
                    val show = active.isNotEmpty()
                    binding.downloadBanner.isVisible = show
                    if (show) {
                        val downloading = active.firstOrNull {
                            it.status == com.myvideolibrary.app.data.model.DownloadStatus.DOWNLOADING.id
                        }
                        val percent = downloading?.progress ?: 0
                        binding.downloadBannerText.text =
                            getString(R.string.downloading_banner, active.size, percent)
                        val bar = binding.downloadBannerProgress
                        if (downloading == null) {
                            // Queued: animate an indeterminate bar so it shows instantly.
                            bar.isIndeterminate = true
                        } else {
                            bar.isIndeterminate = false
                            bar.setProgressCompat(percent, true)
                        }
                    }
                }
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { /* progress notifications are best-effort; downloads run regardless */ }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ---- Tabs: Library (home) + ad-free YouTube (switched from the ⋮ menu) ----

    private var youtubeTab = false
    private var youtubeGrid = false

    private fun showYouTubeTab(youtube: Boolean) {
        youtubeTab = youtube
        binding.youtubePanel.isVisible = youtube
        binding.swipeRefresh.isVisible = !youtube
        binding.fabImport.isVisible = !youtube
        if (youtube) binding.emptyState.isVisible = false
        supportActionBar?.title =
            getString(if (youtube) R.string.tab_youtube else R.string.app_name)
        invalidateOptionsMenu()
        // First time YouTube opens with no results: show trending (like the YT app).
        if (youtube && youtubeViewModel.state.value.results.isEmpty()) {
            youtubeViewModel.loadTrending()
        }
    }

    private fun setupYouTubeTab() {
        youtubeViewModel.setSource(com.myvideolibrary.app.data.model.VideoSource.YOUTUBE)
        youtubeAdapter = com.myvideolibrary.app.ui.search.SearchResultAdapter(
            onPlay = { item -> youtubeViewModel.play(item) },
            onSaveLink = { item -> youtubeViewModel.saveLink(item) },
            onDownload = { item, anchor ->
                com.myvideolibrary.app.ui.provider.DownloadKindDialog.show(anchor) { kind ->
                    youtubeViewModel.downloadItem(item, kind)
                }
            }
        )
        binding.ytRecyclerView.adapter = youtubeAdapter
        binding.ytRecyclerView.setHasFixedSize(true)
        applyYouTubeLayout()
        binding.hideShortsSwitch.setOnCheckedChangeListener { _, _ ->
            renderYouTube(youtubeViewModel.state.value)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                youtubeViewModel.state.collectLatest { renderYouTube(it) }
            }
        }
    }

    private fun applyYouTubeLayout() {
        youtubeAdapter.grid = youtubeGrid
        binding.ytRecyclerView.layoutManager = if (youtubeGrid) {
            GridLayoutManager(this, gridSpanCount())
        } else {
            androidx.recyclerview.widget.LinearLayoutManager(this)
        }
    }

    private fun renderYouTube(state: com.myvideolibrary.app.ui.search.SearchUiState) {
        binding.ytProgress.isVisible = state.loading
        val items = if (binding.hideShortsSwitch.isChecked) {
            state.results.filterNot(::isShort)
        } else {
            state.results
        }
        youtubeAdapter.submitList(items)
        when {
            state.error != null -> {
                binding.ytHint.isVisible = true
                binding.ytHint.text = state.error
            }
            items.isEmpty() && !state.loading -> {
                binding.ytHint.isVisible = true
                binding.ytHint.setText(R.string.youtube_tab_hint)
            }
            else -> binding.ytHint.isVisible = false
        }
        state.message?.let {
            android.widget.Toast.makeText(this, R.string.download_started, android.widget.Toast.LENGTH_LONG).show()
            youtubeViewModel.consumeMessage()
        }
        if (state.savedLink) {
            android.widget.Toast.makeText(this, R.string.link_saved, android.widget.Toast.LENGTH_SHORT).show()
            youtubeViewModel.consumeSavedLink()
        }
        state.streamRequest?.let { req ->
            startActivity(
                com.myvideolibrary.app.ui.player.PlayerActivity.streamIntent(this, req.sourceUrl, req.title)
            )
            youtubeViewModel.consumeStreamRequest()
        }
    }

    /**
     * A YouTube Short: the extractor's own short-form flag, a /shorts/ URL, or —
     * as a fallback for backends without the flag — a clip of ≤ ~61 seconds.
     */
    private fun isShort(item: com.myvideolibrary.app.provider.model.ProviderSearchItem): Boolean {
        if (item.isShort) return true
        val url = item.url.lowercase()
        return url.contains("/shorts/") || (item.durationMs in 1..61_000)
    }

    private fun setupRecycler() {
        adapter = VideoPagingAdapter(
            viewMode = LibraryViewMode.GRID,
            onClick = ::onVideoClick,
            onLongClick = { viewModel.enterSelection(it.id) },
            onMenu = ::showVideoMenu
        )
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)

        binding.swipeRefresh.setOnRefreshListener {
            adapter.refresh()
            binding.swipeRefresh.isRefreshing = false
        }

        adapter.addLoadStateListener { loadStates ->
            val refresh = loadStates.refresh
            binding.progressBar.isVisible = refresh is LoadState.Loading && adapter.itemCount == 0
            val empty = refresh is LoadState.NotLoading && adapter.itemCount == 0
            binding.emptyState.isVisible = empty
        }
    }

    override fun onStop() {
        super.onStop()
        // Privacy: don't keep the last search term around after leaving the screen.
        if (viewModel.uiState.value.search.isNotEmpty()) viewModel.setSearch("")
    }

    private fun applyLayoutManager(mode: LibraryViewMode) {
        binding.recyclerView.layoutManager = when (mode) {
            LibraryViewMode.GRID -> GridLayoutManager(this, gridSpanCount())
            LibraryViewMode.LIST -> LinearLayoutManager(this)
        }
        adapter.setViewMode(mode)
    }

    private fun gridSpanCount(): Int {
        // Smaller cells: ~120dp per column gives 3 on a typical phone.
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 120).coerceAtLeast(3)
    }

    /** The collapsible toolbar search filters the library live, or searches YouTube on submit. */
    private fun setupSearchView(searchView: androidx.appcompat.widget.SearchView) {
        searchView.queryHint = getString(if (youtubeTab) R.string.youtube_search_hint else R.string.search_hint)
        searchView.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (youtubeTab) {
                    youtubeViewModel.setSource(com.myvideolibrary.app.data.model.VideoSource.YOUTUBE)
                    youtubeViewModel.search(query.orEmpty())
                    searchView.clearFocus()
                }
                return youtubeTab
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                // Library filters live; YouTube waits for submit (network call).
                if (!youtubeTab) viewModel.setSearch(newText?.toString().orEmpty())
                return false
            }
        })
    }

    /** All source/type/category filters in one popup, anchored to the filter button. */
    private fun showFilterMenu(anchor: android.view.View) {
        val state = viewModel.uiState.value
        val popup = android.widget.PopupMenu(this, anchor)
        val menu = popup.menu

        // --- Source ---
        val source = menu.addSubMenu(getString(R.string.filter_source))
        source.add(GROUP_SOURCE, ID_SRC_ALL, 0, R.string.filter_all)
        source.add(GROUP_SOURCE, ID_SRC_TIKTOK, 1, R.string.source_tiktok)
        source.add(GROUP_SOURCE, ID_SRC_YOUTUBE, 2, R.string.source_youtube)
        source.add(GROUP_SOURCE, ID_SRC_OTHER, 3, R.string.source_other)
        source.setGroupCheckable(GROUP_SOURCE, true, true)
        source.findItem(
            when (state.sourceFilter) {
                SourceFilter.TIKTOK -> ID_SRC_TIKTOK
                SourceFilter.YOUTUBE -> ID_SRC_YOUTUBE
                SourceFilter.OTHER -> ID_SRC_OTHER
                SourceFilter.ALL -> ID_SRC_ALL
            }
        )?.isChecked = true

        // --- Type (video / audio / image) ---
        val type = menu.addSubMenu(getString(R.string.filter_type))
        type.add(GROUP_TYPE, ID_TYPE_ALL, 0, R.string.filter_all)
        type.add(GROUP_TYPE, ID_TYPE_VIDEO, 1, R.string.type_video)
        type.add(GROUP_TYPE, ID_TYPE_AUDIO, 2, R.string.type_audio)
        type.add(GROUP_TYPE, ID_TYPE_IMAGE, 3, R.string.type_image)
        type.setGroupCheckable(GROUP_TYPE, true, true)
        type.findItem(
            when (state.mediaTypeFilter) {
                "video" -> ID_TYPE_VIDEO
                "audio" -> ID_TYPE_AUDIO
                "image" -> ID_TYPE_IMAGE
                else -> ID_TYPE_ALL
            }
        )?.isChecked = true

        // --- Category (multi-select; only when categories exist) ---
        if (state.categories.isNotEmpty()) {
            val selected = state.categoryFilters.size
            val label = if (selected > 0) {
                getString(R.string.category) + " ($selected)"
            } else {
                getString(R.string.category)
            }
            menu.add(GROUP_CATEGORY, ID_CAT_PICK, 100, label)
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_SRC_ALL -> viewModel.setSourceFilter(SourceFilter.ALL)
                ID_SRC_TIKTOK -> viewModel.setSourceFilter(SourceFilter.TIKTOK)
                ID_SRC_YOUTUBE -> viewModel.setSourceFilter(SourceFilter.YOUTUBE)
                ID_SRC_OTHER -> viewModel.setSourceFilter(SourceFilter.OTHER)
                ID_TYPE_ALL -> viewModel.setMediaTypeFilter(null)
                ID_TYPE_VIDEO -> viewModel.setMediaTypeFilter("video")
                ID_TYPE_AUDIO -> viewModel.setMediaTypeFilter("audio")
                ID_TYPE_IMAGE -> viewModel.setMediaTypeFilter("image")
                ID_CAT_PICK -> showCategoryFilterDialog(state.categories, state.categoryFilters)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    /**
     * Multi-select category filter: tick any number of categories to show only
     * those; clearing all (or "Show all") returns the full library.
     */
    private fun showCategoryFilterDialog(categories: List<String>, selected: Set<String>) {
        val items = categories.toTypedArray()
        val checked = BooleanArray(items.size) { items[it] in selected }
        AlertDialog.Builder(this)
            .setTitle(R.string.category)
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.apply) { _, _ ->
                val chosen = items.filterIndexed { i, _ -> checked[i] }.toSet()
                viewModel.setCategoryFilters(chosen)
            }
            .setNeutralButton(R.string.category_all) { _, _ ->
                viewModel.setCategoryFilters(emptySet())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun setupFab() {
        binding.fabImport.setOnClickListener { showAddMenu(it) }
    }

    /** The bottom "+" opens the download/import actions, within easy thumb reach. */
    private fun showAddMenu(anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 5, 0, getString(R.string.browser_title))
        popup.menu.add(0, 1, 1, getString(R.string.search_title))
        popup.menu.add(0, 2, 2, getString(R.string.action_add_download))
        popup.menu.add(0, 3, 3, getString(R.string.action_downloads))
        popup.menu.add(0, 4, 4, getString(R.string.import_title))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                5 -> startActivity(Intent(this, com.myvideolibrary.app.ui.browser.BrowserActivity::class.java))
                1 -> startActivity(Intent(this, com.myvideolibrary.app.ui.search.SearchActivity::class.java))
                2 -> startActivity(AddDownloadActivity.intent(this))
                3 -> startActivity(Intent(this, DownloadsActivity::class.java))
                4 -> startActivity(Intent(this, ImportActivity::class.java))
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    applyLayoutManager(state.viewMode)
                    adapter.setSelection(state.selectionMode, state.selectedIds)
                    renderStats(state)
                    renderSelectionBar(state)
                    renderProtectedTitle(state)
                    invalidateOptionsMenu()
                }
            }
        }
    }

    private fun observeVideos() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.videos.collectLatest { adapter.submitData(it) }
            }
        }
    }

    private fun renderStats(state: LibraryUiState) {
        binding.statsText.text = getString(
            R.string.library_stats,
            state.videoCount,
            Formatters.fileSize(state.totalSize)
        )
    }

    private fun promptSetCategory(video: VideoEntity) {
        // Offer the same categories shown in "Manage categories", in the same
        // managed order, so the two match.
        val existing = viewModel.uiState.value.categories
        val labels = existing + getString(R.string.category_none) + getString(R.string.add_category)
        // Pre-select (radio mark) the category this clip currently belongs to,
        // so it's clear which one is active — "Uncategorized" if it has none.
        val current = video.category
        val checked = when {
            current.isNullOrBlank() -> existing.size
            else -> existing.indexOf(current).takeIf { it >= 0 } ?: -1
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_category)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                when {
                    which < existing.size -> viewModel.setCategory(video.id, existing[which])
                    which == existing.size -> viewModel.setCategory(video.id, null)
                    else -> promptCustomCategory(video)
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptCustomCategory(video: VideoEntity) {
        val input = EditText(this).apply {
            hint = getString(R.string.category_hint)
            setText(video.category.orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_category)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.setCategory(video.id, input.text.toString().trim().ifEmpty { null })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun renderProtectedTitle(state: LibraryUiState) {
        supportActionBar?.title = if (state.protectedMode) {
            getString(R.string.protected_title, state.videoCount)
        } else {
            getString(R.string.app_name)
        }
    }

    private fun renderSelectionBar(state: LibraryUiState) {
        binding.selectionBar.isVisible = state.selectionMode
        if (state.selectionMode) {
            binding.selectionCount.text = getString(
                R.string.selected_count, state.selectedIds.size
            )
            binding.selectionDelete.setOnClickListener { confirmDeleteSelected() }
            binding.selectionFavorite.setOnClickListener { viewModel.favoriteSelected(true) }
            binding.selectionCategory.setOnClickListener { promptSetCategoryForSelection() }
            binding.selectionClose.setOnClickListener { viewModel.clearSelection() }
        }
    }

    private fun toggleProtected() {
        if (viewModel.uiState.value.protectedMode) {
            viewModel.setProtectedMode(false) // leaving the private view is free
            return
        }
        authenticateForPrivate { viewModel.setProtectedMode(true) }
    }

    /**
     * Always require the device fingerprint (with screen-lock fallback) before
     * revealing the private view — so private videos never show without it.
     */
    private fun authenticateForPrivate(onSuccess: () -> Unit) {
        val authenticators = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
                androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
        }
        val canAuth = androidx.biometric.BiometricManager.from(this)
            .canAuthenticate(authenticators) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS

        when {
            canAuth -> {
                val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.protected_videos))
                    .setSubtitle(getString(R.string.unlock_biometric_subtitle))
                    .setAllowedAuthenticators(authenticators)
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                    info.setNegativeButtonText(getString(R.string.cancel))
                }
                androidx.biometric.BiometricPrompt(
                    this, androidx.core.content.ContextCompat.getMainExecutor(this),
                    object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(
                            result: androidx.biometric.BiometricPrompt.AuthenticationResult
                        ) { onSuccess() }
                    }
                ).authenticate(info.build())
            }
            // No device biometric/lock available, but an app PIN exists → use it.
            securityManager.isLockConfigured -> promptAppPin(onSuccess)
            // Nothing to authenticate with — keep the private view locked and guide
            // the user to set up a fingerprint / screen lock first.
            else -> android.widget.Toast.makeText(
                this, R.string.protected_need_lock, android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun promptAppPin(onSuccess: () -> Unit) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = getString(R.string.pin_hint)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.protected_videos)
            .setView(input)
            .setPositiveButton(R.string.unlock) { _, _ ->
                if (securityManager.verifyPin(input.text.toString())) onSuccess()
                else android.widget.Toast.makeText(this, R.string.wrong_pin, android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun onVideoClick(video: VideoEntity) {
        val state = viewModel.uiState.value
        when {
            state.selectionMode -> viewModel.toggleSelected(video.id)
            // A downloaded cover image opens in an image viewer, not the player.
            video.mediaType == "image" -> openImage(video)
            // Private videos only appear in the already-unlocked private view, so
            // they play normally from there (no separate "locked" block).
            else -> {
                // Queue the videos currently in view (in order) so playback
                // continues to the next clip automatically when this one ends.
                val queue = adapter.snapshot().items
                    .filter { it.mediaType != "image" }
                    .map { it.id }
                val index = queue.indexOf(video.id)
                if (queue.size > 1 && index >= 0) {
                    startActivity(PlayerActivity.playlistIntent(this, queue.toLongArray(), index))
                } else {
                    startActivity(PlayerActivity.intent(this, video.id))
                }
            }
        }
    }

    private fun openImage(video: VideoEntity) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", java.io.File(video.localPath)
            )
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, R.string.share_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun showVideoMenu(video: VideoEntity, anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.play))
        // Favorite lives here now (the per-row heart was removed to save space).
        popup.menu.add(
            0, 9, 1,
            getString(if (video.isFavorite) R.string.unfavorite else R.string.favorite)
        )
        // Saved links can be downloaded to a local file on demand.
        if (video.isLinkOnly) popup.menu.add(0, 7, 2, getString(R.string.download))
        popup.menu.add(0, 2, 3, getString(R.string.action_share))
        // A single "Private" toggle: checked means the clip is in the private view.
        popup.menu.add(0, 3, 4, getString(R.string.lock_video)).apply {
            isCheckable = true
            isChecked = video.isLocked
        }
        popup.menu.add(0, 4, 5, getString(R.string.set_category))
        popup.menu.add(0, 5, 6, getString(R.string.edit_info))
        // Open the original TikTok/YouTube page in its app or the browser.
        if (!video.sourceUrl.isNullOrBlank()) popup.menu.add(0, 8, 7, getString(R.string.open_source))
        // Image editor (crop / hide / text / OCR) only for image items.
        if (video.mediaType == com.myvideolibrary.app.data.model.MediaType.IMAGE.id) {
            popup.menu.add(0, 11, 8, getString(R.string.edit_image))
        }
        popup.menu.add(0, 10, 9, getString(R.string.file_info))
        popup.menu.add(0, 6, 10, getString(R.string.delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { onVideoClick(video); true }
                2 -> { shareVideo(video); true }
                3 -> { protectVideo(video); true }
                4 -> { promptSetCategory(video); true }
                5 -> { promptEditInfo(video); true }
                6 -> { confirmDeleteSingle(video); true }
                8 -> { openSource(video); true }
                9 -> { viewModel.toggleFavorite(video); true }
                10 -> { showFileInfo(video); true }
                11 -> {
                    startActivity(
                        com.myvideolibrary.app.ui.imageeditor.ImageEditorActivity.intent(
                            this, video.localPath
                        )
                    )
                    true
                }
                7 -> {
                    viewModel.downloadLink(video)
                    android.widget.Toast.makeText(
                        this, R.string.download_started, android.widget.Toast.LENGTH_SHORT
                    ).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    /** Shows the file's format (MP4/M4A/JPG…), type, size, resolution and duration. */
    private fun showFileInfo(video: VideoEntity) {
        val mediaType = com.myvideolibrary.app.data.model.MediaType.fromId(video.mediaType)
        val typeLabel = when (mediaType) {
            com.myvideolibrary.app.data.model.MediaType.VIDEO -> getString(R.string.type_video)
            com.myvideolibrary.app.data.model.MediaType.AUDIO -> getString(R.string.type_audio)
            com.myvideolibrary.app.data.model.MediaType.IMAGE -> getString(R.string.type_image)
        }
        val format = when {
            video.isLinkOnly -> getString(R.string.link_badge)
            else -> video.localPath.substringAfterLast('.', "").uppercase()
                .ifBlank { "—" }
        }
        val lines = buildList {
            add(getString(R.string.info_format, format))
            add(getString(R.string.info_type, typeLabel))
            if (!video.isLinkOnly) add(getString(R.string.info_size, Formatters.fileSize(video.fileSize)))
            if (video.width > 0 && video.height > 0) {
                add(getString(R.string.info_resolution, video.width, video.height))
            }
            if (video.duration > 0) {
                add(getString(R.string.info_duration, Formatters.duration(video.duration)))
            }
            video.description?.takeIf { it.isNotBlank() }?.let {
                add(getString(R.string.info_description, it))
            }
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.file_info)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Protects/unprotects a video; when protecting without a lock, points to Settings. */
    private fun protectVideo(video: VideoEntity) {
        viewModel.toggleLock(video)
        // video.isLocked is the state *before* the toggle: false => we just protected it.
        if (!video.isLocked) {
            val msg = if (securityManager.isLockConfigured) {
                R.string.protected_moved_hint
            } else {
                R.string.protected_set_pin_hint
            }
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Opens the video's original page (TikTok/YouTube) in an external app or browser. */
    private fun openSource(video: VideoEntity) {
        val url = video.sourceUrl
        if (url.isNullOrBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, R.string.error_unknown, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareVideo(video: VideoEntity) {
        // A saved link has no local file — share its source URL as text.
        if (video.isLinkOnly) {
            val url = video.sourceUrl
            if (url.isNullOrBlank()) {
                android.widget.Toast.makeText(this, R.string.share_failed, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            startActivity(Intent.createChooser(share, getString(R.string.action_share)))
            return
        }
        try {
            val uri: android.net.Uri = if (video.localPath.startsWith("content://")) {
                android.net.Uri.parse(video.localPath)
            } else {
                androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", java.io.File(video.localPath)
                )
            }
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "video/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.action_share)))
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, R.string.share_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Edit the clip's title and description in one dialog. */
    private fun promptEditInfo(video: VideoEntity) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val titleInput = EditText(this).apply {
            setText(video.title)
            hint = getString(R.string.info_title_hint)
            setSingleLine(true)
        }
        val descInput = EditText(this).apply {
            setText(video.description.orEmpty())
            hint = getString(R.string.info_desc_hint)
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setLines(3)
        }
        container.addView(titleInput)
        container.addView(descInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_info)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = titleInput.text.toString().trim()
                val desc = descInput.text.toString().trim().ifEmpty { null }
                if (name.isNotEmpty()) viewModel.updateInfo(video.id, name, desc)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteSingle(video: VideoEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_videos_title)
            .setMessage(video.title)
            .setPositiveButton(R.string.delete_files) { _, _ -> viewModel.deleteVideo(video.id, true) }
            .setNeutralButton(R.string.remove_only) { _, _ -> viewModel.deleteVideo(video.id, false) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteSelected() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_videos_title)
            .setMessage(R.string.delete_videos_message)
            .setPositiveButton(R.string.delete_files) { _, _ -> viewModel.deleteSelected(true) }
            .setNeutralButton(R.string.remove_only) { _, _ -> viewModel.deleteSelected(false) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Bulk-assign a category to every selected clip. */
    private fun promptSetCategoryForSelection() {
        val existing = viewModel.uiState.value.categories
        val labels = existing + getString(R.string.category_none) + getString(R.string.add_category)
        AlertDialog.Builder(this)
            .setTitle(R.string.set_category)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which < existing.size -> viewModel.setCategorySelected(existing[which])
                    which == existing.size -> viewModel.setCategorySelected(null)
                    else -> promptCustomCategoryForSelection()
                }
            }
            .show()
    }

    private fun promptCustomCategoryForSelection() {
        val input = EditText(this).apply { hint = getString(R.string.category_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.set_category)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.setCategorySelected(input.text.toString().trim().ifEmpty { null })
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Options menu ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        (menu.findItem(R.id.action_search)?.actionView as? androidx.appcompat.widget.SearchView)
            ?.let { setupSearchView(it) }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val favoritesOnly = viewModel.uiState.value.favoritesOnly
        menu.findItem(R.id.action_favorites)?.setIcon(
            if (favoritesOnly) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        // Show the tab you can switch TO; library-only actions are hidden on YouTube.
        menu.findItem(R.id.action_view_youtube)?.isVisible = !youtubeTab
        menu.findItem(R.id.action_view_library)?.isVisible = youtubeTab
        // Search and view-toggle work on both tabs; the rest are library-only.
        for (id in intArrayOf(
            R.id.action_filter, R.id.action_favorites, R.id.action_sort,
            R.id.action_protected, R.id.action_manage_categories, R.id.action_stats
        )) menu.findItem(id)?.isVisible = !youtubeTab
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_view_youtube -> { showYouTubeTab(true); true }
            R.id.action_view_library -> { showYouTubeTab(false); true }
            R.id.action_filter -> {
                val anchor = binding.toolbar.findViewById<android.view.View>(R.id.action_filter)
                    ?: binding.toolbar
                showFilterMenu(anchor)
                true
            }
            R.id.action_toggle_view -> {
                if (youtubeTab) {
                    youtubeGrid = !youtubeGrid
                    applyYouTubeLayout()
                } else {
                    viewModel.toggleViewMode()
                }
                true
            }
            R.id.action_sort -> { showSortDialog(); true }
            R.id.action_favorites -> {
                viewModel.setFavoritesOnly(!viewModel.uiState.value.favoritesOnly)
                true
            }
            R.id.action_manage_categories -> {
                startActivity(com.myvideolibrary.app.ui.categories.CategoriesActivity.intent(this))
                true
            }
            R.id.action_stats -> {
                startActivity(Intent(this, com.myvideolibrary.app.ui.stats.StatsActivity::class.java))
                true
            }
            R.id.action_protected -> { toggleProtected(); true }
            R.id.action_settings -> {
                startActivity(Intent(this, com.myvideolibrary.app.ui.settings.SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showSortDialog() {
        val labels = arrayOf(
            getString(R.string.sort_date_new),
            getString(R.string.sort_date_old),
            getString(R.string.sort_name_az),
            getString(R.string.sort_name_za),
            getString(R.string.sort_duration_long),
            getString(R.string.sort_duration_short),
            getString(R.string.sort_size_large),
            getString(R.string.sort_size_small),
            getString(R.string.sort_category)
        )
        val orders = arrayOf(
            SortOrder.DATE_ADDED_DESC, SortOrder.DATE_ADDED_ASC,
            SortOrder.NAME_ASC, SortOrder.NAME_DESC,
            SortOrder.DURATION_DESC, SortOrder.DURATION_ASC,
            SortOrder.SIZE_DESC, SortOrder.SIZE_ASC,
            SortOrder.CATEGORY_ASC
        )
        val current = orders.indexOf(viewModel.uiState.value.sortOrder).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_by)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                viewModel.setSortOrder(orders[which])
                dialog.dismiss()
            }
            .show()
    }

    private companion object {
        const val GROUP_SOURCE = 1
        const val GROUP_CATEGORY = 2
        const val ID_SRC_ALL = 100
        const val ID_SRC_TIKTOK = 101
        const val ID_SRC_YOUTUBE = 102
        const val ID_SRC_OTHER = 103
        const val GROUP_TYPE = 4
        const val ID_TYPE_ALL = 150
        const val ID_TYPE_VIDEO = 151
        const val ID_TYPE_AUDIO = 152
        const val ID_TYPE_IMAGE = 153
        const val ID_CAT_PICK = 200
    }

    override fun onBackPressed() {
        val state = viewModel.uiState.value
        when {
            youtubeTab -> showYouTubeTab(false) // back returns to the library tab
            state.selectionMode -> viewModel.clearSelection()
            state.protectedMode -> viewModel.setProtectedMode(false)
            // Back first returns to the full, unfiltered library.
            state.categoryFilters.isNotEmpty() -> viewModel.setCategoryFilters(emptySet())
            state.search.isNotEmpty() -> viewModel.setSearch("")
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }
}
