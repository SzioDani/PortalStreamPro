package com.portalstream.app.network

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class ExoPlayerManager(private val context: Context) {

    var exoPlayer: ExoPlayer? = null
        private set

    private var activeListener: Player.Listener? = null
    private var currentConfiguredUserAgent: String? = null

    // Stato predefinito iniziale del formato e della scala
    var currentAspectRatio: String = "16:9"
    var currentScale: Float = 0f

    companion object {
        private const val FALLBACK_MAG_USER_AGENT =
            "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 2 rev: 250 Safari/533.3"
    }

    fun ensureInitialized(userAgent: String?): ExoPlayer {
        val effectiveUa = if (!userAgent.isNullOrBlank()) {
            userAgent.trim()
        } else {
            FALLBACK_MAG_USER_AGENT
        }

        // Se l'istanza esiste già ed ha lo stesso User-Agent, la riusiamo
        exoPlayer?.let {
            if (currentConfiguredUserAgent == effectiveUa) {
                return it
            }
            release()
        }

        currentConfiguredUserAgent = effectiveUa

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setUserAgent(effectiveUa)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(3000, 15000, 1500, 2500)
            .build()

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .setLoadControl(loadControl)
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguage("it")
                    .build()
                exoPlayer = this
                Log.d("PlayerDebug", "ExoPlayer inizializzato con User-Agent: $effectiveUa")
            }
    }

    fun attachAndStart(
        playerView: PlayerView,
        streamUrl: String,
        userAgent: String?,
        aspectRatio: String?, // Riceve lo stato attuale
        scale: Float,         // Riceve lo scale attuale
        onBuffering: (Boolean) -> Unit,
        onPlayingState: (Boolean) -> Unit,
        onAudioTracksUpdated: (List<UiAudioTrack>, Int, String) -> Unit,
        onFormatRefreshNeeded: () -> Unit,
        onError: () -> Unit
    ): ExoPlayer {
        // Aggiorniamo le variabili di stato interne con quelle passate
        if (aspectRatio != null) currentAspectRatio = aspectRatio
        currentScale = scale

        val player = ensureInitialized(userAgent)
        playerView.player = player

        activeListener?.let { player.removeListener(it) }

        fun refreshExoAudioTracks(tracks: Tracks) {
            val list = mutableListOf<UiAudioTrack>()
            var selectedId = -1

            tracks.groups.forEachIndexed { groupIdx, group ->
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    for (trackIdx in 0 until group.length) {
                        val format = group.getTrackFormat(trackIdx)
                        val id = groupIdx * 100 + trackIdx
                        val lang = format.language?.uppercase() ?: "TRACCIA ${list.size + 1}"
                        val label = format.label ?: lang

                        val codec = when {
                            format.sampleMimeType?.contains("mp4a") == true -> "AAC"
                            format.sampleMimeType?.contains("ac3") == true -> "AC3"
                            format.sampleMimeType?.contains("eac3") == true -> "EAC3"
                            format.sampleMimeType?.contains("mpeg") == true -> "MP2"
                            else -> format.sampleMimeType?.substringAfter("/")?.uppercase()
                        }
                        val channels = when (format.channelCount) {
                            1 -> "Mono"
                            2 -> "Stereo 2.0"
                            6 -> "5.1"
                            8 -> "7.1"
                            else -> if (format.channelCount > 0) "${format.channelCount}ch" else null
                        }
                        val details = listOfNotNull(codec, channels).joinToString(" • ")
                        val formatted = if (details.isNotBlank()) "$label ($details)" else label

                        list.add(
                            UiAudioTrack(
                                id = id,
                                name = label,
                                formattedInfo = formatted,
                                exoGroupIndex = groupIdx,
                                exoTrackIndex = trackIdx
                            )
                        )
                        if (group.isTrackSelected(trackIdx)) {
                            selectedId = id
                        }
                    }
                }
            }

            val info = list.firstOrNull { it.id == selectedId }?.formattedInfo
                ?: if (list.isEmpty()) "🔇 Nessun audio" else list.first().formattedInfo
            onAudioTracksUpdated(list, selectedId, info)
        }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> onBuffering(true)
                    Player.STATE_READY -> {
                        onBuffering(false)
                        applyAspectRatioAndScale(playerView, currentAspectRatio, currentScale)
                        onFormatRefreshNeeded()
                    }
                    Player.STATE_ENDED -> onPlayingState(false)
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                playerView.post { onFormatRefreshNeeded() }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                if (playing) onBuffering(false)
                onPlayingState(playing)
            }

            override fun onTracksChanged(tracks: Tracks) {
                refreshExoAudioTracks(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause
                if (cause is HttpDataSource.InvalidResponseCodeException) {
                    val bodyPreview = try {
                        String(cause.responseBody, Charsets.UTF_8).take(200).replace("\n", " ")
                    } catch (_: Exception) {
                        ""
                    }
                    Log.e(
                        "PlayerDebug",
                        "❌ [ExoPlayer HTTP ${cause.responseCode}] URL=$streamUrl | Msg=${cause.responseMessage} | Body='$bodyPreview'"
                    )
                } else {
                    Log.e(
                        "PlayerDebug",
                        "❌ [ExoPlayer Error ${error.errorCodeName}] URL=$streamUrl | Msg=${error.message}",
                        error
                    )
                }
                onBuffering(false)
                onPlayingState(false)
                onError()
            }
        }

        activeListener = listener
        player.addListener(listener)

        Log.d("PlayerDebug", "🎬 [ExoPlayer Opening] Apertura stream in corso: $streamUrl")
        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true

        return player
    }

    /**
     * Logica Formati e Zoom specifica per ExoPlayer:
     * - Formati nativi standard (16:9, 4:3, 21:9, ecc.): applicano il corretto aspect ratio senza distorcere.
     * - Zoom (ZOOM110..135, FILL): applicano i fattori di scala personalizzati su scaleX e scaleY.
     */
    fun applyAspectRatioAndScale(
        playerView: PlayerView?,
        aspectRatio: String?,
        scale: Float
    ) {
        val pv = playerView ?: return
        val contentFrame = pv.findViewById<AspectRatioFrameLayout>(
            androidx.media3.ui.R.id.exo_content_frame
        ) ?: return

        if (aspectRatio != null) currentAspectRatio = aspectRatio
        currentScale = scale

        pv.clipChildren = false
        pv.clipToPadding = false
        contentFrame.clipChildren = false
        contentFrame.clipToPadding = false

        val isZoomMode = scale > 0f || aspectRatio?.startsWith("ZOOM") == true || aspectRatio == "FILL"

        if (!isZoomMode) {
            contentFrame.scaleX = 1f
            contentFrame.scaleY = 1f
            pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            contentFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

            val targetRatio = when (aspectRatio) {
                "16:9"   -> 16f / 9f
                "4:3"    -> 4f / 3f
                "21:9"   -> 21f / 9f
                "1.85:1" -> 1.85f
                "2.35:1" -> 2.35f
                "2.39:1" -> 2.39f
                else     -> 16f / 9f
            }

            contentFrame.setAspectRatio(targetRatio)
            contentFrame.requestLayout()
            return
        }

        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        contentFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        contentFrame.setAspectRatio(16f / 9f)

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

        contentFrame.scaleX = zoomLati
        contentFrame.scaleY = zoomAltoBasso
    }

    fun selectAudioTrack(track: UiAudioTrack) {
        val player = exoPlayer ?: return
        if (track.exoGroupIndex >= 0) {
            val groups = player.currentTracks.groups
            if (track.exoGroupIndex < groups.size) {
                val group = groups[track.exoGroupIndex]
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(group.mediaTrackGroup, track.exoTrackIndex)
                    )
                    .build()
            }
        }
    }

    fun togglePlayPause() {
        exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekBy(deltaMs: Long) {
        exoPlayer?.let {
            it.seekTo((it.currentPosition + deltaMs).coerceAtLeast(0L))
        }
    }

    /**
     * Svuota la cache, ricrea il MediaItem da zero e forza una nuova connessione pulita di rete.
     */
    fun retry(streamUrl: String) {
        val player = exoPlayer ?: return
        player.stop()
        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    fun stopAndDetach(playerView: PlayerView?) {
        activeListener?.let { exoPlayer?.removeListener(it) }
        activeListener = null
        exoPlayer?.stop()
        playerView?.player = null
    }

    fun release() {
        activeListener?.let { exoPlayer?.removeListener(it) }
        activeListener = null
        exoPlayer?.release()
        exoPlayer = null
        currentConfiguredUserAgent = null
    }
}
