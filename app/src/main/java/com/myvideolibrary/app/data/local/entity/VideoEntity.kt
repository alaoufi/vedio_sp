package com.myvideolibrary.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single video in the library. This is the central entity.
 *
 * [tags] are stored as a comma-separated string via a type converter to keep the
 * schema simple for a personal, single-user database. [localPath] points at a file
 * inside the app's private storage (or a MediaStore-backed content URI string).
 */
@Entity(
    tableName = "videos",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["folder_id"]),
        Index(value = ["is_favorite"]),
        Index(value = ["created_date"]),
        Index(value = ["content_hash"]),
        // Hot columns for the library query filters, sorts and the stats screen.
        Index(value = ["is_locked"]),
        Index(value = ["category"]),
        Index(value = ["source"]),
        Index(value = ["media_type"]),
        Index(value = ["play_count"]),
        Index(value = ["last_played_date"])
    ]
)
data class VideoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "description")
    val description: String? = null,

    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String? = null,

    /** Absolute file path or content:// URI string of the playable media. */
    @ColumnInfo(name = "local_path")
    val localPath: String,

    /** Provider id ([com.myvideolibrary.app.data.model.VideoSource.id]). */
    @ColumnInfo(name = "source")
    val source: String,

    /** "video" (default), "audio", or "image" — see [com.myvideolibrary.app.data.model.MediaType]. */
    @ColumnInfo(name = "media_type")
    val mediaType: String = "video",

    /** Original remote URL, if imported from a provider. */
    @ColumnInfo(name = "source_url")
    val sourceUrl: String? = null,

    @ColumnInfo(name = "folder_id")
    val folderId: Long? = null,

    @ColumnInfo(name = "category")
    val category: String? = null,

    /** Comma-separated tags. */
    @ColumnInfo(name = "tags")
    val tags: String? = null,

    /** Duration in milliseconds. */
    @ColumnInfo(name = "duration")
    val duration: Long = 0,

    /** File size in bytes. */
    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0,

    /** Human-readable quality label, e.g. "1080p". */
    @ColumnInfo(name = "quality")
    val quality: String? = null,

    @ColumnInfo(name = "width")
    val width: Int = 0,

    @ColumnInfo(name = "height")
    val height: Int = 0,

    /** Epoch millis when the video was added to the library. */
    @ColumnInfo(name = "created_date")
    val createdDate: Long,

    /** Resume position in milliseconds. */
    @ColumnInfo(name = "last_played_position")
    val lastPlayedPosition: Long = 0,

    /** Epoch millis of the last playback, used for "recently played". */
    @ColumnInfo(name = "last_played_date")
    val lastPlayedDate: Long? = null,

    @ColumnInfo(name = "is_favorite")
    val isFavorite: Boolean = false,

    /** When true the video is locked and won't open without the app lock. */
    @ColumnInfo(name = "is_locked")
    val isLocked: Boolean = false,

    /**
     * When true this row is a saved *link* only — no local file. It streams from
     * its [sourceUrl] on playback and can be downloaded on demand later.
     */
    @ColumnInfo(name = "is_link_only")
    val isLinkOnly: Boolean = false,

    @ColumnInfo(name = "play_count")
    val playCount: Int = 0,

    /**
     * Cheap fingerprint used for duplicate detection
     * (size + duration composite, see the importer). Nullable for legacy rows.
     */
    @ColumnInfo(name = "content_hash")
    val contentHash: String? = null
)
