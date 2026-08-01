package com.myvideolibrary.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-created playlist. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "created_date") val createdDate: Long
)

/** Membership of a video in a playlist, with its ordering position. */
@Entity(
    tableName = "playlist_videos",
    indices = [
        Index("playlist_id"),
        Index(value = ["playlist_id", "video_id"], unique = true)
    ]
)
data class PlaylistVideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "playlist_id") val playlistId: Long,
    @ColumnInfo(name = "video_id") val videoId: Long,
    @ColumnInfo(name = "position") val position: Int
)

/** A playlist plus how many videos it holds, for the list screen. */
data class PlaylistWithCount(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "created_date") val createdDate: Long,
    val videoCount: Int
)
