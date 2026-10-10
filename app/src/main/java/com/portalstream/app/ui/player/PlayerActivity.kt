package com.portalstream.app.ui.player

import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
    
    // ✅ VARIABILI PERSISTENTI PER ROTAZIONE
    private var savedAspectRatio: String? = null
    private var savedScale: Float = 0f
    private var isPlayerPlaying: Boolean = false
    private var playerCurrentTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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

        // ✅ Ripristina lo stato salvato da una rotazione precedente
        if (savedInstanceState != null) {
            savedAspectRatio = savedInstanceState.getString("saved_aspect_ratio")
            savedScale = savedInstanceState.getFloat("saved_scale", 0f)
            isPlayerPlaying = savedInstanceState.getBoolean("is_playing", false)
            playerCurrentTime = savedInstanceState.getLong("player_time", 0L)
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

    // ✅ Salva lo stato prima della rotazione
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        
        mediaPlayer?.let { mp ->
            outState.putString("saved_aspect_ratio", savedAspectRatio)
            outState.putFloat("saved_scale", savedScale)
            outState.putBoolean("is_playing", mp.isPlaying)
            outState.putLong("player_time", mp.time)
        }
    }

    @Composable
    fun PlayerScreen(channelName: String, onBackPressed: () -> Unit) {
        var isPlaying by remember { mutableStateOf(isPlayerPlaying) }
        var isBuffering by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var showControls by remember { mutableStateOf(true) }
        var showSettingsSheet by remember { mutableStateOf(false) }
        var activeTab by remember { mutableIntStateOf(0) }

        var audioTrackDescriptions by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
        var currentAudioTrackId by remember { mutableIntStateOf(-1) }
        var currentAudioInfo by remember { mutableStateOf("Rilevamento audio...") }

        // ✅ STATO PERSISTENTE PER ASPECT RATIO
        var currentAspectRatio by remember { mutableStateOf(savedAspectRatio) }
        var currentScale by remember { mutableStateOf(savedScale) }

        var vlcVideoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }

        LaunchedEffect(showControls, isPlaying) {
            if (showControls && isPlaying) {
                delay(4000)
                showControls = false
            }
        }

        DisposableEffect(vlcVideoLayout) {
            val layout = vlcVideoLayout ?: return@DisposableEffect onDispose {}

            val options = ArrayList<String>().apply {
                add("--no-drop-late-frames")
                add("--no-skip-frames")
                add("--rtsp-tcp")
                add("--http-reconnect")
                add("--network-caching=1500")
            }

            val vlc = LibVLC(this@PlayerActivity, options)
            val mp = MediaPlayer(vlc)

            libVLC = vlc
            mediaPlayer = mp

            mp.attachViews(layout, null, false, true)

            val media = Media(vlc, Uri.parse(streamUrl)).apply {
                setHWDecoderEnabled(true, false)
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

            mp.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Buffering -> {
                        isBuffering = event.buffering < 100f
                    }
                    MediaPlayer.Event.Playing -> {
                        isBuffering = false
                        isPlaying = true
                        isPlayerPlaying = true
                        errorMessage = null
                        refreshAudioState()
                    }
                    MediaPlayer.Event.Paused -> {
                        isPlaying = false
                        isPlayerPlaying = false
                    }
                    MediaPlayer.Event.Stopped -> {
                        isPlaying = false
                        isPlayerPlaying = false
                    }
                    MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESSelected, MediaPlayer.Event.ESDeleted -> {
                        refreshAudioState()
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        isBuffering = false
                        isPlaying = false
                        isPlayerPlaying = false
                        checkHttpError(streamUrl, userAgent) { mappedMessage ->
                            errorMessage = mappedMessage
                        }
                    }
                }
            }

            // ✅ RIPRISTINA ASPECT RATIO DOPO LA ROTAZIONE - VERSIONE CORRETTA
            when (savedAspectRatio) {
                "ZOOM125" -> {
                    mp.aspectRatio = null
                    mp.scale = 1.25f
                    currentScale = 1.25f
                }
                "FILL" -> {
                    mp.aspectRatio = null
                    mp.scale = 1.3f
                    currentScale = 1.3f
                }
                null -> {
                    mp.aspectRatio = null
                    mp.scale = 0f
                    currentScale = 0f
                }
                else -> {
                    mp.scale = 0f
                    mp.aspectRatio = savedAspectRatio
                    currentAspectRatio = savedAspectRatio
                }
            }

            // ✅ RIPRISTINA POSIZIONE TEMPORALE SE ERA IN PAUSA
            if (playerCurrentTime > 0 && !isPlayerPlaying) {
                mp.time = playerCurrentTime
            }

            mp.play()

            onDispose {
                // ✅ SALVA LO STATO PRIMA DI RILASCIARE - VERSIONE CORRETTA
                mediaPlayer?.let {
                    savedAspectRatio = when {
                        it.scale == 1.25f -> "ZOOM125"
                        it.scale == 1.3f -> "FILL"
                        it.aspectRatio != null && it.aspectRatio.isNotBlank() -> it.aspectRatio
                        else -> null
                    }
                    savedScale = it.scale
                    isPlayerPlaying = it.isPlaying
                    playerCurrentTime = it.time
                }

                mp.stop()
                mp.detachViews()
                mp.release()
                vlc.release()
                mediaPlayer = null
                libVLC = null
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showControls = !showControls }
        ) {
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        vlcVideoLayout = layout
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Controlli Overlay
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f))
                ) {
                    // Top Bar
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
                    }

                    // Controlli Centrali
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Indietro -10s
                        IconButton(
                            onClick = {
                                mediaPlayer?.let { mp ->
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

                        // Play / Pausa
                        IconButton(
                            onClick = {
                                mediaPlayer?.let { mp ->
                                    if (mp.isPlaying) {
                                        mp.pause()
                                        isPlayerPlaying = false
                                    } else {
                                        mp.play()
                                        isPlayerPlaying = true
                                    }
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

                        // Stop
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

                        // Avanti +10s
                        IconButton(
                            onClick = {
                                mediaPlayer?.let { mp ->
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

                    // Bottom Bar
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
                            audioTrackDescriptions = mediaPlayer?.audioTracks?.toList() ?: emptyList()
                            currentAudioTrackId = mediaPlayer?.audioTrack ?: -1
                            showSettingsSheet = true
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "Impostazioni", tint = Color.White)
                        }
                    }
                }
            }

            if (isBuffering && errorMessage == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }

            errorMessage?.let { msg ->
                Surface(
                    color = Color.Black.copy(alpha = 0.90f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterVertically,
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
                            mediaPlayer?.play()
                        }) {
                            Text("Riprova")
                        }
                    }
                }
            }

            // ✅ PANNELLO IMPOSTAZIONI COMPLETO
            if (showSettingsSheet) {
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
                                    mediaPlayer?.setAudioTrack(trackId)
                                    currentAudioTrackId = trackId
                                    mediaPlayer?.let { mp ->
                                        currentAudioInfo = formatAudioTrackInfo(mp, audioTrackDescriptions, trackId)
                                    }
                                },
                                formatAudioInfo = { formatAudioTrackInfo(mediaPlayer!!, audioTrackDescriptions, it) }
                            )

                            1 -> AspectRatioTabContent(
                                currentAspectRatio = currentAspectRatio,
                                currentScale = currentScale,
                                onAspectRatioChange = { newRatio ->
                                    currentAspectRatio = newRatio
                                    savedAspectRatio = newRatio
                                    when (newRatio) {
                                        "FILL" -> {
                                            mediaPlayer?.aspectRatio = null
                                            mediaPlayer?.scale = 1.3f
                                            currentScale = 1.3f
                                            savedScale = 1.3f
                                        }
                                        "ZOOM125" -> {
                                            mediaPlayer?.aspectRatio = null
                                            mediaPlayer?.scale = 1.25f
                                            currentScale = 1.25f
                                            savedScale = 1.25f
                                        }
                                        null -> {
                                            mediaPlayer?.aspectRatio = null
                                            mediaPlayer?.scale = 0f
                                            currentScale = 0f
                                            savedScale = 0f
                                        }
                                        else -> {
                                            mediaPlayer?.scale = 0f
                                            mediaPlayer?.aspectRatio = newRatio
                                            currentScale = 0f
                                            savedScale = 0f
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
                            text = formatAudioInfo(track.id)
                                .replace("main - ", ""),
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
            "📦 Originale (Default)" to null,
            "📺 16:9 (Standard HD)" to "16:9",
            "📺 4:3 (TV Classica)" to "4:3",
            "🔍 Zoom 125%" to "ZOOM125",
            "🎬 21:9 (Cinematic)" to "21:9",
            "🎬 1.85:1 (Movie)" to "1.85:1",
            "🎬 2.35:1 (CinemaScope)" to "2.35:1",
            "🎬 2.39:1 (UltraWide)" to "2.39:1",
            "🔲 Riempi Schermo" to "FILL"
        )

        Column {
            ratios.chunked(2).forEach { rowItems ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    rowItems.forEach { (label, ratio) ->
                        // ✅ LOGICA isSelected CORRETTA
                        val isSelected = when {
                            ratio == "FILL" && currentScale == 1.3f -> true
                            ratio == "ZOOM125" && currentScale == 1.25f -> true
                            ratio == null && currentAspectRatio == null && currentScale == 0f -> true
                            ratio != null && ratio != "FILL" && ratio != "ZOOM125" && currentAspectRatio == ratio -> true
                            else -> false
                        }

                        Button(
                             onClick = { onAspectRatioChange(ratio) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(
                                 containerColor = if (isSelected) 
                                    Color.White.copy(alpha = 0.25f)
                            else
                                    Color.White.copy(alpha = 0.08f)
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
                    if (rowItems.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
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
        } catch (_: Exception) {}

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
                    456 -> "🚫 IP rifiutato (HTTP 456)."
                    459 -> "📊 Troppi utenti collegati (HTTP 459)."
                    429, 458, 462 -> "⏱️ Limite raggiunto (HTTP $responseCode)."
                    in 500..504 -> "🔧 Server non disponibile (HTTP $responseCode)."
                    else -> "❌ Errore HTTP $responseCode."
                }
                conn.disconnect()
            } catch (e: Exception) {
                message = "🌐 Impossibile connettersi al server."
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
