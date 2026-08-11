package com.myvideolibrary.app.data.repository

import androidx.paging.PagingData
import com.myvideolibrary.app.data.local.dao.DuplicateGroup
import com.myvideolibrary.app.data.local.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

/** Single access point for video data. Backed by the encrypted Room database. */
interface VideoRepository {

    fun pagedVideos(query: LibraryQuery): Flow<PagingData<VideoEntity>>

    fun observeVideo(id: Long): Flow<VideoEntity?>

    suspend fun getVideo(id: Long): VideoEntity?

    suspend fun addVideo(video: VideoEntity): Long

    suspend fun addVideos(videos: List<VideoEntity>): List<Long>

    suspend fun updateVideo(video: VideoEntity)

    suspend fun deleteVideos(ids: List<Long>, alsoDeleteFiles: Boolean)

    suspend fun setFavorite(id: Long, favorite: Boolean)

    suspend fun setLocked(id: Long, locked: Boolean)

    suspend fun setPrivate(id: Long, isPrivate: Boolean)

    suspend fun rename(id: Long, title: String)

    suspend fun updateInfo(id: Long, title: String, description: String?)

    suspend fun setCategory(ids: List<Long>, category: String?)

    suspend fun renameCategory(oldName: String, newName: String)

    suspend fun deleteCategory(name: String)

    fun observeCategories(): Flow<List<String>>

    /** Sets a video's tags from a raw user string (normalised to trimmed tokens). */
    suspend fun setTags(id: Long, rawTags: String?)

    /** Distinct tags currently in use across the library, alphabetised. */
    fun observeTags(): Flow<List<String>>

    suspend fun moveToFolder(ids: List<Long>, folderId: Long?)

    suspend fun recordPlayback(id: Long, position: Long, countAsPlay: Boolean)

    fun observeCount(): Flow<Int>

    fun observeTotalSize(): Flow<Long>

    fun observeTotalDuration(): Flow<Long>

    fun observeTotalPlays(): Flow<Int>

    fun observeCategoryCounts(): Flow<List<com.myvideolibrary.app.data.local.dao.CategoryCount>>

    fun observeSourceCounts(): Flow<List<com.myvideolibrary.app.data.local.dao.SourceCount>>

    fun observeMostPlayed(limit: Int): Flow<List<VideoEntity>>

    fun observeFavorites(): Flow<List<VideoEntity>>

    fun observeRecentlyPlayed(limit: Int): Flow<List<VideoEntity>>

    /** Groups of videos sharing a content fingerprint, i.e. likely duplicates. */
    suspend fun findDuplicates(): List<List<VideoEntity>>

    suspend fun existsByPath(path: String): Boolean

    suspend fun allVideos(): List<VideoEntity>
}
