package com.portalstream.app.ui.channels

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.portalstream.app.data.AppDatabase
import com.portalstream.app.data.ChannelDao
import com.portalstream.app.data.Portal
import com.portalstream.app.domain.model.Channel
import com.portalstream.app.network.M3UParser
import com.portalstream.app.network.StalkerClient
import com.portalstream.app.network.XtreamClient
import com.portalstream.app.ui.player.PlayerActivity
import com.portalstream.app.ui.theme.PortalStreamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class ChannelsActivity : ComponentActivity() {

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val channelDao by lazy { db.channelDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Lista Canali"
        val portalType = intent.getStringExtra(EXTRA_PORTAL_TYPE) ?: ""
        val server = intent.getStringExtra(EXTRA_SERVER) ?: ""
        val macAddress = intent.getStringExtra(EXTRA_MAC) ?: ""
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: "ts"
        val portalUrl = intent.getStringExtra(EXTRA_PORTAL_URL) ?: ""
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)

        setContent {
            PortalStreamTheme {
                var isLoading by remember { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            // Controllo parametri Stalker
                            if (portalType.equals("STALKER", ignoreCase = true) || macAddress.isNotBlank()) {
                                if (server.isBlank()) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@ChannelsActivity, "⚠️ Errore: Server URL vuoto!", Toast.LENGTH_LONG).show()
                                    }
                                } else if (macAddress.isBlank()) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@ChannelsActivity, "⚠️ Errore: MAC Address vuoto!", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }

                            val channels = when {
                                // Stalker / MAC Address
                                portalType.equals("STALKER", ignoreCase = true) || macAddress.isNotBlank() -> {
                                    val portalDraft = Portal(
                                        id = 0,
                                        server = server,
                                        macAddress = macAddress,
                                        useCustomUserAgent = !userAgent.isNullOrBlank(),
                                        userAgent = userAgent ?: ""
                                    )
                                    StalkerClient.fetchChannels(portalDraft)
                                }
                                // Xtream Codes API
                                portalType.equals("XSTREAM", ignoreCase = true) || (server.isNotBlank() && username.isNotBlank()) -> {
                                    val portalDraft = Portal(
                                        id = 0,
                                        server = server,
                                        username = username,
                                        password = password,
                                        streamFormat = format,
                                        useCustomUserAgent = !userAgent.isNullOrBlank(),
                                        userAgent = userAgent ?: ""
                                    )
                                    XtreamClient.fetchLiveChannels(portalDraft)
                                }
                                // Liste M3U
                                portalUrl.isNotEmpty() -> {
                                    val connection = URL(portalUrl).openConnection()
                                    if (!userAgent.isNullOrBlank()) {
                                        connection.setRequestProperty("User-Agent", userAgent)
                                    }
                                    val inputStream = connection.getInputStream()
                                    val tempBuffer = mutableListOf<Channel>()

                                    M3UParser.parseStreaming(inputStream) { channel ->
                                        tempBuffer.add(channel)
                                    }
                                    tempBuffer
                                }
                                else -> emptyList()
                            }

                            if (channels.isNotEmpty()) {
                                channelDao.clearAll()
                                channelDao.insertChannels(channels)
                            } else {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@ChannelsActivity,
                                        "Nessun canale caricato. Verificare Server e MAC Address.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }

                            withContext(Dispatchers.Main) {
                                isLoading = false
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                isLoading = false
                                Toast.makeText(
                                    this@ChannelsActivity,
                                    "Errore Rete: ${e.localizedMessage}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Caricamento canali in corso...")
                            }
                        }
                    } else {
                        ChannelsScreen(
                            title = title,
                            channelDao = channelDao,
                            onChannelClick = { channel ->
                                val intent = Intent(this@ChannelsActivity, PlayerActivity::class.java).apply {
                                    putExtra("STREAM_URL", channel.url)
                                    putExtra("CHANNEL_NAME", channel.name)
                                    putExtra("USER_AGENT", userAgent)
                                }
                                startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_PORTAL_TYPE = "extra_portal_type"
        const val EXTRA_SERVER = "extra_server"
        const val EXTRA_MAC = "extra_mac"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_FORMAT = "extra_format"
        const val EXTRA_PORTAL_URL = "extra_portal_url"
        const val EXTRA_USER_AGENT = "extra_user_agent"
    }
}

@Composable
fun ChannelsScreen(
    title: String,
    channelDao: ChannelDao,
    onChannelClick: (Channel) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var showOnlyFavorites by remember { mutableStateOf(false) }

    val channelsList by channelDao.getAllChannels().collectAsState(initial = emptyList())

    val filteredChannels = channelsList.filter { channel ->
        val matchesSearch = channel.name.contains(searchQuery, ignoreCase = true) ||
                channel.group.contains(searchQuery, ignoreCase = true)
        val matchesFavorite = if (showOnlyFavorites) channel.isFavorite else true
        matchesSearch && matchesFavorite
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Barra di Ricerca
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Cerca canale...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Cerca") },
            singleLine = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Tasto Filtro Preferiti
        FilterChip(
            selected = showOnlyFavorites,
            onClick = { showOnlyFavorites = !showOnlyFavorites },
            label = { Text("Preferiti") },
            leadingIcon = {
                Icon(
                    imageVector = if (showOnlyFavorites) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = "Preferiti"
                )
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (filteredChannels.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nessun canale trovato.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredChannels, key = { it.id }) { channel ->
                    ChannelItem(
                        channel = channel,
                        onClick = { onChannelClick(channel) },
                        onFavoriteToggle = {
                            coroutineScope.launch(Dispatchers.IO) {
                                channelDao.updateFavorite(channel.id, !channel.isFavorite)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelItem(
    channel: Channel,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium
                )
                if (channel.group.isNotBlank()) {
                    Text(
                        text = channel.group,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(onClick = onFavoriteToggle) {
                Icon(
                    imageVector = if (channel.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = "Preferito",
                    tint = if (channel.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
