package com.portalstream.app.ui.player

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.portalstream.app.network.ExoPlayerManager
import com.portalstream.app.network.StreamNetworkHelper
import com.portalstream.app.network.UiAudioTrack
import com.portalstream.app.network.VlcPlayerManager
import com.portalstream.app.streaming.TorrentStreamEngine
import com.portalstream.app.streaming.TorrentStreamState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.videolan.libvlc.util.VLCVideoLayout

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private lateinit var vlcManager: VlcPlayerManager
    private lateinit var exoManager: ExoPlayerManager
    private lateinit var pipManager: PipManager
    private var torrentEngine: TorrentStreamEngine? = null

    private var streamUrl: String = ""
    private var channelName: String = ""
    private var userAgent: String? = null

    private var isInPipMode by mutableStateOf(false)

    private val prefs by lazy {
        getSharedPreferences("player_settings", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipManager = PipManager(this)
        vlcManager = VlcPlayerManager(this)
        exoManager = ExoPlayerManager(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.BLACK

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL)
            ?: intent.getStringExtra("STREAM_URL")
            ?: intent.dataString
            ?: ""
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME)
            ?: intent.getStringExtra("CHANNEL_NAME") ?: "Canale Live"
        userAgent = intent.getStringExtra(EXTRA_USER_AGENT)

        if (streamUrl.isBlank()) {
            Toast.makeText(this, "URL dello stream non valido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (TorrentStreamEngine.isTorrentSource(streamUrl)) {
            torrentEngine = TorrentStreamEngine(applicationContext).also {
                it.startStream(streamUrl)
            }
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

    override fun onDestroy() {
        super.onDestroy()
        torrentEngine?.stop()
        torrentEngine = null
        exoManager.release()
        vlcManager.release()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("PlayerDebug", "Configurazione cambiata: ${newConfig.orientation}")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val playerMode = appPrefs.getString("player_mode", "both")

        val currentlyPlaying = (exoManager.exoPlayer?.isPlaying == true) ||
            (vlcManager.mediaPlayer?.isPlaying == true)
        if ((playerMode == "pip" || playerMode == "both") && currentlyPlaying && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            pipManager.enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        if (!isInPictureInPictureMode && isFinishing) {
            finish()
        }
    }

    @Composable
    fun PlayerScreen(channelName: String, onBackPressed: () -> Unit) {
        val savedAspectRatio = remember { prefs.getString("aspect_ratio", null) }
        val savedScale = remember { prefs.getFloat("scale", 0f) }

        val isTorrent = remember(streamUrl) { TorrentStreamEngine.isTorrentSource(streamUrl) }
        val torrentState = torrentEngine?.state?.collectAsState()

        var playbackUrl by remember {
            mutableStateOf(if (isTorrent) "" else streamUrl)
        }

        var useExoPlayer by remember { mutableStateOf<Boolean?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        var isBuffering by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var showControls by remember { mutableStateOf(true) }
        var showSettingsSheet by remember { mutableStateOf(false) }
        var activeTab by remember { mutableIntStateOf(0) }
        var isMinimized by remember { mutableStateOf(false) }

        var uiAudioTracks by remember { mutableStateOf<List<UiAudioTrack>>(emptyList()) }
        var currentAudioTrackId by remember { mutableIntStateOf(-1) }
        var currentAudioInfo by remember { mutableStateOf("Rilevamento audio...") }

        var currentAspectRatio by remember { mutableStateOf(savedAspectRatio) }
        var currentScale by remember { mutableStateOf(savedScale) }

        var vlcVideoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
        var exoPlayerView by remember { mutableStateOf<PlayerView?>(null) }

        val configuration = LocalConfiguration.current

        // Osserva lo stato del motore Torrent interno
        LaunchedEffect(torrentState?.value) {
            when (val st = torrentState?.value) {
                is TorrentStreamState.Buffering -> {
                    isBuffering = true
                }
                is TorrentStreamState.Ready -> {
                    errorMessage = null
                    playbackUrl = Uri.fromFile(st.videoFile).toString()
                }
                is TorrentStreamState.Error -> {
                    isBuffering = false
                    errorMessage = st.message
                }
                else -> {}
            }
        }

        LaunchedEffect(playbackUrl) {
            if (playbackUrl.isNotBlank()) {
                isBuffering = true
                useExoPlayer = if (isTorrent) {
                    // Per i file torrent in download progressivo (.mkv/.avi/.mp4), VLC gestisce benissimo i file parziali,
                    // mentre i .mp4/.ts funzionano su entrambi (l'utente può anche cambiare al volo col tasto ⚡ Exo/VLC)
                    val lower = playbackUrl.lowercase()
                    lower.endsWith(".mp4") || lower.endsWith(".ts")
                } else {
                    StreamNetworkHelper.shouldUseExoPlayer(playbackUrl, userAgent)
                }
            }
        }

        LaunchedEffect(isInPipMode) {
            if (isInPipMode) {
                showControls = false
                showSettingsSheet = false
            }
        }

        LaunchedEffect(showControls, isPlaying) {
            if (showControls && isPlaying && !isInPipMode && !isMinimized) {
                delay(4000)
                showControls = false
            }
        }

        // Persistenza formato e rotazione per LibVLC
        LaunchedEffect(currentAspectRatio, currentScale, vlcVideoLayout, useExoPlayer, configuration.orientation) {
            val layout = vlcVideoLayout
            if (layout != null && useExoPlayer == false) {
                vlcManager.applyAspectRatioAndScale(layout, currentAspectRatio, currentScale)
                delay(150)
                vlcManager.applyAspectRatioAndScale(layout, currentAspectRatio, currentScale)
                delay(250)
                vlcManager.applyAspectRatioAndScale(layout, currentAspectRatio, currentScale)
            }
        }

        // Persistenza formato e rotazione per ExoPlayer
        LaunchedEffect(currentAspectRatio, currentScale, exoPlayerView, useExoPlayer, configuration.orientation) {
            val pv = exoPlayerView
            if (pv != null && useExoPlayer == true) {
                exoManager.applyAspectRatioAndScale(pv, currentAspectRatio, currentScale)
                delay(150)
                exoManager.applyAspectRatioAndScale(pv, currentAspectRatio, currentScale)
            }
        }

        // Collegamento Motore ExoPlayer
        DisposableEffect(useExoPlayer, exoPlayerView, playbackUrl) {
            if (useExoPlayer != true || playbackUrl.isBlank()) return@DisposableEffect onDispose {}
            val pv = exoPlayerView ?: return@DisposableEffect onDispose {}

            try {
                exoManager.attachAndStart(
                    playerView = pv,
                    streamUrl = playbackUrl,
                    userAgent = userAgent,
                    aspectRatio = currentAspectRatio,
                    scale = currentScale,
                    onBuffering = { isBuffering = it },
                    onPlayingState = { playing ->
                        isPlaying = playing
                        if (playing) errorMessage = null
                        pipManager.updatePipParams(playing)
                    },
                    onAudioTracksUpdated = { tracks, selectedId, info ->
                        uiAudioTracks = tracks
                        currentAudioTrackId = selectedId
                        currentAudioInfo = info
                    },
                    onFormatRefreshNeeded = {
                        exoManager.applyAspectRatioAndScale(pv, currentAspectRatio, currentScale)
                    },
                    onError = {
                        if (isTorrent) {
                            // Passa automaticamente a LibVLC se il contenitore del torrent richiede VLC
                            useExoPlayer = false
                        } else {
                            lifecycleScope.launch {
                                errorMessage = StreamNetworkHelper.checkHttpError(playbackUrl, userAgent)
                            }
                        }
                    }
                )

                onDispose {
                    exoManager.stopAndDetach(pv)
                }
            } catch (e: Exception) {
                Log.e("PlayerDebug", "Errore setup ExoPlayer", e)
                errorMessage = "Errore ExoPlayer: ${e.message}"
                onDispose {}
            }
        }

        // Collegamento Motore LibVLC
        DisposableEffect(useExoPlayer, vlcVideoLayout, playbackUrl) {
            if (useExoPlayer != false || playbackUrl.isBlank()) return@DisposableEffect onDispose {}
            val layout = vlcVideoLayout ?: return@DisposableEffect onDispose {}

            try {
                val mp = vlcManager.attachAndPrepare(
                    layout = layout,
                    streamUrl = playbackUrl,
                    userAgent = userAgent,
                    onBuffering = { isBuffering = it },
                    onPlayingState = { playing ->
                        isPlaying = playing
                        if (playing) errorMessage = null
                        pipManager.updatePipParams(playing)
                    },
                    onAudioTracksUpdated = { tracks, selectedId, info ->
                        uiAudioTracks = tracks
                        currentAudioTrackId = selectedId
                        currentAudioInfo = info
                    },
                    onFormatRefreshNeeded = {
                        vlcManager.applyAspectRatioAndScale(layout, currentAspectRatio, currentScale)
                    },
                    onError = {
                        lifecycleScope.launch {
                            errorMessage = if (isTorrent) {
                                "Errore riproduzione file Torrent"
                            } else {
                                StreamNetworkHelper.checkHttpError(playbackUrl, userAgent)
                            }
                        }
                    }
                )

                lifecycleScope.launch {
                    delay(500)
                    mp.play()
                }

                onDispose {
                    vlcManager.stopAndDetach()
                }
            } catch (e: Exception) {
                Log.e("PlayerDebug", "Errore nel setup di LibVLC", e)
                errorMessage = "Errore: ${e.message}"
                onDispose {}
            }
        }

        // =========================================================================
        // INTERFACCIA GRAFICA (PULSANTI, CARD, MINIPLAYER, OPZIONI)
        // =========================================================================
        DraggableMiniPlayerContainer(
            isMinimized = isMinimized,
            onExpand = { isMinimized = false },
            onClose = onBackPressed,
            isPlaying = isPlaying,
            onTogglePlayPause = {
                if (useExoPlayer == true) exoManager.togglePlayPause() else vlcManager.togglePlayPause()
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (!isInPipMode && !isMinimized) {
                            showControls = !showControls
                        }
                    }
            ) {
                when (useExoPlayer) {
                    true -> {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    fitsSystemWindows = false
                                    clipChildren = false
                                    clipToPadding = false
                                    exoPlayerView = this
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    false -> {
                        AndroidView(
                            factory = { ctx ->
                                VLCVideoLayout(ctx).also { layout ->
                                    vlcVideoLayout = layout
                                    layout.fitsSystemWindows = false
                                    layout.clipChildren = false
                                    layout.clipToPadding = false
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    null -> {}
                }

                AnimatedVisibility(
                    visible = showControls && !isInPipMode && !isMinimized,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f))
                    ) {
                        // BARRA SUPERIORE
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBackPressed) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Indietro", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = channelName,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Audio: $currentAudioInfo",
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }

                            // Pulsante rapido cambio motore (ExoPlayer <-> LibVLC)
                            Surface(
                                onClick = {
                                    isBuffering = true
                                    errorMessage = null
                                    useExoPlayer = !(useExoPlayer ?: false)
                                },
                                color = Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Text(
                                    text = if (useExoPlayer == true) "⚡ Exo" else "⚡ VLC",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(onClick = { isMinimized = true }) {
                                Icon(
                                    imageVector = Icons.Default.FullscreenExit,
                                    contentDescription = "Miniplayer",
                                    tint = Color.White
                                )
                            }
                        }

                        // PULSANTI CENTRALI (Indietro 10s, Play/Pausa, Stop, Avanti 10s)
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (useExoPlayer == true) exoManager.seekBy(-10000L) else vlcManager.seekBy(-10000L)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Text(
                                    text = "« 10s",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (useExoPlayer == true) exoManager.togglePlayPause() else vlcManager.togglePlayPause()
                                },
                                modifier = Modifier
                                    .size(56.dp)
                                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            ) {
                                if (isPlaying) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.width(5.dp).height(20.dp).background(Color.White, RoundedCornerShape(2.dp)))
                                        Box(modifier = Modifier.width(5.dp).height(20.dp).background(Color.White, RoundedCornerShape(2.dp)))
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = {
                                    exoManager.stopAndDetach(exoPlayerView)
                                    vlcManager.stopAndDetach()
                                    onBackPressed()
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .background(Color.White, RoundedCornerShape(2.dp))
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (useExoPlayer == true) exoManager.seekBy(10000L) else vlcManager.seekBy(10000L)
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Text(
                                    text = "10s »",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // BARRA INFERIORE
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomEnd)
                                .navigationBarsPadding()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showSettingsSheet = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Impostazioni", tint = Color.White)
                            }
                        }
                    }
                }

                if (isBuffering && errorMessage == null && !isInPipMode && !isMinimized) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color.White)
                        val st = torrentState?.value
                        if (st is TorrentStreamState.Buffering) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = st.statusText,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Peer: ${st.peers} • ${st.downloadSpeedKbps} KB/s",
                                color = Color.LightGray,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                // CARD ERRORE DI RIPRODUZIONE
                errorMessage?.let { msg ->
                    if (!isInPipMode && !isMinimized) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.90f),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Text(
                                    text = "⚠️ Errore di Riproduzione",
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
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Button(onClick = {
                                        errorMessage = null
                                        isBuffering = true
                                        if (isTorrent && playbackUrl.isBlank()) {
                                            torrentEngine?.startStream(streamUrl)
                                        } else if (useExoPlayer == true) {
                                            exoManager.retry()
                                        } else {
                                            lifecycleScope.launch {
                                                delay(300)
                                                vlcManager.mediaPlayer?.play()
                                            }
                                        }
                                    }) {
                                        Text("Riprova")
                                    }

                                    OutlinedButton(onClick = {
                                        errorMessage = null
                                        isBuffering = true
                                        useExoPlayer = !(useExoPlayer ?: false)
                                    }) {
                                        val otherEngine = if (useExoPlayer == true) "LibVLC" else "ExoPlayer"
                                        Text("Usa $otherEngine", color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }

                // PANNELLO IMPOSTAZIONI (AUDIO E FORMATO)
                if (showSettingsSheet && !isInPipMode && !isMinimized) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.95f),
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
                                    text = "📺 Opzioni Riproduzione",
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
                                    text = { Text("🔊 Audio") }
                                )
                                Tab(
                                    selected = activeTab == 1,
                                    onClick = { activeTab = 1 },
                                    text = { Text("🎬 Formato") }
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            when (activeTab) {
                                0 -> AudioTabContent(
                                    audioTracks = uiAudioTracks,
                                    currentTrackId = currentAudioTrackId,
                                    onTrackSelected = { track ->
                                        if (useExoPlayer == true) {
                                            exoManager.selectAudioTrack(track)
                                        } else {
                                            vlcManager.selectAudioTrack(track.id)
                                        }
                                        currentAudioTrackId = track.id
                                        currentAudioInfo = track.formattedInfo
                                    }
                                )

                                1 -> AspectRatioTabContent(
                                    currentAspectRatio = currentAspectRatio,
                                    currentScale = currentScale,
                                    onAspectRatioChange = { newRatio ->
                                        currentAspectRatio = newRatio

                                        prefs.edit().apply {
                                            if (newRatio != null) {
                                                putString("aspect_ratio", newRatio)
                                            } else {
                                                remove("aspect_ratio")
                                            }
                                            apply()
                                        }

                                        val newScale = when (newRatio) {
                                            "FILL"    -> 1.45f
                                            "ZOOM110" -> 1.10f
                                            "ZOOM120" -> 1.20f
                                            "ZOOM125" -> 1.25f
                                            "ZOOM130" -> 1.30f
                                            "ZOOM135" -> 1.35f
                                            else      -> 0f
                                        }
                                        currentScale = newScale
                                        prefs.edit().putFloat("scale", newScale).apply()

                                        if (useExoPlayer == false) {
                                            vlcManager.applyAspectRatioAndScale(vlcVideoLayout, newRatio, newScale)
                                        } else if (useExoPlayer == true) {
                                            exoManager.applyAspectRatioAndScale(exoPlayerView, newRatio, newScale)
                                        }

                                        showSettingsSheet = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AudioTabContent(
        audioTracks: List<UiAudioTrack>,
        currentTrackId: Int,
        onTrackSelected: (UiAudioTrack) -> Unit
    ) {
        if (audioTracks.isEmpty()) {
            Text(
                text = "🔇 Nessuna traccia audio disponibile",
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 220.dp).fillMaxWidth()) {
                items(audioTracks) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrackSelected(track) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (track.id == currentTrackId),
                            onClick = { onTrackSelected(track) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = track.formattedInfo,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AspectRatioTabContent(
        currentAspectRatio: String?,
        currentScale: Float,
        onAspectRatioChange: (String?) -> Unit
    ) {
        val ratios = listOf(
            "📺 16:9 (Standard HD)" to "16:9",
            "📺 4:3 (TV Classica)" to "4:3",
            "🔍 Zoom 110%" to "ZOOM110",
            "🔍 Zoom 120%" to "ZOOM120",
            "🔍 Zoom 125%" to "ZOOM125",
            "🔍 Zoom 130%" to "ZOOM130",
            "🔍 Zoom 135%" to "ZOOM135",
            "🎬 21:9 (Cinematic)" to "21:9",
            "🎬 1.85:1 (Movie)" to "1.85:1",
            "🎬 2.35:1 (CinemaScope)" to "2.35:1",
            "🎬 2.39:1 (UltraWide)" to "2.39:1",
            "🔲 Riempi Schermo" to "FILL"
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 130.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
        ) {
            items(ratios) { (label, ratio) ->
                val isSelected = when (ratio) {
                    "FILL"    -> currentScale == 1.45f
                    "ZOOM110" -> currentScale == 1.10f
                    "ZOOM120" -> currentScale == 1.20f
                    "ZOOM125" -> currentScale == 1.25f
                    "ZOOM130" -> currentScale == 1.30f
                    "ZOOM135" -> currentScale == 1.35f
                    else      -> currentAspectRatio == ratio && currentScale == 0f
                }

                Button(
                    onClick = { onAspectRatioChange(ratio) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSelected) {
                            Color.White.copy(alpha = 0.25f)
                        } else {
                            Color.White.copy(alpha = 0.08f)
                        }
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isSelected) "✓ $label" else label,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_USER_AGENT = "extra_user_agent"
    }
}
