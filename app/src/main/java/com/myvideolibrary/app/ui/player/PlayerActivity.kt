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

    @javax.inject.Inject
    lateinit var storageManager: com.myvideolibrary.app.util.StorageManager

    @javax.inject.Inject
    lateinit var videoRepository: com.myvideolibrary.app.data.repository.VideoRepository

    // Sleep timer + loop state.
    private var sleepRunnable: Runnable? = null
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var looping = false

    private var player: ExoPlayer? = null
    private var controlsLocked = false
    private var backgroundPlayback = false
    private var videoId = -1L
    private var hideEditing = false
    private var isAudioTrack = false

    // Autoplay queue (ids of the videos that were in view), and our position in it.
    private var playlist: LongArray = LongArray(0)
    private var streamUrls: Array<String> = emptyArray()
    private var streamTitles: Array<String> = emptyArray()
    private var playlistIndex = -1
    private var rotationLocked = false

    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 2

    // Currently playing source and an optional user-chosen subtitle sidecar file.
    private var currentSource: String? = null
    private var subtitleUri: Uri? = null

    /** Lets the user pick an .srt / .vtt subtitle file to overlay on the video. */
    private val subtitlePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            subtitleUri = uri
            reloadWithSubtitles()
            showGestureHint(getString(R.string.subtitles_loaded))
        }
    }

    private lateinit var gestureDetector: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScreenshotPolicy(securityManager)
        // Follow the device orientation by default — rotate with the phone.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Three queue shapes: a library id queue, a stream URL queue (search
        // results), or a single item. All share playlistIndex for the position.
        intent.getLongArrayExtra(EXTRA_PLAYLIST)?.takeIf { it.isNotEmpty() }?.let { queue ->
            playlist = queue
            playlistIndex = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, 0).coerceIn(0, queue.size - 1)
        }
        intent.getStringArrayExtra(EXTRA_STREAM_URLS)?.takeIf { it.isNotEmpty() }?.let { urls ->
            streamUrls = urls
            streamTitles = intent.getStringArrayExtra(EXTRA_STREAM_TITLES) ?: Array(urls.size) { "" }
            playlistIndex = intent.getIntExtra(EXTRA_STREAM_INDEX, 0).coerceIn(0, urls.size - 1)
        }
        val videoId = if (playlist.isNotEmpty() && playlistIndex >= 0) playlist[playlistIndex]
        else intent.getLongExtra(EXTRA_VIDEO_ID, -1)
        this.videoId = videoId
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
        // Opened from a file manager / another app via "Open with" — play that file.
        val externalUri = if (intent.action == Intent.ACTION_VIEW) intent.data else null

        setupControls()
        setupGestures()
        loadHideBox()

        when {
            externalUri != null -> viewModel.loadStream(externalUri.toString(), externalTitle(externalUri))
            streamUrls.isNotEmpty() -> viewModel.loadStream(
                streamUrls[playlistIndex], streamTitles.getOrElse(playlistIndex) { "" }
            )
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
        binding.speedButton.text = getString(R.string.speed_format, speeds[speedIndex])
        binding.speedButton.setOnClickListener { cycleSpeed() }
        binding.backButton.setOnClickListener { finish() }
        binding.moreButton.setOnClickListener { showPlayerMenu(it) }

        // Declutter the center overlay: the skip buttons are redundant now that a
        // double-tap seeks, and the controls auto-hide quickly so the video is clean.
        binding.playerView.setShowRewindButton(false)
        binding.playerView.setShowFastForwardButton(false)
        binding.playerView.setShowPreviousButton(false)
        binding.playerView.setShowNextButton(false)
        binding.playerView.controllerShowTimeoutMs = 2000
        // A single tap toggles play/pause (handled in the gesture detector), so the
        // tap must NOT also toggle the controller overlay.
        binding.playerView.controllerHideOnTouch = false
        // Don't keep the big pause/play button on screen when paused — it covers
        // the video. A brief ▶/⏸ hint on tap is enough.
        binding.playerView.controllerAutoShow = false
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
            m.add(0, 9, 2, getString(R.string.subtitles_add))
            m.add(0, 12, 3, getString(R.string.capture_frame))
            m.add(0, 5, 4, getString(R.string.hide_text))
            if (binding.hideBox.hasBox) m.add(0, 6, 5, getString(R.string.hide_text_remove))
        }
        m.add(0, 11, 6, getString(R.string.loop_one)).apply {
            isCheckable = true
            isChecked = looping
        }
        m.add(0, 10, 7, getString(R.string.sleep_timer))
        m.add(0, 3, 8, getString(R.string.background_play)).apply {
            isCheckable = true
            isChecked = backgroundPlayback
        }
        if (supportsPip() && !isAudioTrack) m.add(0, 4, 9, getString(R.string.cd_pip))
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
                9 -> {
                    runCatching {
                        subtitlePicker.launch(
                            arrayOf("application/x-subrip", "text/vtt", "text/*", "*/*")
                        )
                    }
                    true
                }
                10 -> { showSleepTimerMenu(anchor); true }
                11 -> { toggleLoop(); true }
                12 -> { captureFrame(); true }
                else -> false
            }
        }
        popup.show()
    }

    // ---- Loop / sleep timer / frame capture ----

    private fun toggleLoop() {
        looping = !looping
        player?.repeatMode =
            if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        showGestureHint(getString(if (looping) R.string.loop_on else R.string.loop_off))
    }

    private fun showSleepTimerMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        val mins = intArrayOf(10, 20, 30, 60)
        mins.forEachIndexed { i, min -> popup.menu.add(0, i, i, getString(R.string.sleep_minutes, min)) }
        popup.menu.add(0, 100, mins.size, getString(R.string.sleep_off))
        popup.setOnMenuItemClickListener { item ->
            cancelSleep()
            if (item.itemId == 100) {
                showGestureHint(getString(R.string.sleep_cancelled))
            } else {
                val minutes = mins[item.itemId]
                val r = Runnable {
                    player?.pause()
                    showGestureHint(getString(R.string.sleep_done))
                }
                sleepRunnable = r
                sleepHandler.postDelayed(r, minutes * 60_000L)
                showGestureHint(getString(R.string.sleep_set, minutes))
            }
            true
        }
        popup.show()
    }

    private fun cancelSleep() {
        sleepRunnable?.let { sleepHandler.removeCallbacks(it) }
        sleepRunnable = null
    }

    /** Saves the currently displayed frame as an image in the library. */
    private fun captureFrame() {
        val src = currentSource ?: return
        val posMs = player?.currentPosition ?: return
        showGestureHint(getString(R.string.capturing_frame))
        lifecycleScope.launch {
            val ok = runCatching { saveFrame(src, posMs) }.getOrDefault(false)
            showGestureHint(getString(if (ok) R.string.frame_saved else R.string.frame_failed))
        }
    }

    private suspend fun saveFrame(source: String, positionMs: Long): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                when {
                    source.startsWith("content://") -> retriever.setDataSource(this@PlayerActivity, Uri.parse(source))
                    source.startsWith("http") -> retriever.setDataSource(source, HashMap())
                    source.startsWith("file://") -> retriever.setDataSource(Uri.parse(source).path)
                    else -> retriever.setDataSource(source)
                }
                val bmp = retriever.getFrameAtTime(
                    positionMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST
                ) ?: return@withContext false
                val dest = storageManager.newVideoFile("jpg")
                java.io.FileOutputStream(dest).use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                }
                val w = bmp.width
                val h = bmp.height
                bmp.recycle()
                if (dest.length() == 0L) { dest.delete(); return@withContext false }
                videoRepository.addVideo(
                    com.myvideolibrary.app.data.local.entity.VideoEntity(
                        title = getString(R.string.captured_frame),
                        thumbnailPath = dest.absolutePath,
                        localPath = dest.absolutePath,
                        source = com.myvideolibrary.app.data.model.VideoSource.LOCAL_IMPORT.id,
                        mediaType = com.myvideolibrary.app.data.model.MediaType.IMAGE.id,
                        duration = 0L,
                        fileSize = dest.length(),
                        width = w,
                        height = h,
                        createdDate = System.currentTimeMillis(),
                        contentHash = "frame_${dest.length()}_$positionMs"
                    )
                )
                true
            } catch (e: Exception) {
                false
            } finally {
                runCatching { retriever.release() }
            }
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

    private fun sourceUri(source: String): Uri = when {
        source.startsWith("content://") ||
            source.startsWith("http") ||
            source.startsWith("file://") -> Uri.parse(source)
        else -> Uri.fromFile(java.io.File(source))
    }

    /** MediaItem for [uri], with the user's chosen subtitle sidecar if any. */
    private fun buildMediaItem(uri: Uri): MediaItem {
        val sub = subtitleUri ?: return MediaItem.fromUri(uri)
        val mime = if ((sub.lastPathSegment ?: "").lowercase().endsWith(".vtt")) {
            androidx.media3.common.MimeTypes.TEXT_VTT
        } else {
            androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
        }
        val config = MediaItem.SubtitleConfiguration.Builder(sub)
            .setMimeType(mime)
            .setLanguage("und")
            .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
            .build()
        return MediaItem.Builder().setUri(uri).setSubtitleConfigurations(listOf(config)).build()
    }

    /** Rebuilds the current media with the chosen subtitle track, keeping position. */
    private fun reloadWithSubtitles() {
        val exo = player ?: return
        val src = currentSource ?: return
        val pos = exo.currentPosition
        val wasPlaying = exo.playWhenReady
        exo.setMediaItem(buildMediaItem(sourceUri(src)))
        exo.prepare()
        exo.seekTo(pos)
        exo.playWhenReady = wasPlaying
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

        currentSource = source
        exo.setMediaItem(buildMediaItem(sourceUri(source)))
        exo.playWhenReady = true
        if (resumeMs > 0) exo.seekTo(resumeMs)
        exo.setPlaybackSpeed(speeds[speedIndex])
        exo.prepare()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // Show the spinner while buffering so it's clear playback is loading.
                binding.loadingBar.isVisible = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) onClipEnded()
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                binding.errorText.isVisible = true
                binding.errorText.text = getString(R.string.playback_error)
            }
        })
        player = exo
    }

    /** Applies the user's end-of-clip preference: stop, repeat, or play next. */
    private fun onClipEnded() {
        when (viewModel.endAction.value) {
            com.myvideolibrary.app.data.model.EndOfClipAction.REPEAT -> {
                player?.seekTo(0)
                player?.play()
            }
            com.myvideolibrary.app.data.model.EndOfClipAction.NEXT -> {
                if (hasNext()) playNext()
            }
            com.myvideolibrary.app.data.model.EndOfClipAction.STOP -> Unit // stay on the ended frame
        }
    }

    // ---- Controls behaviour ----

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

    // ---- Autoplay queue (library ids or stream URLs) ----

    private fun queueSize(): Int = if (streamUrls.isNotEmpty()) streamUrls.size else playlist.size

    private fun hasNext(): Boolean = playlistIndex in 0 until (queueSize() - 1)
    private fun hasPrevious(): Boolean = playlistIndex > 0

    private fun playNext() = playAt(playlistIndex + 1)
    private fun playPrevious() = playAt(playlistIndex - 1)

    /** Restarts the player on another queue entry, replacing this screen. */
    private fun playAt(index: Int) {
        if (index < 0 || index >= queueSize()) return
        savePosition()
        val next = if (streamUrls.isNotEmpty()) {
            Intent(this, PlayerActivity::class.java)
                .putExtra(EXTRA_STREAM_URLS, streamUrls)
                .putExtra(EXTRA_STREAM_TITLES, streamTitles)
                .putExtra(EXTRA_STREAM_INDEX, index)
        } else {
            Intent(this, PlayerActivity::class.java)
                .putExtra(EXTRA_PLAYLIST, playlist)
                .putExtra(EXTRA_PLAYLIST_INDEX, index)
        }
        startActivity(next)
        overridePendingTransition(0, 0)
        finish()
    }

    // ---- Gestures ----
    // TikTok-style: a vertical swipe moves between clips (up = next, down =
    // previous). Double-tap right/left = seek ±10s. Pinch = zoom the video; when
    // zoomed, a one-finger drag pans and double-tap resets the zoom.

    private var downX = 0f
    private var downY = 0f
    private var lastPanX = 0f
    private var lastPanY = 0f
    private var videoScale = 1f
    private var videoTransX = 0f
    private var videoTransY = 0f
    private lateinit var scaleDetector: android.view.ScaleGestureDetector

    /** A queue exists when the player was opened on a list of clips or streams. */
    private fun hasQueue(): Boolean = queueSize() > 1

    private fun isZoomed(): Boolean = videoScale > 1.02f

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsLocked) return false
                val p = player ?: return false
                if (p.isPlaying) { p.pause(); showGestureHint("⏸") }
                else { p.play(); showGestureHint("▶") }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (controlsLocked) return false
                // Double-tap resets the zoom first, otherwise seeks.
                if (isZoomed()) { resetZoom(); return true }
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
        })

        scaleDetector = android.view.ScaleGestureDetector(
            this,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                    if (controlsLocked) return false
                    videoScale = (videoScale * d.scaleFactor).coerceIn(1f, 4f)
                    if (!isZoomed()) { videoTransX = 0f; videoTransY = 0f }
                    applyVideoTransform()
                    return true
                }
            }
        )

        binding.playerView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y; lastPanX = event.x; lastPanY = event.y
                }
                MotionEvent.ACTION_MOVE ->
                    // While zoomed, a single finger pans the video.
                    if (isZoomed() && event.pointerCount == 1 && !scaleDetector.isInProgress) {
                        videoTransX += event.x - lastPanX
                        videoTransY += event.y - lastPanY
                        lastPanX = event.x; lastPanY = event.y
                        applyVideoTransform()
                    }
                MotionEvent.ACTION_UP ->
                    // Swipe-to-navigate only when not zoomed (zoom uses the drag to pan).
                    if (!isZoomed()) maybeSwipeNavigate(event.x - downX, event.y - downY)
            }
            false
        }
    }

    private fun applyVideoTransform() {
        val maxTx = binding.playerView.width * (videoScale - 1f) / 2f
        val maxTy = binding.playerView.height * (videoScale - 1f) / 2f
        videoTransX = videoTransX.coerceIn(-maxTx, maxTx)
        videoTransY = videoTransY.coerceIn(-maxTy, maxTy)
        binding.playerView.scaleX = videoScale
        binding.playerView.scaleY = videoScale
        binding.playerView.translationX = videoTransX
        binding.playerView.translationY = videoTransY
    }

    private fun resetZoom() {
        videoScale = 1f; videoTransX = 0f; videoTransY = 0f
        applyVideoTransform()
    }

    /** Moves to the next/previous clip when the finger travelled far enough vertically. */
    private fun maybeSwipeNavigate(dx: Float, dy: Float) {
        if (controlsLocked || !hasQueue()) return
        val minPx = binding.root.height * SWIPE_NAV_FRACTION
        if (abs(dy) < minPx || abs(dy) < abs(dx)) return
        if (dy < 0 && hasNext()) playNext()          // swipe up → next
        else if (dy > 0 && hasPrevious()) playPrevious()  // swipe down → previous
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
        // Only keep playing (via PiP) when the user explicitly enabled background
        // play; otherwise leaving the player stops playback (see onStop).
        if (backgroundPlayback && player?.isPlaying == true) enterPipIfPossible()
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
        cancelSleep()
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

    /** A readable title for an externally-opened file: its display name, else the URI tail. */
    private fun externalTitle(uri: Uri): String {
        val name = runCatching {
            if (uri.scheme == "content") {
                contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } else null
        }.getOrNull()
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: getString(R.string.app_name)
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L
        /** A vertical swipe past this fraction of the screen height changes clips. */
        private const val SWIPE_NAV_FRACTION = 0.10f
        private const val EXTRA_VIDEO_ID = "extra_video_id"
        private const val EXTRA_STREAM_URL = "extra_stream_url"
        private const val EXTRA_STREAM_TITLE = "extra_stream_title"
        private const val EXTRA_PLAYLIST = "extra_playlist"
        private const val EXTRA_PLAYLIST_INDEX = "extra_playlist_index"
        private const val EXTRA_STREAM_URLS = "extra_stream_urls"
        private const val EXTRA_STREAM_TITLES = "extra_stream_titles"
        private const val EXTRA_STREAM_INDEX = "extra_stream_index"

        fun intent(context: Context, videoId: Long): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_VIDEO_ID, videoId)

        /** Streams a queue of results, so a vertical swipe moves between them. */
        fun streamPlaylistIntent(
            context: Context,
            urls: Array<String>,
            titles: Array<String>,
            index: Int
        ): Intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_STREAM_URLS, urls)
            .putExtra(EXTRA_STREAM_TITLES, titles)
            .putExtra(EXTRA_STREAM_INDEX, index)

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
