package com.myvideolibrary.app.ui.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.model.VideoSource
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.util.StorageManager
import com.myvideolibrary.app.util.ThumbnailGenerator
import com.myvideolibrary.app.security.AppLockManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Receives a video (or several) shared from another app ("Share → My Video
 * Library"), copies it into the app's private storage and adds it to the
 * library. Copying makes the clip independent of the sender's transient URI
 * permission, so it stays playable afterwards.
 */
@AndroidEntryPoint
class ShareReceiverActivity : AppCompatActivity() {

    @Inject lateinit var videoRepository: VideoRepository
    @Inject lateinit var thumbnailGenerator: ThumbnailGenerator
    @Inject lateinit var storageManager: StorageManager
    @Inject lateinit var appLockManager: AppLockManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (appLockManager.shouldLock()) {
            startActivity(
                com.myvideolibrary.app.ui.security.LockActivity.intent(this, Intent(intent))
            )
            finish()
            return
        }
        setContentView(
            FrameLayout(this).apply {
                addView(
                    ProgressBar(this@ShareReceiverActivity).apply { isIndeterminate = true },
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.CENTER
                    )
                )
            }
        )

        val uris = extractUris(intent)
        if (uris.isEmpty()) { finish(); return }

        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                var c = 0
                for (u in uris) if (runCatching { saveOne(u) }.getOrDefault(false)) c++
                c
            }
            Toast.makeText(
                this@ShareReceiverActivity,
                if (count > 0) getString(R.string.share_saved, count) else getString(R.string.share_save_failed),
                Toast.LENGTH_LONG
            ).show()
            finish()
        }
    }

    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
        else -> emptyList()
    }

    private suspend fun saveOne(uri: Uri): Boolean {
        val mime = contentResolver.getType(uri).orEmpty()
        val isImage = mime.startsWith("image/")
        val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?: if (isImage) "jpg" else "mp4"
        val name = queryDisplayName(uri)

        val dest = storageManager.newVideoFile(ext)
        contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: return false
        if (dest.length() == 0L) { dest.delete(); return false }

        val title = name?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: getString(if (isImage) R.string.shared_image else R.string.shared_video)

        if (isImage) {
            val (w, h) = imageDimensions(dest.absolutePath)
            videoRepository.addVideo(
                VideoEntity(
                    title = title,
                    thumbnailPath = dest.absolutePath,
                    localPath = dest.absolutePath,
                    source = VideoSource.LOCAL_IMPORT.id,
                    mediaType = com.myvideolibrary.app.data.model.MediaType.IMAGE.id,
                    duration = 0L,
                    fileSize = dest.length(),
                    width = w,
                    height = h,
                    createdDate = System.currentTimeMillis(),
                    contentHash = "img_${dest.length()}"
                )
            )
        } else {
            val meta = thumbnailGenerator.readMetadata(dest.absolutePath)
            val thumb = thumbnailGenerator.generateThumbnail(dest.absolutePath)
            videoRepository.addVideo(
                VideoEntity(
                    title = title,
                    thumbnailPath = thumb,
                    localPath = dest.absolutePath,
                    source = VideoSource.LOCAL_IMPORT.id,
                    duration = meta?.durationMs ?: 0L,
                    fileSize = dest.length(),
                    width = meta?.width ?: 0,
                    height = meta?.height ?: 0,
                    createdDate = System.currentTimeMillis(),
                    contentHash = "${dest.length()}_${meta?.durationMs ?: 0L}"
                )
            )
        }
        return true
    }

    private fun imageDimensions(path: String): Pair<Int, Int> {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, opts)
        return opts.outWidth.coerceAtLeast(0) to opts.outHeight.coerceAtLeast(0)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "content") {
            contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } else uri.lastPathSegment
    }.getOrNull()
}
