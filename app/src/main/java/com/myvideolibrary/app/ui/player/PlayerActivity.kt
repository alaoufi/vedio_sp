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
    private var videoId = -1L
    private var hideEditing = false
    private var isAudioTrack = false

    // Autoplay queue (ids of the videos that were in view), and our position in it.
    private var playlist: LongArray = LongArray(0)
    private var playlistIndex = -1
    private var rotationLocked = false

    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 2

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotPolicy(securityManager)
        // Follow the device orientation by default — rotate with the phone.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // A playlist intent carries the whole queue; the single-video intent one id.
        intent.getLongArrayExtra(EXTRA_PLAYLIST)?.takeIf { it.isNotEmpty() }?.let { queue ->
            playlist = queue
            playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, 0)
                .coerceIn(0, queue.size - 1)
        }
        val videoId = if (playlistIndex >= 0) playlist[playlistIndex]
        else intent.getLongExtra(EXTRA_VIDEO_ID, -1)
        this.videoId = videoId
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)

        setupControls()
        setupGestures()
        loadHideBox()

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
        binding.speedButton.text = getString(R.string.speed_format, speeds[speedIndex])
        binding.speedButton.setOnClickListener { cycleSpeed() }
        binding.backButton.setOnClickListener { finish() }
        binding.moreButton.setOnClickListener { showPlayerMenu(it) }
    }

    /** Overflow menu grouping the less-frequent player actions, each clearly named. */
    private fun showPlayerMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        val m = popup.menu
        if (hasNext()) m.add(0, 7, 0, getString(R.string.play_next))
        if (hasPrevious()) m.add(0, 8, 1, getString(R.string.play_previous))
        if (!isAudioTrack) {
            m.add(0, 1, 0, getString(if (rotationLocked) R.string.rotation_auto else R.string.rotation_lock))
            m.add(0, 2, 1, getString(R.string.cd_resize))
            m.add(0, 5, 2, getString(R.string.hide_text))
            if (binding.hideBox.hasBox) m.add(0, 6, 3, getString(R.string.hide_text_remove))
        }
        m.add(0, 3, 4, getString(R.string.background_play)).apply {
            isCheckable = true
            isChecked = backgroundPlayback
        }
        if (supportsPip() && !isAudioTrack) m.add(0, 4, 5, getString(R.string.cd_pip))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { toggleRotationLock(); true }
                2 -> { cycleResizeMode(); true }
                3 -> { backgroundPlayback = !backgroundPlayback; true }
                4 -> { enterPipIfPossible(); true }
                5 -> { toggleHideBox(); true }
                6 -> {
                    binding.hideBox.clearBox()
                    hideBoxPrefs().edit().remove("v$videoId").apply()
                    showGestureHint(getString(R.string.hide_text_cleared))
                    true
                }
                7 -> { playNext(); true }
                8 -> { playPrevious(); true }
                else -> false
            }
        }
        popup.show()
    }

    // ---- Hide-box: cover floating text during playback ----

    private fun hideBoxPrefs() = getSharedPreferences("hidebox", MODE_PRIVATE)

    private fun toggleHideBox() {
        hideEditing = !hideEditing
        binding.hideBox.setEditable(hideEditing)
        if (hideEditing) {
            android.widget.Toast.makeText(this, R.string.hide_text_hint, android.widget.Toast.LENGTH_LONG).show()
        } else {
            saveHideBox()
        }
    }

    /** Persist the drawn box (per video) so it returns next time. */
    private fun saveHideBox() {
        if (videoId <= 0) return
        val n = binding.hideBox.normalizedRect() ?: return
        hideBoxPrefs().edit().putString("v$videoId", n.joinToString(",")).apply()
    }

    private fun loadHideBox() {
        if (videoId <= 0) return
        val saved = hideBoxPrefs().getString("v$videoId", null) ?: return
        val n = saved.split(",").mapNotNull { it.toFloatOrNull() }
        if (n.size == 4) binding.hideBox.setNormalizedRect(n.toFloatArray())
    }

    /**
     * Audio tracks have no video frame, so the bare player surface is just
     * black ("a video without picture"). Overlay the cover art instead so it
     * plays like real audio. The resize toggle is meaningless for audio.
     */
    private fun showArtworkIfAudio(isAudio: Boolean, artwork: String?) {
        isAudioTrack = isAudio
        // Use the PlayerView's own artwork slot (not an overlay) so the play/pause
        // controller and seek bar stay visible on top of the cover for audio.
        binding.playerView.artworkDisplayMode = if (isAudio) {
            androidx.media3.ui.PlayerView.ARTWORK_DISPLAY_MODE_FIT
        } else {
            androidx.media3.ui.PlayerView.ARTWORK_DISPLAY_MODE_OFF
        }
        if (!isAudio) {
            binding.playerView.defaultArtwork = null
            return
        }
        binding.playerView.defaultArtwork =
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_headphones)
        if (!artwork.isNullOrBlank()) {
            com.bumptech.glide.Glide.with(this)
                .load(artwork)
                .placeholder(R.drawable.ic_headphones)
                .error(R.drawable.ic_headphones)
                .into(object : com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        transition: com.bumptech.glide.request.transition.Transition<in android.graphics.drawable.Drawable>?
                    ) {
                        binding.playerView.defaultArtwork = resource
                    }
                    override fun onLoadCleared(placeholder: android.graphics.drawable.Drawable?) {}
                })
        }
    }

    private fun preparePlayer(source: String, resumeMs: Long) {
        if (player != null) return
        // Start playback as soon as ~0.5s is buffered instead of ExoPlayer's
        // default 2.5s, so a tapped clip starts almost immediately.
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                androidx.media3.exoplayer.DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                500,
                1000
            )
            .build()
        val exo = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
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
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Show the spinner while buffering so it's clear playback is loading.
                binding.loadingBar.isVisible = playbackState == Player.STATE_BUFFERING
                // When a clip ends, roll on to the next one in the queue.
                if (playbackState == Player.STATE_ENDED && hasNext()) playNext()
            }
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

    /**
     * By default the player follows the device orientation (rotates when you tilt
     * the phone). This toggles between that automatic mode and locking to the
     * orientation you're currently in.
     */
    private fun toggleRotationLock() {
        rotationLocked = !rotationLocked
        requestedOrientation = if (rotationLocked) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        showGestureHint(getString(if (rotationLocked) R.string.rotation_locked else R.string.rotation_auto))
    }

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.size
        player?.setPlaybackSpeed(speeds[speedIndex])
        binding.speedButton.text = getString(R.string.speed_format, speeds[speedIndex])
    }

    // ---- Autoplay queue ----

    private fun hasNext(): Boolean = playlistIndex in playlist.indices && playlistIndex < playlist.lastIndex
    private fun hasPrevious(): Boolean = playlistIndex > 0

    private fun playNext() = playAt(playlistIndex + 1)
    private fun playPrevious() = playAt(playlistIndex - 1)

    /** Restarts the player on another queue entry, replacing this screen. */
    private fun playAt(index: Int) {
        if (index !in playlist.indices) return
        savePosition()
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(EXTRA_PLAYLIST, playlist)
                .putExtra(EXTRA_PLAYLIST_INDEX, index)
        )
        overridePendingTransition(0, 0)
        finish()
    }

    // ---- Gestures ----
    // Double-tap right/left = seek ±10s. Vertical fling = next/previous clip.
    // Slow vertical drag: left half = brightness, right half = volume.

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (controlsLocked) return false
                val p = player ?: return false
                if (e.x < binding.root.width / 2f) {
                    p.seekTo((p.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
                    showGestureHint(getString(R.string.seek_back))
                } else {
                    val dur = p.duration
                    val target = p.currentPosition + SEEK_STEP_MS
                    p.seekTo(if (dur > 0) target.coerceAtMost(dur) else target)
                    showGestureHint(getString(R.string.seek_forward))
                }
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (controlsLocked || e1 == null) return false
                // A fast vertical swipe moves between clips (up = next, down = previous).
                if (abs(velocityY) > abs(velocityX) && abs(velocityY) > FLING_MIN_VELOCITY) {
                    if (velocityY < 0 && hasNext()) { playNext(); return true }
                    if (velocityY > 0 && hasPrevious()) { playPrevious(); return true }
                }
                return false
            }

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
        saveHideBox()
        // Closing the PiP window (its X) finishes the activity — release the player
        // so audio actually stops, even when background playback is enabled.
        if (isFinishing) {
            player?.release()
            player = null
            return
        }
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
        player?.let {
            // A finished (or all-but-finished) clip should restart from the top
            // next time — don't resume it a second from the end.
            val ended = it.playbackState == Player.STATE_ENDED ||
                (it.duration > 0 && it.currentPosition >= it.duration - 3000)
            viewModel.savePosition(if (ended) 0L else it.currentPosition)
        }
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L
        private const val FLING_MIN_VELOCITY = 1800f
        private const val EXTRA_VIDEO_ID = "extra_video_id"
        private const val EXTRA_STREAM_URL = "extra_stream_url"
        private const val EXTRA_STREAM_TITLE = "extra_stream_title"
        private const val EXTRA_PLAYLIST = "extra_playlist"
        private const val EXTRA_PLAYLIST_INDEX = "extra_playlist_index"

        fun intent(context: Context, videoId: Long): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_VIDEO_ID, videoId)

        /** Opens the player on a queue of videos, auto-advancing when each ends. */
        fun playlistIntent(context: Context, ids: LongArray, index: Int): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_PLAYLIST, ids)
                .putExtra(EXTRA_PLAYLIST_INDEX, index)

        /** Streams a video straight from its platform page URL, no download. */
        fun streamIntent(context: Context, sourceUrl: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_STREAM_URL, sourceUrl)
                .putExtra(EXTRA_STREAM_TITLE, title)
    }
}
