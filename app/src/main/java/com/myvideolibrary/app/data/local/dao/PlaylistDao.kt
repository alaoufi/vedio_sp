package com.myvideolibrary.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.myvideolibrary.app.data.local.entity.PlaylistEntity
import com.myvideolibrary.app.data.local.entity.PlaylistVideoEntity
import com.myvideolibrary.app.data.local.entity.PlaylistWithCount
import com.myvideolibrary.app.data.local.entity.VideoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_videos WHERE playlist_id = :id")
    suspend fun clearVideos(id: Long)

    @Query(
        "SELECT p.id AS id, p.name AS name, p.created_date AS created_date, " +
            "(SELECT COUNT(*) FROM playlist_videos pv WHERE pv.playlist_id = p.id) AS videoCount " +
            "FROM playlists p ORDER BY p.created_date DESC"
    )
    fun observePlaylists(): Flow<List<PlaylistWithCount>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_videos WHERE playlist_id = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addVideo(ref: PlaylistVideoEntity)

    @Query("DELETE FROM playlist_videos WHERE playlist_id = :playlistId AND video_id = :videoId")
    suspend fun removeVideo(playlistId: Long, videoId: Long)

    @Query(
        "SELECT v.* FROM videos v " +
            "INNER JOIN playlist_videos pv ON pv.video_id = v.id " +
            "WHERE pv.playlist_id = :playlistId ORDER BY pv.position ASC"
    )
    fun observeVideos(playlistId: Long): Flow<List<VideoEntity>>

    @Query(
        "SELECT v.id FROM videos v " +
            "INNER JOIN playlist_videos pv ON pv.video_id = v.id " +
            "WHERE pv.playlist_id = :playlistId ORDER BY pv.position ASC"
    )
    suspend fun videoIds(playlistId: Long): List<Long>
}
