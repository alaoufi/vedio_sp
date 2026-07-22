package com.myvideolibrary.app.ui.trim

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.myvideolibrary.app.R
import com.myvideolibrary.app.data.local.entity.VideoEntity
import com.myvideolibrary.app.data.repository.VideoRepository
import com.myvideolibrary.app.databinding.ActivityTrimBinding
import com.myvideolibrary.app.util.Formatters
import com.myvideolibrary.app.util.StorageManager
import com.myvideolibrary.app.util.ThumbnailGenerator
import com.myvideolibrary.app.util.VideoTrimmer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Losslessly trims a downloaded video: two handles pick the part to keep (so an
 * intro can be dropped), then the copy-only cut is saved as a new library entry.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class TrimActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTrimBinding

    @Inject lateinit var videoRepository: VideoRepository
    @Inject lateinit var storageManager: StorageManager
    @Inject lateinit var thumbnailGenerator: ThumbnailGenerator

    private var player: ExoPlayer? = null
    private var durationMs = 0L
    private var sourcePath: String = ""
    private var sourceTitle: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTrimBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        sourcePath = intent.getStringExtra(EXTRA_PATH).orEmpty()
        sourceTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val file = File(sourcePath)
        if (!file.exists()) { finish(); return }

        val exo = ExoPlayer.Builder(this).build()
        binding.playerView.player = exo
        exo.setMediaItem(MediaItem.fromUri(file.toUri()))
        exo.prepare()
        exo.playWhenReady = false
        player = exo

        exo.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == androidx.media3.common.Player.STATE_READY && durationMs == 0L) {
                    durationMs = exo.duration.coerceAtLeast(1000)
                    setupSlider()
                }
            }
        })

        binding.trimButton.setOnClickListener { doTrim() }
    }

    private fun setupSlider() {
        binding.rangeSlider.valueFrom = 0f
        binding.rangeSlider.valueTo = durationMs.toFloat()
        binding.rangeSlider.setValues(0f, durationMs.toFloat())
        updateLabels(0f, durationMs.toFloat())
        binding.rangeSlider.addOnChangeListener { slider, _, fromUser ->
            val values = slider.values
            val start = values.first()
            val end = values.last()
            updateLabels(start, end)
            // Seek the preview to whichever handle the user is holding.
            if (fromUser) player?.seekTo(start.toLong())
        }
    }

    private fun updateLabels(startMs: Float, endMs: Float) {
        binding.startLabel.text = Formatters.duration(startMs.toLong())
        binding.endLabel.text = Formatters.duration(endMs.toLong())
    }

    private fun doTrim() {
        val values = binding.rangeSlider.values
        val startMs = values.first().toLong()
        val endMs = values.last().toLong()
        if (endMs - startMs < 500) {
            Toast.makeText(this, R.string.trim_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        binding.trimProgress.isVisible = true
        binding.trimButton.isEnabled = false
        lifecycleScope.launch {
            val out = File(storageManager.newVideoFile("mp4").absolutePath)
            val ok = withContext(Dispatchers.IO) {
                VideoTrimmer.trim(File(sourcePath), out, startMs, endMs)
            }
            if (ok) {
                val meta = withContext(Dispatchers.IO) {
                    thumbnailGenerator.readMetadata(out.absolutePath)
                }
                val thumb = withContext(Dispatchers.IO) {
                    thumbnailGenerator.generateThumbnail(out.absolutePath)
                }
                videoRepository.addVideo(
                    VideoEntity(
                        title = getString(R.string.trim_result_title, sourceTitle),
                        localPath = out.absolutePath,
                        thumbnailPath = thumb,
                        source = "other",
                        mediaType = "video",
                        duration = meta?.durationMs ?: (endMs - startMs),
                        fileSize = out.length(),
                        width = meta?.width ?: 0,
                        height = meta?.height ?: 0,
                        quality = meta?.qualityLabel,
                        createdDate = System.currentTimeMillis()
                    )
                )
                Toast.makeText(this@TrimActivity, R.string.trim_done, Toast.LENGTH_LONG).show()
                finish()
            } else {
                binding.trimProgress.isVisible = false
                binding.trimButton.isEnabled = true
                Toast.makeText(this@TrimActivity, R.string.trim_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    companion object {
        private const val EXTRA_PATH = "extra_path"
        private const val EXTRA_TITLE = "extra_title"

        fun intent(context: Context, path: String, title: String): Intent =
            Intent(context, TrimActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_TITLE, title)
    }
}
