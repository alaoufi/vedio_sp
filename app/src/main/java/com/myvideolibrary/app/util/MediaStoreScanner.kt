package com.myvideolibrary.app.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A device video discovered via MediaStore, before it is added to the library. */
data class ScannedVideo(
    val contentUri: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSeconds: Long,
    val relativeBucket: String?,
    val width: Int,
    val height: Int
)

/**
 * Reads the device's video collection through MediaStore using scoped, modern
 * storage APIs. No broad file-system access is required beyond READ_MEDIA_VIDEO.
 */
@Singleton
class MediaStoreScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {

    suspend fun scanDeviceVideos(): List<ScannedVideo> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ScannedVideo>()

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val bucketColumn = MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            bucketColumn
        )

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            collection, projection, null, null, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val bucketCol = cursor.getColumnIndex(bucketColumn)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri: Uri = MediaStore.Video.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL, id
                ).let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it else Uri.withAppendedPath(collection, id.toString()) }

                result.add(
                    ScannedVideo(
                        contentUri = uri.toString(),
                        displayName = cursor.getString(nameCol) ?: "video_$id",
                        durationMs = cursor.getLong(durationCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        dateAddedSeconds = cursor.getLong(dateCol),
                        relativeBucket = if (bucketCol >= 0) cursor.getString(bucketCol) else null,
                        width = cursor.getInt(widthCol),
                        height = cursor.getInt(heightCol)
                    )
                )
            }
        }
        result
    }
}
