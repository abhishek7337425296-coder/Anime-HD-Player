package com.animehd.player

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.GridView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.SeekBar
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.graphics.drawable.GradientDrawable
import android.widget.ProgressBar
import kotlin.math.abs
import kotlin.math.roundToInt
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import android.view.Surface
import android.animation.ValueAnimator
import org.videolan.libvlc.util.VLCVideoLayout
import java.math.BigInteger
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private var currentVideoFd: ParcelFileDescriptor? = null
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var shaderView: LibVlcShaderView
    private lateinit var shaderStatusBadge: TextView
    private var shaderSurface: Surface? = null
    private var shaderAttached = false
    private var shaderFailed = false
    private var scanAnimator: ValueAnimator? = null
    private lateinit var media3View: PlayerView
    private var exoPlayer: ExoPlayer? = null
    private var media3Active = false
    private lateinit var videoContainer: FrameLayout
    private lateinit var playerOverlay: LinearLayout
    private lateinit var topControls: LinearLayout
    private lateinit var centerControls: LinearLayout
    private lateinit var timelineControls: LinearLayout
    private lateinit var bottomControls: LinearLayout
    private lateinit var gestureHud: LinearLayout
    private lateinit var gestureHudLabel: TextView
    private lateinit var gestureHudBar: ProgressBar
    private lateinit var audioManager: AudioManager
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var durationText: TextView
    private lateinit var settingsButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var rootLayout: android.widget.LinearLayout
    private lateinit var titleView: TextView
    private lateinit var audioButton: MaterialButton
    private lateinit var enhanceButton: MaterialButton
    private lateinit var hdButton: MaterialButton
    private lateinit var videoGrid: GridView
    private lateinit var playPauseButton: MaterialButton
    private lateinit var gestureDetector: GestureDetector
    private var seekIntervalMs = 10_000L
    private lateinit var backButton: MaterialButton
    private val uiHandler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private val progressUpdater = object : Runnable {
        override fun run() {
            val mp = player
            if (fullscreen && !userSeeking) {
                if (media3Active) {
                    val ep = exoPlayer
                    val length = ep?.duration?.takeIf { it > 0L } ?: 0L
                    val time = ep?.currentPosition?.coerceAtLeast(0L) ?: 0L
                    if (length > 0L) {
                        seekBar.progress = ((time * 1000L) / length).toInt().coerceIn(0, 1000)
                        durationText.text = formatTime(length)
                        currentTimeText.text = formatTime(time)
                    }
                    playPauseButton.text = if (ep?.isPlaying == true) "Ⅱ" else "▶"
                } else if (mp != null) {
                    val length = mp.length.coerceAtLeast(0L)
                    val time = mp.time.coerceAtLeast(0L)
                    if (length > 0L) {
                        seekBar.progress = ((time * 1000L) / length).toInt().coerceIn(0, 1000)
                        durationText.text = formatTime(length)
                        currentTimeText.text = formatTime(time)
                    }
                    playPauseButton.text = if (mp.isPlaying) "Ⅱ" else "▶"
                }
            }
            uiHandler.postDelayed(this, 500L)
        }
    }
    private var videoItems: List<VideoEntry> = emptyList()
    private var fullscreen = false
    private var enhancementEnabled = false
    // Real-time conventional edge sharpening; this is not AI super-resolution.
    private var hdEnhancementEnabled = false
    private var currentVideoUri: Uri? = null
    private var currentVideoName: String = "Video"
    private var pendingSeekMs: Long = 0L
    private var controlsVisible = true
    private var gestureStartY = 0f
    private var gestureStartValue = 0
    private var gestureSide = 0
    private var gestureMoved = false
    private var gestureStartBrightness = 50
    private var gestureStartVolume = 0
    // VLC software gain: 100% is normal device output; up to 200% amplifies in the player.
    private var softwareVolumePercent = 100
    private var thermalManager: PowerManager? = null
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var latestThermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var thermalProtectionActive = false
    // 0 = original, 1 = Lite shader, 2 = High shader. No RAM-based lock or downgrade.
    private var animeShaderMode = 0
    private var activeAnimeShaderEffect: AnimeLineShaderEffect? = null
    private val hideControlsRunnable = Runnable {
        if (fullscreen && (if (media3Active) exoPlayer?.isPlaying == true else player?.isPlaying == true)) setControlsVisible(false)
    }
    private val hideHudRunnable = Runnable { gestureHud.visibility = View.GONE }
    private data class VideoEntry(val name: String, val uri: Uri, val id: Long)

    private val openVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { playVideo(it, displayName(it)) }
    }
    private val requestVideoAccess = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val allowed = results.values.any { it } || hasVideoPermission()
        if (allowed) loadPhoneVideos()
        else statusText.text = "Video access was not allowed. Enable video access in app settings."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rootLayout = findViewById(R.id.rootLayout)
        titleView = findViewById(R.id.titleView)
        videoLayout = findViewById(R.id.playerView)
        shaderView = findViewById(R.id.libVlcShaderView)
        shaderStatusBadge = findViewById(R.id.shaderStatusBadge)
        videoLayout.visibility = View.GONE
        shaderView.callback = object : LibVlcShaderView.Callback {
            override fun onSurfaceReady(surface: Surface) { shaderSurface = surface; shaderFailed = false; attachLibVlcToShaderSurface() }
            override fun onRendererError(message: String) { activateVlcFallback("GL renderer failed: $message") }
            override fun onSurfaceReleased() {
                if (shaderAttached) {
                    try { player?.vlcVout?.detachViews() } catch (_: Exception) { }
                    shaderAttached = false
                }
            }
        }
        media3View = findViewById(R.id.media3PlayerView)
        media3View.useController = false
        media3View.visibility = View.GONE
        videoContainer = findViewById(R.id.videoContainer)
        playerOverlay = findViewById(R.id.playerOverlay)
        topControls = findViewById(R.id.topControls)
        centerControls = findViewById(R.id.centerControls)
        timelineControls = findViewById(R.id.timelineControls)
        bottomControls = findViewById(R.id.bottomControls)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        seekBar = findViewById(R.id.seekBar)
        currentTimeText = findViewById(R.id.currentTimeText)
        durationText = findViewById(R.id.durationText)
        settingsButton = findViewById(R.id.settingsButton)
        statusText = findViewById(R.id.statusText)
        emptyMessage = findViewById(R.id.emptyMessage)
        audioButton = findViewById(R.id.audioButton)
        enhanceButton = findViewById(R.id.enhanceButton)
        hdButton = findViewById(R.id.hdButton)
        animeShaderMode = getSharedPreferences("player_settings", MODE_PRIVATE).getInt("anime_shader_mode", 0).coerceIn(0, 2)
        hdButton.text = when (animeShaderMode) { 0 -> "Anime4K: OFF"; 1 -> "Anime4K: Lite"; else -> "Anime4K: High" }
        videoGrid = findViewById(R.id.videoGrid)
        playPauseButton = findViewById(R.id.playPauseButton)
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsVisible) {
                    setControlsVisible(false)
                    uiHandler.removeCallbacks(hideControlsRunnable)
                } else revealControls()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val x = e.x
                val width = playerOverlay.width.toFloat().coerceAtLeast(1f)
                when {
                    x < width * 0.42f -> {
                        seekBy(-seekIntervalMs)
                        showGestureHud(0, "−${seekIntervalMs / 1000}s", 1, 1)
                    }
                    x > width * 0.58f -> {
                        seekBy(seekIntervalMs)
                        showGestureHud(1, "+${seekIntervalMs / 1000}s", 1, 1)
                    }
                    else -> {
                        if (media3Active) exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
                        else player?.let { if (it.isPlaying) it.pause() else it.play() }
                    }
                }
                revealControls()
                return true
            }
        })
        backButton = findViewById(R.id.backButton)
        setupGestureHud()
        playerOverlay.setOnTouchListener { _, event -> handleOverlayTouch(event) }
        videoContainer.visibility = View.GONE
        playPauseButton.setOnClickListener {
            if (media3Active) {
                exoPlayer?.let { if (it.isPlaying) { it.pause(); playPauseButton.text = "▶" } else { it.play(); playPauseButton.text = "Ⅱ" } }
            } else {
                player?.let { if (it.isPlaying) { it.pause(); playPauseButton.text = "▶" } else { it.play(); playPauseButton.text = "Ⅱ" } }
            }
            revealControls()
        }
        settingsButton.setOnClickListener { showPlaybackSettings(); revealControls() }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val length = if (media3Active) (exoPlayer?.duration ?: 0L) else (player?.length ?: 0L)
                    if (length > 0L) currentTimeText.text = formatTime(length * progress / 1000L)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { userSeeking = true; revealControls() }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                val length = if (media3Active) (exoPlayer?.duration ?: 0L) else (player?.length ?: 0L)
                if (length > 0L) {
                    val target = length * (bar?.progress ?: 0) / 1000L
                    if (media3Active) exoPlayer?.seekTo(target) else player?.setTime(target)
                }
                userSeeking = false
            }
        })
        backButton.setOnClickListener { player?.stop(); exoPlayer?.pause(); exitFullscreen() }
        audioButton.setOnClickListener { showTrackOptions(); revealControls() }
        hdButton.setOnClickListener {
            showAnimeShaderOptions()
            revealControls()
        }
        enhanceButton.setOnClickListener {
            if (currentVideoUri == null) statusText.text = "Open a video before enabling Auto Enhance."
            else if (thermalProtectionActive) statusText.text = "Enhancement is paused while the device is severely hot."
            else {
                enhancementEnabled = true; animeShaderMode = 2
                getSharedPreferences("player_settings", MODE_PRIVATE).edit().putInt("anime_shader_mode", 2).apply()
                hdButton.text = "Anime4K: High"; enhanceButton.text = "Enhance: ON"
                applyShaderMode(2, runScan = true)
            }
            revealControls()
        }
        uiHandler.post(progressUpdater)
        initializePlayer()
        startThermalMonitoring()
        handleIncomingIntent(intent)
        if (intent?.action == Intent.ACTION_MAIN) requestPhoneVideoAccess()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(incoming: Intent?) {
        if (incoming == null) return
        val uri = when (incoming.action) {
            Intent.ACTION_VIEW -> incoming.data
            Intent.ACTION_SEND -> {
                incoming.clipData?.getItemAt(0)?.uri ?: run {
                    @Suppress("DEPRECATION")
                    incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
            }
            else -> null
        }
        uri?.let { playVideo(it, displayName(it)) }
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "Selected video"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) name = cursor.getString(index) ?: name
                }
            }
        } catch (_: Exception) { }
        return name
    }

    private fun requiredVideoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasVideoPermission(): Boolean {
        return requiredVideoPermissions().any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPhoneVideoAccess() {
        if (hasVideoPermission()) loadPhoneVideos()
        else requestVideoAccess.launch(requiredVideoPermissions())
    }

    private fun loadPhoneVideos() {
        statusText.text = "Loading videos from phone storage…"
        val found = mutableListOf<VideoEntry>()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME)
        try {
            contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn) ?: "Video"
                    found.add(VideoEntry(name, ContentUris.withAppendedId(collection, id), id))
                }
            }
            videoItems = found.sortedWith { a, b -> naturalCompare(a.name, b.name) }
            statusText.text = "Found ${videoItems.size} phone video(s)"
            statusText.visibility = View.GONE
            if (videoItems.isNotEmpty()) {
                emptyMessage.text = "${videoItems.size} videos • Tap a thumbnail to play"
                videoGrid.adapter = object : BaseAdapter() {
                    override fun getCount() = videoItems.size
                    override fun getItem(position: Int) = videoItems[position]
                    override fun getItemId(position: Int) = videoItems[position].id
                    override fun getView(position: Int, convertView: android.view.View?, parent: ViewGroup): android.view.View {
                        val item = videoItems[position]
                        val tile = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(5, 5, 5, 9)
                        }
                        tile.removeAllViews()
                        val thumb = ImageView(this@MainActivity).apply {
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 140)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(0xFF292832.toInt())
                        }
                        try {
                            thumb.setImageBitmap(MediaStore.Video.Thumbnails.getThumbnail(
                                contentResolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null
                            ))
                        } catch (_: Exception) { }
                        val label = TextView(this@MainActivity).apply {
                            text = item.name
                            setTextColor(0xFFFFFFFF.toInt())
                            textSize = 12f
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setPadding(2, 6, 2, 0)
                        }
                        tile.addView(thumb)
                        tile.addView(label)
                        tile.setOnClickListener { playVideo(item.uri, item.name) }
                        return tile
                    }
                }
                videoGrid.visibility = android.view.View.VISIBLE
            } else {
                emptyMessage.text = "No indexed videos found. Save Telegram videos to Downloads or Movies first."
                videoGrid.visibility = android.view.View.GONE
            }
        } catch (_: SecurityException) {
            statusText.text = "Storage access was limited. Allow video access in Android app settings."
        } catch (e: Exception) {
            statusText.text = "Could not read phone videos: ${e.javaClass.simpleName}"
        }
    }

    private fun startThermalMonitoring() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        thermalManager = manager
        latestThermalStatus = manager.currentThermalStatus
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            runOnUiThread {
                latestThermalStatus = status
                if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                    thermalProtectionActive = true
                    if (animeShaderMode > 0) {
                        scanAnimator?.cancel(); shaderView.setMode(0); shaderView.setScanX(1f); updateShaderBadge()
                    }
                    statusText.text = "Device is very hot • video enhancement temporarily paused"
                } else {
                    val wasProtected = thermalProtectionActive
                    thermalProtectionActive = false
                    if (wasProtected && animeShaderMode > 0) { applyShaderMode(animeShaderMode); statusText.text = "Device temperature improved • selected GL shader restored" }
                    if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
                        statusText.text = "Device temperature is elevated • consider turning off enhancements"
                    }
                }
            }
        }
        thermalListener = listener
        manager.addThermalStatusListener(listener)
    }

    private fun stopThermalMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { listener ->
                thermalManager?.removeThermalStatusListener(listener)
            }
        }
        thermalListener = null
        thermalManager = null
    }

    private fun thermalStatusLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "Normal"
        PowerManager.THERMAL_STATUS_LIGHT -> "Light"
        PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown level"
        else -> "Unknown"
    }

    private fun showDeviceHealthInfo() {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
        val maxMb = runtime.maxMemory() / (1024L * 1024L)
        val thermalText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatusLabel(latestThermalStatus)
        } else {
            "Unavailable on Android versions below 10"
        }
        val protectionText = if (thermalProtectionActive) {
            "Thermal protection is active. Video enhancement has been paused."
        } else {
            "If the device becomes severely hot, video enhancement will be paused automatically."
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Device temperature & performance")
            .setMessage(
                "Thermal status: $thermalText\n" +
                "App memory: $usedMb MB used / $maxMb MB limit\n\n" +
                "$protectionText\n\n" +
                "This reports Android thermal status and app memory; it is not a benchmark or a measured FPS test."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun initializePlayer() {
        if (player != null) { attachLibVlcToShaderSurface(); return }
        try {
            val vlc = LibVLC(this, arrayListOf("--audio-time-stretch", "--no-video-title-show"))
            libVLC = vlc
            player = MediaPlayer(vlc).also { mp ->
                mp.setEventListener { event ->
                    runOnUiThread {
                        when (event.type) {
                            MediaPlayer.Event.Opening -> statusText.text = "Opening video…"
                            MediaPlayer.Event.Buffering -> statusText.text = "Buffering…"
                            MediaPlayer.Event.Playing -> {
                                mp.setScale(0f); mp.setVolume(softwareVolumePercent); updateShaderBadge()
                                statusText.text = if (animeShaderMode == 0 || thermalProtectionActive) "Playing • LibVLC • Offline • No ads" else "Custom offline GL shader active • LibVLC playback"
                                scheduleHideControls(); playPauseButton.text = "Ⅱ"
                                if (pendingSeekMs > 0L) { val restoreAt = pendingSeekMs; pendingSeekMs = 0L; mp.time = restoreAt.coerceAtMost(mp.length.coerceAtLeast(0L)) }
                            }
                            MediaPlayer.Event.Paused -> { statusText.text = "Paused"; playPauseButton.text = "▶" }
                            MediaPlayer.Event.EndReached -> statusText.text = "Playback finished"
                            MediaPlayer.Event.EncounteredError -> statusText.text = "VLC could not decode this file. Try another copy or format."
                        }
                    }
                }
            }
            attachLibVlcToShaderSurface()
        } catch (e: Exception) { statusText.text = "Could not start VLC engine: ${e.javaClass.simpleName}" }
    }

    private fun attachLibVlcToShaderSurface() {
        val mp = player ?: return
        val surface = shaderSurface ?: return
        if (shaderFailed || shaderAttached) return
        try {
            mp.vlcVout.setVideoSurface(surface, null)
            mp.vlcVout.attachViews(object : IVLCVout.OnNewVideoLayoutListener {
                override fun onNewVideoLayout(vout: IVLCVout, width: Int, height: Int, visibleWidth: Int, visibleHeight: Int, sarNum: Int, sarDen: Int) {
                    runOnUiThread { shaderView.setVideoSize(width, height, visibleWidth, visibleHeight, sarNum, sarDen) }
                }
            })
            shaderAttached = true; videoLayout.visibility = View.GONE; shaderView.visibility = View.VISIBLE
            shaderView.setMode(if (thermalProtectionActive) 0 else animeShaderMode); updateShaderBadge()
        } catch (e: Exception) { activateVlcFallback("Could not attach GL surface: ${e.message ?: e.javaClass.simpleName}") }
    }

    private fun activateVlcFallback(reason: String) {
        if (shaderFailed) return
        shaderFailed = true
        try { player?.vlcVout?.detachViews() } catch (_: Exception) { }
        shaderAttached = false; shaderView.visibility = View.GONE; shaderStatusBadge.visibility = View.GONE
        videoLayout.visibility = View.VISIBLE
        try { player?.attachViews(videoLayout, null, false, false) }
        catch (_: Exception) { statusText.text = "GL renderer and VLC fallback unavailable."; return }
        statusText.text = "$reason • using standard LibVLC video output"
    }

    private fun updateShaderBadge() {
        val active = if (thermalProtectionActive) 0 else animeShaderMode
        if (shaderFailed || active == 0) shaderStatusBadge.visibility = View.GONE
        else { shaderStatusBadge.text = if (active == 1) "GL Shader: Anime4K Lite" else "GL Shader: Anime4K High"; shaderStatusBadge.visibility = View.VISIBLE }
    }

    private fun applyShaderMode(mode: Int, runScan: Boolean = false) {
        animeShaderMode = mode.coerceIn(0, 2)
        shaderView.setMode(if (thermalProtectionActive) 0 else animeShaderMode)
        scanAnimator?.cancel()
        if (runScan && animeShaderMode > 0 && !thermalProtectionActive) {
            shaderView.setScanX(0f)
            scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2_000L
                addUpdateListener { shaderView.setScanX(it.animatedValue as Float) }
                start()
            }
        } else shaderView.setScanX(1f)
        updateShaderBadge()
    }

    private fun playVideo(uri: Uri, name: String) {
        initializePlayer()
        val vlc = libVLC; val mp = player
        if (vlc == null || mp == null) { statusText.text = "Player engine did not start. Please reopen the app."; return }
        currentVideoUri = uri; currentVideoName = name; emptyMessage.text = name; enterFullscreen()
        try {
            mp.stop(); currentVideoFd?.close(); currentVideoFd = null
            val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Android could not open this video")
            currentVideoFd = descriptor
            val media = Media(vlc, descriptor.fileDescriptor)
            media.setHWDecoderEnabled(true, false)
            mp.media = media; media.release(); mp.setVolume(softwareVolumePercent)
            applyShaderMode(if (thermalProtectionActive) 0 else animeShaderMode)
            mp.play()
        } catch (e: Exception) { currentVideoFd?.close(); currentVideoFd = null; statusText.text = "Could not open video: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun playVideoWithGpuEnhancement(uri: Uri, name: String, mode: Int, startScan: Boolean = false) {
        currentVideoUri = uri; currentVideoName = name
        applyShaderMode(mode, runScan = startScan)
    }

    private fun showAnimeShaderOptions() {
        val labels = arrayOf("Off (Original)", "Anime4K Lite", "Anime4K High")
        MaterialAlertDialogBuilder(this).setTitle("Video enhancement")
            .setSingleChoiceItems(labels, animeShaderMode.coerceIn(0, 2)) { dialog, which ->
                if (thermalProtectionActive && which != 0) { statusText.text = "Enhancement is paused while the device is severely hot."; return@setSingleChoiceItems }
                animeShaderMode = which
                getSharedPreferences("player_settings", MODE_PRIVATE).edit().putInt("anime_shader_mode", which).apply()
                hdButton.text = when (which) { 0 -> "Anime4K: OFF"; 1 -> "Anime4K: Lite"; else -> "Anime4K: High" }
                enhancementEnabled = which == 2
                enhanceButton.text = if (which == 2) "Enhance: ON" else "Auto Enhance"
                applyShaderMode(which, runScan = which == 2)
                if (currentVideoUri == null) statusText.text = if (which == 0) "Original video mode selected" else "Enhancement profile saved for the next video"
                dialog.dismiss()
            }.setNegativeButton("Close", null).show()
    }

    private fun enterFullscreen() {
        if (fullscreen) return
        fullscreen = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        titleView.visibility = android.view.View.GONE
        videoGrid.visibility = android.view.View.GONE
        videoContainer.visibility = android.view.View.VISIBLE
        emptyMessage.visibility = android.view.View.GONE
        statusText.visibility = View.GONE
        rootLayout.setPadding(0, 0, 0, 0)
        setControlsVisible(true)
        scheduleHideControls()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        fullscreen = false
        uiHandler.removeCallbacks(hideControlsRunnable)
        uiHandler.removeCallbacks(hideHudRunnable)
        gestureHud.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        titleView.visibility = android.view.View.VISIBLE
        videoContainer.visibility = android.view.View.GONE
        videoGrid.visibility = android.view.View.VISIBLE
        emptyMessage.visibility = android.view.View.VISIBLE
        statusText.visibility = View.VISIBLE
        rootLayout.setPadding(16, 16, 16, 16)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    @Deprecated("Deprecated in Android 13; retained for the app's back navigation")
    override fun onBackPressed() {
        if (fullscreen) exitFullscreen() else super.onBackPressed()
    }


    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun setupGestureHud() {
        gestureHudLabel = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
        }
        gestureHudBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 200
            progress = 100
            progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x66888888)
        }
        gestureHud = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xCC202027.toInt())
                cornerRadius = dp(12).toFloat()
            }
            visibility = View.GONE
            addView(gestureHudLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))
            addView(gestureHudBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)))
        }
        videoContainer.addView(gestureHud, FrameLayout.LayoutParams(dp(148), dp(58), Gravity.CENTER))
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        val visibility = if (visible) View.VISIBLE else View.GONE
        topControls.visibility = visibility
        centerControls.visibility = visibility
        timelineControls.visibility = visibility
        bottomControls.visibility = visibility
        if (visible) statusText.visibility = View.GONE
    }

    private fun scheduleHideControls() {
        uiHandler.removeCallbacks(hideControlsRunnable)
        if (fullscreen && (if (media3Active) exoPlayer?.isPlaying == true else player?.isPlaying == true)) {
            uiHandler.postDelayed(hideControlsRunnable, 3000L)
        }
    }

    private fun revealControls() {
        if (!fullscreen) return
        setControlsVisible(true)
        scheduleHideControls()
    }

    private fun showGestureHud(side: Int, label: String, value: Int, maximum: Int) {
        gestureHudLabel.text = label
        gestureHudBar.max = maximum
        gestureHudBar.progress = value.coerceIn(0, maximum)
        val params = gestureHud.layoutParams as FrameLayout.LayoutParams
        params.gravity = if (side == 0) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.CENTER_VERTICAL or Gravity.END
        params.marginStart = if (side == 0) dp(22) else 0
        params.marginEnd = if (side == 1) dp(22) else 0
        gestureHud.layoutParams = params
        gestureHud.visibility = View.VISIBLE
        uiHandler.removeCallbacks(hideHudRunnable)
        uiHandler.postDelayed(hideHudRunnable, 1200L)
    }

    private fun currentBrightnessPercent(): Int {
        val value = window.attributes.screenBrightness
        return if (value >= 0f) (value * 100f).roundToInt().coerceIn(1, 100) else 50
    }

    private fun handleOverlayTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDetector.onTouchEvent(event)
                gestureStartY = event.y
                gestureMoved = false
                gestureSide = if (event.x < playerOverlay.width / 2f) 0 else 1
                gestureStartBrightness = currentBrightnessPercent()
                // Read the player's software gain, not the Android device volume.
                gestureStartVolume = softwareVolumePercent
                gestureStartValue = if (gestureSide == 0) gestureStartBrightness else gestureStartVolume
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                gestureDetector.onTouchEvent(event)
                val deltaY = gestureStartY - event.y
                if (!gestureMoved && abs(deltaY) > dp(8)) gestureMoved = true
                if (gestureMoved) {
                    val fraction = (deltaY / playerOverlay.height.coerceAtLeast(1).toFloat())
                    if (gestureSide == 0) {
                        val level = (gestureStartBrightness + fraction * 100f).roundToInt().coerceIn(1, 100)
                        val attributes = window.attributes
                        attributes.screenBrightness = level / 100f
                        window.attributes = attributes
                        showGestureHud(0, "Brightness  $level%", level, 100)
                    } else {
                        val level = (gestureStartVolume + fraction * 200f).roundToInt().coerceIn(0, 200)
                        softwareVolumePercent = level
                        // LibVLC supports software player volume up to 200%, beyond the device's
                        // normal 100% output level. Android's hardware volume remains unchanged.
                        player?.setVolume(level)
                        showGestureHud(1, "Player volume  $level%", level, 200)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                gestureDetector.onTouchEvent(event)
                if (gestureMoved) {
                    uiHandler.removeCallbacks(hideControlsRunnable)
                    if (player?.isPlaying == true) scheduleHideControls()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { gestureDetector.onTouchEvent(event); return true }
        }
        return true
    }

    private fun seekBy(offsetMs: Long) {
        if (media3Active) {
            val ep = exoPlayer ?: return
            val length = ep.duration
            if (length > 0L) ep.seekTo((ep.currentPosition + offsetMs).coerceIn(0L, length))
        } else {
            val mp = player ?: return
            val length = mp.length
            if (length <= 0L || !mp.isSeekable) return
            mp.setTime((mp.time + offsetMs).coerceIn(0L, length))
        }
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }

    private fun showPlaybackSettings() {
        val options = arrayOf("Playback speed", "Player volume (up to 200%)", "Screen brightness", "Reset screen brightness", "Gesture settings", "Device temperature & performance")
        MaterialAlertDialogBuilder(this)
            .setTitle("Playback settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPlaybackSpeedOptions()
                    1 -> showPlayerVolumeControl()
                    2 -> showScreenBrightnessControl()
                    3 -> {
                        val attributes = window.attributes
                        attributes.screenBrightness = -1f
                        window.attributes = attributes
                        statusText.text = "Screen brightness follows device setting"
                    }
                    4 -> showGestureSettings()
                    5 -> showDeviceHealthInfo()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPlayerVolumeControl() {
        val volumeSeek = SeekBar(this).apply {
            max = 200
            progress = softwareVolumePercent.coerceIn(0, 200)
        }
        val volumeLabel = TextView(this).apply {
            text = "Player volume: ${softwareVolumePercent}%"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 15f
            setPadding(0, 0, 0, dp(8))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(volumeLabel)
            addView(volumeSeek)
            addView(TextView(this@MainActivity).apply {
                text = "Above 100% uses digital amplification and may cause distortion."
                setTextColor(0xFFBBBBBB.toInt())
                textSize = 12f
                setPadding(0, dp(6), 0, 0)
            })
        }
        volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    softwareVolumePercent = value.coerceIn(0, 200)
                    player?.setVolume(softwareVolumePercent)
                    volumeLabel.text = "Player volume: ${softwareVolumePercent}%"
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        MaterialAlertDialogBuilder(this)
            .setTitle("Player volume")
            .setView(content)
            .setPositiveButton("Done", null)
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showGestureSettings() {
        val labels = arrayOf("5 seconds", "10 seconds", "15 seconds", "20 seconds", "30 seconds")
        val values = longArrayOf(5_000L, 10_000L, 15_000L, 20_000L, 30_000L)
        val selected = values.indexOf(seekIntervalMs).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Gesture settings • Double-tap seek")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                seekIntervalMs = values[which]
                dialog.dismiss()
                statusText.text = "Double-tap skips ${labels[which]}"
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showPlaybackSpeedOptions() {
        val speeds = arrayOf("0.5×", "0.75×", "1.0× (Normal)", "1.25×", "1.5×", "2.0×")
        val rates = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        MaterialAlertDialogBuilder(this)
            .setTitle("Playback speed")
            .setItems(speeds) { _, which ->
                player?.setRate(rates[which])
                statusText.text = "Playback speed: ${speeds[which]}"
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showScreenBrightnessControl() {
        val seek = SeekBar(this).apply {
            max = 100
            val current = window.attributes.screenBrightness
            progress = if (current < 0f) 50 else (current * 100f).toInt().coerceIn(1, 100)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 8)
            addView(TextView(this@MainActivity).apply {
                text = "Adjust display brightness while watching"
                setTextColor(0xFFFFFFFF.toInt())
            })
            addView(seek)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Screen brightness")
            .setView(content)
            .setPositiveButton("Done", null)
            .setNegativeButton("Close", null)
            .create()
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    val attributes = window.attributes
                    attributes.screenBrightness = (value.coerceAtLeast(1) / 100f)
                    window.attributes = attributes
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        dialog.show()
    }

    private fun naturalCompare(first: String, second: String): Int {
        val a = Regex("\\d+|\\D+").findAll(first.lowercase(Locale.ROOT)).map { it.value }.toList()
        val b = Regex("\\d+|\\D+").findAll(second.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i]; val y = b[i]
            val result = if (x.firstOrNull()?.isDigit() == true && y.firstOrNull()?.isDigit() == true)
                BigInteger(x).compareTo(BigInteger(y)) else x.compareTo(y)
            if (result != 0) return result
        }
        return a.size.compareTo(b.size)
    }

    private fun showEpisodes() {
        if (videoItems.isEmpty()) { statusText.text = "Tap Phone videos to scan your storage."; return }
        MaterialAlertDialogBuilder(this)
            .setTitle("Episodes • ${videoItems.size} videos")
            .setItems(videoItems.map { it.name }.toTypedArray()) { _, which ->
                val item = videoItems[which]
                playVideo(item.uri, item.name)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun friendlyTrackName(raw: String?, fallback: String): String {
        val value = raw.orEmpty()
        val normalizedCode = value.trim().lowercase(Locale.ROOT)
            .substringBefore(',').substringBefore(';').substringBefore('-')
        val languageCodes = mapOf(
            "te" to "Telugu", "tel" to "Telugu",
            "ta" to "Tamil", "tam" to "Tamil",
            "hi" to "Hindi", "hin" to "Hindi",
            "en" to "English", "eng" to "English",
            "ja" to "Japanese", "jpn" to "Japanese",
            "ko" to "Korean", "kor" to "Korean",
            "zh" to "Chinese", "zho" to "Chinese", "chi" to "Chinese",
            "es" to "Spanish", "spa" to "Spanish",
            "fr" to "French", "fra" to "French", "fre" to "French",
            "de" to "German", "deu" to "German", "ger" to "German",
            "ar" to "Arabic", "ara" to "Arabic",
            "pt" to "Portuguese", "por" to "Portuguese",
            "ru" to "Russian", "rus" to "Russian",
            "it" to "Italian", "ita" to "Italian",
            "und" to "Unknown language", "zxx" to "No spoken language"
        )
        languageCodes[normalizedCode]?.let { return it }
        val known = listOf("Telugu", "Tamil", "Hindi", "English", "Japanese", "Korean", "Chinese", "Spanish", "French", "German", "Arabic", "Portuguese", "Russian")
        known.firstOrNull { value.contains(it, ignoreCase = true) }?.let { return it }
        val cleaned = value.substringBefore(",").substringAfterLast(" - ")
            .replace(Regex("\\[[^]]*]"), "")
            .replace(Regex("(?i)handler_name=.*|comment=.*|encoder=.*"), "")
            .replace('_', ' ').trim().trim('-', '•', ' ')
        return cleaned.takeIf { it.isNotBlank() && it.length < 48 } ?: fallback
    }

    private fun showTrackOptions() {
        if (media3Active) {
            val ep = exoPlayer ?: return
            val options = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            ep.currentTracks.groups.forEach { group ->
                val isAudio = group.type == C.TRACK_TYPE_AUDIO
                val isText = group.type == C.TRACK_TYPE_TEXT
                if (isAudio || isText) {
                    for (index in 0 until group.length) {
                        val format = group.getTrackFormat(index)
                        val kind = if (isAudio) "Audio" else "Subtitle"
                        val label = friendlyTrackName(format.language ?: format.label, "Track ${index + 1}")
                        options.add("$kind • $label")
                        actions.add {
                            val override = TrackSelectionOverride(group.mediaTrackGroup, listOf(index))
                            ep.trackSelectionParameters = ep.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(group.type, false)
                                .clearOverridesOfType(group.type)
                                .addOverride(override)
                                .build()
                        }
                    }
                }
            }
            if (ep.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
                options.add("Turn subtitles off")
                actions.add {
                    ep.trackSelectionParameters = ep.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .build()
                }
            }
            if (options.isEmpty()) {
                statusText.text = "No selectable audio or subtitle tracks were found."
                return
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("Audio & subtitles")
                .setItems(options.toTypedArray()) { _, which -> actions[which]() }
                .setNegativeButton("Close", null)
                .show()
            return
        }
        val mediaPlayer = player
        if (mediaPlayer == null || mediaPlayer.media == null) {
            statusText.text = "Open a video first to choose audio or subtitles."
            return
        }
        val audioTracks = mediaPlayer.audioTracks?.toList().orEmpty()
        val subtitleTracks = mediaPlayer.spuTracks?.toList().orEmpty()
        if (audioTracks.isEmpty() && subtitleTracks.isEmpty()) {
            statusText.text = "This file has no selectable audio or subtitle tracks."
            return
        }
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        audioTracks.forEach { track ->
            options.add("Audio   •   ${friendlyTrackName(track.name, "Audio track")}")
            actions.add { mediaPlayer.setAudioTrack(track.id) }
        }
        subtitleTracks.forEach { track ->
            options.add("Subtitles   •   ${friendlyTrackName(track.name, "Subtitle track")}")
            actions.add { mediaPlayer.setSpuTrack(track.id) }
        }
        if (subtitleTracks.isNotEmpty()) {
            options.add("Turn subtitles off")
            actions.add { mediaPlayer.setSpuTrack(-1) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Audio & subtitles")
            .setItems(options.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        shaderView.onResume()
        initializePlayer()
        attachLibVlcToShaderSurface()
    }

    override fun onPause() {
        try { if (shaderAttached) player?.vlcVout?.detachViews() } catch (_: Exception) { }
        shaderAttached = false
        shaderView.onPause()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(progressUpdater)
        stopThermalMonitoring()
        uiHandler.removeCallbacks(hideControlsRunnable)
        uiHandler.removeCallbacks(hideHudRunnable)
        player?.stop()
        exoPlayer?.release()
        exoPlayer = null
        activeAnimeShaderEffect = null
        media3View.player = null
        media3Active = false
        currentVideoFd?.close()
        currentVideoFd = null
        try { player?.vlcVout?.detachViews() } catch (_: Exception) { }
        player?.detachViews()
        scanAnimator?.cancel()
        player?.release()
        player = null
        libVLC?.release()
        libVLC = null
    }
}