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
    private lateinit var statusText: TextView
    private lateinit var emptyMessage: TextView
    private lateinit var rootLayout: android.widget.LinearLayout
    private lateinit var titleView: TextView
    private lateinit var openVideoButton: MaterialButton
    private lateinit var storageButton: MaterialButton
    private lateinit var episodesButton: MaterialButton
    private lateinit var audioButton: MaterialButton
    private var videoItems: List<VideoEntry> = emptyList()
    private var fullscreen = false
    private data class VideoEntry(val name: String, val uri: Uri)

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
        statusText = findViewById(R.id.statusText)
        emptyMessage = findViewById(R.id.emptyMessage)
        openVideoButton = findViewById(R.id.openVideoButton)
        storageButton = findViewById(R.id.folderButton)
        episodesButton = findViewById(R.id.episodesButton)
        audioButton = findViewById(R.id.audioButton)
        openVideoButton.setOnClickListener { openVideo.launch(arrayOf("video/*")) }
        storageButton.setOnClickListener { requestPhoneVideoAccess() }
        episodesButton.setOnClickListener { showEpisodes() }
        audioButton.setOnClickListener { showTrackOptions() }
        initializePlayer()
        handleIncomingIntent(intent)
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
                    found.add(VideoEntry(name, ContentUris.withAppendedId(collection, id)))
                }
            }
            videoItems = found.sortedWith { a, b -> naturalCompare(a.name, b.name) }
            statusText.text = "Found ${videoItems.size} phone video(s) • Episode-number order"
            if (videoItems.isNotEmpty()) showEpisodes()
            else emptyMessage.text = "No indexed videos found. Save Telegram videos to Downloads or Movies first."
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
                            MediaPlayer.Event.Playing -> statusText.text = "Playing • VLC engine • Offline • No ads"
                            MediaPlayer.Event.Paused -> statusText.text = "Paused"
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
            val media = Media(vlc, Uri.parse("fd://${descriptor.fd}"))
            media.setHWDecoderEnabled(true, false)
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
        emptyMessage.visibility = android.view.View.GONE
        openVideoButton.visibility = android.view.View.GONE
        storageButton.visibility = android.view.View.GONE
        episodesButton.visibility = android.view.View.GONE
        audioButton.visibility = android.view.View.GONE
        statusText.visibility = android.view.View.GONE
        rootLayout.setPadding(0, 0, 0, 0)
        videoLayout.layoutParams = videoLayout.layoutParams.apply {
            height = 0
            (this as android.widget.LinearLayout.LayoutParams).weight = 1f
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        fullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        titleView.visibility = android.view.View.VISIBLE
        emptyMessage.visibility = android.view.View.VISIBLE
        openVideoButton.visibility = android.view.View.VISIBLE
        storageButton.visibility = android.view.View.VISIBLE
        episodesButton.visibility = android.view.View.VISIBLE
        audioButton.visibility = android.view.View.VISIBLE
        statusText.visibility = android.view.View.VISIBLE
        rootLayout.setPadding(16, 16, 16, 16)
        videoLayout.layoutParams = videoLayout.layoutParams.apply {
            height = 0
            (this as android.widget.LinearLayout.LayoutParams).weight = 1f
        }
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, true)
    }

    @Deprecated("Deprecated in Android 13; retained for the app's back navigation")
    override fun onBackPressed() {
        if (fullscreen) exitFullscreen() else super.onBackPressed()
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
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        mediaPlayer.audioTracks?.forEach { track ->
            options.add("Audio: ${track.name}")
            actions.add { mediaPlayer.setAudioTrack(track.id) }
        }
        mediaPlayer.spuTracks?.forEach { track ->
            options.add("Subtitle: ${track.name}")
            actions.add { mediaPlayer.setSpuTrack(track.id) }
        }
        options.add("Disable subtitles")
        actions.add { mediaPlayer.setSpuTrack(-1) }
        if (options.isEmpty()) {
            statusText.text = "No selectable audio or subtitle tracks found."
            return
        }
        MaterialAlertDialogBuilder(this).setTitle("Audio and subtitles")
            .setItems(options.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Close", null).show()
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
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
