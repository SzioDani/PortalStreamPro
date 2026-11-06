package com.portalstream.app.network

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

class VlcPlayerManager(private val context: Context) {

    var libVLC: LibVLC? = null
        private set
    var mediaPlayer: MediaPlayer? = null
        private set

    private val initialBufferThreshold = 85f

    fun ensureInitialized(): MediaPlayer {
        if (libVLC != null && mediaPlayer != null) return mediaPlayer!!

        val options = ArrayList<String>().apply {
            add("--no-drop-late-frames")
            add("--no-skip-frames")
            add("--rtsp-tcp")
            add("--http-reconnect")
            add("--network-caching=8000")
            add("--live-caching=8000")
            add("--disc-caching=8000")
            add("--file-caching=8000")
            add("--clock-jitter=-1")
            add("--clock-synchro=0")
            add("--udp-timeout=40000")
            add("--http-continuous")
        }

        libVLC = LibVLC(context, options)
        mediaPlayer = MediaPlayer(libVLC)
        Log.d("PlayerDebug", "LibVLC inizializzato")
        return mediaPlayer!!
    }

    fun attachAndPrepare(
        layout: VLCVideoLayout,
        streamUrl: String,
        userAgent: String?,
        onBuffering: (Boolean) -> Unit,
        onPlayingState: (Boolean) -> Unit,
        onAudioTracksUpdated: (List<UiAudioTrack>, Int, String) -> Unit,
        onFormatRefreshNeeded: () -> Unit,
        onError: () -> Unit
    ): MediaPlayer {
        val mp = ensureInitialized()
        val vlc = libVLC!!

        mp.attachViews(layout, null, false, true)

        val media = Media(vlc, Uri.parse(streamUrl)).apply {
            setHWDecoderEnabled(true, true)
            addOption(":http-continuous")
            addOption(":http-reconnect")
            addOption(":clock-jitter=0")
            addOption(":network-caching=8000")
            addOption(":live-caching=8000")

            if (!userAgent.isNullOrBlank()) {
                addOption(":http-user-agent=$userAgent")
            }
        }

        mp.media = media
        media.release()

        fun refreshAudio() {
            val tracks = mp.audioTracks?.toList() ?: emptyList()
            var currentId = mp.audioTrack
            val validTracks = tracks.filter { it.id != -1 }

            if (currentId == -1 && validTracks.isNotEmpty()) {
                val defaultTrackId = validTracks.first().id
                mp.audioTrack = defaultTrackId
                currentId = defaultTrackId
            }

            val mapped = validTracks.map { t ->
                val cleanName = t.name.replace("main - ", "")
                UiAudioTrack(
                    id = t.id,
                    name = cleanName,
                    formattedInfo = formatAudioTrackInfo(mp, tracks, t.id)
                )
            }

            val info = formatAudioTrackInfo(mp, tracks, currentId)
            onAudioTracksUpdated(mapped, currentId, info)
        }

        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Buffering -> {
                    onBuffering(event.buffering < initialBufferThreshold)
                }
                MediaPlayer.Event.Playing -> {
                    onBuffering(false)
                    onPlayingState(true)
                    layout.post { onFormatRefreshNeeded() }
                    refreshAudio()
                }
                MediaPlayer.Event.Vout -> {
                    if (event.voutCount > 0) {
                        layout.post { onFormatRefreshNeeded() }
                    }
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                    onPlayingState(false)
                }
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected, MediaPlayer.Event.ESDeleted -> {
                    refreshAudio()
                }
                MediaPlayer.Event.EncounteredError -> {
                    onBuffering(false)
                    onPlayingState(false)
                    onError()
                }
            }
        }

        return mp
    }

    /**
     * Logica Formati e Zoom specifica per LibVLC:
     * - Formati classici (16:9, 4:3, ecc.): usa mp.aspectRatio nativo senza distorsioni.
     * - Zoom (110%..135%, FILL): allarga i lati (zoomLati) e rimpicciolisce dal basso in alto (zoomAltoBasso).
     */
    fun applyAspectRatioAndScale(
        vlcLayout: VLCVideoLayout?,
        aspectRatio: String?,
        scale: Float
    ) {
        val mp = mediaPlayer ?: return

        vlcLayout?.let { layout ->
            layout.clipChildren = false
            layout.clipToPadding = false
            (layout.parent as? ViewGroup)?.let { parent ->
                parent.clipChildren = false
                parent.clipToPadding = false
            }
        }

        // Se è un formato nativo standard (es. "16:9", "4:3", o null), resetta le scale e usa LibVLC
        if (aspectRatio == "16:9" || aspectRatio == "4:3" || (aspectRatio != null && !aspectRatio.startsWith("ZOOM") && aspectRatio != "FILL" && scale == 0f)) {
            vlcLayout?.scaleX = 1f
            vlcLayout?.scaleY = 1f
            mp.scale = 0f
            mp.aspectRatio = aspectRatio
            return
        }

        // Altrimenti, applica lo zoom personalizzato solo se richiesto esplicitamente
        if (scale > 0f || aspectRatio?.startsWith("ZOOM") == true || aspectRatio == "FILL") {
            mp.scale = 0f
            mp.aspectRatio = null // Lasciamo gestire al layout via scaleX/scaleY

            val zoomLati = when (aspectRatio) {
                "ZOOM110" -> 1.06f
                "ZOOM120" -> 1.12f
                "ZOOM125" -> 1.16f
                "ZOOM130" -> 1.20f
                "ZOOM135" -> 1.24f
                "FILL"    -> 1.28f
                else -> when (scale) {
                    1.10f -> 1.06f
                    1.20f -> 1.12f
                    1.25f -> 1.16f
                    1.30f -> 1.20f
                    1.35f -> 1.24f
                    1.45f -> 1.28f
                    else  -> 1.0f
                }
            }

            val zoomAltoBasso = when (aspectRatio) {
                "ZOOM110" -> 0.90f
                "ZOOM120" -> 0.94f
                "ZOOM125" -> 0.98f
                "ZOOM130" -> 1.00f
                "ZOOM135" -> 1.02f
                "FILL"    -> 1.04f
                else -> when (scale) {
                    1.10f -> 0.90f
                    1.20f -> 0.94f
                    1.25f -> 0.98f
                    1.30f -> 1.00f
                    1.35f -> 1.02f
                    1.45f -> 1.04f
                    else  -> 0.85f
                }
            }

            vlcLayout?.scaleX = zoomLati
            vlcLayout?.scaleY = zoomAltoBasso
            return
        }

        // Default di sicurezza
        vlcLayout?.scaleX = 1f
        vlcLayout?.scaleY = 1f
        mp.scale = 0f
        mp.aspectRatio = aspectRatio
    }

    private fun formatAudioTrackInfo(
        mp: MediaPlayer,
        tracks: List<MediaPlayer.TrackDescription>,
        activeId: Int
    ): String {
        if (activeId == -1) {
            val validTracks = tracks.filter { it.id != -1 }
            return if (validTracks.isEmpty()) "🔇 Nessun audio" else "⊘ Disattivato"
        }

        val selectedTrackDesc = tracks.firstOrNull { it.id == activeId }
        val trackName = (selectedTrackDesc?.name ?: "Traccia $activeId")
            .replace("main - ", "")

        try {
            val media = mp.media
            if (media != null) {
                val count = media.trackCount
                for (i in 0 until count) {
                    val t = media.getTrack(i)
                    if (t != null && t.type == IMedia.Track.Type.Audio && t is IMedia.AudioTrack) {
                        val codec = when (t.codec?.uppercase()) {
                            "MPGA", "MP2", "MP3" -> "MP2"
                            "A52", "AC3" -> "AC3"
                            "EAC3" -> "EAC3"
                            "AAC", "MP4A" -> "AAC"
                            else -> t.codec?.uppercase()
                        }
                        val channels = when (t.channels) {
                            1 -> "Mono"
                            2 -> "Stereo 2.0"
                            6 -> "5.1"
                            8 -> "7.1"
                            else -> if (t.channels > 0) "${t.channels}ch" else null
                        }
                        val details = listOfNotNull(codec, channels).joinToString(" • ")
                        if (details.isNotBlank()) {
                            return "$trackName ($details)"
                        }
                    }
                }
            }
        } catch (_: Exception) {
            Log.e("PlayerDebug", "Errore nel parsing audio info")
        }

        return trackName
    }

    fun selectAudioTrack(trackId: Int) {
        mediaPlayer?.audioTrack = trackId
    }

    fun togglePlayPause() {
        mediaPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekBy(deltaMs: Long) {
        mediaPlayer?.let {
            it.time = (it.time + deltaMs).coerceAtLeast(0L)
        }
    }

    fun stopAndDetach() {
        mediaPlayer?.setEventListener(null)
        mediaPlayer?.stop()
        mediaPlayer?.detachViews()
    }

    fun release() {
        mediaPlayer?.release()
        libVLC?.release()
        mediaPlayer = null
        libVLC = null
    }
}
