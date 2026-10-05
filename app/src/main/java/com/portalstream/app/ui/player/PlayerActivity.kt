package com.portalstream.app.ui.player

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlayer.Builder
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.portalstream.app.network.NetworkSniffer
import com.portalstream.app.streaming.AdaptiveBitrateManager
import com.portalstream.app.utils.DeviceDetector
import com.portalstream.app.utils.DeviceType
import timber.log.Timber

private const val DEFAULT_UA =
    "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG200 stbapp ver: 4.3.1939"

class PlayerActivity : ComponentActivity() {

    private lateinit var exoPlayer: ExoPlayer
    private lateinit var pipManager: PipManager
    private lateinit var adaptiveBitrate: AdaptiveBitrateManager
    private lateinit var deviceDetector: DeviceDetector
    private lateinit var networkSniffer: NetworkSniffer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            initApp()
        } catch (e: Exception) {
            Timber.e(e, "Crash in onCreate")
            showCrashScreen(e)
        }
    }

    private fun initApp() {
        deviceDetector = DeviceDetector(this)
        networkSniffer = NetworkSniffer(this)

        Timber.d("Device: ${deviceDetector.getDeviceInfo()}")

        // User-Agent: quello del portale se impostato, altrimenti default MAG
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_UA

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("User-Agent" to userAgent))

        exoPlayer = Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .build()

        pipManager = PipManager(this, exoPlayer)

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        adaptiveBitrate = AdaptiveBitrateManager(exoPlayer, connectivityManager)
        adaptiveBitrate.autoSelectQuality()

        loadStreamFromIntent()

        setupUI()
    }

    private fun loadStreamFromIntent() {
        val streamUrl = intent.getStringExtra(EXTRA_STREAM_URL) ?: TEST_STREAM_URL
        val channelName = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Test Stream"
        Timber.d("Riproduco: $channelName -> $streamUrl")

        val mediaItem = MediaItem.fromUri(streamUrl)
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    private fun showCrashScreen(e: Exception) {
        setContentView(ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    CrashScreen(e)
                }
            }
        })
    }

    private fun setupUI() {
        val device = deviceDetector.detectDevice()

        setContentView(ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        when (device) {
                            DeviceType.PHONE -> PhonePlayerUI(exoPlayer, pipManager)
                            DeviceType.TABLET -> TabletPlayerUI(exoPlayer)
                            DeviceType.TV, DeviceType.FIRESTICK, DeviceType.BOX_ANDROID -> TVPlayerUI(exoPlayer)
                            else -> PhonePlayerUI(exoPlayer, pipManager)
                        }
                    }
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        if (::exoPlayer.isInitialized && ::pipManager.isInitialized && !pipManager.isInPipMode()) {
            exoPlayer.pause()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::exoPlayer.isInitialized) {
            exoPlayer.play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::exoPlayer.isInitialized) {
            exoPlayer.release()
        }
    }

    companion object {
        const val EXTRA_STREAM_URL = "com.portalstream.app.extra.STREAM_URL"
        const val EXTRA_CHANNEL_NAME = "com.portalstream.app.extra.CHANNEL_NAME"
        const val EXTRA_USER_AGENT = "com.portalstream.app.extra.USER_AGENT"
        const val TEST_STREAM_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    }
}
