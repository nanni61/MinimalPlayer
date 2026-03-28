package com.minimalplayer

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.minimalplayer.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var resumeManager: ResumeManager
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetector

    private var player: ExoPlayer? = null
    private var videoUrl = ""
    private var startPosition = 0L
    private var jellyfinItemId = ""
    private var jellyfinToken = ""
    private var jellyfinBaseUrl = ""

    private var subtitleTracks = listOf<SubtitleTrack>()
    private var currentSubtitleIndex = -1

    private val maxVolumeBoost = 2.0f
    private var currentVolumeMultiplier = 1.0f

    private val hudHandler = Handler(Looper.getMainLooper())
    private val hudRunnable = object : Runnable {
        override fun run() { updateHud(); hudHandler.postDelayed(this, 1000) }
    }
    private val overlayHandler = Handler(Looper.getMainLooper())

    // Seek velocità
    private var seekVelocityActive = false
    private var seekLastX = 0f
    private var seekLastTime = 0L
    private var seekAccumulator = 0L  // ms accumulati

    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var gestureType = GestureType.NONE
    private var initialVolume = 0
    private var initialBrightness = 0f

    private val maxSystemVolume by lazy {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    private enum class GestureType { NONE, SEEK, VOLUME, BRIGHTNESS }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        resumeManager = ResumeManager(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setImmersive()

        videoUrl = intent.getStringExtra("video_url") ?: ""
        startPosition = intent.getLongExtra("start_position", 0L)
        jellyfinItemId = intent.getStringExtra("jellyfin_item_id") ?: ""
        jellyfinToken = intent.getStringExtra("jellyfin_token") ?: ""
        jellyfinBaseUrl = intent.getStringExtra("jellyfin_base_url") ?: ""

        setupGestureDetector()
        setupTouchListener()
    }

    override fun onStart() {
        super.onStart()
        loadSubtitlesAndInit()
        hudHandler.post(hudRunnable)
    }

    private fun loadSubtitlesAndInit() {
        if (jellyfinItemId.isEmpty() || jellyfinToken.isEmpty()) {
            initPlayer(emptyList()); return
        }
        lifecycleScope.launch {
            val tracks = withContext(Dispatchers.IO) {
                val jellyfin = JellyfinClient()
                jellyfin.baseUrl = jellyfinBaseUrl
                jellyfin.accessToken = jellyfinToken
                jellyfin.userId = ""
                jellyfin.getSubtitles(jellyfinItemId).getOrDefault(emptyList())
            }
            subtitleTracks = tracks
            initPlayer(tracks)
            binding.playerView.findViewById<View>(R.id.btnSubtitles)?.visibility =
                if (tracks.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun initPlayer(subtitles: List<SubtitleTrack>) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent("MinimalPlayer/1.0")
            setConnectTimeoutMs(15_000)
            setReadTimeoutMs(15_000)
            if (jellyfinToken.isNotEmpty()) {
                setDefaultRequestProperties(mapOf(
                    "Authorization" to "MediaBrowser Token=\"$jellyfinToken\""
                ))
            }
        }

        val subtitleConfigs = subtitles.map { track ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(track.url))
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(track.language)
                .setLabel(track.title)
                .setSelectionFlags(0)
                .build()
        }

        val mediaItem = MediaItem.Builder()
            .setUri(videoUrl)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .build().also { exo ->
                binding.playerView.player = exo
                binding.playerView.useController = true
                binding.playerView.controllerShowTimeoutMs = 3000
                binding.playerView.controllerAutoShow = false

                exo.trackSelectionParameters = exo.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()

                // Bottoni nella barra
                binding.playerView.findViewById<View>(R.id.btnSubtitles)?.setOnClickListener { cycleSubtitles() }
                binding.playerView.findViewById<View>(R.id.btnGoToStart)?.setOnClickListener {
                    exo.seekTo(0L)
                    showOverlay("⏮ Inizio")
                }
                binding.playerView.findViewById<View>(R.id.btnGoToEnd)?.setOnClickListener {
                    exo.seekTo(exo.duration - 5000L)
                    showOverlay("⏭ Fine")
                }
                binding.playerView.findViewById<View>(R.id.btnClose)?.setOnClickListener {
                    finish()
                }

                exo.setMediaItem(mediaItem)
                exo.seekTo(startPosition)
                exo.prepare()
                exo.playWhenReady = true

                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            resumeManager.markWatched(videoUrl)
                            finish()
                        }
                    }
                })
            }
    }

    // ── Sottotitoli ───────────────────────────────────────────────────────────

    private fun cycleSubtitles() {
        if (subtitleTracks.isEmpty()) return
        currentSubtitleIndex++
        if (currentSubtitleIndex >= subtitleTracks.size) currentSubtitleIndex = -1
        val exo = player ?: return
        if (currentSubtitleIndex == -1) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            showOverlay("⬜ Sub off")
        } else {
            val track = subtitleTracks[currentSubtitleIndex]
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage(track.language).build()
            showOverlay("💬 ${track.title}")
        }
    }

    // ─── HUD ───────────────────────────────────────────────────────────────────

    private fun updateHud() {
        val exo = player ?: return
        val position = exo.currentPosition
        val duration = exo.duration
        val nowStr = timeFormat.format(Date())
        val endStr = if (duration > 0)
            timeFormat.format(Date(System.currentTimeMillis() + (duration - position)))
        else "--:--"
        binding.tvHudLeft.text = "$nowStr → $endStr"
        binding.tvHudRight.text = "${formatDuration(position)} / ${formatDuration(duration)}"
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0) return "--:--"
        val totalSec = ms / 1000
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    // ─── GESTURE ───────────────────────────────────────────────────────────────

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (binding.playerView.isControllerFullyVisible) binding.playerView.hideController()
                else binding.playerView.showController()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // Doppio tap funziona solo fuori dalla zona centrale
                val w = binding.root.width
                val x = e.x
                if (x < w * 0.33f || x > w * 0.66f) {
                    seekBy(if (x > w / 2) 10_000L else -10_000L)
                }
                return true
            }
        })
    }

    private fun setupTouchListener() {
        binding.playerView.setOnTouchListener { _, event ->
            // Se la barra controlli è visibile e il touch è nella zona inferiore (ultimi 15%),
            // non intercettare — lascia che i bottoni ricevano il click
            val screenHeight = binding.root.height.toFloat()
            if (binding.playerView.isControllerFullyVisible &&
                event.y > screenHeight * 0.85f) {
                return@setOnTouchListener false
            }

            gestureDetector.onTouchEvent(event)
            val screenWidth = binding.root.width.toFloat()

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    gestureStartX = event.x
                    gestureStartY = event.y
                    gestureType = GestureType.NONE
                    initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    initialBrightness = getCurrentBrightness()
                    currentVolumeMultiplier = 1.0f
                    // Reset seek velocità
                    seekVelocityActive = false
                    seekAccumulator = player?.currentPosition ?: 0L
                    seekLastX = event.x
                    seekLastTime = System.currentTimeMillis()
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - gestureStartX
                    val dy = event.y - gestureStartY

                    if (gestureType == GestureType.NONE && (abs(dx) > 20 || abs(dy) > 20)) {
                        val inCenterZone = gestureStartX in (screenWidth * 0.33f)..(screenWidth * 0.66f)
                        gestureType = if (abs(dx) > abs(dy) && inCenterZone) {
                            seekVelocityActive = true
                            GestureType.SEEK
                        } else if (abs(dx) <= abs(dy)) {
                            if (gestureStartX < screenWidth / 2) GestureType.BRIGHTNESS
                            else GestureType.VOLUME
                        } else GestureType.NONE
                    }

                    when (gestureType) {
                        GestureType.SEEK -> handleVelocitySeek(event.x)
                        GestureType.VOLUME -> handleVolumeGesture(dy, screenHeight)
                        GestureType.BRIGHTNESS -> handleBrightnessGesture(dy, screenHeight)
                        else -> {}
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gestureType == GestureType.SEEK && seekVelocityActive) {
                        // Applica il seek alla posizione accumulata
                        player?.seekTo(seekAccumulator.coerceIn(0L, player?.duration ?: Long.MAX_VALUE))
                    }
                    seekVelocityActive = false
                    gestureType = GestureType.NONE
                    hideOverlayDelayed()
                }
            }
            true
        }
    }

    // Seek basato sulla velocità del dito
    private fun handleVelocitySeek(currentX: Float) {
        val now = System.currentTimeMillis()
        val dt = (now - seekLastTime).coerceAtLeast(1L)
        val dx = currentX - seekLastX

        // Velocità in px/ms → converti in ms di video
        // Più veloce il dito, più si salta (scala: 1px/ms = 5 secondi di video)
        val velocity = dx / dt  // px/ms
        val seekDelta = (velocity * 5000L).toLong()  // ms di video per px/ms di velocità

        seekAccumulator = (seekAccumulator + seekDelta).coerceIn(0L, player?.duration ?: Long.MAX_VALUE)

        showOverlay(formatDuration(seekAccumulator))

        seekLastX = currentX
        seekLastTime = now
    }

    private fun seekBy(ms: Long) {
        player?.let {
            val target = (it.currentPosition + ms).coerceIn(0L, it.duration)
            it.seekTo(target)
            showOverlay(if (ms > 0) "⏩ +${formatDuration(abs(ms))}" else "⏪ -${formatDuration(abs(ms))}")
        }
    }

    private fun handleVolumeGesture(dy: Float, screenHeight: Float) {
        val rawTarget = initialVolume + (-dy / screenHeight * maxSystemVolume * maxVolumeBoost)
        if (rawTarget <= maxSystemVolume) {
            val target = rawTarget.coerceIn(0f, maxSystemVolume.toFloat()).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            player?.volume = 1.0f
            showOverlay("🔊 ${(target * 100f / maxSystemVolume).toInt()}%")
        } else {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxSystemVolume, 0)
            currentVolumeMultiplier = (rawTarget / maxSystemVolume).coerceIn(1f, maxVolumeBoost)
            player?.volume = currentVolumeMultiplier
            showOverlay("🔊 ${(currentVolumeMultiplier * 100).toInt()}% 🔥")
        }
    }

    private fun handleBrightnessGesture(dy: Float, screenHeight: Float) {
        val target = (initialBrightness - dy / screenHeight).coerceIn(0.01f, 1.0f)
        window.attributes = window.attributes.also { it.screenBrightness = target }
        showOverlay("☀️ ${(target * 100).toInt()}%")
    }

    private fun getCurrentBrightness(): Float {
        val b = window.attributes.screenBrightness
        return if (b < 0) try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) { 0.5f } else b
    }

    private fun showOverlay(text: String) {
        binding.tvOverlay.text = text
        binding.tvOverlay.visibility = View.VISIBLE
        overlayHandler.removeCallbacksAndMessages(null)
    }

    private fun hideOverlayDelayed(delayMs: Long = 1500) {
        overlayHandler.postDelayed({ binding.tvOverlay.visibility = View.GONE }, delayMs)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        saveCurrentPosition()
    }

    override fun onPause() {
        super.onPause()
        saveCurrentPosition()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        saveCurrentPosition()
        hudHandler.removeCallbacks(hudRunnable)
        player?.release(); player = null
    }

    private fun saveCurrentPosition() {
        player?.let {
            val pos = it.currentPosition
            val dur = it.duration
            if (dur > 0) resumeManager.savePosition(videoUrl, pos, dur)
        }
    }

    private fun setImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) setImmersive()
    }
}
