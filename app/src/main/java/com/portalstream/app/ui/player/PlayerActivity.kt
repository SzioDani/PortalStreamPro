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
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var streamUrl: String = ""
    private var channelName: String = ""
    private var userAgent: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schermo sempre attivo durante la riproduzione
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

    @OptIn(UnstableApi::class)
    @Composable
    fun PlayerScreen(
        channelName: String,
        onBackPressed: () -> Unit
    ) {
        var isBuffering by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }

        DisposableEffect(Unit) {
            val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
                setUserAgent(userAgent ?: "PortalStreamPro/1.0")
                setAllowCrossProtocolRedirects(true)
            }

            val mediaSourceFactory = DefaultMediaSourceFactory(httpDataSourceFactory)

            val exoPlayer = ExoPlayer.Builder(this@PlayerActivity)
                .setMediaSourceFactory(mediaSourceFactory)
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

                        override fun onPlayerError(error: PlaybackException) {
                            val cause = error.cause

                            val customMessage = when (cause) {
                                is HttpDataSource.InvalidResponseCodeException -> {
                                    when (cause.responseCode) {
                                        401, 403 -> "User-Agent o MAC non autorizzato dal server (HTTP ${cause.responseCode}). Prova a modificare l'User-Agent nelle impostazioni."
                                        429 -> "Troppi utenti connessi (HTTP 429). La playlist ha raggiunto il limite di connessioni contemporanee."
                                        500, 502, 503, 504 -> "Server IPTV momentaneamente non disponibile o in errore (HTTP ${cause.responseCode})."
                                        else -> "Il server ha restituito un errore HTTP ${cause.responseCode} durante la riproduzione."
                                    }
                                }
                                is HttpDataSource.HttpDataSourceException -> {
                                    "Impossibile connettersi al flusso video. Verifica la connessione di rete o l'URL del server."
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
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                update = { view ->
                    view.player = player
                },
                modifier = Modifier.fillMaxSize()
            )

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
                            player?.prepare()
                            player?.play()
                        }) {
                            Text("Riprova")
                        }
                    }
                }
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
