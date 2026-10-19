package com.portalstream.app.ui.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.util.Rational

class PipManager(private val activity: Activity) {

    fun isPipSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    /**
     * Aggiorna i parametri PiP dell'Activity.
     * Su Android 12+ (API 31+) imposta setAutoEnterEnabled in base allo stato di riproduzione (stile YouTube/VLC).
     */
    fun updatePipParams(isPlaying: Boolean) {
        if (!isPipSupported()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val aspectRatio = Rational(16, 9)
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Attiva l'auto-enter solo se il video sta effettivamente suonando
                builder.setAutoEnterEnabled(isPlaying)
            }

            activity.setPictureInPictureParams(builder.build())
        }
    }

    /**
     * Fallback esplicito per versioni precedenti ad Android 12 (API 26 - 30)
     */
    fun enterPipMode() {
        if (!isPipSupported()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val aspectRatio = Rational(16, 9)
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
            activity.enterPictureInPictureMode(builder.build())
        }
    }
}
