package com.myvideolibrary.app.data.repository

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.myvideolibrary.app.data.local.dao.VideoDao
import com.myvideolibrary.app.data.local.entity.VideoEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRepositoryImpl @Inject constructor(
    private val videoDao: VideoDao
) : VideoRepository {

    override fun pagedVideos(query: LibraryQuery): Flow<PagingData<VideoEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = PAGE_SIZE,
                prefetchDistance = PAGE_SIZE,
                enablePlaceholders = false,
                initialLoadSize = PAGE_SIZE * 2
            ),
            pagingSourceFactory = { videoDao.pagingSource(query.toSupportQuery()) }
        ).flow

    override fun observeVideo(id: Long): Flow<VideoEntity?> = videoDao.observeById(id)

    override suspend fun getVideo(id: Long): VideoEntity? = videoDao.getById(id)

    override suspend fun addVideo(video: VideoEntity): Long = videoDao.insert(video)

    override suspend fun addVideos(videos: List<VideoEntity>): List<Long> =
        videoDao.insertAll(videos)

    override suspend fun updateVideo(video: VideoEntity) = videoDao.update(video)

    override suspend fun deleteVideos(ids: List<Long>, alsoDeleteFiles: Boolean) {
        if (alsoDeleteFiles) {
            ids.forEach { id ->
                videoDao.getById(id)?.let { video ->
                    runCatching {
                        // Only delete files we own inside app storage; never touch
                        // arbitrary MediaStore content the user imported by reference.
                        if (!video.localPath.startsWith("content://")) {
                            File(video.localPath).takeIf { it.exists() }?.delete()
                        }
                        video.thumbnailPath?.let { File(it).takeIf(File::exists)?.delete() }
                    }
                }
            }
        }
        videoDao.deleteByIds(ids)
    }

    override suspend fun setFavorite(id: Long, favorite: Boolean) =
        videoDao.setFavorite(id, favorite)

    override suspend fun setLocked(id: Long, locked: Boolean) =
        videoDao.setLocked(id, locked)

    override suspend fun rename(id: Long, title: String) = videoDao.rename(id, title)

    override suspend fun setCategory(ids: List<Long>, category: String?) =
        videoDao.setCategory(ids, category?.trim()?.takeIf { it.isNotEmpty() })

    override fun observeCategories(): Flow<List<String>> = videoDao.observeCategories()

    override suspend fun moveToFolder(ids: List<Long>, folderId: Long?) =
        videoDao.moveToFolder(ids, folderId)

    override suspend fun recordPlayback(id: Long, position: Long, countAsPlay: Boolean) {
        videoDao.updatePlayback(
            id = id,
            position = position,
            playedAt = System.currentTimeMillis(),
            incrementPlays = if (countAsPlay) 1 else 0
        )
    }

    override fun observeCount(): Flow<Int> = videoDao.observeCount()

    override fun observeTotalSize(): Flow<Long> = videoDao.observeTotalSize()

    override fun observeFavorites(): Flow<List<VideoEntity>> = videoDao.observeFavorites()

    override fun observeRecentlyPlayed(limit: Int): Flow<List<VideoEntity>> =
        videoDao.observeRecentlyPlayed(limit)

    override suspend fun findDuplicates(): List<List<VideoEntity>> =
        videoDao.findDuplicateGroups().mapNotNull { group ->
            videoDao.getByContentHash(group.contentHash).takeIf { it.size > 1 }
        }

    override suspend fun existsByPath(path: String): Boolean =
        videoDao.getByLocalPath(path) != null

    companion object {
        private const val PAGE_SIZE = 30
    }
}
