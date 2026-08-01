package com.myvideolibrary.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvideolibrary.app.data.local.dao.CategoryCount
import com.myvideolibrary.app.data.local.dao.SourceCount
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.repository.VideoRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Everything the statistics screen shows about the library. */
data class StatsUiState(
    val videoCount: Int = 0,
    val totalSize: Long = 0,
    val totalDuration: Long = 0,
    val totalPlays: Int = 0,
    val categoryCounts: List<CategoryCount> = emptyList(),
    val sourceCounts: List<SourceCount> = emptyList(),
    val mostPlayed: List<VideoEntity> = emptyList(),
    val recentlyPlayed: List<VideoEntity> = emptyList()
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    repository: VideoRepository
) : ViewModel() {

    private val totals = combine(
        repository.observeCount(),
        repository.observeTotalSize(),
        repository.observeTotalDuration(),
        repository.observeTotalPlays()
    ) { count, size, duration, plays -> Totals(count, size, duration, plays) }

    private val lists = combine(
        repository.observeCategoryCounts(),
        repository.observeSourceCounts(),
        repository.observeMostPlayed(5),
        repository.observeRecentlyPlayed(5)
    ) { categories, sources, mostPlayed, recent -> Lists(categories, sources, mostPlayed, recent) }

    val state: StateFlow<StatsUiState> = combine(totals, lists) { t, l ->
        StatsUiState(
            videoCount = t.count,
            totalSize = t.size,
            totalDuration = t.duration,
            totalPlays = t.plays,
            categoryCounts = l.categories,
            sourceCounts = l.sources,
            mostPlayed = l.mostPlayed,
            recentlyPlayed = l.recent
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsUiState())

    private data class Totals(val count: Int, val size: Long, val duration: Long, val plays: Int)
    private data class Lists(
        val categories: List<CategoryCount>,
        val sources: List<SourceCount>,
        val mostPlayed: List<VideoEntity>,
        val recent: List<VideoEntity>
    )
}
