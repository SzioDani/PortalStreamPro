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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.portalstream.app.network.ExoPlayerManager
import com.portalstream.app.network.StreamNetworkHelper
import com.portalstream.app.network.StreamErrorResult
import com.portalstream.app.network.StreamErrorType
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
        
        val isTorrentInput = TorrentStreamEngine.isTorrentSource(streamUrl)
        val rawExtraName = intent.getStringExtra(EXTRA_CHANNEL_NAME)
            ?: intent.getStringExtra("CHANNEL_NAME")

        channelName = if (isTorrentInput) {
            val magnetDn = TorrentStreamEngine.extractDisplayNameFromMagnet(streamUrl)
            when {
                magnetDn.isNotBlank() -> magnetDn
                !rawExtraName.isNullOrBlank() && !rawExtraName.startsWith("Torrent ") -> rawExtraName
                else -> "Caricamento titolo Torrent..."
            }
        } else {
            rawExtraName ?: "Canale Live"
        }

        userAgent = intent.getStringExtra(EXTRA_USER_AGENT)

        if (streamUrl.isBlank()) {
            Toast.makeText(this, "URL dello stream non valido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (isTorrentInput) {
            torrentEngine = TorrentStreamEngine(applicationContext).also {
                it.startStream(streamUrl)
            }
        }
        setContent {
            MaterialTheme {
                PlayerScreen(
                    initialChannelName = channelName,
                    onExitAndClearCache = { stopStreamAndExit() }
                )
            }
        }
    }
    /**
     * Ferma la riproduzione, svuota immediatamente la cache del file Torrent e chiude il player.
     */
    private fun stopStreamAndExit() {
        exoManager.release()
        vlcManager.release()
        torrentEngine?.stopAndClearCache()
        torrentEngine = null
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        torrentEngine?.stopAndClearCache()
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
            stopStreamAndExit()
        }
    }

    @Composable
    fun PlayerScreen(initialChannelName: String, onExitAndClearCache: () -> Unit) {
        BackHandler {
            onExitAndClearCache()
        }

        val savedAspectRatio = remember { prefs.getString("aspect_ratio", null) }
        val savedScale = remember { prefs.getFloat("scale", 0f) }
        val savedSubLang = remember { prefs.getString("sub_lang", "it") ?: "it" }
        val savedSubScale = remember { prefs.getFloat("sub_scale", 1.0f) }
        val savedSubStyleId = remember { prefs.getString("sub_style", SubtitleStylePreset.WHITE_OUTLINE.id) }

        val isTorrent = remember(streamUrl) { TorrentStreamEngine.isTorrentSource(streamUrl) }
        val torrentState = torrentEngine?.state?.collectAsState()

        var displayTitle by remember { mutableStateOf(initialChannelName) }
        var playbackUrl by remember { mutableStateOf(if (isTorrent) "" else streamUrl) }

        var useExoPlayer by remember { mutableStateOf<Boolean?>(null) }
        var isPlaying by remember { mutableStateOf(false) }
        var isBuffering by remember { mutableStateOf(true) }
        
        // Sostituito errorMessage con StreamErrorResult strutturato
        var errorResult by remember { mutableStateOf<StreamErrorResult?>(null) }
        
        var showControls by remember { mutableStateOf(true) }
        var showSettingsSheet by remember { mutableStateOf(false) }
        var isMinimized by remember { mutableStateOf(false) }

        var uiAudioTracks by remember { mutableStateOf<List<UiAudioTrack>>(emptyList()) }
        var currentAudioTrackId by remember { mutableIntStateOf(-1) }
        var currentAudioInfo by remember { mutableStateOf("Rilevamento audio...") }

        var openSubtitleItems by remember { mutableStateOf<List<OpenSubtitleItem>>(emptyList()) }
        var selectedOpenSubId by remember { mutableStateOf("") }
        var activeSubtitleCues by remember { mutableStateOf<List<SubtitleCue>>(emptyList()) }
        var currentSubtitleText by remember { mutableStateOf<String?>(null) }
        var isLoadingSubtitles by remember { mutableStateOf(false) }
        var preferredSubLanguage by remember { mutableStateOf(savedSubLang) }
        var subtitleSizeScale by remember { mutableFloatStateOf(savedSubScale) }
        var subtitleStyle by remember { mutableStateOf(SubtitleStylePreset.fromId(savedSubStyleId)) }

        var currentAspectRatio by remember { mutableStateOf(savedAspectRatio) }
        var currentScale by remember { mutableFloatStateOf(savedScale) }

        var vlcVideoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
        var exoPlayerView by remember { mutableStateOf<PlayerView?>(null) }

        val configuration = LocalConfiguration.current
        val coroutineScope = rememberCoroutineScope()

        fun triggerOpenSubtitlesSearch(titleToSearch: String, langCode: String) {
            if (titleToSearch.isBlank() || titleToSearch.startsWith("Caricamento")) return
            coroutineScope.launch {
                isLoadingSubtitles = true
                val found = OpenSubtitlesManager.searchSubtitles(
                    rawTorrentTitle = titleToSearch,
                    languageCode2 = langCode
                )
                openSubtitleItems = found
                isLoadingSubtitles = false
            }
        }
        // Osserva lo stato del motore Torrent
        LaunchedEffect(torrentState?.value) {
            when (val st = torrentState?.value) {
                is TorrentStreamState.Buffering -> {
                    isBuffering = true
                    if (st.torrentTitle.isNotBlank() && st.torrentTitle != displayTitle) {
                        displayTitle = st.torrentTitle
                    }
                }
                is TorrentStreamState.Ready -> {
                    errorResult = null
                    if (st.torrentTitle.isNotBlank()) {
                        displayTitle = st.torrentTitle
                    }
                    playbackUrl = Uri.fromFile(st.videoFile).toString()
                }
                is TorrentStreamState.Error -> {
                    isBuffering = false
                    errorResult = StreamErrorResult(
                        message = st.message,
                        type = StreamErrorType.UNKNOWN
                    )
                }
                else -> {}
            }
        }

        LaunchedEffect(displayTitle, preferredSubLanguage) {
            if (isTorrent && displayTitle.isNotBlank() && !displayTitle.startsWith("Caricamento")) {
                triggerOpenSubtitlesSearch(displayTitle, preferredSubLanguage)
            }
        }

        LaunchedEffect(activeSubtitleCues, isPlaying, useExoPlayer) {
            if (activeSubtitleCues.isEmpty()) {
                currentSubtitleText = null
                return@LaunchedEffect
            }
            while (true) {
                val posMs = if (useExoPlayer == true) {
                    exoManager.exoPlayer?.currentPosition ?: 0L
                } else {
                    vlcManager.mediaPlayer?.time ?: 0L
                }
                currentSubtitleText = OpenSubtitlesManager.findActiveText(activeSubtitleCues, posMs)
                delay(180)
            }
        }

        LaunchedEffect(playbackUrl) {
            if (playbackUrl.isNotBlank()) {
                isBuffering = true
                useExoPlayer = if (isTorrent) {
                    false
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

        LaunchedEffect(showControls, isPlaying, showSettingsSheet) {
            if (showControls && isPlaying && !isInPipMode && !isMinimized && !showSettingsSheet) {
                delay(4000)
                showControls = false
            }
        }

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
                        if (playing) errorResult = null
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
                            useExoPlayer = false
                        } else {
                            lifecycleScope.launch {
                                errorResult = StreamNetworkHelper.checkHttpError(playbackUrl, userAgent)
                            }
                        }
                    }
                )

                onDispose {
                    exoManager.stopAndDetach(pv)
                }
            } catch (e: Exception) {
                Log.e("PlayerDebug", "Errore setup ExoPlayer", e)
                errorResult = StreamErrorResult(
                    message = "Errore ExoPlayer: ${e.message}",
                    type = StreamErrorType.UNKNOWN
                )
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
                        if (playing) errorResult = null
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
                            errorResult = if (isTorrent) {
                                StreamErrorResult(
                                    message = "Errore riproduzione file Torrent",
                                    type = StreamErrorType.UNKNOWN
                                )
                            } else {
                                StreamNetworkHelper.checkHttpError(playbackUrl, userAgent)
                            }
                        }
                    }
                )

                lifecycleScope.launch {
                    delay(400)
                    mp.play()
                }

                onDispose {
                    vlcManager.stopAndDetach()
                }
            } catch (e: Exception) {
                Log.e("PlayerDebug", "Errore nel setup di LibVLC", e)
                errorResult = StreamErrorResult(
                    message = "Errore LibVLC: ${e.message}",
                    type = StreamErrorType.UNKNOWN
                )
                onDispose {}
            }
        }

        DraggableMiniPlayerContainer(
            isMinimized = isMinimized,
            onExpand = { isMinimized = false },
            onClose = onExitAndClearCache,
            isPlaying = isPlaying,
            onTogglePlayPause = {
                if (useExoPlayer == true) exoManager.togglePlayPause() else vlcManager.togglePlayPause()
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
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
                            update = { pv ->
                                exoManager.applyAspectRatioAndScale(pv, currentAspectRatio, currentScale)
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
                            update = { layout ->
                                vlcManager.applyAspectRatioAndScale(layout, currentAspectRatio, currentScale)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    null -> {}
                }

                SubtitleOverlay(
                    text = currentSubtitleText,
                    sizeScale = subtitleSizeScale,
                    stylePreset = subtitleStyle,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                )

                PlayerGestureOverlay(
                    activity = this@PlayerActivity,
                    enabled = !isInPipMode && !isMinimized && !showSettingsSheet,
                    onSingleTap = {
                        if (!isInPipMode && !isMinimized) {
                            showControls = !showControls
                        }
                    }
                )

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
                        // BARRA SUPERIORE PROFESSIONALE
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .statusBarsPadding()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onExitAndClearCache) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Indietro", tint = Color.White)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = displayTitle,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "Audio: $currentAudioInfo",
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // [STATO PRESSIONE] Rileva quando il dito preme sulla chip
							val chipInteractionSource = remember { MutableInteractionSource() }
							val isChipPressed by chipInteractionSource.collectIsPressedAsState()
							
							// [COLORI DEI MOTORI] Scegli i due colori esadecimali per i rispettivi player
							val exoColor = Color(0xFF00E5FF) // Ciano neon elettrico per EXO
							val vlcColor = Color(0xFFFF6D00) // Arancione neon brillante (stile cono VLC) per VLC
							
							// [COLORE DINAMICO ANIMATO] Sfuma dolcemente da un colore all'altro al cambio motore
							val activeNeonColor by animateColorAsState(
							    targetValue = if (useExoPlayer == true) exoColor else vlcColor,
							    // [VELOCITÀ TRANSIZIONE] Durata in millisecondi della sfumatura colore (350ms = fluida)
							    animationSpec = tween(durationMillis = 350),
							    label = "activeNeonColor"
							)
							
							// [BORDO] Spessore del contorno della pillola (Premuto vs Normale)
							val chipBorderWidth by animateDpAsState(
							    targetValue = if (isChipPressed) 2.5.dp else 1.5.dp,
							    label = "chipBorderWidth"
							)
							
							// [OPACITÀ LUCE] Intensità del bordo e dell'alone quando premi (da 0.0f a 1.0f)
							val chipGlowAlpha by animateFloatAsState(
							    targetValue = if (isChipPressed) 1f else 0.75f,
							    label = "chipGlowAlpha"
							)
							
							// [SCALA PRESSIONE] Leggero effetto rimpicciolimento al tocco (1f = 100%, 0.95f = 95%)
							val chipScale by animateFloatAsState(
							    targetValue = if (isChipPressed) 0.95f else 1f,
							    label = "chipScale"
							)
							
							// [AMPIEZZA ALONE] Quanto si espande la sfumatura neon fuori dai bordi della pillola
							val chipBlurRadius by animateDpAsState(
							    targetValue = if (isChipPressed) 18.dp else 10.dp,
							    label = "chipBlurRadius"
							)
							
							// CHIP DI CAMBIO MOTORE STILE CYBER/NEON DINAMICO
							Surface(
							    onClick = {
							        isBuffering = true
                                    errorResult = null
							        if (useExoPlayer == true) {
							            exoManager.release()
							        } else {
							            vlcManager.release()
							        }
							        useExoPlayer = !(useExoPlayer ?: false)
							    },
							    // [COLORE SFONDO CHIP] Sfondo scuro interno
							    color = Color(0xFF161922),
							    // [FORMA E ANGOLI] Arrotondamento degli angoli della pillola
							    shape = RoundedCornerShape(16.dp),
							    border = BorderStroke(
							        width = chipBorderWidth, 
							        color = activeNeonColor.copy(alpha = chipGlowAlpha)
							    ),
							    interactionSource = chipInteractionSource,
							    modifier = Modifier
							        // [ALTEZZA CHIP] Altezza totale del pulsante
							        .height(36.dp)
							        .graphicsLayer(scaleX = chipScale, scaleY = chipScale)
							        // ✨ DISEGNA L'ALONE NEON RETTANGOLARE DIETRO LA CHIP
							        .drawBehind {
							            drawIntoCanvas { canvas ->
							                val paint = Paint().apply {
							                    // [INTENSITÀ ALONE ESTERNO] Trasparenza della luce diffusa
							                    color = activeNeonColor.copy(alpha = if (isChipPressed) 0.65f else 0.35f)
							                    asFrameworkPaint().apply {
							                        isAntiAlias = true
							                        // Applica la sfocatura reale che segue i bordi arrotondati
							                        maskFilter = android.graphics.BlurMaskFilter(
							                            chipBlurRadius.toPx(),
							                            android.graphics.BlurMaskFilter.Blur.NORMAL
							                        )
							                    }
							                }
							                // Disegna il rettangolo sfocato leggermente più largo della pillola
							                canvas.drawRoundRect(
							                    left = -2.dp.toPx(),
							                    top = -2.dp.toPx(),
							                    right = size.width + 2.dp.toPx(),
							                    bottom = size.height + 2.dp.toPx(),
							                    radiusX = 16.dp.toPx(),
							                    radiusY = 16.dp.toPx(),
							                    paint = paint
							                )
							            }
							        }
							) {
							    Row(
							        verticalAlignment = Alignment.CenterVertically,
							        // [SPAZIATURA INTERNA] Margini interni tra bordo, pallino e testo
							        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
							    ) {
							        // PALLINO INDICATORE CON MICRO-ALONE LED DINAMICO
							        Box(
							            modifier = Modifier
							                // [DIMENSIONE PALLINO] Grandezza del punto luminoso
							                .size(8.dp)
							                // ✨ DISEGNA UN PICCOLO BAGLIORE ATTORNO AL PALLINO LED
							                .drawBehind {
							                    drawCircle(
							                        brush = Brush.radialGradient(
							                            colors = listOf(
							                                activeNeonColor.copy(alpha = 0.85f),
							                                Color.Transparent
							                            ),
							                            // [RAGGIO ALONE PALLINO] Ampiezza bagliore del LED
							                            radius = size.minDimension * 1.1f
							                        ),
							                        radius = size.minDimension * 1.1f
							                    )
							                }
							                .background(activeNeonColor, CircleShape)
							        )
							        
							        // [DISTANZA PALLINO-TESTO] Spazio tra il LED e la scritta
							        Spacer(modifier = Modifier.width(8.dp))
							        
							        Text(
							            // [TESTO MOTORE] Etichetta visualizzata
							            text = if (useExoPlayer == true) "EXO" else "VLC",
							            // [COLORE TESTO] Colore della scritta
							            color = Color.White,
							            style = MaterialTheme.typography.labelMedium,
							            fontWeight = FontWeight.Bold
							        )
							    }
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
                                Icon(
                                    imageVector = Icons.Default.Replay10,
                                    contentDescription = "Indietro 10 secondi",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
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
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pausa" else "Play",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            IconButton(
                                onClick = {
                                    exoManager.stopAndDetach(exoPlayerView)
                                    vlcManager.stopAndDetach()
                                    onExitAndClearCache()
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
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
                                Icon(
                                    imageVector = Icons.Default.Forward10,
                                    contentDescription = "Avanti 10 secondi",
                                    tint = Color.White,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomEnd)
                                .navigationBarsPadding()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    showControls = false
                                    showSettingsSheet = true
                                }
                            ) {
                                Icon(Icons.Default.Settings, contentDescription = "Impostazioni", tint = Color.White)
                            }
                        }
                    }
                }

                // CONTROLLO BUFFERING AGGIORNATO CON errorResult == null
                if (isBuffering && errorResult == null && !isInPipMode && !isMinimized) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color(0xFF00E5FF))
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

                // =================================================================
                // CARD ERRORE DI RIPRODUZIONE (PULSANTE CIRCOLARE CYBER/NEON)
                // =================================================================
                errorResult?.let { result ->
                    if (!isInPipMode && !isMinimized) {
                        val errorIcon = when (result.type) {
                            StreamErrorType.UNAUTHORIZED -> Icons.Default.Lock
                            StreamErrorType.NOT_FOUND -> Icons.Default.SearchOff
                            StreamErrorType.GEO_BLOCKED -> Icons.Default.PublicOff
                            StreamErrorType.CONNECTION_LIMIT -> Icons.Default.Group
                            StreamErrorType.SERVER_ERROR -> Icons.Default.CloudOff
                            StreamErrorType.NETWORK_FAILURE -> Icons.Default.WifiOff
                            StreamErrorType.DECODE_ERROR -> Icons.Default.MovieCreation
                            else -> Icons.Default.ErrorOutline
                        }

                        Surface(
                            color = Color.Black.copy(alpha = 0.92f),
                            shape = RoundedCornerShape(20.dp),
                            tonalElevation = 8.dp,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(24.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = errorIcon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(30.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = when (result.type) {
                                            StreamErrorType.UNAUTHORIZED -> "Accesso Non Autorizzato"
                                            StreamErrorType.GEO_BLOCKED -> "Blocco Geografico / VPN"
                                            StreamErrorType.NOT_FOUND -> "Contenuto Non Trovato"
                                            StreamErrorType.SERVER_ERROR -> "Errore del Server"
                                            StreamErrorType.CONNECTION_LIMIT -> "Limite Raggiunto"
                                            else -> "Errore di Riproduzione"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                
                                Text(
                                    text = result.message,
                                    color = Color.White.copy(alpha = 0.85f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center
                                )
                                
                                Spacer(modifier = Modifier.height(20.dp))
                                
								// 🔄 PULSANTE CIRCOLARE "RIPROVA" CON EFFETTO LUMINOSO AL TOCCO
                                val interactionSource = remember { MutableInteractionSource() }
								val isPressed by interactionSource.collectIsPressedAsState()
								
								// [BORDO] Spessore del contorno del pulsante (Premuto vs Normale)
								val borderWidth by animateDpAsState(
								    targetValue = if (isPressed) 3.5.dp else 2.dp, // Aumentato leggermente per marcare il bordo
								    label = "borderWidth"
								)
								
								// [OPACITÀ BORDO E ICONA] Intensità della luce del bordo e dell'icona (da 0.0f a 1.0f)
								val glowAlpha by animateFloatAsState(
								    targetValue = if (isPressed) 1f else 0.8f, // Più luminoso anche a riposo
								    label = "glowAlpha"
								)
								
								// [SCALA PRESSIONE] Rimpicciolimento al tocco (1f = dimensione originale, 0.92f = 92%)
								val scale by animateFloatAsState(
								    targetValue = if (isPressed) 0.92f else 1f,
								    label = "scale"
								)
								
								// [AMPIEZZA ALONE] Quanto si estende la luce fuori dal pulsante (1.0f = bordo esatto del pulsante)
								val glowRadiusMultiplier by animateFloatAsState(
								    targetValue = if (isPressed) 2.1f else 1.65f, // AMPLIATO: prima era 1.55f e 1.25f
								    label = "glowRadius"
								)
								
								// [COLORE PRINCIPALE] Colore della luce e del tema del pulsante
								val cyanColor = Color(0xFF00E5FF)
								
								Surface(
								    onClick = {
								        errorResult = null
								        isBuffering = true
								        
								        if (isTorrent && playbackUrl.isBlank()) {
								            torrentEngine?.startStream(streamUrl)
								        } else if (useExoPlayer == true) {
								            exoManager.retry(playbackUrl)
								        } else {
								            vlcManager.retry(playbackUrl, userAgent)
								        }
								    },
								    modifier = Modifier
								        // [DIMENSIONE PULSANTE] Larghezza e altezza del cerchio centrale
								        .size(64.dp)
								        .graphicsLayer(scaleX = scale, scaleY = scale)
								        // ✨ DISEGNA L'ALONE LUMINOSO DIETRO AL PULSANTE
								        .drawBehind {
								            val radius = (size.minDimension / 2f) * glowRadiusMultiplier
								            drawCircle(
								                brush = Brush.radialGradient(
								                    // [INTENSITÀ E SFUMATURA ALONE] Più colori metti, più l'alone resta pieno prima di svanire
								                    colors = listOf(
								                        // 1. Centro: luce molto forte (era 0.55f / 0.25f)
								                        cyanColor.copy(alpha = if (isPressed) 0.85f else 0.45f), 
								                        // 2. Fascia intermedia: mantiene l'alone marcato più a lungo
								                        cyanColor.copy(alpha = if (isPressed) 0.40f else 0.18f), 
								                        // 3. Bordo esterno: sfuma completamente nel nulla
								                        Color.Transparent 
								                    ),
								                    center = center,
								                    radius = radius
								                ),
								                radius = radius,
								                center = center
								            )
								        },
								    shape = CircleShape,
								    // [COLORE SFONDO PULSANTE] Il colore scuro interno del cerchio
								    color = Color(0xFF161922),
								    border = BorderStroke(
								        width = borderWidth, 
								        color = cyanColor.copy(alpha = glowAlpha)
								    ),
								    interactionSource = interactionSource
								) {
								    Box(contentAlignment = Alignment.Center) {
								        Icon(
								            imageVector = Icons.Default.Refresh,
								            contentDescription = "Riprova",
								            tint = cyanColor.copy(alpha = glowAlpha),
								            // [DIMENSIONE ICONA] Grandezza della freccia interna
								            modifier = Modifier.size(34.dp)
								        )
								    }
							    }
                            }
                        }
                    }
                }
                PlayerOptionsMenu(
                    visible = showSettingsSheet && !isInPipMode && !isMinimized,
                    onDismiss = { showSettingsSheet = false },
                    audioTracks = uiAudioTracks,
                    currentAudioTrackId = currentAudioTrackId,
                    currentAudioInfo = currentAudioInfo,
                    onAudioTrackSelected = { track ->
                        if (useExoPlayer == true) {
                            exoManager.selectAudioTrack(track)
                        } else {
                            vlcManager.selectAudioTrack(track.id)
                        }
                        currentAudioTrackId = track.id
                        currentAudioInfo = track.formattedInfo
                    },
                    currentAspectRatio = currentAspectRatio,
                    currentScale = currentScale,
                    onAspectRatioSelected = { newRatio ->
                        currentAspectRatio = newRatio
                        prefs.edit().apply {
                            if (newRatio != null) putString("aspect_ratio", newRatio)
                            else remove("aspect_ratio")
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
                    },
                    openSubtitleItems = openSubtitleItems,
                    selectedOpenSubId = selectedOpenSubId,
                    isLoadingSubtitles = isLoadingSubtitles,
                    preferredSubLanguage = preferredSubLanguage,
                    onPreferredSubLanguageChanged = { langCode ->
                        preferredSubLanguage = langCode
                        prefs.edit().putString("sub_lang", langCode).apply()
                        triggerOpenSubtitlesSearch(displayTitle, langCode)
                    },
                    onOpenSubtitleSelected = { item ->
                        if (item == null) {
                            selectedOpenSubId = ""
                            activeSubtitleCues = emptyList()
                            currentSubtitleText = null
                        } else {
                            selectedOpenSubId = item.id
                            coroutineScope.launch {
                                isLoadingSubtitles = true
                                val parsedCues = OpenSubtitlesManager.downloadAndParseSubtitle(item)
                                activeSubtitleCues = parsedCues
                                isLoadingSubtitles = false
                                if (parsedCues.isEmpty()) {
                                    Toast.makeText(
                                        this@PlayerActivity,
                                        "Impossibile scaricare questo sottotitolo, provane un altro dalla lista",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    onRefreshSubtitles = {
                        triggerOpenSubtitlesSearch(displayTitle, preferredSubLanguage)
                    },
                    subtitleSizeScale = subtitleSizeScale,
                    subtitleStyle = subtitleStyle,
                    onSubtitleSizeChanged = { newSize ->
                        subtitleSizeScale = newSize
                        prefs.edit().putFloat("sub_scale", newSize).apply()
                    },
                    onSubtitleStyleChanged = { newStyle ->
                        subtitleStyle = newStyle
                        prefs.edit().putString("sub_style", newStyle.id).apply()
                    }
                )
            }
        }
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_USER_AGENT = "extra_user_agent"
    }
}
                
