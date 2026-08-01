package com.myvideolibrary.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.databinding.ActivityStatsBinding
import com.myvideolibrary.app.util.Formatters
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** A read-only dashboard of library statistics. */
@AndroidEntryPoint
class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding
    private val viewModel: StatsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.tileVideos.label.setText(R.string.stat_videos)
        binding.tileSize.label.setText(R.string.stat_size)
        binding.tileDuration.label.setText(R.string.stat_duration)
        binding.tilePlays.label.setText(R.string.stat_plays)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { render(it) }
            }
        }
    }

    private fun render(s: StatsUiState) {
        binding.tileVideos.value.text = s.videoCount.toString()
        binding.tileSize.value.text = Formatters.fileSize(s.totalSize)
        binding.tileDuration.value.text = Formatters.duration(s.totalDuration)
        binding.tilePlays.value.text = s.totalPlays.toString()

        fillVideos(binding.mostPlayedContainer, s.mostPlayed) {
            getString(R.string.stat_play_count, it.playCount)
        }
        fillVideos(binding.recentContainer, s.recentlyPlayed) {
            Formatters.duration(it.duration)
        }
        fillRows(binding.categoriesContainer, s.categoryCounts.map {
            (it.category ?: "—") to it.count.toString()
        })
        fillRows(binding.sourcesContainer, s.sourceCounts.map {
            sourceLabel(it.source) to it.count.toString()
        })
    }

    private fun fillVideos(
        container: LinearLayout,
        videos: List<VideoEntity>,
        value: (VideoEntity) -> String
    ) {
        fillRows(container, videos.map { it.title to value(it) })
    }

    private fun fillRows(container: LinearLayout, rows: List<Pair<String, String>>) {
        container.removeAllViews()
        if (rows.isEmpty()) {
            val empty = TextView(this).apply {
                setText(R.string.stat_empty)
                setPadding(24, 16, 24, 16)
                alpha = 0.6f
            }
            container.addView(empty)
            return
        }
        val inflater = LayoutInflater.from(this)
        rows.forEach { (name, value) ->
            val row = inflater.inflate(R.layout.item_stat_row, container, false)
            row.findViewById<TextView>(R.id.name).text = name
            row.findViewById<TextView>(R.id.value).text = value
            container.addView(row)
        }
    }

    private fun sourceLabel(id: String): String = when (VideoSource.fromId(id)) {
        VideoSource.TIKTOK -> getString(R.string.source_tiktok)
        VideoSource.YOUTUBE -> getString(R.string.source_youtube)
        VideoSource.INSTAGRAM -> getString(R.string.source_instagram)
        VideoSource.SNAPCHAT -> "Snapchat"
        VideoSource.LOCAL_IMPORT -> getString(R.string.source_device)
        VideoSource.OTHER -> getString(R.string.source_other)
    }
}
