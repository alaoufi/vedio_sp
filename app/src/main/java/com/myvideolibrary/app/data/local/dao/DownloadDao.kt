package com.myvideolibrary.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.myvideolibrary.app.data.local.entity.DownloadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(download: DownloadEntity): Long

    @Update
    suspend fun update(download: DownloadEntity)

    @Delete
    suspend fun delete(download: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun getById(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observeById(id: Long): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads ORDER BY download_date DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status IN (:statuses) ORDER BY download_date ASC")
    fun observeByStatuses(statuses: List<String>): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY download_date ASC")
    suspend fun getByStatus(status: String): List<DownloadEntity>

    @Query(
        "UPDATE downloads SET status = :status, progress = :progress, " +
            "downloaded_bytes = :downloaded, total_bytes = :total, " +
            "download_speed = :speed WHERE id = :id"
    )
    suspend fun updateProgress(
        id: Long,
        status: String,
        progress: Int,
        downloaded: Long,
        total: Long,
        speed: Long
    )

    @Query("UPDATE downloads SET status = :status, error_message = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, error: String?)

    @Query("DELETE FROM downloads WHERE status = :status")
    suspend fun clearByStatus(status: String)
}
