package com.portalstream.app.ui.player

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.portalstream.app.R

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var pipManager: PipManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        pipManager = PipManager(this)

        // Recuperiamo l'URL del canale passato dalla schermata precedente.
        // Se non c'è, usiamo un video di test (Big Buck Bunny in HLS) per provare se funziona.
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
            playWhenReady = true // Avvia in automatico
        }
    }

    override fun onPause() {
        super.onPause()
        // Mette in pausa se l'app va in background (se non siamo in PiP)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !isInPictureInPictureMode) {
            player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        // Libera le risorse quando l'Activity viene chiusa del tutto
        player?.release()
        player = null
    }

    // --- GESTIONE TELECOMANDO (Android TV / Firestick) ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                // TODO: Mostra overlay per fare zapping
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // TODO: Mostra info EPG
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Attiva il Picture-in-Picture se l'utente preme il tasto Home
        if (pipManager.isPipSupported()) {
            pipManager.enterPipMode()
        }
    }
}
