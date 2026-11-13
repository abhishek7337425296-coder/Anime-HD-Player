package com.animehd.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.math.BigInteger
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var statusText: TextView
    private lateinit var emptyMessage: TextView
    private var videoItems: List<VideoEntry> = emptyList()
    private data class VideoEntry(val name: String, val uri: Uri)

    private val openVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { keepReadPermission(it); playVideo(it, displayName(it)) }
    }
    private val chooseFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            catch (_: SecurityException) { }
            scanFolder(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.playerView)
        statusText = findViewById(R.id.statusText)
        emptyMessage = findViewById(R.id.emptyMessage)
        findViewById<MaterialButton>(R.id.openVideoButton).setOnClickListener { openVideo.launch(arrayOf("video/*")) }
        findViewById<MaterialButton>(R.id.folderButton).setOnClickListener { chooseFolder.launch(null) }
        findViewById<MaterialButton>(R.id.episodesButton).setOnClickListener { showEpisodes() }
        findViewById<MaterialButton>(R.id.audioButton).setOnClickListener { showTrackOptions() }
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
                @Suppress("DEPRECATION")
                incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            else -> null
        }
        uri?.let { keepReadPermission(it); playVideo(it, displayName(it)) }
    }

    private fun keepReadPermission(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        catch (_: SecurityException) { }
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

    private fun initializePlayer() {
        if (player != null) return
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    statusText.text = when (playbackState) {
                        Player.STATE_BUFFERING -> "Loading video…"
                        Player.STATE_READY -> "Ready • Offline playback • No ads"
                        Player.STATE_ENDED -> "Playback finished"
                        else -> "Choose a video or folder"
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    statusText.text = "Can't decode this video (${error.errorCodeName}). Try MP4/H.264."
                }
            })
        }
    }

    private fun playVideo(uri: Uri, name: String) {
        initializePlayer()
        emptyMessage.text = name
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
        player?.playWhenReady = true
    }

    private fun scanFolder(treeUri: Uri) {
        statusText.text = "Scanning selected folder…"
        val root = DocumentFile.fromTreeUri(this, treeUri)
        if (root == null) { statusText.text = "Could not open this folder."; return }
        val found = mutableListOf<VideoEntry>()
        collectVideos(root, found, 0)
        videoItems = found.sortedWith { a, b -> naturalCompare(a.name, b.name) }
        statusText.text = "Found ${videoItems.size} video(s) • Sorted by episode number"
        if (videoItems.isNotEmpty()) showEpisodes()
        else emptyMessage.text = "No supported video files found in this folder."
    }

    private fun collectVideos(folder: DocumentFile, output: MutableList<VideoEntry>, depth: Int) {
        if (depth > 8) return
        try {
            for (file in folder.listFiles()) {
                if (file.isDirectory) collectVideos(file, output, depth + 1)
                else if (file.isFile && isVideoFile(file.name ?: "")) output.add(VideoEntry(file.name ?: "Video", file.uri))
            }
        } catch (_: SecurityException) { }
    }

    private fun isVideoFile(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return listOf(".mp4", ".mkv", ".webm", ".3gp", ".mov", ".m4v", ".ts", ".avi").any { lower.endsWith(it) }
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
        if (videoItems.isEmpty()) { statusText.text = "Choose a folder first to scan your episodes."; return }
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
        val exo = player
        if (exo == null || exo.currentMediaItem == null) {
            statusText.text = "Open a video first to choose audio or subtitles."
            return
        }
        val groups = exo.currentTracks.groups
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        for (i in 0 until groups.size) {
            val group = groups[i]
            val typeName = when (group.type) {
                androidx.media3.common.C.TRACK_TYPE_AUDIO -> "Audio"
                androidx.media3.common.C.TRACK_TYPE_TEXT -> "Subtitle"
                else -> continue
            }
            for (j in 0 until group.length) {
                val format = group.getTrackFormat(j)
                val language = format.language ?: "Unknown language"
                val label = format.label ?: language
                options.add("$typeName: $label ($language)")
                actions.add {
                    val override = androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, listOf(j))
                    exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().addOverride(override).build()
                }
            }
        }
        if (options.isEmpty()) { statusText.text = "No selectable audio or subtitle tracks found."; return }
        options.add("Disable subtitles")
        actions.add {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true).build()
        }
        MaterialAlertDialogBuilder(this).setTitle("Audio and subtitles")
            .setItems(options.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Close", null).show()
    }

    override fun onStart() { super.onStart(); initializePlayer() }
    override fun onStop() {
        super.onStop()
        playerView.player = null
        player?.release()
        player = null
    }
}
