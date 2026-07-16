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
import com.google.android.material.chip.Chip
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

    @javax.inject.Inject lateinit var securityManager: SecurityManager

    private lateinit var adapter: VideoPagingAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotPolicy(securityManager)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupRecycler()
        setupSearch()
        setupSourceChips()
        setupFab()
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

    private fun setupRecycler() {
        adapter = VideoPagingAdapter(
            viewMode = LibraryViewMode.GRID,
            onClick = ::onVideoClick,
            onLongClick = { viewModel.enterSelection(it.id) },
            onFavorite = { viewModel.toggleFavorite(it) },
            onMenu = ::showVideoMenu
        )
        binding.recyclerView.adapter = adapter

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

    private fun applyLayoutManager(mode: LibraryViewMode) {
        binding.recyclerView.layoutManager = when (mode) {
            LibraryViewMode.GRID -> GridLayoutManager(this, gridSpanCount())
            LibraryViewMode.LIST -> LinearLayoutManager(this)
        }
        adapter.setViewMode(mode)
    }

    private fun gridSpanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 180).coerceAtLeast(2)
    }

    private fun setupSearch() {
        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                viewModel.setSearch(s?.toString().orEmpty())
            }
        })
    }

    private val sourceChipModels = listOf(
        SourceFilter.ALL to R.string.filter_all,
        SourceFilter.TIKTOK to R.string.source_tiktok,
        SourceFilter.YOUTUBE to R.string.source_youtube,
        SourceFilter.OTHER to R.string.source_other
    )

    private fun setupSourceChips() {
        binding.sourceChips.removeAllViews()
        sourceChipModels.forEach { (filter, labelRes) ->
            val chip = com.google.android.material.chip.Chip(this).apply {
                text = getString(labelRes)
                isCheckable = true
                isChecked = filter == SourceFilter.ALL
                tag = filter
                setOnClickListener { viewModel.setSourceFilter(filter) }
            }
            binding.sourceChips.addView(chip)
        }
    }

    private fun renderSourceChips(state: LibraryUiState) {
        for (i in 0 until binding.sourceChips.childCount) {
            val chip = binding.sourceChips.getChildAt(i)
                    as? com.google.android.material.chip.Chip ?: continue
            chip.isChecked = chip.tag == state.sourceFilter
        }
    }

    private fun setupFab() {
        binding.fabImport.setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    applyLayoutManager(state.viewMode)
                    adapter.setSelection(state.selectionMode, state.selectedIds)
                    renderStats(state)
                    renderSourceChips(state)
                    renderFolderChips(state)
                    renderSelectionBar(state)
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

    private fun renderFolderChips(state: LibraryUiState) {
        // Hide the folder row entirely until the user actually has folders.
        binding.folderChipsScroll.isVisible = state.folders.isNotEmpty()
        val group = binding.folderChips
        // Rebuild only when the folder set changes to avoid flicker.
        if (group.tag == state.folders.map { it.id }) return
        group.tag = state.folders.map { it.id }
        group.removeAllViews()

        val allChip = Chip(this).apply {
            text = getString(R.string.filter_all)
            isCheckable = true
            isChecked = state.folderId == null
            setOnClickListener { viewModel.setFolderFilter(null) }
        }
        group.addView(allChip)

        state.folders.forEach { folder ->
            val chip = Chip(this).apply {
                text = folder.name
                isCheckable = true
                isChecked = state.folderId == folder.id
                setOnClickListener { viewModel.setFolderFilter(folder.id) }
            }
            group.addView(chip)
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
            binding.selectionMove.setOnClickListener { promptMoveSelected(state) }
            binding.selectionClose.setOnClickListener { viewModel.clearSelection() }
        }
    }

    private fun onVideoClick(video: VideoEntity) {
        val state = viewModel.uiState.value
        if (state.selectionMode) {
            viewModel.toggleSelected(video.id)
        } else if (video.isLocked) {
            android.widget.Toast.makeText(this, R.string.video_locked, android.widget.Toast.LENGTH_SHORT).show()
        } else {
            startActivity(PlayerActivity.intent(this, video.id))
        }
    }

    private fun showVideoMenu(video: VideoEntity, anchor: android.view.View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.play))
        popup.menu.add(0, 2, 1, getString(R.string.action_share))
        popup.menu.add(
            0, 3, 2,
            getString(if (video.isLocked) R.string.unlock_video else R.string.lock_video)
        )
        popup.menu.add(0, 4, 3, getString(R.string.rename))
        popup.menu.add(0, 5, 4, getString(R.string.delete))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { onVideoClick(video); true }
                2 -> { shareVideo(video); true }
                3 -> { viewModel.toggleLock(video); true }
                4 -> { promptRenameVideo(video); true }
                5 -> { confirmDeleteSingle(video); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun shareVideo(video: VideoEntity) {
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

    private fun promptRenameVideo(video: VideoEntity) {
        val input = EditText(this).apply { setText(video.title) }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) viewModel.rename(video.id, name)
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

    private fun promptMoveSelected(state: LibraryUiState) {
        val names = listOf(getString(R.string.no_folder)) + state.folders.map { it.name }
        AlertDialog.Builder(this)
            .setTitle(R.string.move_to_folder)
            .setItems(names.toTypedArray()) { _, which ->
                val folderId = if (which == 0) null else state.folders[which - 1].id
                viewModel.moveSelectedToFolder(folderId)
            }
            .show()
    }

    // ---- Options menu ----

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val favoritesOnly = viewModel.uiState.value.favoritesOnly
        menu.findItem(R.id.action_favorites)?.setIcon(
            if (favoritesOnly) R.drawable.ic_favorite else R.drawable.ic_favorite_border
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_view -> { viewModel.toggleViewMode(); true }
            R.id.action_sort -> { showSortDialog(); true }
            R.id.action_favorites -> {
                viewModel.setFavoritesOnly(!viewModel.uiState.value.favoritesOnly)
                true
            }
            R.id.action_new_folder -> { promptNewFolder(); true }
            R.id.action_downloads -> {
                startActivity(Intent(this, DownloadsActivity::class.java))
                true
            }
            R.id.action_add_download -> {
                startActivity(AddDownloadActivity.intent(this))
                true
            }
            R.id.action_search_online -> {
                startActivity(Intent(this, com.myvideolibrary.app.ui.search.SearchActivity::class.java))
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
            getString(R.string.sort_size_small)
        )
        val orders = arrayOf(
            SortOrder.DATE_ADDED_DESC, SortOrder.DATE_ADDED_ASC,
            SortOrder.NAME_ASC, SortOrder.NAME_DESC,
            SortOrder.DURATION_DESC, SortOrder.DURATION_ASC,
            SortOrder.SIZE_DESC, SortOrder.SIZE_ASC
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

    private fun promptNewFolder() {
        val input = EditText(this).apply { hint = getString(R.string.folder_name_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_folder)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) viewModel.createFolder(name)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onBackPressed() {
        if (viewModel.uiState.value.selectionMode) {
            viewModel.clearSelection()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }
}
