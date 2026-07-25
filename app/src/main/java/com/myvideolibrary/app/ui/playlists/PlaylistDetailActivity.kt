package com.myvideolibrary.app.ui.playlists

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.databinding.ActivityPlaylistDetailBinding
import com.myvideolibrary.app.ui.player.PlayerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaylistDetailBinding
    private val viewModel: PlaylistDetailViewModel by viewModels()
    private lateinit var adapter: PlaylistVideoAdapter

    private var current: List<VideoEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getLongExtra(EXTRA_ID, -1)
        if (id <= 0) { finish(); return }
        binding.toolbar.title = intent.getStringExtra(EXTRA_NAME).orEmpty()
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = PlaylistVideoAdapter(onPlay = ::play, onMenu = ::showMenu)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.playAllButton.setOnClickListener { if (current.isNotEmpty()) playAt(current.first()) }

        viewModel.setPlaylist(id)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.videos.collectLatest { list ->
                    current = list
                    adapter.submitList(list)
                    binding.emptyText.isVisible = list.isEmpty()
                    binding.playAllButton.isVisible = list.isNotEmpty()
                }
            }
        }
    }

    private fun play(video: VideoEntity) = playAt(video)

    /** Plays the whole playlist as a queue, starting at [start]. */
    private fun playAt(start: VideoEntity) {
        val ids = current.map { it.id }.toLongArray()
        val index = current.indexOfFirst { it.id == start.id }.coerceAtLeast(0)
        startActivity(PlayerActivity.playlistIntent(this, ids, index))
    }

    private fun showMenu(video: VideoEntity, anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(R.string.play))
        popup.menu.add(0, 2, 1, getString(R.string.playlist_remove_video))
        popup.setOnMenuItemClickListener { m ->
            when (m.itemId) {
                1 -> { play(video); true }
                2 -> {
                    viewModel.removeVideo(video.id)
                    Toast.makeText(this, R.string.playlist_removed, Toast.LENGTH_SHORT).show()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    companion object {
        private const val EXTRA_ID = "extra_playlist_id"
        private const val EXTRA_NAME = "extra_playlist_name"

        fun intent(context: Context, id: Long, name: String): Intent =
            Intent(context, PlaylistDetailActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_NAME, name)
    }
}
