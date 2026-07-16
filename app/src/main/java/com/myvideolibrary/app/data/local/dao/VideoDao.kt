package com.myvideolibrary.app.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.myvideolibrary.app.data.local.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

/** Aggregate row for a folder and how many videos it holds. */
data class FolderVideoCount(
    val folderId: Long,
    val count: Int
)

/** A group of videos that share the same [VideoEntity.contentHash]. */
data class DuplicateGroup(
    val contentHash: String,
    val copies: Int
)

@Dao
interface VideoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(video: VideoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(videos: List<VideoEntity>): List<Long>

    @Update
    suspend fun update(video: VideoEntity)

    @Delete
    suspend fun delete(video: VideoEntity)

    @Query("DELETE FROM videos WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun getById(id: Long): VideoEntity?

    @Query("SELECT * FROM videos WHERE id = :id")
    fun observeById(id: Long): Flow<VideoEntity?>

    @Query("SELECT * FROM videos WHERE local_path = :path LIMIT 1")
    suspend fun getByLocalPath(path: String): VideoEntity?

    /** All videos in one shot (used by backup export). */
    @Query("SELECT * FROM videos ORDER BY created_date ASC")
    suspend fun getAllOnce(): List<VideoEntity>

    /**
     * Flexible, sorted, filtered library query backing Paging 3. The query is
     * assembled in the repository so sort order and filters can vary at runtime.
     */
    @RawQuery(observedEntities = [VideoEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, VideoEntity>

    // ---- Metadata mutations ----

    @Query("UPDATE videos SET is_favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("UPDATE videos SET is_locked = :locked WHERE id = :id")
    suspend fun setLocked(id: Long, locked: Boolean)

    @Query("UPDATE videos SET folder_id = :folderId WHERE id IN (:ids)")
    suspend fun moveToFolder(ids: List<Long>, folderId: Long?)

    @Query("UPDATE videos SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query(
        "UPDATE videos SET last_played_position = :position, " +
            "last_played_date = :playedAt, play_count = play_count + :incrementPlays " +
            "WHERE id = :id"
    )
    suspend fun updatePlayback(
        id: Long,
        position: Long,
        playedAt: Long,
        incrementPlays: Int
    )

    // ---- Aggregates ----

    @Query("SELECT COUNT(*) FROM videos")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(file_size), 0) FROM videos")
    fun observeTotalSize(): Flow<Long>

    @Query("SELECT folder_id AS folderId, COUNT(*) AS count FROM videos WHERE folder_id IS NOT NULL GROUP BY folder_id")
    fun observeFolderCounts(): Flow<List<FolderVideoCount>>

    @Query("SELECT * FROM videos WHERE is_favorite = 1 ORDER BY created_date DESC")
    fun observeFavorites(): Flow<List<VideoEntity>>

    @Query("SELECT * FROM videos WHERE last_played_date IS NOT NULL ORDER BY last_played_date DESC LIMIT :limit")
    fun observeRecentlyPlayed(limit: Int): Flow<List<VideoEntity>>

    // ---- Duplicate detection ----

    @Query(
        "SELECT content_hash AS contentHash, COUNT(*) AS copies FROM videos " +
            "WHERE content_hash IS NOT NULL GROUP BY content_hash HAVING COUNT(*) > 1"
    )
    suspend fun findDuplicateGroups(): List<DuplicateGroup>

    @Query("SELECT * FROM videos WHERE content_hash = :hash ORDER BY created_date ASC")
    suspend fun getByContentHash(hash: String): List<VideoEntity>
}
