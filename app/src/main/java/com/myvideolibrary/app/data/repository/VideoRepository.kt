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

    suspend fun rename(id: Long, title: String)

    suspend fun moveToFolder(ids: List<Long>, folderId: Long?)

    suspend fun recordPlayback(id: Long, position: Long, countAsPlay: Boolean)

    fun observeCount(): Flow<Int>

    fun observeTotalSize(): Flow<Long>

    fun observeFavorites(): Flow<List<VideoEntity>>

    fun observeRecentlyPlayed(limit: Int): Flow<List<VideoEntity>>

    /** Groups of videos sharing a content fingerprint, i.e. likely duplicates. */
    suspend fun findDuplicates(): List<List<VideoEntity>>

    suspend fun existsByPath(path: String): Boolean
}
