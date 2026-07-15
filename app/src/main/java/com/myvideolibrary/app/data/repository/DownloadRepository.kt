package com.myvideolibrary.app.data.repository

import com.myvideolibrary.app.data.local.dao.DownloadDao
import com.myvideolibrary.app.data.local.entity.DownloadEntity
import com.myvideolibrary.app.data.model.DownloadStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface DownloadRepository {
    fun observeAll(): Flow<List<DownloadEntity>>
    fun observeActive(): Flow<List<DownloadEntity>>
    fun observe(id: Long): Flow<DownloadEntity?>
    suspend fun get(id: Long): DownloadEntity?
    suspend fun create(download: DownloadEntity): Long
    suspend fun update(download: DownloadEntity)
    suspend fun updateProgress(
        id: Long,
        status: DownloadStatus,
        progress: Int,
        downloaded: Long,
        total: Long,
        speed: Long
    )
    suspend fun setStatus(id: Long, status: DownloadStatus, error: String? = null)
    suspend fun delete(download: DownloadEntity)
    suspend fun clearCompleted()
    suspend fun queued(): List<DownloadEntity>
}

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    private val downloadDao: DownloadDao
) : DownloadRepository {

    override fun observeAll(): Flow<List<DownloadEntity>> = downloadDao.observeAll()

    override fun observeActive(): Flow<List<DownloadEntity>> =
        downloadDao.observeByStatuses(
            listOf(
                DownloadStatus.WAITING.id,
                DownloadStatus.DOWNLOADING.id,
                DownloadStatus.PAUSED.id
            )
        )

    override fun observe(id: Long): Flow<DownloadEntity?> = downloadDao.observeById(id)

    override suspend fun get(id: Long): DownloadEntity? = downloadDao.getById(id)

    override suspend fun create(download: DownloadEntity): Long = downloadDao.insert(download)

    override suspend fun update(download: DownloadEntity) = downloadDao.update(download)

    override suspend fun updateProgress(
        id: Long,
        status: DownloadStatus,
        progress: Int,
        downloaded: Long,
        total: Long,
        speed: Long
    ) = downloadDao.updateProgress(id, status.id, progress, downloaded, total, speed)

    override suspend fun setStatus(id: Long, status: DownloadStatus, error: String?) =
        downloadDao.updateStatus(id, status.id, error)

    override suspend fun delete(download: DownloadEntity) = downloadDao.delete(download)

    override suspend fun clearCompleted() =
        downloadDao.clearByStatus(DownloadStatus.COMPLETED.id)

    override suspend fun queued(): List<DownloadEntity> =
        downloadDao.getByStatus(DownloadStatus.WAITING.id)
}
