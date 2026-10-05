package com.portalstream.app.ui.player

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.portalstream.app.R

class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var pipManager: PipManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        pipManager = PipManager(this)

        // Recupera l'URL del canale passato dalla lista
        val streamUrl = intent.getStringExtra("STREAM_URL") 
            ?: "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

        initializePlayer(streamUrl)
    }

    private fun initializePlayer(url: String) {
        player = ExoPlayer.Builder(this).build().apply {
            playerView.player = this
            val mediaItem = MediaItem.fromUri(url)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) {
            player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> true
            KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipManager.isPipSupported()) {
            pipManager.enterPipMode()
        }
    }
}
