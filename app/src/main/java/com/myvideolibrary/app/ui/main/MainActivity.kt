package com.myvideolibrary.app.ui.main

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
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
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: LibraryViewModel by viewModels()
    private val youtubeViewModel: com.myvideolibrary.app.ui.search.SearchViewModel by viewModels()

    @javax.inject.Inject lateinit var securityManager: SecurityManager
    @javax.inject.Inject lateinit var okHttpClient: okhttp3.OkHttpClient
    @javax.inject.Inject lateinit var thumbnailGenerator: com.myvideolibrary.app.util.ThumbnailGenerator
    @javax.inject.Inject lateinit var autoBackupManager: com.myvideolibrary.app.data.backup.AutoBackupManager

    private lateinit var adapter: VideoPagingAdapter
    /** Runs the animated preview on the centred grid card; one at a time. */
    private var previewJob: Job? = null
    private lateinit var youtubeAdapter: com.myvideolibrary.app.ui.search.SearchResultAdapter

    /** The YouTube results currently on screen, used to build a swipe-able queue. */
    private var youtubeItems: List<com.myvideolibrary.app.provider.model.ProviderSearchItem> = emptyList()

    /** Opens a search result as a stream, with the whole result list as a queue. */
    private fun playStreamQueue(
        list: List<com.myvideolibrary.app.provider.model.ProviderSearchItem>,
        item: com.myvideolibrary.app.provider.model.ProviderSearchItem
    ) {
        if (list.size > 1) {
            val index = list.indexOfFirst { it.url == item.url }.coerceAtLeast(0)
            startActivity(
                PlayerActivity.streamPlaylistIntent(
                    this,
                    list.map { it.url }.toTypedArray(),
                    list.map { it.title }.toTypedArray(),
                    index
                )
            )
        } else {
            startActivity(PlayerActivity.streamIntent(this, item.url, item.title))
        }
    }

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
        maybeOnboard()
        maybeCheckForUpdate()
        maybeRemindAutoBackup()
    }

    /**
     * Nudges the user to turn on the external auto-backup that protects the library
     * from the OEM "wipe on update" failure. Shown only after onboarding, only while
     * auto-backup is off, throttled to at most once every few days, and dismissable
     * for good via "don't remind me". Tapping "Protect now" jumps straight into the
     * auto-backup setup in Settings.
     */
    private fun maybeRemindAutoBackup() {
        // Don't stack on top of first-launch onboarding / the guide prompt.
        val ob = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (!ob.getBoolean("done", false) || ob.getBoolean("guide_pending", false)) return
        if (autoBackupManager.isEnabled) return

        val prefs = getSharedPreferences("backup_reminder", MODE_PRIVATE)
        if (prefs.getBoolean("never", false)) return
        val now = System.currentTimeMillis()
        val gap = 3L * 24 * 60 * 60 * 1000 // at most once every 3 days
        if (now - prefs.getLong("last_shown", 0L) < gap) return
        prefs.edit().putLong("last_shown", now).apply()

        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_reminder_title)
            .setMessage(R.string.backup_reminder_message)
            .setPositiveButton(R.string.backup_reminder_enable) { _, _ ->
                startActivity(
                    com.myvideolibrary.app.ui.settings.SettingsActivity.autoBackupIntent(this)
                )
            }
            .setNeutralButton(R.string.backup_reminder_never) { _, _ ->
                prefs.edit().putBoolean("never", true).apply()
            }
            .setNegativeButton(R.string.later, null)
            .show()
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

    /** Picks images/videos from the device (no permission needed) to add to the library. */
    private val devicePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            Toast.makeText(this, R.string.importing, Toast.LENGTH_SHORT).show()
            viewModel.importDeviceMedia(uris)
        }
    }

    private fun pickFromDevice() {
        devicePicker.launch(
            androidx.activity.result.PickVisualMediaRequest(
                androidx.activity.result.contract.ActivityResultContracts
                    .PickVisualMedia.ImageAndVideo
            )
        )
    }

    /** Opening a (possibly hidden/locked) category from the management screen filters to it. */
    private val manageCategoriesLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data
                ?.getStringExtra(
                    com.myvideolibrary.app.ui.categories.CategoriesActivity.EXTRA_OPEN_CATEGORY
                )
                ?.let { viewModel.setCategoryFilters(setOf(it)) }
        }
    }

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
    private var youtubeGrid = true

    private fun showYouTubeTab(youtube: Boolean) {
        youtubeTab = youtube
        binding.youtubePanel.isVisible = youtube
        binding.swipeRefresh.isVisible = !youtube
        binding.fabImport.isVisible = !youtube
        binding.mediaTypeScroll.isVisible = !youtube
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
            onPlay = { item -> playStreamQueue(youtubeItems, item) },
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
        // Always use YouTube-style 16:9 cards; the toggle only changes column count
        // (multi-column grid vs. a single full-width column), never the card style.
        youtubeAdapter.style = com.myvideolibrary.app.ui.search.SearchResultAdapter.Style.GRID
        binding.ytRecyclerView.layoutManager = if (youtubeGrid) {
            GridLayoutManager(this, 2)
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
        youtubeItems = items
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
            onMenu = ::showVideoMenu,
            onFavorite = { viewModel.toggleFavorite(it) }
        )
        binding.recyclerView.adapter = adapter
        binding.recyclerView.setHasFixedSize(true)
        // Keep a few more views ready off-screen for smoother fast scrolling.
        binding.recyclerView.setItemViewCacheSize(12)

        // Auto-preview: when scrolling settles, briefly animate the centred card.
        binding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) startCenterPreview(rv)
                else stopPreview()
            }
        })

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

        setupMediaTypeChips()
    }

    /** Quick filter row: All / Continue watching / Videos / Images / Audio. */
    private fun setupMediaTypeChips() {
        binding.mediaTypeChips.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipTypeContinue -> viewModel.setContinueOnly(true)
                R.id.chipTypeVideos -> viewModel.setMediaTypeFilters(setOf("video"))
                R.id.chipTypeImages -> viewModel.setMediaTypeFilters(setOf("image"))
                R.id.chipTypeAudio -> viewModel.setMediaTypeFilters(setOf("audio"))
                else -> { viewModel.setContinueOnly(false); viewModel.setMediaTypeFilters(emptySet()) }
            }
        }
    }

    /** Reflects the current quick filter onto the chip row without looping. */
    private fun renderMediaTypeChips(state: LibraryUiState) {
        val targetId = when {
            state.continueOnly -> R.id.chipTypeContinue
            state.mediaTypeFilters.singleOrNull() == "video" -> R.id.chipTypeVideos
            state.mediaTypeFilters.singleOrNull() == "image" -> R.id.chipTypeImages
            state.mediaTypeFilters.singleOrNull() == "audio" -> R.id.chipTypeAudio
            else -> R.id.chipTypeAll
        }
        if (binding.mediaTypeChips.checkedChipId != targetId) {
            binding.mediaTypeChips.check(targetId)
        }
        binding.mediaTypeScroll.isVisible = !youtubeTab
    }

    override fun onStop() {
        super.onStop()
        // Don't keep decoding preview frames while the screen isn't visible.
        stopPreview()
        // Privacy: don't keep the last search term around after leaving the screen.
        if (viewModel.uiState.value.search.isNotEmpty()) viewModel.setSearch("")
    }

    /** Cancels any running auto-preview (called when scrolling resumes or on pause). */
    private fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
    }

    /**
     * When scrolling settles, animates the card sitting under the centre of the
     * grid: a few decoded frames cycled on its thumbnail. Grid mode only, never in
     * selection mode, and only for local playable video files. A short debounce
     * skips cards the user merely scrolled past. Every guard re-checks that the row
     * still shows the same clip, so a recycled row is never touched, and frames are
     * left to GC (not recycled) so a late draw can't hit a dead bitmap.
     */
    private fun startCenterPreview(rv: RecyclerView) {
        stopPreview()
        val state = viewModel.uiState.value
        if (state.viewMode != LibraryViewMode.GRID || state.selectionMode) return

        val child = rv.findChildViewUnder(rv.width / 2f, rv.height / 2f) ?: return
        val pos = rv.getChildAdapterPosition(child)
        if (pos == RecyclerView.NO_POSITION) return
        val video = adapter.peekAt(pos) ?: return
        // Never animate-preview a clip that's obscured (per-clip) or in a locked category.
        if (isClipObscured(video) || isCategoryLocked(video)) return
        if (video.isLinkOnly ||
            video.mediaType != com.myvideolibrary.app.data.model.MediaType.VIDEO.id ||
            video.localPath.isBlank() || video.localPath.startsWith("content://")
        ) return
        val target = adapter.previewImageFor(rv.getChildViewHolder(child)) ?: return

        previewJob = lifecycleScope.launch {
            delay(500) // debounce: ignore quick fly-bys
            val frames = thumbnailGenerator.extractPreviewFrames(
                video.localPath, count = 6, targetWidth = 360
            )
            if (frames.isEmpty()) return@launch
            try {
                var i = 0
                while (isActive) {
                    val curPos = rv.getChildAdapterPosition(child)
                    if (curPos == RecyclerView.NO_POSITION ||
                        adapter.peekAt(curPos)?.id != video.id
                    ) break
                    target.setImageBitmap(frames[i % frames.size])
                    i++
                    delay(600)
                }
            } finally {
                // Restore the static thumbnail if the row still shows this clip.
                val curPos = rv.getChildAdapterPosition(child)
                if (curPos != RecyclerView.NO_POSITION && adapter.peekAt(curPos)?.id == video.id) {
                    com.bumptech.glide.Glide.with(target)
                        .load(video.thumbnailPath ?: video.localPath)
                        .placeholder(R.drawable.ic_video_placeholder)
                        .centerCrop()
                        .into(target)
                }
            }
        }
    }

    private fun applyLayoutManager(mode: LibraryViewMode) {
        binding.recyclerView.layoutManager = when (mode) {
            LibraryViewMode.GRID -> {
                val span = gridSpanCount()
                adapter.setSpanCount(span)
                // Staggered so cards can take the clip's real aspect ratio. NONE
                // keeps items from swapping columns mid-scroll.
                StaggeredGridLayoutManager(span, StaggeredGridLayoutManager.VERTICAL).apply {
                    gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
                }
            }
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

        // Each opens a multi-select dialog; a count shows how many are active.
        fun label(base: Int, n: Int) = if (n > 0) getString(base) + " ($n)" else getString(base)
        menu.add(GROUP_SOURCE, ID_SRC_ALL, 0, label(R.string.filter_source, state.sourceFilters.size))
        menu.add(GROUP_TYPE, ID_TYPE_ALL, 1, label(R.string.filter_type, state.mediaTypeFilters.size))
        if (state.categories.isNotEmpty()) {
            menu.add(GROUP_CATEGORY, ID_CAT_PICK, 2, label(R.string.category, state.categoryFilters.size))
        }
        if (viewModel.allTags.value.isNotEmpty()) {
            menu.add(GROUP_TAG, ID_TAG_PICK, 3, label(R.string.tags, state.tagFilters.size))
        }
        // Persist / re-apply whole filter combinations.
        menu.add(GROUP_SAVED, ID_SEARCH_SAVE, 4, getString(R.string.save_filters))
        val saved = viewModel.savedSearches.value
        if (saved.isNotEmpty()) {
            menu.add(GROUP_SAVED, ID_SEARCH_LIST, 5, label(R.string.saved_searches, saved.size))
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                ID_SRC_ALL -> showSourceFilterDialog(state.sourceFilters)
                ID_TYPE_ALL -> showTypeFilterDialog(state.mediaTypeFilters)
                ID_CAT_PICK -> showCategoryFilterDialog(state.categories, state.categoryFilters)
                ID_TAG_PICK -> showTagFilterDialog(viewModel.allTags.value, state.tagFilters)
                ID_SEARCH_SAVE -> promptSaveSearch()
                ID_SEARCH_LIST -> showSavedSearchesDialog()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    /** Multi-select source filter: tick any sources to show; "All" clears them. */
    private fun showSourceFilterDialog(selected: Set<SourceFilter>) {
        val options = listOf(
            SourceFilter.TIKTOK to getString(R.string.source_tiktok),
            SourceFilter.YOUTUBE to getString(R.string.source_youtube),
            SourceFilter.OTHER to getString(R.string.source_other)
        )
        val labels = options.map { it.second }.toTypedArray()
        val checked = BooleanArray(options.size) { options[it].first in selected }
        AlertDialog.Builder(this)
            .setTitle(R.string.filter_source)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.apply) { _, _ ->
                viewModel.setSourceFilters(
                    options.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                )
            }
            .setNeutralButton(R.string.filter_all) { _, _ -> viewModel.setSourceFilters(emptySet()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Multi-select tag filter: tick any tags to show clips carrying any of them. */
    private fun showTagFilterDialog(tags: List<String>, selected: Set<String>) {
        if (tags.isEmpty()) return
        val labels = tags.toTypedArray()
        val checked = BooleanArray(tags.size) { tags[it] in selected }
        AlertDialog.Builder(this)
            .setTitle(R.string.tags)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.apply) { _, _ ->
                viewModel.setTagFilters(tags.filterIndexed { i, _ -> checked[i] }.toSet())
            }
            .setNeutralButton(R.string.filter_all) { _, _ -> viewModel.setTagFilters(emptySet()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Names the current filter + sort combination and saves it for one-tap re-use. */
    private fun promptSaveSearch() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            hint = getString(R.string.save_filters_hint)
            setSingleLine(true)
        }
        container.addView(input)
        AlertDialog.Builder(this)
            .setTitle(R.string.save_filters)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewModel.saveCurrentSearch(name)
                    android.widget.Toast.makeText(this, R.string.saved, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Lists saved searches; tapping one re-applies it, "Delete" opens a remove picker. */
    private fun showSavedSearchesDialog() {
        val saved = viewModel.savedSearches.value
        if (saved.isEmpty()) return
        val names = saved.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.saved_searches)
            .setItems(names) { _, which -> viewModel.applySavedSearch(saved[which]) }
            .setNeutralButton(R.string.delete) { _, _ -> showDeleteSavedSearchesDialog(saved) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDeleteSavedSearchesDialog(
        saved: List<com.myvideolibrary.app.data.local.entity.SavedSearchEntity>
    ) {
        val names = saved.map { it.name }.toTypedArray()
        val checked = BooleanArray(saved.size)
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.delete) { _, _ ->
                saved.filterIndexed { i, _ -> checked[i] }.forEach { viewModel.deleteSavedSearch(it.id) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Multi-select media-type filter: tick any of video/audio/image; "All" clears them. */
    private fun showTypeFilterDialog(selected: Set<String>) {
        val options = listOf(
            "video" to getString(R.string.type_video),
            "audio" to getString(R.string.type_audio),
            "image" to getString(R.string.type_image)
        )
        val labels = options.map { it.second }.toTypedArray()
        val checked = BooleanArray(options.size) { options[it].first in selected }
        AlertDialog.Builder(this)
            .setTitle(R.string.filter_type)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(R.string.apply) { _, _ ->
                viewModel.setMediaTypeFilters(
                    options.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                )
            }
            .setNeutralButton(R.string.filter_all) { _, _ -> viewModel.setMediaTypeFilters(emptySet()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        // A bottom sheet (not a bare popup) so each action can carry a one-line
        // description — it's clear what the browser and import actually do.
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_add, null)
        sheet.setContentView(view)
        fun go(intent: Intent) { sheet.dismiss(); startActivity(intent) }
        view.findViewById<android.view.View>(R.id.rowPickDevice).setOnClickListener {
            sheet.dismiss(); pickFromDevice()
        }
        view.findViewById<android.view.View>(R.id.rowBrowser).setOnClickListener {
            go(Intent(this, com.myvideolibrary.app.ui.browser.BrowserActivity::class.java))
        }
        view.findViewById<android.view.View>(R.id.rowSearch).setOnClickListener {
            go(Intent(this, com.myvideolibrary.app.ui.search.SearchActivity::class.java))
        }
        view.findViewById<android.view.View>(R.id.rowLink).setOnClickListener {
            go(AddDownloadActivity.intent(this))
        }
        view.findViewById<android.view.View>(R.id.rowDownloads).setOnClickListener {
            go(Intent(this, DownloadsActivity::class.java))
        }
        view.findViewById<android.view.View>(R.id.rowImport).setOnClickListener {
            go(Intent(this, ImportActivity::class.java))
        }
        sheet.show()
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    applyLayoutManager(state.viewMode)
                    adapter.setSelection(state.selectionMode, state.selectedIds)
                    adapter.setObscuredCategories(state.obscuredCategories)
                    renderStats(state)
                    renderFilterChip(state)
                    renderMediaTypeChips(state)
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
        // Toast the outcome of a device-media import.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.importResult.collectLatest { count ->
                    if (count != null) {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.import_done, count),
                            Toast.LENGTH_LONG
                        ).show()
                        viewModel.consumeImportResult()
                    }
                }
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

    /**
     * First-launch onboarding: pick the language (Arabic / English), then offer
     * to open the interactive guide. Runs once. Because changing the locale
     * recreates the activity, the guide prompt is deferred via a pref flag so it
     * survives the recreate (and still fires if the locale was unchanged).
     */
    private fun maybeOnboard() {
        val prefs = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (prefs.getBoolean("guide_pending", false)) {
            prefs.edit().putBoolean("guide_pending", false).apply()
            showGuidePrompt()
            return
        }
        if (prefs.getBoolean("done", false)) return
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_language)
            .setCancelable(false)
            .setItems(arrayOf("العربية", "English")) { _, which ->
                prefs.edit().putBoolean("done", true).putBoolean("guide_pending", true).apply()
                val tag = if (which == 0) "ar" else "en"
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(tag)
                )
                // If the locale was unchanged there is no recreate, so trigger the
                // deferred guide prompt here on the next frame.
                binding.root.post {
                    if (prefs.getBoolean("guide_pending", false)) {
                        prefs.edit().putBoolean("guide_pending", false).apply()
                        showGuidePrompt()
                    }
                }
            }
            .show()
    }

    private fun showGuidePrompt() {
        AlertDialog.Builder(this)
            .setTitle(R.string.guide_prompt_title)
            .setMessage(R.string.guide_prompt_message)
            .setPositiveButton(R.string.view_guide) { _, _ ->
                startActivity(Intent(this, com.myvideolibrary.app.ui.help.HelpActivity::class.java))
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    /**
     * Once every few hours, quietly asks GitHub Releases whether a newer build
     * exists and, if so, offers to download it. Any failure is silent — this
     * never blocks or nags, and nothing about the library is sent.
     */
    private fun maybeCheckForUpdate() {
        // Stay quiet until first-launch onboarding is finished.
        val ob = getSharedPreferences("onboarding", MODE_PRIVATE)
        if (!ob.getBoolean("done", false) || ob.getBoolean("guide_pending", false)) return
        val prefs = getSharedPreferences("updates", MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_check", 0L) < 6 * 60 * 60 * 1000L) return

        lifecycleScope.launch {
            val outcome = com.myvideolibrary.app.util.UpdateChecker.checkOutcome(
                com.myvideolibrary.app.BuildConfig.VERSION_CODE, okHttpClient
            )
            // A failed check (e.g. a dropped connection) must not suppress retries for
            // 6h — only stamp the timestamp once we actually reached GitHub.
            if (outcome is com.myvideolibrary.app.util.UpdateChecker.Outcome.Failed) return@launch
            prefs.edit().putLong("last_check", now).apply()
            val result = (outcome as? com.myvideolibrary.app.util.UpdateChecker.Outcome.Available)
                ?.result ?: return@launch
            if (prefs.getInt("skip_build", -1) == result.latestBuild) return@launch
            if (isFinishing || isDestroyed) return@launch
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.update_available_title)
                .setMessage(getString(R.string.update_available_message, result.latestVersion))
                .setPositiveButton(R.string.update_download) { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                android.net.Uri.parse(com.myvideolibrary.app.util.UpdateChecker.APK_URL)
                            )
                        )
                    }
                }
                .setNeutralButton(R.string.update_skip) { _, _ ->
                    prefs.edit().putInt("skip_build", result.latestBuild).apply()
                }
                .setNegativeButton(R.string.later, null)
                .show()
        }
    }

    /**
     * Shows a removable chip for each active quick-filter (favourites, category
     * selection), so it's clear the library is filtered and there's an obvious
     * way back — tap a chip or its × to clear that filter. Hidden when none.
     */
    private fun renderFilterChip(state: LibraryUiState) {
        val group = binding.filterChips
        group.removeAllViews()
        if (state.favoritesOnly) {
            group.addView(
                quickFilterChip(getString(R.string.action_favorites)) {
                    viewModel.setFavoritesOnly(false)
                }
            )
        }
        if (state.categoryFilters.isNotEmpty()) {
            group.addView(
                quickFilterChip(state.categoryFilters.joinToString("، ")) {
                    viewModel.setCategoryFilters(emptySet())
                }
            )
        }
        binding.filterChipsScroll.isVisible = group.childCount > 0
    }

    /** A removable chip; both its body and its × clear the filter via [onClear]. */
    private fun quickFilterChip(text: String, onClear: () -> Unit): com.google.android.material.chip.Chip {
        return com.google.android.material.chip.Chip(this).apply {
            this.text = text
            isCheckable = false
            isCloseIconVisible = true
            setOnCloseIconClickListener { onClear() }
            setOnClickListener { onClear() }
        }
    }

    /** Pick an existing playlist for this video, or create a new one on the spot. */
    private fun showAddToPlaylist(video: VideoEntity) {
        val playlists = viewModel.playlists.value
        val labels = playlists.map { it.name } + getString(R.string.playlist_new)
        AlertDialog.Builder(this)
            .setTitle(R.string.add_to_playlist)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which < playlists.size) {
                    viewModel.addToPlaylist(playlists[which].id, video.id)
                    android.widget.Toast.makeText(this, R.string.playlist_added, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val input = EditText(this).apply { hint = getString(R.string.playlist_name_hint) }
                    AlertDialog.Builder(this)
                        .setTitle(R.string.playlist_new)
                        .setView(input)
                        .setPositiveButton(R.string.create) { _, _ ->
                            viewModel.createPlaylistWith(input.text.toString(), video.id)
                            android.widget.Toast.makeText(this, R.string.playlist_added, android.widget.Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
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
        supportActionBar?.title = getString(R.string.app_name)
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


    private fun onVideoClick(video: VideoEntity) {
        val state = viewModel.uiState.value
        when {
            state.selectionMode -> viewModel.toggleSelected(video.id)
            // An individually-obscured clip: ask for the obscure password first.
            isClipObscured(video) -> promptClipUnlock { onVideoClick(video) }
            // A clip in a protected category (blurred or visible): ask for the
            // category password first, then re-open once unlocked.
            isCategoryLocked(video) -> promptCategoryUnlock(video.category) { onVideoClick(video) }
            // A downloaded cover image opens in an image viewer, not the player.
            video.mediaType == "image" -> openImage(video)
            else -> {
                // Queue the videos currently in view (in order) so playback
                // continues to the next clip automatically when this one ends.
                // Exclude clips in still-locked categories, and obscured clips, so
                // swiping up/down in the player can't reach a protected clip.
                val queue = adapter.snapshot().items
                    .filter { it.mediaType != "image" && !isCategoryLocked(it) && !isClipObscured(it) }
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
        val path = video.localPath
        // Open private images inside the app — the external gallery would expose
        // them and adds its own gestures (swipe-up shows EXIF info). File-backed
        // images go to the in-app viewer/editor; content-URI imports fall back.
        if (path.isNotBlank() && !path.startsWith("content://")) {
            startActivity(com.myvideolibrary.app.ui.imageeditor.ImageEditorActivity.intent(this, path))
            return
        }
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", java.io.File(path)
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
        // An obscured clip's menu could leak it, so require its password first.
        if (isClipObscured(video)) {
            promptClipUnlock { showVideoMenu(video, anchor) }
            return
        }
        // A protected clip's menu (preview/trim/open source…) could leak it,
        // so require the category password before showing it.
        if (isCategoryLocked(video)) {
            promptCategoryUnlock(video.category) { showVideoMenu(video, anchor) }
            return
        }
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
        popup.menu.add(0, 4, 5, getString(R.string.set_category))
        popup.menu.add(0, 14, 5, getString(R.string.add_to_playlist))
        popup.menu.add(0, 5, 6, getString(R.string.edit_info))
        popup.menu.add(0, 16, 6, getString(R.string.edit_tags))
        // Trim (lossless) only for downloaded video files.
        val isVideoFile = video.mediaType == com.myvideolibrary.app.data.model.MediaType.VIDEO.id &&
            !video.isLinkOnly && video.localPath.isNotBlank() &&
            !video.localPath.startsWith("content://")
        // Animated quick preview — video files only (needs decodable frames).
        if (isVideoFile) popup.menu.add(0, 15, 7, getString(R.string.quick_preview))
        if (isVideoFile) popup.menu.add(0, 12, 7, getString(R.string.trim_menu))
        // Compress (HEVC) to reclaim space — downloaded video files only.
        if (isVideoFile) popup.menu.add(0, 13, 7, getString(R.string.compress_menu))
        // Open the original TikTok/YouTube page in its app or the browser.
        if (!video.sourceUrl.isNullOrBlank()) popup.menu.add(0, 8, 7, getString(R.string.open_source))
        // Image editor (crop / hide / text / OCR) only for image items.
        if (video.mediaType == com.myvideolibrary.app.data.model.MediaType.IMAGE.id) {
            popup.menu.add(0, 11, 8, getString(R.string.edit_image))
        }
        // Per-clip obscure toggle (works for video / image / audio alike).
        popup.menu.add(
            0, 17, 9,
            getString(if (video.isPrivate) R.string.unobscure_clip else R.string.obscure_clip)
        )
        popup.menu.add(0, 10, 9, getString(R.string.file_info))
        popup.menu.add(0, 6, 10, getString(R.string.delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { onVideoClick(video); true }
                17 -> { toggleObscure(video); true }
                2 -> { shareVideo(video); true }
                4 -> { promptSetCategory(video); true }
                5 -> { promptEditInfo(video); true }
                16 -> { promptEditTags(video); true }
                6 -> { confirmDeleteSingle(video); true }
                8 -> { openSource(video); true }
                9 -> { viewModel.toggleFavorite(video); true }
                14 -> { showAddToPlaylist(video); true }
                15 -> { showQuickPreview(video); true }
                10 -> { showFileInfo(video); true }
                12 -> {
                    startActivity(
                        com.myvideolibrary.app.ui.trim.TrimActivity.intent(
                            this, video.localPath, video.title
                        )
                    )
                    true
                }
                13 -> {
                    startActivity(
                        com.myvideolibrary.app.ui.compress.CompressActivity.intent(
                            this, video.id, video.localPath
                        )
                    )
                    true
                }
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

    /**
     * Animated "quick preview": extracts a handful of frames across the clip and
     * cycles through them in a dialog, giving a sense of the content without
     * opening the player. Frames are decoded off the main thread; the cycling
     * loop and every bitmap are torn down when the dialog is dismissed.
     */
    private fun showQuickPreview(video: VideoEntity) {
        val image = android.widget.ImageView(this).apply {
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            minimumHeight = (220 * resources.displayMetrics.density).toInt()
            setBackgroundColor(android.graphics.Color.BLACK)
        }
        val progress = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
        }
        val container = android.widget.FrameLayout(this).apply {
            addView(
                image,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                progress,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.CENTER
                )
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(video.title)
            .setView(container)
            .setPositiveButton(R.string.play) { _, _ -> onVideoClick(video) }
            .setNegativeButton(R.string.close, null)
            .create()

        val frames = mutableListOf<android.graphics.Bitmap>()
        // Cycle frames on the main thread; cancelled on dismiss so it can't leak.
        val cycleJob = lifecycleScope.launch {
            // extractPreviewFrames already does its decoding on Dispatchers.IO.
            val loaded = thumbnailGenerator.extractPreviewFrames(video.localPath)
            frames.addAll(loaded)
            progress.visibility = android.view.View.GONE
            if (frames.isEmpty()) {
                android.widget.Toast.makeText(
                    this@MainActivity, R.string.preview_failed, android.widget.Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
                return@launch
            }
            var i = 0
            while (true) {
                image.setImageBitmap(frames[i % frames.size])
                i++
                kotlinx.coroutines.delay(450)
            }
        }

        dialog.setOnDismissListener {
            cycleJob.cancel()
            // Detach before recycling so a redraw can't hit a recycled bitmap.
            image.setImageDrawable(null)
            frames.forEach { runCatching { it.recycle() } }
            frames.clear()
        }
        dialog.show()
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

    /** Edit a clip's tags as a comma-separated list. */
    private fun promptEditTags(video: VideoEntity) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            // Show current tags comma-joined for easy editing.
            setText(
                com.myvideolibrary.app.data.repository.TagFormat.split(video.tags)
                    .joinToString(", ")
            )
            hint = getString(R.string.tags_hint)
            setSingleLine(true)
        }
        container.addView(input)
        AlertDialog.Builder(this)
            .setTitle(R.string.edit_tags)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.setTags(video.id, input.text.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- Protected-category covers (obscured until the category password is entered) ----

    /**
     * True when a clip is in a protected category (VISIBLE or OBSCURED) that hasn't
     * been unlocked this session — so opening it, or its menu, must ask the password.
     */
    private fun isCategoryLocked(video: VideoEntity): Boolean {
        val cat = video.category?.trim()?.lowercase() ?: return false
        return cat in viewModel.uiState.value.lockedCategories &&
            !com.myvideolibrary.app.security.ProtectedCategoriesSession.isUnlocked(video.category)
    }

    /** Password EditText inside a padded container, matching the edit dialogs. */
    private fun passwordField(hintRes: Int): Pair<android.widget.LinearLayout, EditText> {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val input = EditText(this).apply {
            hint = getString(hintRes)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(input)
        return container to input
    }

    /** Prompts for a protected category's password; on success unlocks it this session. */
    private fun promptCategoryUnlock(category: String?, onSuccess: () -> Unit) {
        val name = category?.trim().orEmpty()
        if (name.isEmpty()) { onSuccess(); return }
        val store = viewModel.uiState.value.categoryPasswordsRaw
        val (container, input) = passwordField(R.string.enter_password)
        AlertDialog.Builder(this)
            .setTitle(R.string.locked_section_title)
            .setView(container)
            .setPositiveButton(R.string.unlock) { _, _ ->
                if (com.myvideolibrary.app.util.CategorySecurity.verify(store, name, input.text.toString())) {
                    com.myvideolibrary.app.security.ProtectedCategoriesSession.unlock(category)
                    refreshCovers()
                    onSuccess()
                } else {
                    Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Rebinds the grid so obscured covers re-evaluate after a category unlocks. */
    private fun refreshCovers() = adapter.refresh()

    // ---- Per-clip obscure ----

    /** True when this clip is individually obscured and not unlocked this session. */
    private fun isClipObscured(video: VideoEntity): Boolean =
        video.isPrivate && !com.myvideolibrary.app.security.ObscuredClipsSession.isUnlocked()

    /**
     * Turns per-clip obscure on/off. Enabling for the first time asks the user to set
     * a shared obscure password (stored only as a hash). The menu that reaches here is
     * already gated by [promptClipUnlock], so disabling needs no extra prompt.
     */
    private fun toggleObscure(video: VideoEntity) {
        if (video.isPrivate) {
            viewModel.setClipObscured(video.id, false)
            refreshCovers()
            return
        }
        val apply = {
            viewModel.setClipObscured(video.id, true)
            // Lock the session so the clip obscures at once — not only after the app
            // is backgrounded or the clip is opened once.
            com.myvideolibrary.app.security.ObscuredClipsSession.lockAll()
            refreshCovers()
        }
        if (viewModel.uiState.value.obscurePasswordHash.isNullOrEmpty()) {
            promptSetObscurePassword(apply)
        } else {
            apply()
        }
    }

    /** Prompts for the shared obscure password; on success reveals obscured clips. */
    private fun promptClipUnlock(onSuccess: () -> Unit) {
        val hash = viewModel.uiState.value.obscurePasswordHash
        if (hash.isNullOrEmpty()) { onSuccess(); return }
        val (container, input) = passwordField(R.string.enter_password)
        AlertDialog.Builder(this)
            .setTitle(R.string.obscured_clip_title)
            .setView(container)
            .setPositiveButton(R.string.unlock) { _, _ ->
                if (com.myvideolibrary.app.util.CategorySecurity.verifyHash(hash, input.text.toString())) {
                    com.myvideolibrary.app.security.ObscuredClipsSession.unlock()
                    refreshCovers()
                    onSuccess()
                } else {
                    Toast.makeText(this, R.string.wrong_password, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** First-time setup of the shared obscure password (min 4 chars). */
    private fun promptSetObscurePassword(onSet: () -> Unit) {
        val (container, input) = passwordField(R.string.set_obscure_password)
        AlertDialog.Builder(this)
            .setTitle(R.string.set_obscure_password)
            .setMessage(R.string.set_obscure_password_desc)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val pw = input.text.toString()
                if (pw.length < 4) {
                    Toast.makeText(this, R.string.password_too_short, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                viewModel.setObscurePassword(com.myvideolibrary.app.util.CategorySecurity.hashPassword(pw))
                onSet()
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

    override fun onRestart() {
        super.onRestart()
        // Returning here after another screen (e.g. the player) re-locks extra-private
        // covers, so a private clip is obscured again right after you finish with it.
        // onRestart fires only after the activity was stopped — not on dialogs, and
        // not on first launch — so an in-place unlock+play flow isn't cut short.
        var relocked = false
        if (com.myvideolibrary.app.security.ProtectedCategoriesSession.anyUnlocked()) {
            com.myvideolibrary.app.security.ProtectedCategoriesSession.lockAll()
            relocked = true
        }
        if (com.myvideolibrary.app.security.ObscuredClipsSession.isUnlocked()) {
            com.myvideolibrary.app.security.ObscuredClipsSession.lockAll()
            relocked = true
        }
        if (relocked) refreshCovers()
    }

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
        // Toolbar grid/list toggle shows the mode you'd switch TO.
        val gridNow = if (youtubeTab) youtubeGrid
        else viewModel.uiState.value.viewMode == LibraryViewMode.GRID
        menu.findItem(R.id.action_toggle_view)?.setIcon(
            if (gridNow) R.drawable.ic_view_list else R.drawable.ic_grid_view
        )
        // Show the tab you can switch TO; library-only actions are hidden on YouTube.
        menu.findItem(R.id.action_view_youtube)?.isVisible = !youtubeTab
        menu.findItem(R.id.action_view_library)?.isVisible = youtubeTab
        // Search and view-toggle work on both tabs; the rest are library-only.
        for (id in intArrayOf(
            R.id.action_filter, R.id.action_favorites, R.id.action_sort,
            R.id.action_manage_categories, R.id.action_stats,
            R.id.action_playlists, R.id.action_duplicates
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
                    invalidateOptionsMenu()
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
                manageCategoriesLauncher.launch(
                    com.myvideolibrary.app.ui.categories.CategoriesActivity.intent(this)
                )
                true
            }
            R.id.action_stats -> {
                startActivity(Intent(this, com.myvideolibrary.app.ui.stats.StatsActivity::class.java))
                true
            }
            R.id.action_duplicates -> {
                startActivity(com.myvideolibrary.app.ui.duplicates.DuplicatesActivity.intent(this))
                true
            }
            R.id.action_arrange -> {
                startActivity(com.myvideolibrary.app.ui.reorder.ReorderActivity.intent(this))
                true
            }
            R.id.action_playlists -> {
                startActivity(com.myvideolibrary.app.ui.playlists.PlaylistsActivity.intent(this))
                true
            }
            R.id.action_guide -> {
                startActivity(Intent(this, com.myvideolibrary.app.ui.help.HelpActivity::class.java))
                true
            }
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
            getString(R.string.sort_category),
            getString(R.string.sort_custom)
        )
        val orders = arrayOf(
            SortOrder.DATE_ADDED_DESC, SortOrder.DATE_ADDED_ASC,
            SortOrder.NAME_ASC, SortOrder.NAME_DESC,
            SortOrder.DURATION_DESC, SortOrder.DURATION_ASC,
            SortOrder.SIZE_DESC, SortOrder.SIZE_ASC,
            SortOrder.CATEGORY_ASC, SortOrder.CUSTOM
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
        const val GROUP_TAG = 5
        const val ID_TAG_PICK = 250
        const val GROUP_SAVED = 6
        const val ID_SEARCH_SAVE = 300
        const val ID_SEARCH_LIST = 301
    }

    override fun onBackPressed() {
        val state = viewModel.uiState.value
        when {
            youtubeTab -> showYouTubeTab(false) // back returns to the library tab
            state.selectionMode -> viewModel.clearSelection()
            // Back first returns to the full, unfiltered library.
            state.categoryFilters.isNotEmpty() -> viewModel.setCategoryFilters(emptySet())
            state.favoritesOnly -> viewModel.setFavoritesOnly(false)
            state.search.isNotEmpty() -> viewModel.setSearch("")
            else -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }
}
