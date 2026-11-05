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

    fun ensureInitialized(userAgent: String?): ExoPlayer {
        exoPlayer?.let { return it }

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)
            .apply {
                if (!userAgent.isNullOrBlank()) {
                    setUserAgent(userAgent)
                } else {
                    setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                }
            }

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
                Log.d("PlayerDebug", "ExoPlayer inizializzato")
            }
    }

    fun attachAndStart(
        playerView: PlayerView,
        streamUrl: String,
        userAgent: String?,
        onBuffering: (Boolean) -> Unit,
        onPlayingState: (Boolean) -> Unit,
        onAudioTracksUpdated: (List<UiAudioTrack>, Int, String) -> Unit,
        onFormatRefreshNeeded: () -> Unit,
        onError: () -> Unit
    ): ExoPlayer {
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
                Log.e("PlayerDebug", "Errore ExoPlayer: ${error.message}", error)
                onBuffering(false)
                onPlayingState(false)
                onError()
            }
        }

        activeListener = listener
        player.addListener(listener)

        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true

        return player
    }

    /**
     * Logica Formati e Zoom specifica per ExoPlayer:
     * - zoomLati (scaleX): allarga il video verso i lati.
     * - zoomAltoBasso (scaleY): rimpicciolito dal basso in alto per non tagliare il logo.
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

        pv.clipChildren = false
        pv.clipToPadding = false
        contentFrame.clipChildren = false
        contentFrame.clipToPadding = false

        if (scale > 0f || aspectRatio == "FILL") {
            pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            contentFrame.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            contentFrame.setAspectRatio(16f / 9f)

            val zoomLati = when (aspectRatio) {
                "ZOOM110" -> 1.05f
                "ZOOM120" -> 1.10f
                "ZOOM125" -> 1.14f
                "ZOOM130" -> 1.18f
                "ZOOM135" -> 1.22f
                "FILL"    -> 1.26f
                else -> when (scale) {
                    1.10f -> 1.05f
                    1.20f -> 1.10f
                    1.25f -> 1.14f
                    1.30f -> 1.18f
                    1.35f -> 1.22f
                    1.45f -> 1.26f
                    else  -> 1.0f
                }
            }

            val zoomAltoBasso = when (aspectRatio) {
                "ZOOM110" -> 0.85f // Ridotto rispetto a 0.94f
                "ZOOM120" -> 0.88f // Ridotto rispetto a 0.96f
                "ZOOM125" -> 0.91f // Ridotto rispetto a 0.98f
                "ZOOM130" -> 0.94f // Ridotto rispetto a 1.00f
                "ZOOM135" -> 0.97f // Ridotto rispetto a 1.02f
                "FILL"    -> 1.00f // Ridotto rispetto a 1.04f
                else -> when (scale) {
                    1.10f -> 0.85f
                    1.20f -> 0.88f
                    1.25f -> 0.91f
                    1.30f -> 0.94f
                    1.35f -> 0.97f
                    1.45f -> 1.00f
                    else  -> 0.85f // Valore di default più compatto in altezza
                }
            }

            contentFrame.scaleX = zoomLati
            contentFrame.scaleY = zoomAltoBasso
            return
        }

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

    fun retry() {
        exoPlayer?.prepare()
        exoPlayer?.play()
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
    }
}
