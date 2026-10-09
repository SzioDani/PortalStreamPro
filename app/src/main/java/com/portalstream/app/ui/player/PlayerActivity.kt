package com.portalstream.app.ui.player

import android.app.PictureInPictureParams
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: intent.getStringExtra("STREAM_URL") ?: ""
        channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: intent.getStringExtra("CHANNEL_NAME") ?: "Canale Live"
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
    fun PlayerScreen(channelName: String, onBackPressed: () -> Unit) {
        var isPlaying by remember { mutableStateOf(true) }
        var isBuffering by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var showControls by remember { mutableStateOf(true) }
        var showSettingsSheet by remember { mutableStateOf(false) }
        var activeTab by remember { mutableIntStateOf(0) }

        var audioTrackDescriptions by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
        var currentAudioTrackId by remember { mutableIntStateOf(-1) }
        var currentAudioInfo by remember { mutableStateOf("Analisi audio...") }

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

            mp.setEventListener { event ->
                when (event.type) {
                    MediaPlayer.Event.Buffering -> {
                        isBuffering = event.buffering < 100f
                    }
                    MediaPlayer.Event.Playing -> {
                        isBuffering = false
                        isPlaying = true
                        errorMessage = null
                        audioTrackDescriptions = mp.audioTracks?.toList() ?: emptyList()
                        currentAudioTrackId = mp.audioTrack
                        currentAudioInfo = extractAudioTrackDetails(mp)
                    }
                    MediaPlayer.Event.Paused -> {
                        isPlaying = false
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        isBuffering = false
                        isPlaying = false
                        checkHttpError(streamUrl, userAgent) { mappedMessage ->
                            errorMessage = mappedMessage
                        }
                    }
                }
            }

            mp.play()

            onDispose {
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

                    IconButton(
                        onClick = {
                            mediaPlayer?.let { mp ->
                                if (mp.isPlaying) mp.pause() else mp.play()
                            }
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.6f), shape = MaterialTheme.shapes.extraLarge)
                    ) {
                        if (isPlaying) {
                            Text("II", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(44.dp))
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Button(
                                onClick = { triggerPipMode() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.2f))
                            ) {
                                Text("PIP", color = Color.White)
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

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
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = "Errore di Riproduzione",
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
                            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }, text = { Text("Audio") })
                            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }, text = { Text("Formato Aspect") })
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        when (activeTab) {
                            0 -> {
                                if (audioTrackDescriptions.isEmpty()) {
                                    Text(
                                        text = "Nessuna traccia audio rilevata.",
                                        color = Color.Gray,
                                        modifier = Modifier.padding(vertical = 16.dp)
                                    )
                                } else {
                                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                                        items(audioTrackDescriptions) { track ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        mediaPlayer?.setAudioTrack(track.id)
                                                        currentAudioTrackId = track.id
                                                        mediaPlayer?.let { currentAudioInfo = extractAudioTrackDetails(it) }
                                                    }
                                                    .padding(vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = (track.id == currentAudioTrackId),
                                                    onClick = {
                                                        mediaPlayer?.setAudioTrack(track.id)
                                                        currentAudioTrackId = track.id
                                                        mediaPlayer?.let { currentAudioInfo = extractAudioTrackDetails(it) }
                                                    }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "${track.name ?: "Traccia ${track.id}"} ${if (track.id == currentAudioTrackId) "($currentAudioInfo)" else ""}",
                                                    color = Color.White,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            1 -> {
                                val ratios = listOf(
                                    "Originale (Default)" to null,
                                    "16:9 (Standard HD)" to "16:9",
                                    "4:3 (TV Classica)" to "4:3",
                                    "21:9 (Cinematic)" to "21:9",
                                    "Riempi Schermo" to "18:9"
                                )
                                Column {
                                    ratios.forEach { (label, ratio) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { mediaPlayer?.aspectRatio = ratio }
                                                .padding(vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(text = label, color = Color.White)
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

    private fun extractAudioTrackDetails(mp: MediaPlayer): String {
        val media = mp.media ?: return "Sconosciuto"
        val activeTrackId = mp.audioTrack

        if (activeTrackId == -1) return "Disattivato"

        try {
            val trackCount = media.trackCount
            for (i in 0 until trackCount) {
                val track = media.getTrack(i)
                if (track != null && track.type == IMedia.Track.Type.Audio) {
                    if (track is IMedia.AudioTrack) {
                        val codecName = when (track.codec?.uppercase()) {
                            "MPGA", "MP2", "MP3" -> "MP2/MPEG"
                            "A52", "AC3" -> "AC3 (Dolby Digital)"
                            "EAC3" -> "EAC3 (Dolby Digital Plus)"
                            "AAC", "MP4A" -> "AAC"
                            else -> track.codec?.uppercase() ?: "Sconosciuto"
                        }
                        val channels = when (track.channels) {
                            1 -> "Mono"
                            2 -> "Stereo 2.0"
                            6 -> "5.1 Surround"
                            8 -> "7.1 Surround"
                            else -> "${track.channels} Ch"
                        }
                        val sampleRate = if (track.rate > 0) "${track.rate / 1000} kHz" else ""
                        return "$codecName • $channels $sampleRate".trim()
                    }
                }
            }
        } catch (e: Exception) {
            // In caso di eccezione o traccia grezza
        }

        val currentTrack = mp.audioTracks?.firstOrNull { it.id == activeTrackId }
        return currentTrack?.name ?: "Standard Audio"
    }

    private fun triggerPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    private fun checkHttpError(urlStr: String, userAgentStr: String?, onResult: (String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            var message = "Errore durante la riproduzione del flusso multimediale."
            try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                userAgentStr?.let { conn.setRequestProperty("User-Agent", it) }

                val responseCode = conn.responseCode
                message = when (responseCode) {
                    200 -> "Impossibile decodificare il flusso video."
                    401, 403 -> "Accesso rifiutato (HTTP $responseCode). Verifica User-Agent / MAC."
                    404 -> "Canale non trovato sul server IPTV (HTTP 404)."
                    456 -> "Accesso o IP rifiutato dal server (HTTP 456)."
                    459 -> "Limite connessioni contemporanee superato (HTTP 459). Disconnetti altri dispositivi."
                    429, 458, 462 -> "Troppi utenti collegati al server (HTTP $responseCode)."
                    in 500..504 -> "Server IPTV momentaneamente non disponibile (HTTP $responseCode)."
                    else -> "Errore di connessione HTTP $responseCode."
                }
                conn.disconnect()
            } catch (e: Exception) {
                message = "Impossibile connettersi al server IPTV. Verifica la connessione."
            }
            withContext(Dispatchers.Main) { onResult(message) }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        triggerPipMode()
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_CHANNEL_NAME = "extra_channel_name"
        const val EXTRA_USER_AGENT = "extra_user_agent"
    }
}
