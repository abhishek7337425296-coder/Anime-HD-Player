package com.animehd.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var statusText: TextView
    private lateinit var emptyMessage: TextView

    private val openVideo = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            playVideo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.playerView)
        statusText = findViewById(R.id.statusText)
        emptyMessage = findViewById(R.id.emptyMessage)

        findViewById<MaterialButton>(R.id.openVideoButton).setOnClickListener {
            openVideo.launch(arrayOf("video/*"))
        }
        findViewById<MaterialButton>(R.id.audioButton).setOnClickListener {
            showTrackOptions()
        }
        initializePlayer()
    }

    private fun initializePlayer() {
        if (player != null) return
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo
            exo.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    statusText.text = when (playbackState) {
                        Player.STATE_BUFFERING -> "Loading local video…"
                        Player.STATE_READY -> "Ready • Offline playback • No ads"
                        Player.STATE_ENDED -> "Playback finished"
                        else -> "Offline • No ads • Local playback"
                    }
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    statusText.text = "Cannot play this file: " + error.errorCodeName
                }
            })
        }
    }

    private fun playVideo(uri: Uri) {
        initializePlayer()
        emptyMessage.text = uri.lastPathSegment ?: "Local video"
        player?.setMediaItem(MediaItem.fromUri(uri))
        player?.prepare()
        player?.playWhenReady = true
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
                    val override = androidx.media3.common.TrackSelectionOverride(
                        group.mediaTrackGroup, listOf(j)
                    )
                    exo.trackSelectionParameters = exo.trackSelectionParameters
                        .buildUpon().addOverride(override).build()
                }
            }
        }
        if (options.isEmpty()) {
            statusText.text = "No selectable audio or subtitle tracks found."
            return
        }
        options.add("Disable subtitles")
        actions.add {
            exo.trackSelectionParameters = exo.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                .build()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Audio and subtitles")
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
        playerView.player = null
        player?.release()
        player = null
    }
}
