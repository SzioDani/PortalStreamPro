package com.portalstream.app.ui.player

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.portalstream.app.R

class PlayerActivity : AppCompatActivity() {

    private lateinit var pipManager: PipManager
    private var areControlsVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        pipManager = PipManager(this)
    }

    /**
     * Intercetta direttamente i comandi del D-Pad / Telecomando Firestick / Android TV
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                toggleControls()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                // Mostra la lista canali rapida (overlay zapping)
                showQuickChannelOverlay()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Mostra EPG del canale corrente
                showEpgOverlay()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                // Rewind rapido o canale precedente
                switchChannelDelta(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                // Forward rapido o canale successivo
                switchChannelDelta(1)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePlayPause()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun toggleControls() {
        areControlsVisible = !areControlsVisible
        // Gestisci visibilità UI sovrapposta
    }

    private fun showQuickChannelOverlay() {
        // Implementazione pannello zapping D-Pad
    }

    private fun showEpgOverlay() {
        // Implementazione pannello info EPG
    }

    private fun switchChannelDelta(delta: Int) {
        // Cambia canale (+1 / -1)
    }

    private fun togglePlayPause() {
        // Play / Pause ExoPlayer
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Entra automaticamente in PiP quando si preme Home (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            pipManager.enterPipMode()
        }
    }
}
