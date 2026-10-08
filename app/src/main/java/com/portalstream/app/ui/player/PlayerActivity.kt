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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
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
        var showSettingsSheet by remember { mutableStateOf(false) }
        var activeTab by remember { mutableIntStateOf(0) }

        var audioTracks by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
        var currentAudioTrackId by remember { mutableIntStateOf(-1) }

        var vlcVideoLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }

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

            // useTextureView = true per renderizzare correttamente con Jetpack Compose
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
                        errorMessage = null
                        audioTracks = mp.audioTracks?.toList() ?: emptyList()
                        currentAudioTrackId = mp.audioTrack
                    }
                    MediaPlayer.Event.EncounteredError -> {
                        isBuffering = false
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
        ) {
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        vlcVideoLayout = layout
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            IconButton(
                onClick = {
                    audioTracks = mediaPlayer?.audioTracks?.toList() ?: emptyList()
                    currentAudioTrackId = mediaPlayer?.audioTrack ?: -1
                    showSettingsSheet = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Impostazioni",
                    tint = Color.White
                )
            }

            if (isBuffering && errorMessage == null) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }

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
                            mediaPlayer?.play()
                        }) {
                            Text("Riprova")
                        }
                    }
                }
            }

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
                                text = "Opzioni Riproduzione (LibVLC)",
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
                                text = { Text("Audio") }
                            )
                            Tab(
                                selected = activeTab == 1,
                                onClick = { activeTab = 1 },
                                text = { Text("Formato") }
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        when (activeTab) {
                            0 -> {
                                if (audioTracks.isEmpty()) {
                                    Text(
                                        text = "Nessuna traccia audio trovata.",
                                        color = Color.Gray,
                                        modifier = Modifier.padding(vertical = 16.dp)
                                    )
                                } else {
                                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                                        items(audioTracks) { track ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        mediaPlayer?.setAudioTrack(track.id)
                                                        currentAudioTrackId = track.id
                                                    }
                                                    .padding(vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = (track.id == currentAudioTrackId),
                                                    onClick = {
                                                        mediaPlayer?.setAudioTrack(track.id)
                                                        currentAudioTrackId = track.id
                                                    }
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = track.name ?: "Traccia ${track.id}",
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            1 -> {
                                val ratios = listOf(
                                    "Originale" to null,
                                    "16:9" to "16:9",
                                    "4:3" to "4:3",
                                    "Riempi Schermo" to "18:9"
                                )
                                Column {
                                    ratios.forEach { (label, ratio) ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    mediaPlayer?.aspectRatio = ratio
                                                }
                                                .padding(vertical = 10.dp),
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
                    200 -> "Errore del flusso video o formato non supportato."
                    401, 403 -> "User-Agent o MAC non autorizzato dal server (HTTP $responseCode)."
                    404 -> "Canale o risorsa non trovata sul server (HTTP 404)."
                    456 -> "Accesso o IP rifiutato dal server (HTTP $responseCode)."
                    429, 458, 462 -> "Troppi utenti o connessioni contemporanee al server (HTTP $responseCode)."
                    in 500..504 -> "Server IPTV momentaneamente non disponibile (HTTP $responseCode)."
                    else -> "Errore HTTP $responseCode dal server IPTV."
                }
                conn.disconnect()
            } catch (e: Exception) {
                message = "Impossibile connettersi al server IPTV. Verifica la connessione di rete."
            }
            withContext(Dispatchers.Main) {
                onResult(message)
            }
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
