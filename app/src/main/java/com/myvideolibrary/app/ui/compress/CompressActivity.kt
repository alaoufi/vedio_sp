package com.myvideolibrary.app.ui.compress

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.databinding.ActivityCompressBinding
import com.myvideolibrary.app.util.Formatters
import com.myvideolibrary.app.util.StorageManager
import com.myvideolibrary.app.util.ThumbnailGenerator
import com.myvideolibrary.app.util.VideoCompressor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Compresses one downloaded video to HEVC on the device, showing live progress.
 * On success the user can replace the original (to reclaim space) or keep both.
 */
@AndroidEntryPoint
class CompressActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCompressBinding

    @Inject lateinit var videoRepository: VideoRepository
    @Inject lateinit var storageManager: StorageManager
    @Inject lateinit var thumbnailGenerator: ThumbnailGenerator

    private var videoId: Long = 0
    private var sourcePath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCompressBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        videoId = intent.getLongExtra(EXTRA_ID, 0)
        sourcePath = intent.getStringExtra(EXTRA_PATH).orEmpty()
        val file = File(sourcePath)
        if (videoId == 0L || !file.exists()) { finish(); return }

        binding.progress.isIndeterminate = false
        binding.percentText.text = "0%"
        startCompression(file)
    }

    private fun startCompression(input: File) {
        lifecycleScope.launch {
            val output = File(storageManager.newVideoFile("mp4").absolutePath)
            val result = VideoCompressor.compress(
                context = applicationContext,
                input = input,
                output = output
            ) { percent ->
                binding.progress.setProgressCompat(percent, true)
                binding.percentText.text = "$percent%"
            }
            when (result) {
                is VideoCompressor.Result.Success -> onCompressed(input, result)
                VideoCompressor.Result.NoGain -> {
                    Toast.makeText(this@CompressActivity, R.string.compress_no_gain, Toast.LENGTH_LONG).show()
                    finish()
                }
                is VideoCompressor.Result.Failed -> {
                    Toast.makeText(this@CompressActivity, R.string.compress_failed, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun onCompressed(original: File, success: VideoCompressor.Result.Success) {
        binding.progress.setProgressCompat(100, true)
        binding.percentText.text = "100%"
        val saved = success.originalBytes - success.newBytes
        val percent = (saved * 100 / success.originalBytes.coerceAtLeast(1)).toInt()
        val message = getString(
            R.string.compress_result,
            Formatters.fileSize(success.originalBytes),
            Formatters.fileSize(success.newBytes),
            percent
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.compress_done)
            .setMessage(message)
            .setPositiveButton(R.string.replace_original) { _, _ ->
                replaceOriginal(original, success.output)
            }
            .setNeutralButton(R.string.keep_both) { _, _ ->
                keepBoth(success.output)
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                success.output.delete()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    /** Points the existing library entry at the smaller file and removes the old one. */
    private fun replaceOriginal(original: File, compressed: File) {
        lifecycleScope.launch {
            val existing = videoRepository.getVideo(videoId)
            if (existing == null) { finish(); return@launch }
            val meta = withContext(Dispatchers.IO) {
                thumbnailGenerator.readMetadata(compressed.absolutePath)
            }
            val thumb = withContext(Dispatchers.IO) {
                thumbnailGenerator.generateThumbnail(compressed.absolutePath)
            }
            videoRepository.updateVideo(
                existing.copy(
                    localPath = compressed.absolutePath,
                    fileSize = compressed.length(),
                    thumbnailPath = thumb ?: existing.thumbnailPath,
                    duration = meta?.durationMs ?: existing.duration,
                    width = meta?.width ?: existing.width,
                    height = meta?.height ?: existing.height,
                    quality = meta?.qualityLabel ?: existing.quality
                )
            )
            withContext(Dispatchers.IO) { runCatching { original.delete() } }
            Toast.makeText(this@CompressActivity, R.string.compress_replaced, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Adds the compressed file as a new entry, leaving the original in place. */
    private fun keepBoth(compressed: File) {
        lifecycleScope.launch {
            val existing = videoRepository.getVideo(videoId)
            val meta = withContext(Dispatchers.IO) {
                thumbnailGenerator.readMetadata(compressed.absolutePath)
            }
            val thumb = withContext(Dispatchers.IO) {
                thumbnailGenerator.generateThumbnail(compressed.absolutePath)
            }
            val title = getString(R.string.compress_result_title, existing?.title.orEmpty())
            videoRepository.addVideo(
                VideoEntity(
                    title = title,
                    localPath = compressed.absolutePath,
                    thumbnailPath = thumb,
                    source = existing?.source ?: "other",
                    mediaType = "video",
                    category = existing?.category,
                    duration = meta?.durationMs ?: (existing?.duration ?: 0),
                    fileSize = compressed.length(),
                    width = meta?.width ?: 0,
                    height = meta?.height ?: 0,
                    quality = meta?.qualityLabel,
                    createdDate = System.currentTimeMillis()
                )
            )
            Toast.makeText(this@CompressActivity, R.string.compress_saved, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        private const val EXTRA_ID = "extra_id"
        private const val EXTRA_PATH = "extra_path"

        fun intent(context: Context, id: Long, path: String): Intent =
            Intent(context, CompressActivity::class.java)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_PATH, path)
    }
}
