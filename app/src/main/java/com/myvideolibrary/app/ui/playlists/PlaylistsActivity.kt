package com.myvideolibrary.app.ui.playlists

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.PlaylistWithCount
import com.myvideolibrary.app.databinding.ActivityPlaylistsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PlaylistsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistsBinding
    private val viewModel: PlaylistsViewModel by viewModels()
    private lateinit var adapter: PlaylistAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = PlaylistAdapter(onOpen = ::openPlaylist, onMenu = ::showMenu)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.fabCreate.setOnClickListener { promptCreate() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.playlists.collectLatest { list ->
                    adapter.submitList(list)
                    binding.emptyText.isVisible = list.isEmpty()
                }
            }
        }
    }

    private fun openPlaylist(item: PlaylistWithCount) {
        startActivity(PlaylistDetailActivity.intent(this, item.id, item.name))
    }

    private fun promptCreate() {
        val input = EditText(this).apply { hint = getString(R.string.playlist_name_hint) }
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_new)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ -> viewModel.create(input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showMenu(item: PlaylistWithCount, anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.rename))
        popup.menu.add(0, 2, 1, getString(R.string.delete))
        popup.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                1 -> { promptRename(item); true }
                2 -> { confirmDelete(item); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun promptRename(item: PlaylistWithCount) {
        val input = EditText(this).apply { setText(item.name) }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ -> viewModel.rename(item.id, input.text.toString()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(item: PlaylistWithCount) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(getString(R.string.playlist_delete_confirm, item.name))
            .setPositiveButton(R.string.delete) { _, _ -> viewModel.delete(item.id) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, PlaylistsActivity::class.java)
    }
}
