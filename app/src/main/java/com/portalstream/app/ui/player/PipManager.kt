package com.portalstream.app.ui.player

import android.app.PictureInPictureParams
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.media3.exoplayer.ExoPlayer

class PipManager(
    private val activity: ComponentActivity,
    private val exoPlayer: ExoPlayer
) {
    
    fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pipParams = PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .setAutoEnterEnabled(true)
                .build()
            
            activity.enterPictureInPictureMode(pipParams)
        }
    }
    
    fun exitPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.moveTaskToBack(false)
        }
    }
    
    fun isInPipMode(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.isInPictureInPictureMode
        } else {
            false
        }
    }
}
