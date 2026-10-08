package com.portalstream.app.ui.player

import android.app.PictureInPictureParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.R as Media3R

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var streamUrl: String = ""
    private var channelName: String = ""
    private var userAgent: String? = null
    private var hasAttemptedHlsFallback: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Mantiene lo schermo acceso durante la riproduzione
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: intent.getStringExtra("STREAM_URL") ?: ""
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: intent.getStringExtra("CHANNEL_NAME") ?: "Canale"
        userAgent = intent.getStringExtra(EXTRA_USER_AGENT)

        if (streamUrl.isBlank()) {
            Toast.makeText(this, "URL dello stream non valido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            MaterialTheme {
                PlayerScreen(
                    channelName = channelName,
                    onBackPressed = { finish() }
                )
            }
        }
    }

    @Composable
    fun PlayerScreen(
        channelName: String,
        onBackPressed: () -> Unit
    ) {
        var isBuffering by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
        var showSettingsSheet by remember { mutableStateOf(false) }

        var currentTracks by remember { mutableStateOf<Tracks?>(null) }
        var activeTab by remember { mutableIntStateOf(0) } // 0: Formato, 1: Audio, 2: Sottotitoli

        DisposableEffect(Unit) {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
                setUserAgent(userAgent ?: "PortalStreamPro/1.0")
                setAllowCrossProtocolRedirects(true)
            }

            val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

            val renderersFactory = DefaultRenderersFactory(this@PlayerActivity).apply {
                setEnableDecoderFallback(true)
            }

            // Ottimizzazione Buffer per azzerare il ritardo audio/video nei flussi live
            val lowLatencyLoadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1500, // Min buffer per avvio rapido (1.5 sec)
                    5000, // Max buffer (5 sec)
                    1000, // Buffer necessario per far partire la riproduzione (1 sec)
                    1500  // Buffer dopo eventuale re-buffering (1.5 sec)
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()

            val exoPlayer = ExoPlayer.Builder(this@PlayerActivity)
                .setRenderersFactory(renderersFactory)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(lowLatencyLoadControl)
                .setAudioAttributes(audioAttributes, true)
                .build()
                .apply {
                    val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
                    setMediaItem(mediaItem)
                    prepare()
                    playWhenReady = true

                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            isBuffering = (playbackState == Player.STATE_BUFFERING)
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            currentTracks = tracks

                            // Rilevamento automatico: se lo stream ha tracce audio MA NESSUNA è supportata dall'hardware del telefono
                            val hasAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
                            val hasSupportedAudio = tracks.groups.any { group ->
                                group.type == C.TRACK_TYPE_AUDIO && (0 until group.length).any { group.isTrackSupported(it) }
                            }

                            if (hasAudio && !hasSupportedAudio && !hasAttemptedHlsFallback) {
                                hasAttemptedHlsFallback = true

                                val fallbackUrl = when {
                                    streamUrl.contains(".ts?") -> streamUrl.replace(".ts?", ".m3u8?")
                                    streamUrl.endsWith(".ts") -> streamUrl.dropLast(3) + ".m3u8"
                                    streamUrl.contains(".ts") -> streamUrl.replace(".ts", ".m3u8")
                                    !streamUrl.contains(".m3u8") -> {
                                        if (streamUrl.contains("?")) {
                                            val parts = streamUrl.split("?", limit = 2)
                                            "${parts[0]}.m3u8?${parts[1]}"
                                        } else {
                                            "$streamUrl.m3u8"
                                        }
                                    }
                                    else -> streamUrl
                                }

                                if (fallbackUrl != streamUrl) {
                                    streamUrl = fallbackUrl
                                    isBuffering = true
                                    errorMessage = null
                                    setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
                                    prepare()
                                    play()
                                    return
                                }
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val cause = error.cause
                            val errorMsg = error.localizedMessage ?: ""

                            // Fallback di emergenza anche su errore fatale
                            if (!hasAttemptedHlsFallback && (streamUrl.contains(".ts") || errorMsg.contains("audio/mpeg-L2") || errorMsg.contains("audio/ac3"))) {
                                hasAttemptedHlsFallback = true
                                val fallbackUrl = when {
                                    streamUrl.contains(".ts?") -> streamUrl.replace(".ts?", ".m3u8?")
                                    streamUrl.endsWith(".ts") -> streamUrl.dropLast(3) + ".m3u8"
                                    else -> streamUrl
                                }

                                if (fallbackUrl != streamUrl) {
                                    streamUrl = fallbackUrl
                                    isBuffering = true
                                    errorMessage = null
                                    setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
                                    prepare()
                                    play()
                                    return
                                }
                            }

                            val customMessage = when (cause) {
                                is HttpDataSource.InvalidResponseCodeException -> {
                                    when (cause.responseCode) {
                                        401, 403 -> "User-Agent o MAC non autorizzato dal server (HTTP ${cause.responseCode}). Prova a modificare l'User-Agent."
                                        456 -> "Accesso o IP rifiutato dal server (HTTP ${cause.responseCode}). Verifica credenziali o VPN."
                                        429, 458, 462 -> "Troppi utenti o connessioni contemporanee al server (HTTP ${cause.responseCode})."
                                        500, 502, 503, 504 -> "Server IPTV momentaneamente non disponibile (HTTP ${cause.responseCode})."
                                        else -> "Errore HTTP ${cause.responseCode} dal server."
                                    }
                                }
                                is HttpDataSource.HttpDataSourceException -> {
                                    "Impossibile connettersi al flusso video. Verifica la connessione di rete."
                                }
                                else -> "Errore durante la riproduzione: ${error.localizedMessage ?: error.errorCodeName}"
                            }

                            errorMessage = customMessage
                            isBuffering = false
                        }
                    })
                }

            player = exoPlayer

            onDispose {
                exoPlayer.release()
                player = null
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        this.resizeMode = resizeMode
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)

                        findViewById<View>(Media3R.id.exo_settings)?.setOnClickListener {
                            showSettingsSheet = true
                        }
                    }
                },
                update = { view ->
                    view.player = player
                    view.resizeMode = resizeMode
                    view.findViewById<View>(Media3R.id.exo_settings)?.setOnClickListener {
                        showSettingsSheet = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isBuffering && errorMessage == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }

            // Dialog Errore
            errorMessage?.let { msg ->
                Surface(
                    color = Color.Black.copy(alpha = 0.88f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "Attenzione",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        Text(
                            text = msg,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(onClick = {
                            errorMessage = null
                            isBuffering = true
                            player?.prepare()
                            player?.play()
                        }) {
                            Text("Riprova")
                        }
                    }
                }
            }

            // Pannello Impostazioni Avanzate (Unificato)
            if (showSettingsSheet) {
                Surface(
                    color = Color.Black.copy(alpha = 0.92f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Opzioni Riproduzione",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(onClick = { showSettingsSheet = false }) {
                                Icon(Icons.Default.Close, contentDescription = "Chiudi", tint = Color.White)
                            }
                        }

                        TabRow(
                            selectedTabIndex = activeTab,
                            containerColor = Color.Transparent,
                            contentColor = Color.White
                        ) {
                            Tab(
                                selected = activeTab == 0,
                                onClick = { activeTab = 0 },
                                text = { Text("Formato") }
                            )
                            Tab(
                                selected = activeTab == 1,
                                onClick = { activeTab = 1 },
                                text = { Text("Audio") }
                            )
                            Tab(
                                selected = activeTab == 2,
                                onClick = { activeTab = 2 },
                                text = { Text("Sottotitoli") }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        when (activeTab) {
                            0 -> {
                                Column {
                                    val modes = listOf(
                                        "Adatta Schermo (Originale)" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
                                        "Riempi Schermo (Stretch)" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
                                        "Zoom / Ritaglio (Crop)" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                    )
                                    modes.forEach { (label, mode) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { resizeMode = mode }
                                                .padding(vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = (resizeMode == mode),
                                                onClick = { resizeMode = mode }
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(text = label, color = Color.White)
                                        }
                                    }
                                }
                            }

                            1 -> {
                                val audioTracks = remember(currentTracks) {
                                    getTracksForType(currentTracks, C.TRACK_TYPE_AUDIO)
                                }
                                if (audioTracks.isEmpty()) {
                                    Text(
                                        text = "Nessuna traccia audio disponibile.",
                                        color = Color.Gray,
                                        modifier = Modifier.padding(vertical = 16.dp)
                                    )
                                } else {
                                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                                        items(audioTracks) { trackInfo ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        if (trackInfo.isSupported) {
                                                            selectTrack(player, trackInfo)
                                                        } else {
                                                            Toast.makeText(
                                                                this@PlayerActivity,
                                                                "Traccia audio non supportata dal dispositivo",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    }
                                                    .padding(vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = trackInfo.isSelected,
                                                    enabled = trackInfo.isSupported,
                                                    onClick = {
                                                        if (trackInfo.isSupported) {
                                                            selectTrack(player, trackInfo)
                                                        }
                                                    }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = if (trackInfo.isSupported) trackInfo.label else "${trackInfo.label} (Non supportato)",
                                                    color = if (trackInfo.isSupported) Color.White else Color.Gray
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            2 -> {
                                val subtitleTracks = remember(currentTracks) {
                                    getTracksForType(currentTracks, C.TRACK_TYPE_TEXT)
                                }
                                Column {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                disableTrackType(player, C.TRACK_TYPE_TEXT)
                                            }
                                            .padding(vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = subtitleTracks.none { it.isSelected },
                                            onClick = { disableTrackType(player, C.TRACK_TYPE_TEXT) }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(text = "Disattivati", color = Color.White)
                                    }

                                    LazyColumn(modifier = Modifier.heightIn(max = 180.dp)) {
                                        items(subtitleTracks) { trackInfo ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectTrack(player, trackInfo)
                                                    }
                                                    .padding(vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = trackInfo.isSelected,
                                                    onClick = { selectTrack(player, trackInfo) }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = trackInfo.label,
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private data class TrackInfo(
        val group: Tracks.Group,
        val trackIndex: Int,
        val label: String,
        val isSelected: Boolean,
        val isSupported: Boolean
    )

    @OptIn(UnstableApi::class)
    private fun getTracksForType(tracks: Tracks?, trackType: Int): List<TrackInfo> {
        if (tracks == null) return emptyList()
        val result = mutableListOf<TrackInfo>()

        for (group in tracks.groups) {
            if (group.type == trackType) {
                val mediaTrackGroup = group.mediaTrackGroup
                for (i in 0 until mediaTrackGroup.length) {
                    val format = mediaTrackGroup.getFormat(i)
                    val lang = format.language?.uppercase() ?: "IT"
                    val codec = format.sampleMimeType?.substringAfterLast("/")?.uppercase() ?: ""
                    val label = format.label ?: "Traccia ${result.size + 1} ($lang $codec)".trim()
                    val isSelected = group.isTrackSelected(i)
                    val isSupported = group.isTrackSupported(i)

                    result.add(TrackInfo(group, i, label, isSelected, isSupported))
                }
            }
        }
        return result
    }

    @OptIn(UnstableApi::class)
    private fun selectTrack(exoPlayer: ExoPlayer?, trackInfo: TrackInfo) {
        exoPlayer?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(trackInfo.group.type, false)
                .setOverrideForType(
                    TrackSelectionOverride(trackInfo.group.mediaTrackGroup, trackInfo.trackIndex)
                )
                .build()
        }
    }

    @OptIn(UnstableApi::class)
    private fun disableTrackType(exoPlayer: ExoPlayer?, trackType: Int) {
        exoPlayer?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(trackType, true)
                .build()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_USER_AGENT = "extra_user_agent"
    }
}
