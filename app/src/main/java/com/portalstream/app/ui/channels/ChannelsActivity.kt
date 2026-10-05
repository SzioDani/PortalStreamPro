package com.portalstream.app.ui.channels

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalstream.app.R
import com.portalstream.app.domain.model.Channel
import com.portalstream.app.network.PlaylistDownloader
import com.portalstream.app.ui.home.ChannelCache
import com.portalstream.app.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

class ChannelsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Canali"
        val url = intent.getStringExtra(EXTRA_URL)
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)?.takeIf { it.isNotBlank() }

        setContentView(ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    ChannelsScreen(title, url, userAgent, ChannelCache.take())
                }
            }
        })
    }

    @Composable
    fun ChannelsScreen(
        title: String,
        url: String?,
        userAgent: String?,
        initial: List<Channel>
    ) {
        var channels by remember { mutableStateOf(initial) }
        var loading by remember { mutableStateOf(initial.isEmpty() && !url.isNullOrBlank()) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(Unit) {
            if (channels.isEmpty() && !url.isNullOrBlank()) {
                loading = true
                try {
                    // Usiamo la nuova architettura in un thread separato (IO)
                    withContext(Dispatchers.IO) {
                        val tempList = mutableListOf<Channel>()
                        // Il nuovo downloader estrae i canali uno ad uno e li mettiamo nella lista
                        PlaylistDownloader().downloadAndParse(url) { channel ->
                            tempList.add(channel)
                        }
                        channels = tempList
                    }
                    if (channels.isEmpty()) error = getString(R.string.playlist_no_channels)
                } catch (e: Exception) {
                    Timber.e(e, "Download playlist fallito")
                    error = "${getString(R.string.config_import_failed)}: ${e.message}"
                } finally {
                    loading = false
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { finish() }) { Text("Indietro") }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (channels.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.playlist_channels_count, channels.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.config_importing))
                }
            }

            error?.let {
                Text("Attenzione: $it", color = MaterialTheme.colorScheme.error)
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(channels, key = { it.url }) { channel ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                startActivity(
                                    Intent(this@ChannelsActivity, PlayerActivity::class.java).apply {
                                        // Le nuove costanti allineate con ExoPlayer!
                                        putExtra("STREAM_URL", channel.url)
                                        putExtra("CHANNEL_NAME", channel.name)
                                        putExtra("USER_AGENT", userAgent)
                                    }
                                )
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(channel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // Aggiornato: group ora non è più nullable nel nuovo modello
                            if (channel.group.isNotBlank()) {
                                Text(
                                    text = channel.group,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "com.portalstream.app.extra.TITLE"
        const val EXTRA_URL = "com.portalstream.app.extra.URL"
        const val EXTRA_USER_AGENT = "com.portalstream.app.extra.USER_AGENT"
    }
}
