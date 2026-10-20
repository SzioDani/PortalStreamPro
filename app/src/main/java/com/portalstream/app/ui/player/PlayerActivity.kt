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
import androidx.compose.material.icons.filled.Pause
import androidx.core.view.WindowInsetsCompat
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
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout
import java.net.HttpURLConnection
import java.net.URL

class PlayerActivity : ComponentActivity() {

    private var libVLC: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var streamUrl: String = ""
    private var channelName: String = ""
    private var userAgent: String? = null
    private lateinit var pipManager: PipManager

    private var isInPipMode by mutableStateOf(false)
    private val INITIAL_BUFFER_THRESHOLD = 85f

    private val prefs by lazy {
        getSharedPreferences("player_settings", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipManager = PipManager(this)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
            ?: intent.getStringExtra("STREAM_URL") ?: ""
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME)
            ?: intent.getStringExtra("CHANNEL_NAME") ?: "Canale Live"
        userAgent = intent.getStringExtra(EXTRA_USER_AGENT)

        if (streamUrl.isBlank()) {
            Toast.makeText(this, "URL dello stream non valido", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        try {
            initializeLibVLC()
            Log.d("PlayerDebug", "LibVLC inizializzato con successo")
        } catch (e: Exception) {
            Log.e("PlayerDebug", "Errore nell'inizializzazione di LibVLC", e)
            Toast.makeText(this, "Errore LibVLC: ${e.message}", Toast.LENGTH_LONG).show()
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

    private fun initializeLibVLC() {
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

        libVLC = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVLC)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
        libVLC?.release()
        mediaPlayer = null
        libVLC = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d("PlayerDebug", "Configurazione cambiata: ${newConfig.orientation}")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Legge la modalità scelta nel menu impostazioni in alto a destra
        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val playerMode = appPrefs.getString("player_mode", "both") // Default a "both" o "miniplayer"

        // Se l'utente ha scelto esplicitamente il PiP di sistema ed esce premendo Home
        if ((playerMode == "pip" || playerMode == "both") && mediaPlayer?.isPlaying == true && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            pipManager.enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
        Log.d("PlayerPip", "PiP = $isInPictureInPictureMode")
        if (!isInPictureInPictureMode) {
            if (isFinishing) {
                finish()
            }
        }
    }

    @Composable
    fun PlayerScreen(channelName: String, onBackPressed: () -> Unit) {
        val savedAspectRatio = remember { prefs.getString("aspect_ratio", null) }
        val savedScale = remember { prefs.getFloat("scale", 0f) }

        var isPlaying by remember { mutableStateOf(false) }
        var isBuffering by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var showControls by remember { mutableStateOf(true) }
        var showSettingsSheet by remember { mutableStateOf(false) }
        var activeTab by remember { mutableIntStateOf(0) }

        // Stato locale per gestire l'attivazione del Miniplayer in-app trascinabile
        var isMinimized by remember { mutableStateOf(false) }

        var audioTrackDescriptions by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
        var currentAudioTrackId by remember { mutableIntStateOf(-1) }
        var currentAudioInfo by remember { mutableStateOf("Rilevamento audio...") }

        var currentAspectRatio by remember { mutableStateOf(savedAspectRatio) }
        var currentScale by remember { mutableStateOf(savedScale) }

        var vlcVideoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }

        val configuration = LocalConfiguration.current
        var composeMediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

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

        LaunchedEffect(configuration.orientation, currentAspectRatio, currentScale, composeMediaPlayer) {
            val mp = composeMediaPlayer
            if (mp != null) {
                delay(400) 
                
                if (currentScale > 0f) {
                    mp.aspectRatio = null
                    mp.scale = 0f 
                    delay(50)
                    mp.scale = currentScale
                } else if (currentAspectRatio != null) {
                    mp.scale = 0f
                    mp.aspectRatio = null
                    delay(50)
                    mp.aspectRatio = currentAspectRatio
                } else {
                    mp.scale = 0f
                    mp.aspectRatio = null
                }
            }
        }

        DisposableEffect(vlcVideoLayout) {
            val layout = vlcVideoLayout ?: return@DisposableEffect onDispose {}

            try {
                val vlc = libVLC ?: throw Exception("LibVLC non inizializzato")
                val mp = mediaPlayer ?: throw Exception("MediaPlayer non inizializzato")

                composeMediaPlayer = mp
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

                fun refreshAudioState() {
                    val tracks = mp.audioTracks?.toList() ?: emptyList()
                    audioTrackDescriptions = tracks
                    var currentId = mp.audioTrack
                    val validTracks = tracks.filter { it.id != -1 }

                    if (currentId == -1 && validTracks.isNotEmpty()) {
                        val defaultTrackId = validTracks.first().id
                        mp.audioTrack = defaultTrackId
                        currentId = defaultTrackId
                    }

                    currentAudioTrackId = currentId
                    currentAudioInfo = formatAudioTrackInfo(mp, tracks, currentId)
                }

                var initialBufferingStarted = false
                var isFirstPlay = true

                mp.setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.Buffering -> {
                            if (!initialBufferingStarted) {
                                initialBufferingStarted = true
                            }
                            isBuffering = event.buffering < INITIAL_BUFFER_THRESHOLD
                        }
                        MediaPlayer.Event.Playing -> {
                            isBuffering = false
                            isPlaying = true
                            errorMessage = null
                            pipManager.updatePipParams(true)

                            if (isFirstPlay) {
                                isFirstPlay = false
                                if (currentScale > 0f) {
                                    mp.aspectRatio = null
                                    mp.scale = currentScale
                                } else if (currentAspectRatio != null) {
                                    mp.scale = 0f
                                    mp.aspectRatio = currentAspectRatio
                                }
                            }
                            refreshAudioState()
                        }
                        MediaPlayer.Event.Paused -> {
                            isPlaying = false
                            pipManager.updatePipParams(false)
                        }
                        MediaPlayer.Event.Stopped -> {
                            isPlaying = false
                            pipManager.updatePipParams(false)
                        }
                        MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected, MediaPlayer.Event.ESDeleted -> {
                            refreshAudioState()
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            isBuffering = false
                            isPlaying = false
                            pipManager.updatePipParams(false)
                            checkHttpError(streamUrl, userAgent) { mappedMessage ->
                                errorMessage = mappedMessage
                            }
                        }
                    }
                }

                lifecycleScope.launch {
                    delay(500)
                    mp.play()
                }

                onDispose {
                    mp.stop()
                    mp.detachViews()
                }

            } catch (e: Exception) {
                Log.e("PlayerDebug", "Errore nel setup del player", e)
                errorMessage = "Errore: ${e.message}"
                onDispose {}
            }
        }

        // INTEGRAZIONE MINIPLAYER: Racchiude il player nel contenitore fluttuante definito in PlayerUIs.kt
        DraggableMiniPlayerContainer(
            isMinimized = isMinimized,
            onExpand = { isMinimized = false },
            onClose = onBackPressed,
            isPlaying = isPlaying,
            onTogglePlayPause = {
                val mp = composeMediaPlayer
                if (mp != null) {
                    if (mp.isPlaying) mp.pause() else mp.play()
                }
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
                
                AndroidView(
                    factory = { ctx ->
                        VLCVideoLayout(ctx).also { layout ->
                            vlcVideoLayout = layout
                            layout.fitsSystemWindows = false
                        }
                    },
                    modifier = Modifier.fillMaxSize()
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

                            // Pulsante pulito per ridurre a Miniplayer in-app (stile YouTube)
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
                                    val mp = composeMediaPlayer
                                    if (mp != null) {
                                        val target = (mp.time - 10000).coerceAtLeast(0)
                                        mp.time = target
                                    }
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
                                    val mp = composeMediaPlayer
                                    if (mp != null) {
                                        if (mp.isPlaying) mp.pause() else mp.play()
                                    }
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
                                    mediaPlayer?.stop()
                                    onBackPressed()
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp).background(Color.White, RoundedCornerShape(2.dp))
                                )
                            }

                            IconButton(
                                onClick = {
                                    val mp = composeMediaPlayer
                                    if (mp != null) {
                                        val target = mp.time + 10000
                                        mp.time = target
                                    }
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

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomEnd)
                                .navigationBarsPadding()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                val mp = composeMediaPlayer
                                if (mp != null) {
                                    audioTrackDescriptions = mp.audioTracks?.toList() ?: emptyList()
                                    currentAudioTrackId = mp.audioTrack
                                }
                                showSettingsSheet = true
                            }) {
                                Icon(Icons.Default.Settings, contentDescription = "Impostazioni", tint = Color.White)
                            }
                        }
                    }
                }

                if (isBuffering && errorMessage == null && !isInPipMode && !isMinimized) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }

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
                                Button(onClick = {
                                    errorMessage = null
                                    isBuffering = true
                                    lifecycleScope.launch {
                                        delay(500)
                                        mediaPlayer?.play()
                                    }
                                }) {
                                    Text("Riprova")
                                }
                            }
                        }
                    }
                }

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
                                    audioTracks = audioTrackDescriptions,
                                    currentTrackId = currentAudioTrackId,
                                    onTrackSelected = { trackId ->
                                        composeMediaPlayer?.setAudioTrack(trackId)
                                        currentAudioTrackId = trackId
                                        val mp = composeMediaPlayer
                                        if (mp != null) {
                                            currentAudioInfo = formatAudioTrackInfo(mp, audioTrackDescriptions, trackId)
                                        }
                                    },
                                    formatAudioInfo = { trackId ->
                                        val mp = composeMediaPlayer
                                        if (mp != null) {
                                            formatAudioTrackInfo(mp, audioTrackDescriptions, trackId)
                                        } else {
                                            "Traccia $trackId"
                                        }
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

                                        val mp = composeMediaPlayer
                                        if (mp != null) {
                                            when (newRatio) {
                                                "FILL" -> {
                                                    mp.scale = 1.45f
                                                    mp.aspectRatio = null
                                                    currentScale = 1.45f
                                                    prefs.edit().putFloat("scale", 1.45f).apply()
                                                }
                                                "ZOOM110" -> {
                                                    mp.scale = 1.10f
                                                    mp.aspectRatio = null
                                                    currentScale = 1.10f
                                                    prefs.edit().putFloat("scale", 1.10f).apply()
                                                }
                                                "ZOOM120" -> {
                                                    mp.scale = 1.20f
                                                    mp.aspectRatio = null
                                                    currentScale = 1.20f
                                                    prefs.edit().putFloat("scale", 1.20f).apply()
                                                }
                                                "ZOOM125" -> {
                                                    mp.scale = 1.25f
                                                    mp.aspectRatio = null
                                                    currentScale = 1.25f
                                                    prefs.edit().putFloat("scale", 1.25f).apply()
                                                }
                                                "ZOOM130" -> {
                                                    mp.scale = 1.30f
                                                    mp.aspectRatio = null
                                                    currentScale = 1.30f
                                                    prefs.edit().putFloat("scale", 1.30f).apply()
                                                }
                                                "ZOOM135" -> {
                                                    mp.scale = 1.35f
                                                    mp.aspectRatio = null
                                                    currentScale = 1.35f
                                                    prefs.edit().putFloat("scale", 1.35f).apply()
                                                }
                                                null -> {
                                                    mp.scale = 0f
                                                    mp.aspectRatio = null
                                                    currentScale = 0f
                                                    prefs.edit().putFloat("scale", 0f).apply()
                                                }
                                                else -> {
                                                    mp.scale = 0f
                                                    mp.aspectRatio = newRatio
                                                    currentScale = 0f
                                                    prefs.edit().putFloat("scale", 0f).apply()
                                                }
                                            }
                                        }
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
        audioTracks: List<MediaPlayer.TrackDescription>,
        currentTrackId: Int,
        onTrackSelected: (Int) -> Unit,
        formatAudioInfo: (Int) -> String
    ) {
        val validTracks = audioTracks.filter { it.id != -1 }

        if (validTracks.isEmpty()) {
            Text(
                text = "🔇 Nessuna traccia audio disponibile",
                color = Color.Gray,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(validTracks) { track ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onTrackSelected(track.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (track.id == currentTrackId),
                            onClick = { onTrackSelected(track.id) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatAudioInfo(track.id).replace("main - ", ""),
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
        var trackName = (selectedTrackDesc?.name ?: "Traccia $activeId")
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

    private fun checkHttpError(urlStr: String, userAgentStr: String?, onResult: (String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            var message = "❌ Errore durante la riproduzione del flusso multimediale."
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                userAgentStr?.let { conn.setRequestProperty("User-Agent", it) }

                val responseCode = conn.responseCode
                message = when (responseCode) {
                    200 -> "❌ Impossibile decodificare il flusso video."
                    401, 403 -> "🔐 Accesso rifiutato (HTTP $responseCode). Verifica User-Agent / MAC."
                    404 -> "🔍 Canale non trovato (HTTP 404)."
                    456 -> "🚫 IP rifiutato (HTTP 456) Errore geolocalizzazione (VPN)."
                    459 -> "📊 Troppi utenti collegati (HTTP $responseCode)."
                    429, 458, 462 -> "⏱️ Limite raggiunto (HTTP $responseCode)."
                    in 500..504 -> "🔧 Server non disponibile (HTTP $responseCode)."
                    else -> "❌ Errore HTTP $responseCode."
                }
                conn.disconnect()
            } catch (e: Exception) {
                message = "🌐 Impossibile connettersi al server."
                Log.e("PlayerDebug", "Errore HTTP check", e)
            }
            withContext(Dispatchers.Main) { onResult(message) }
        }
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_USER_AGENT = "extra_user_agent"
    }
}
