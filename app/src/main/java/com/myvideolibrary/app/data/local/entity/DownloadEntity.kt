package com.myvideolibrary.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A download job. Rows persist across app restarts so the queue, history and
 * resumable state survive process death. Consumed by the download manager
 * (implemented in a later phase); defined here so the schema is complete.
 */
@Entity(
    tableName = "downloads",
    indices = [Index(value = ["status"]), Index(value = ["download_date"])]
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Links to a [VideoEntity] once the download completes. Null while in flight. */
    @ColumnInfo(name = "video_id")
    val videoId: Long? = null,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "source")
    val source: String,

    @ColumnInfo(name = "source_url")
    val sourceUrl: String,

    /** Remote direct media URL resolved by the provider. */
    @ColumnInfo(name = "download_url")
    val downloadUrl: String? = null,

    @ColumnInfo(name = "thumbnail_url")
    val thumbnailUrl: String? = null,

    /** Destination file path inside app storage. */
    @ColumnInfo(name = "dest_path")
    val destPath: String? = null,

    /** [com.myvideolibrary.app.data.model.DownloadStatus.id]. */
    @ColumnInfo(name = "status")
    val status: String,

    /** 0..100 */
    @ColumnInfo(name = "progress")
    val progress: Int = 0,

    @ColumnInfo(name = "downloaded_bytes")
    val downloadedBytes: Long = 0,

    @ColumnInfo(name = "total_bytes")
    val totalBytes: Long = 0,

    /** Bytes per second at last sample. */
    @ColumnInfo(name = "download_speed")
    val downloadSpeed: Long = 0,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "download_date")
    val downloadDate: Long
)
