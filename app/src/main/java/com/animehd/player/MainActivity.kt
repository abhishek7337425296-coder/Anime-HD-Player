package com.animehd.player

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
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
import org.videolan.libvlc.util.VLCVideoLayout
import java.math.BigInteger
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private var currentVideoFd: ParcelFileDescriptor? = null
    private lateinit var videoLayout: VLCVideoLayout
    private lateinit var videoContainer: FrameLayout
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeText: TextView
    private lateinit var durationText: TextView
    private lateinit var settingsButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var rootLayout: android.widget.LinearLayout
    private lateinit var titleView: TextView
    private lateinit var openVideoButton: MaterialButton
    private lateinit var storageButton: MaterialButton
    private lateinit var episodesButton: MaterialButton
    private lateinit var audioButton: MaterialButton
    private lateinit var enhanceButton: MaterialButton
    private lateinit var videoGrid: GridView
    private lateinit var playPauseButton: MaterialButton
    private lateinit var seekBackButton: MaterialButton
    private lateinit var seekForwardButton: MaterialButton
    private lateinit var backButton: MaterialButton
    private val uiHandler = Handler(Looper.getMainLooper())
    private var userSeeking = false
    private val progressUpdater = object : Runnable {
        override fun run() {
            val mp = player
            if (mp != null && fullscreen && !userSeeking) {
                val length = mp.length.coerceAtLeast(0L)
                val time = mp.time.coerceAtLeast(0L)
                if (length > 0L) {
                    seekBar.progress = ((time * 1000L) / length).toInt().coerceIn(0, 1000)
                    durationText.text = formatTime(length)
                    currentTimeText.text = formatTime(time)
                }
                playPauseButton.text = if (mp.isPlaying) "Ⅱ" else "▶"
            }
            uiHandler.postDelayed(this, 500L)
        }
    }
    private var videoItems: List<VideoEntry> = emptyList()
    private var fullscreen = false
    private var enhancementEnabled = false
    private var currentVideoUri: Uri? = null
    private var currentVideoName: String = "Video"
    private var pendingSeekMs: Long = 0L
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
        videoContainer = findViewById(R.id.videoContainer)
        seekBar = findViewById(R.id.seekBar)
        currentTimeText = findViewById(R.id.currentTimeText)
        durationText = findViewById(R.id.durationText)
        settingsButton = findViewById(R.id.settingsButton)
        statusText = findViewById(R.id.statusText)
        emptyMessage = findViewById(R.id.emptyMessage)
        openVideoButton = findViewById(R.id.openVideoButton)
        storageButton = findViewById(R.id.folderButton)
        episodesButton = findViewById(R.id.episodesButton)
        audioButton = findViewById(R.id.audioButton)
        enhanceButton = findViewById(R.id.enhanceButton)
        videoGrid = findViewById(R.id.videoGrid)
        playPauseButton = findViewById(R.id.playPauseButton)
        seekBackButton = findViewById(R.id.seekBackButton)
        seekForwardButton = findViewById(R.id.seekForwardButton)
        backButton = findViewById(R.id.backButton)
        videoContainer.visibility = android.view.View.GONE
        playPauseButton.setOnClickListener { player?.let { if (it.isPlaying) { it.pause(); playPauseButton.text = "▶" } else { it.play(); playPauseButton.text = "Ⅱ" } } }
        seekBackButton.setOnClickListener { seekBy(-10_000L) }
        seekForwardButton.setOnClickListener { seekBy(10_000L) }
        settingsButton.setOnClickListener { showPlaybackSettings() }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val length = player?.length ?: 0L
                    if (length > 0L) currentTimeText.text = formatTime(length * progress / 1000L)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) { userSeeking = true }
            override fun onStopTrackingTouch(bar: SeekBar?) {
                val length = player?.length ?: 0L
                if (length > 0L) player?.setTime(length * (bar?.progress ?: 0) / 1000L)
                userSeeking = false
            }
        })
        backButton.setOnClickListener { player?.stop(); exitFullscreen() }
        openVideoButton.setOnClickListener { openVideo.launch(arrayOf("video/*")) }
        storageButton.setOnClickListener { requestPhoneVideoAccess() }
        episodesButton.setOnClickListener { showEpisodes() }
        audioButton.setOnClickListener { showTrackOptions() }
        enhanceButton.setOnClickListener {
            val uri = currentVideoUri
            if (uri == null) {
                statusText.text = "Open a video before enabling Auto Enhance."
            } else {
                enhancementEnabled = !enhancementEnabled
                pendingSeekMs = player?.time?.coerceAtLeast(0L) ?: 0L
                enhanceButton.text = if (enhancementEnabled) "Enhance: ON" else "Auto Enhance"
                statusText.text = if (enhancementEnabled) "Applying automatic video enhancement…" else "Automatic enhancement turned off"
                playVideo(uri, currentVideoName)
            }
        }
        uiHandler.post(progressUpdater)
        initializePlayer()
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
            statusText.text = "Found ${videoItems.size} phone video(s) • Episode-number order"
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

    private fun initializePlayer() {
        if (player != null) return
        try {
            val vlc = LibVLC(this, arrayListOf("--audio-time-stretch", "--no-video-title-show"))
            libVLC = vlc
            player = MediaPlayer(vlc).also { mediaPlayer ->
                mediaPlayer.attachViews(videoLayout, null, false, false)
                mediaPlayer.setEventListener { event ->
                    runOnUiThread {
                        when (event.type) {
                            MediaPlayer.Event.Opening -> statusText.text = "Opening video…"
                            MediaPlayer.Event.Buffering -> statusText.text = "Buffering…"
                            MediaPlayer.Event.Playing -> {
                                statusText.text = if (enhancementEnabled) "Auto Enhance active • VLC engine" else "Playing • VLC engine • Offline • No ads"
                                playPauseButton.text = "Ⅱ"
                                if (pendingSeekMs > 0L) {
                                    val restoreAt = pendingSeekMs
                                    pendingSeekMs = 0L
                                    mediaPlayer.time = restoreAt.coerceAtMost(mediaPlayer.length.coerceAtLeast(0L))
                                }
                            }
                            MediaPlayer.Event.Paused -> { statusText.text = "Paused"; playPauseButton.text = "▶" }
                            MediaPlayer.Event.EndReached -> statusText.text = "Playback finished"
                            MediaPlayer.Event.EncounteredError -> statusText.text = "VLC could not decode this file. Try another copy or format."
                        }
                    }
                }
            }
        } catch (e: Exception) {
            statusText.text = "Could not start VLC engine: ${e.javaClass.simpleName}"
        }
    }

    private fun playVideo(uri: Uri, name: String) {
        initializePlayer()
        val vlc = libVLC
        val mediaPlayer = player
        if (vlc == null || mediaPlayer == null) {
            statusText.text = "Player engine did not start. Please reopen the app."
            return
        }
        currentVideoUri = uri
        currentVideoName = name
        emptyMessage.text = name
        enterFullscreen()
        try {
            mediaPlayer.stop()
            currentVideoFd?.close()
            currentVideoFd = null

            // LibVLC cannot reliably read every Android content:// provider URI
            // directly. Open it through Android's ContentResolver and pass VLC
            // an fd:// media URI while keeping the descriptor alive.
            val descriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IllegalStateException("Android could not open this video")
            currentVideoFd = descriptor
            val media = Media(vlc, descriptor.fileDescriptor)
            media.setHWDecoderEnabled(true, false)
            if (enhancementEnabled) {
                // LibVLC adjust filter performs playback-time image processing.
                // This is conventional enhancement, not AI upscaling.
                media.addOption(":video-filter=adjust")
                media.addOption(":contrast=1.12")
                media.addOption(":brightness=1.03")
                media.addOption(":saturation=1.12")
                media.addOption(":gamma=1.0")
            }
            mediaPlayer.media = media
            media.release()
            mediaPlayer.play()
        } catch (e: Exception) {
            currentVideoFd?.close()
            currentVideoFd = null
            statusText.text = "Could not open video: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun enterFullscreen() {
        if (fullscreen) return
        fullscreen = true
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        titleView.visibility = android.view.View.GONE
        videoGrid.visibility = android.view.View.GONE
        videoContainer.visibility = android.view.View.VISIBLE
        emptyMessage.visibility = android.view.View.GONE
        openVideoButton.visibility = android.view.View.GONE
        storageButton.visibility = android.view.View.GONE
        episodesButton.visibility = android.view.View.GONE
        statusText.visibility = android.view.View.VISIBLE
        rootLayout.setPadding(0, 0, 0, 0)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        fullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        titleView.visibility = android.view.View.VISIBLE
        videoContainer.visibility = android.view.View.GONE
        videoGrid.visibility = android.view.View.VISIBLE
        emptyMessage.visibility = android.view.View.VISIBLE
        openVideoButton.visibility = android.view.View.VISIBLE
        storageButton.visibility = android.view.View.VISIBLE
        episodesButton.visibility = android.view.View.VISIBLE
        statusText.visibility = android.view.View.VISIBLE
        rootLayout.setPadding(16, 16, 16, 16)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    @Deprecated("Deprecated in Android 13; retained for the app's back navigation")
    override fun onBackPressed() {
        if (fullscreen) exitFullscreen() else super.onBackPressed()
    }

    private fun seekBy(offsetMs: Long) {
        val mp = player ?: return
        val length = mp.length
        if (length <= 0L || !mp.isSeekable) return
        mp.setTime((mp.time + offsetMs).coerceIn(0L, length))
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
        val options = arrayOf("Playback speed", "Screen brightness", "Reset screen brightness")
        MaterialAlertDialogBuilder(this)
            .setTitle("Playback settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPlaybackSpeedOptions()
                    1 -> showScreenBrightnessControl()
                    2 -> {
                        val attributes = window.attributes
                        attributes.screenBrightness = -1f
                        window.attributes = attributes
                        statusText.text = "Screen brightness follows device setting"
                    }
                }
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

    private fun showTrackOptions() {
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
            options.add("Audio • ${track.name}")
            actions.add { mediaPlayer.setAudioTrack(track.id) }
        }
        subtitleTracks.forEach { track ->
            options.add("Subtitle • ${track.name}")
            actions.add { mediaPlayer.setSpuTrack(track.id) }
        }
        if (subtitleTracks.isNotEmpty()) {
            options.add("Turn subtitles off")
            actions.add { mediaPlayer.setSpuTrack(-1) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Audio tracks and subtitles")
            .setItems(options.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(progressUpdater)
        player?.stop()
        currentVideoFd?.close()
        currentVideoFd = null
        player?.detachViews()
        player?.release()
        player = null
        libVLC?.release()
        libVLC = null
    }
}
