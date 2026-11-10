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
import java.io.File

class VlcPlayerManager(private val context: Context) {

    var libVLC: LibVLC? = null
        private set
    var mediaPlayer: MediaPlayer? = null
        private set

    private var currentTorrentMode: Boolean? = null

    private val initialBufferThreshold = 85f

    fun ensureInitialized(isTorrent: Boolean = false): MediaPlayer {
        if (libVLC != null && mediaPlayer != null && currentTorrentMode == isTorrent) {
            return mediaPlayer!!
        }

        if (currentTorrentMode != null && currentTorrentMode != isTorrent) {
            release()
        }

        val options = ArrayList<String>().apply {
            if (isTorrent) {
                // =========================================================
                // PROFILO LEGGERO E VELOCE PER TORRENT (Sottotitoli delegati a OpenSubtitles)
                // =========================================================
                add("--no-drop-late-frames")
                add("--no-skip-frames")
                add("--file-caching=2500")
                add("--disc-caching=2500")
                add("--no-spu") // Disabilita il motore sottotitoli interno di LibVLC per risparmiare RAM e CPU!
                add("--audio-language=it,ita,italiano,en,eng,und")
                add("--avcodec-fast")
            } else {
                // =========================================================
                // PROFILO ORIGINALE INTATTO PER FLUSSI MAC .TS E IPTV
                // =========================================================
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
                add("--audio-language=it,ita,italiano")
            }
        }

        libVLC = LibVLC(context, options)
        mediaPlayer = MediaPlayer(libVLC)
        currentTorrentMode = isTorrent
        Log.d("PlayerDebug", "LibVLC inizializzato (modalità Torrent=$isTorrent)")
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
        val trimmedUrl = streamUrl.trim()
        val lowerUrl = trimmedUrl.lowercase()

        val isTorrentFile = trimmedUrl.startsWith("/") ||
            trimmedUrl.startsWith("file://") ||
            lowerUrl.contains("torrent_stream")

        val mp = ensureInitialized(isTorrent = isTorrentFile)
        val vlc = libVLC!!

        // Sottotitoli nativi VLC disabilitati (terzo parametro = false) perché usiamo OpenSubtitles + Compose Overlay
        mp.attachViews(layout, null, false, true)

        val isRawMacStream = !isTorrentFile && (
            lowerUrl.contains("live.php") ||
                lowerUrl.contains("extension=ts") ||
                lowerUrl.endsWith(".ts")
            )

        val mediaUri: Uri = if (isTorrentFile && trimmedUrl.startsWith("/")) {
            Uri.fromFile(File(trimmedUrl))
        } else {
            Uri.parse(trimmedUrl)
        }

        val media = Media(vlc, mediaUri).apply {
            if (isTorrentFile) {
                setHWDecoderEnabled(true, false)
                addOption(":file-caching=2500")
                addOption(":no-spu")
                addOption(":audio-language=it,ita,italiano,en,eng,und")
            } else {
                setHWDecoderEnabled(true, true)
                addOption(":http-reconnect")
                addOption(":network-caching=8000")
                addOption(":live-caching=8000")
                addOption(":audio-language=it,ita,italiano")

                if (isRawMacStream) {
                    addOption(":http-continuous")
                    addOption(":clock-jitter=0")
                }

                if (!userAgent.isNullOrBlank()) {
                    addOption(":http-user-agent=$userAgent")
                }
            }
        }

        mp.media = media
        media.release()

        var hasAutoSelectedBestTrack = false

        fun refreshAudio(allowAutoSelect: Boolean) {
            val tracks = mp.audioTracks?.toList() ?: emptyList()
            val validTracks = tracks.filter { it.id != -1 }
            var currentId = mp.audioTrack

            if (allowAutoSelect && validTracks.isNotEmpty() && !hasAutoSelectedBestTrack) {
                hasAutoSelectedBestTrack = true

                val bestTrack = validTracks.firstOrNull { t ->
                    val n = t.name.lowercase()
                    (n.contains("ita") || n.contains("it") || n.contains("main") || n.contains("traccia 1") || n.contains("track 1")) &&
                        !n.contains("oth") && !n.contains("descri")
                } ?: validTracks.firstOrNull { t ->
                    val n = t.name.lowercase()
                    !n.contains("oth") && !n.contains("descri")
                } ?: validTracks.first()

                if (currentId == -1 || validTracks.none { it.id == currentId }) {
                    mp.audioTrack = bestTrack.id
                    currentId = bestTrack.id
                } else if (isTorrentFile) {
                    val explicitItalian = validTracks.firstOrNull { t ->
                        val n = t.name.lowercase()
                        (n.contains("ita") || n.contains("italiano")) && !n.contains("oth") && !n.contains("descri")
                    }
                    if (explicitItalian != null && currentId != explicitItalian.id) {
                        mp.audioTrack = explicitItalian.id
                        currentId = explicitItalian.id
                    }
                }
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
                    if (isTorrentFile) {
                        onBuffering(!mp.isPlaying && event.buffering < 50f)
                    } else {
                        onBuffering(event.buffering < initialBufferThreshold)
                    }
                }
                MediaPlayer.Event.Playing -> {
                    onBuffering(false)
                    onPlayingState(true)
                    layout.post { onFormatRefreshNeeded() }
                    refreshAudio(allowAutoSelect = true)
                }
                MediaPlayer.Event.Vout -> {
                    if (event.voutCount > 0) {
                        if (isTorrentFile) onBuffering(false)
                        layout.post { onFormatRefreshNeeded() }
                        refreshAudio(allowAutoSelect = true)
                    }
                }
                MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                    onPlayingState(false)
                }
                MediaPlayer.Event.ESAdded -> {
                    if (isTorrentFile && mp.isPlaying) {
                        refreshAudio(allowAutoSelect = !hasAutoSelectedBestTrack)
                    }
                }
                MediaPlayer.Event.ESSelected -> {
                    if (mp.isPlaying) {
                        refreshAudio(allowAutoSelect = false)
                    }
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
     * Svuota la cache, distrugge il media precedente e rinegozia una nuova connessione da zero.
     */
    fun retry(streamUrl: String, userAgent: String?) {
        val mp = mediaPlayer ?: return
        val vlc = libVLC ?: return

        // 1. Ferma la riproduzione attuale
        mp.stop()

        val trimmedUrl = streamUrl.trim()
        val lowerUrl = trimmedUrl.lowercase()

        val isTorrentFile = trimmedUrl.startsWith("/") ||
            trimmedUrl.startsWith("file://") ||
            lowerUrl.contains("torrent_stream")

        val mediaUri: Uri = if (isTorrentFile && trimmedUrl.startsWith("/")) {
            Uri.fromFile(File(trimmedUrl))
        } else {
            Uri.parse(trimmedUrl)
        }

        // 2. Crea un oggetto Media totalmente nuovo con tutte le opzioni e gli header aggiornati
        val media = Media(vlc, mediaUri).apply {
            if (isTorrentFile) {
                setHWDecoderEnabled(true, false)
                addOption(":file-caching=2500")
                addOption(":no-spu")
                addOption(":audio-language=it,ita,italiano,en,eng,und")
            } else {
                setHWDecoderEnabled(true, true)
                addOption(":http-reconnect")
                addOption(":network-caching=8000")
                addOption(":live-caching=8000")
                addOption(":audio-language=it,ita,italiano")

                val isRawMacStream = lowerUrl.contains("live.php") ||
                    lowerUrl.contains("extension=ts") ||
                    lowerUrl.endsWith(".ts")

                if (isRawMacStream) {
                    addOption(":http-continuous")
                    addOption(":clock-jitter=0")
                }

                if (!userAgent.isNullOrBlank()) {
                    addOption(":http-user-agent=$userAgent")
                }
            }
        }

        // 3. Assegna il nuovo media e avvia la riproduzione pulita
        mp.media = media
        media.release()
        mp.play()
    }

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

        val isZoomOrFill = scale > 0f || aspectRatio?.startsWith("ZOOM") == true || aspectRatio == "FILL"

        // 1. Formati standard e cinematografici (Originale, 16:9, 4:3, 21:9, 1.85:1, 2.35:1, 2.39:1)
        if (!isZoomOrFill) {
            vlcLayout?.scaleX = 1f
            vlcLayout?.scaleY = 1f
            mp.scale = 0f
            mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT

            // LibVLC in C accetta solo frazioni di numeri interi (NUM:DEN) e ignora i punti decimali!
            val vlcNativeRatio = when (aspectRatio) {
                "1.85:1" -> "185:100"
                "2.35:1" -> "235:100"
                "2.39:1" -> "239:100"
                else -> aspectRatio // "16:9", "4:3", "21:9" o null (Originale)
            }

            mp.aspectRatio = vlcNativeRatio
            vlcLayout?.post {
                vlcLayout.requestLayout()
                vlcLayout.invalidate()
            }
            return
        }

        // 2. Zoom personalizzati (ZOOM110..ZOOM135 e FILL)
        mp.scale = 0f
        mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
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
        vlcLayout?.post {
            vlcLayout.requestLayout()
            vlcLayout.invalidate()
        }
    }

    private fun formatAudioTrackInfo(
        mp: MediaPlayer,
        tracks: List<MediaPlayer.TrackDescription>,
        activeId: Int
    ): String {
        if (activeId == -1) {
            val validTracks = tracks.filter { it.id != -1 }
            return if (validTracks.isEmpty()) "Nessun audio" else "Disattivato"
        }

        val selectedTrackDesc = tracks.firstOrNull { it.id == activeId }
        val trackName = (selectedTrackDesc?.name ?: "Traccia $activeId")
            .replace("main - ", "")

        var media: IMedia? = null
        try {
            media = mp.media
            if (media != null) {
                val count = media.trackCount
                for (i in 0 until count) {
                    val t = media.getTrack(i)
                    if (t != null && t.type == IMedia.Track.Type.Audio && t.id == activeId && t is IMedia.AudioTrack) {
                        val codec = when (t.codec?.uppercase()) {
                            "MPGA", "MP2", "MP3" -> "MP2"
                            "A52", "AC3" -> "AC3"
                            "EAC3" -> "EAC3"
                            "DTS" -> "DTS"
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
                        val lang = t.language?.takeIf { it.isNotBlank() }?.uppercase()
                        val details = listOfNotNull(lang, codec, channels).joinToString(" • ")
                        if (details.isNotBlank()) {
                            return "$trackName ($details)"
                        }
                    }
                }
            }
        } catch (_: Exception) {
            Log.e("PlayerDebug", "Errore nel parsing audio info")
        } finally {
            media?.release()
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
        currentTorrentMode = null
    }
}
