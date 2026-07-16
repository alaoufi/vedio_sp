package com.myvideolibrary.app.ui.player

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.myvideolibrary.app.R
import com.myvideolibrary.app.databinding.ActivityPlayerBinding
import com.myvideolibrary.app.security.SecurityManager
import com.myvideolibrary.app.security.applyScreenshotPolicy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: PlayerViewModel by viewModels()

    @javax.inject.Inject
    lateinit var securityManager: SecurityManager

    private var player: ExoPlayer? = null
    private var controlsLocked = false
    private var backgroundPlayback = false

    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 2

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotPolicy(securityManager)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val videoId = intent.getLongExtra(EXTRA_VIDEO_ID, -1)
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)

        setupControls()
        setupGestures()

        when {
            videoId > 0 -> viewModel.loadVideo(videoId)
            streamUrl != null -> viewModel.loadStream(
                streamUrl, intent.getStringExtra(EXTRA_STREAM_TITLE).orEmpty()
            )
            else -> { finish(); return }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    when (state) {
                        is PlayerUiState.Loading -> {
                            binding.loadingBar.isVisible = true
                            binding.errorText.isVisible = false
                        }
                        is PlayerUiState.Ready -> {
                            binding.loadingBar.isVisible = false
                            binding.titleText.text = state.title
                            showArtworkIfAudio(state.isAudio, state.artwork)
                            preparePlayer(state.url, state.resumeMs)
                        }
                        is PlayerUiState.Error -> {
                            binding.loadingBar.isVisible = false
                            binding.errorText.isVisible = true
                            binding.errorText.text =
                                state.message ?: getString(R.string.playback_error)
                        }
                    }
                }
            }
        }
    }

    private val resizeModes = intArrayOf(
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    )
    private var resizeIndex = 0

    private fun setupControls() {
        binding.lockButton.setOnClickListener { toggleLock() }
        binding.rotateButton.setOnClickListener { toggleOrientation() }
        binding.resizeButton.setOnClickListener { cycleResizeMode() }
        binding.speedButton.text = getString(R.string.speed_format, speeds[speedIndex])
        binding.speedButton.setOnClickListener { cycleSpeed() }
        binding.backButton.setOnClickListener { finish() }
        binding.pipButton.isVisible = supportsPip()
        binding.pipButton.setOnClickListener { enterPipIfPossible() }
        binding.backgroundToggle.setOnClickListener {
            backgroundPlayback = !backgroundPlayback
            binding.backgroundToggle.setImageResource(
                if (backgroundPlayback) R.drawable.ic_headphones_on else R.drawable.ic_headphones
            )
        }
    }

    /**
     * Audio tracks have no video frame, so the bare player surface is just
     * black ("a video without picture"). Overlay the cover art instead so it
     * plays like real audio. The resize toggle is meaningless for audio.
     */
    private fun showArtworkIfAudio(isAudio: Boolean, artwork: String?) {
        binding.audioArtwork.isVisible = isAudio
        binding.resizeButton.isVisible = !isAudio
        if (isAudio && !artwork.isNullOrBlank()) {
            com.bumptech.glide.Glide.with(this)
                .load(artwork)
                .placeholder(R.drawable.ic_headphones)
                .error(R.drawable.ic_headphones)
                .into(binding.audioArtwork)
        }
    }

    private fun preparePlayer(source: String, resumeMs: Long) {
        if (player != null) return
        val exo = ExoPlayer.Builder(this).build()
        binding.playerView.player = exo

        val uri = when {
            source.startsWith("content://") -> Uri.parse(source)
            source.startsWith("http") -> Uri.parse(source)
            else -> Uri.fromFile(java.io.File(source))
        }
        exo.setMediaItem(MediaItem.fromUri(uri))
        exo.playWhenReady = true
        if (resumeMs > 0) exo.seekTo(resumeMs)
        exo.setPlaybackSpeed(speeds[speedIndex])
        exo.prepare()

        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                binding.errorText.isVisible = true
                binding.errorText.text = getString(R.string.playback_error)
            }
        })
        player = exo
    }

    // ---- Controls behaviour ----

    private fun toggleLock() {
        controlsLocked = !controlsLocked
        binding.controlsGroup.isVisible = !controlsLocked
        binding.lockButton.setImageResource(
            if (controlsLocked) R.drawable.ic_lock else R.drawable.ic_lock_open
        )
        binding.playerView.useController = !controlsLocked
    }

    /** Cycles the video scaling: fit (letterbox) → zoom (crop) → fill (stretch). */
    private fun cycleResizeMode() {
        resizeIndex = (resizeIndex + 1) % resizeModes.size
        binding.playerView.resizeMode = resizeModes[resizeIndex]
        val label = when (resizeModes[resizeIndex]) {
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.string.resize_zoom
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.string.resize_fill
            else -> R.string.resize_fit
        }
        showGestureHint(getString(label))
    }

    private fun toggleOrientation() {
        requestedOrientation =
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.size
        player?.setPlaybackSpeed(speeds[speedIndex])
        binding.speedButton.text = getString(R.string.speed_format, speeds[speedIndex])
    }

    // ---- Gestures: left half = brightness, right half = volume ----

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (controlsLocked || e1 == null) return false
                if (abs(distanceX) > abs(distanceY)) return false
                val onLeft = e1.x < binding.root.width / 2f
                val delta = distanceY / binding.root.height
                if (onLeft) adjustBrightness(delta) else adjustVolume(delta)
                return true
            }
        })

        // Feed touches to the gesture detector but let PlayerView keep handling its
        // own controller (tap to show/hide, seek bar), so both coexist.
        binding.playerView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    private fun adjustBrightness(delta: Float) {
        val lp = window.attributes
        val current = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
        lp.screenBrightness = (current + delta).coerceIn(0.01f, 1f)
        window.attributes = lp
        showGestureHint(getString(R.string.brightness_label, (lp.screenBrightness * 100).toInt()))
    }

    private fun adjustVolume(delta: Float) {
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val current = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val target = (current + (delta * max)).toInt().coerceIn(0, max)
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
        showGestureHint(getString(R.string.volume_label, (target * 100 / max)))
    }

    private fun showGestureHint(text: String) {
        binding.gestureHint.isVisible = true
        binding.gestureHint.text = text
        binding.gestureHint.removeCallbacks(hideHint)
        binding.gestureHint.postDelayed(hideHint, 700)
    }

    private val hideHint = Runnable { binding.gestureHint.isVisible = false }

    // ---- Picture in Picture ----

    private fun supportsPip(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun enterPipIfPossible() {
        if (!supportsPip()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) enterPipIfPossible()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        binding.controlsGroup.isVisible = !isInPictureInPictureMode && !controlsLocked
        binding.playerView.useController = !isInPictureInPictureMode && !controlsLocked
    }

    // ---- Lifecycle: persist position, honour background playback ----

    override fun onStart() {
        super.onStart()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        super.onStop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        savePosition()
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        if (!backgroundPlayback && !inPip) {
            player?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        savePosition()
        player?.release()
        player = null
    }

    private fun savePosition() {
        player?.let { viewModel.savePosition(it.currentPosition) }
    }

    companion object {
        private const val EXTRA_VIDEO_ID = "extra_video_id"
        private const val EXTRA_STREAM_URL = "extra_stream_url"
        private const val EXTRA_STREAM_TITLE = "extra_stream_title"

        fun intent(context: Context, videoId: Long): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_VIDEO_ID, videoId)

        /** Streams a video straight from its platform page URL, no download. */
        fun streamIntent(context: Context, sourceUrl: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_STREAM_URL, sourceUrl)
                .putExtra(EXTRA_STREAM_TITLE, title)
    }
}
