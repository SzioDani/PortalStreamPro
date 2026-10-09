package com.portalstream.app.ui.channels

import android.content.Context
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.portalstream.app.data.Portal
import com.portalstream.app.data.local.AppDatabase
import com.portalstream.app.domain.model.Channel
import com.portalstream.app.network.PortalException
import com.portalstream.app.network.StalkerClient
import com.portalstream.app.network.XtreamClient
import com.portalstream.app.streaming.M3UParser
import com.portalstream.app.ui.home.ChannelCache
import com.portalstream.app.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
class ChannelsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Canali"
        val portalUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        val portalType = intent.getStringExtra(EXTRA_PORTAL_TYPE) ?: ""
        val server = intent.getStringExtra(EXTRA_SERVER) ?: ""
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: "m3u8"
        val macAddress = intent.getStringExtra(EXTRA_MAC) ?: ""

        val db = AppDatabase.getDatabase(this)
        val channelDao = db.channelDao()
        val prefs = getSharedPreferences("portal_stream_prefs", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                val dbChannels by channelDao.getAllChannels().collectAsState(initial = emptyList())
                val localChannels = remember { ChannelCache.take() }

                val allChannels = if (localChannels.isNotEmpty()) localChannels else dbChannels
                val scope = rememberCoroutineScope()

                var selectedGroup by remember { mutableStateOf<String?>(null) }
                var searchQuery by remember { mutableStateOf("") }
                var showOnlyFavorites by remember { mutableStateOf(false) }

                // Preferiti per singoli canali
                var favoriteIds by remember {
                    mutableStateOf(prefs.getStringSet("favorite_ids", emptySet())?.toSet() ?: emptySet())
                }

                // Preferiti per interi Gruppi
                var favoriteGroups by remember {
                    mutableStateOf(prefs.getStringSet("favorite_groups", emptySet())?.toSet() ?: emptySet())
                }

                var isLoading by remember { mutableStateOf(true) }
                var loadingChannelId by remember { mutableStateOf<String?>(null) }
                var statusMessage by remember { mutableStateOf("") }

                var showGroupDialog by remember { mutableStateOf(false) }
                var groupSearchQuery by remember { mutableStateOf("") }

                val allGroups = remember(allChannels) {
                    allChannels.map { it.group }.distinct().filter { it.isNotBlank() }
                }

                val currentChannels = remember(
                    allChannels, selectedGroup, searchQuery, showOnlyFavorites, favoriteIds
                ) {
                    allChannels.filter { channel ->
                        val matchesGroup = selectedGroup == null || channel.group == selectedGroup
                        val matchesSearch = searchQuery.isBlank() ||
                                channel.name.contains(searchQuery, ignoreCase = true)
                        val matchesFavorites = !showOnlyFavorites || favoriteIds.contains(channel.id)
                        matchesGroup && matchesSearch && matchesFavorites
                    }
                }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        try {
                            if (localChannels.isNotEmpty()) {
                                isLoading = false
                                return@withContext
                            }

                            val isStalker = portalType.equals("STALKER", ignoreCase = true) || portalType.equals("MAG", ignoreCase = true)
                            val isXtream = portalType.equals("XTREAM", ignoreCase = true) || portalType.equals("XSTREAM", ignoreCase = true)

                            val fetched = when {
                                isStalker || macAddress.isNotBlank() -> {
                                    if (server.isBlank() || macAddress.isBlank()) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@ChannelsActivity, "Server o MAC vuoti!", Toast.LENGTH_LONG).show()
                                        }
                                        emptyList()
                                    } else {
                                        val portalDraft = Portal(
                                            id = 0,
                                            server = server,
                                            macAddress = macAddress,
                                            useCustomUserAgent = !userAgent.isNullOrBlank(),
                                            userAgent = userAgent ?: ""
                                        )
                                        StalkerClient.fetchChannels(portalDraft)
                                    }
                                }

                                isXtream || (username.isNotBlank() && password.isNotBlank()) -> {
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

                                portalUrl.isNotEmpty() -> {
                                    var formattedUrl = portalUrl.trim()
                                    if (!formattedUrl.startsWith("http://") && !formattedUrl.startsWith("https://")) {
                                        formattedUrl = "http://$formattedUrl"
                                    }
                                    val connection = URL(formattedUrl).openConnection()
                                    if (!userAgent.isNullOrBlank()) {
                                        connection.setRequestProperty("User-Agent", userAgent)
                                    }

                                    val inputStream = connection.getInputStream()
                                    val tempBuffer = mutableListOf<Channel>()

                                    M3UParser.parseStreaming(inputStream) {
                                        tempBuffer.add(it)
                                    }
                                    tempBuffer
                                }

                                else -> emptyList()
                            }

                            if (fetched.isNotEmpty()) {
                                channelDao.clearAll()
                                channelDao.insertChannels(fetched)
                            } else {
                                statusMessage = "Nessun canale caricato. Controlla server e credenziali."
                            }
                        } catch (e: PortalException) {
                            statusMessage = e.localizedMessage ?: "Errore del portale"
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ChannelsActivity, e.localizedMessage, Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            statusMessage = "Errore: ${e.localizedMessage}"
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@ChannelsActivity,
                                    "Errore Rete: ${e.localizedMessage}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                isLoading = false
                            }
                        }
                    }
                }

                // Dialogo Selezione e Gestione Gruppi
                if (showGroupDialog) {
                    AlertDialog(
                        onDismissRequest = {
                            showGroupDialog = false
                            // Manteniamo groupSearchQuery per non resettare la ricerca "IT"
                        },
                        title = { Text("Seleziona Gruppo") },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = groupSearchQuery,
                                    onValueChange = { groupSearchQuery = it },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    placeholder = { Text("Cerca categoria (es. IT)...") },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    trailingIcon = {
                                        if (groupSearchQuery.isNotEmpty()) {
                                            IconButton(onClick = { groupSearchQuery = "" }) {
                                                Icon(Icons.Default.Clear, contentDescription = "Cancella")
                                            }
                                        }
                                    },
                                    singleLine = true
                                )

                                // Filtra e ordina i gruppi mantenendo i preferiti in alto
                                val filteredGroups = remember(allGroups, groupSearchQuery, favoriteGroups) {
                                    val baseList = if (groupSearchQuery.isBlank()) allGroups
                                    else allGroups.filter { it.contains(groupSearchQuery, ignoreCase = true) }

                                    baseList.sortedWith(
                                        compareByDescending<String> { favoriteGroups.contains(it) }
                                            .thenBy { it }
                                    )
                                }

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 350.dp)
                                ) {
                                    item {
                                        ListItem(
                                            headlineContent = {
                                                Text(
                                                    "Tutti i Gruppi",
                                                    style = MaterialTheme.typography.bodyLarge
                                                )
                                            },
                                            modifier = Modifier.clickable {
                                                selectedGroup = null
                                                showOnlyFavorites = false
                                                showGroupDialog = false
                                            }
                                        )
                                        HorizontalDivider()
                                    }

                                    items(filteredGroups) { group ->
                                        val isFavGroup = favoriteGroups.contains(group)

                                        ListItem(
                                            headlineContent = { Text(group.ifBlank { "Generale" }) },
                                            trailingContent = {
                                                IconButton(
                                                    onClick = {
                                                        val newFavs = if (isFavGroup) favoriteGroups - group else favoriteGroups + group
                                                        favoriteGroups = newFavs
                                                        prefs.edit().putStringSet("favorite_groups", newFavs).apply()
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = if (isFavGroup) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                        contentDescription = "Preferito Gruppo",
                                                        tint = if (isFavGroup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            },
                                            modifier = Modifier.clickable {
                                                selectedGroup = group
                                                showOnlyFavorites = false
                                                showGroupDialog = false
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showGroupDialog = false }) {
                                Text("Chiudi")
                            }
                        }
                    )
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (isLoading && allChannels.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Caricamento canali dal server...")
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                placeholder = { Text("Cerca canale...") },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = "Cerca")
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Cancella")
                                        }
                                    }
                                },
                                singleLine = true
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = showOnlyFavorites,
                                    onClick = {
                                        showOnlyFavorites = !showOnlyFavorites
                                        if (showOnlyFavorites) selectedGroup = null
                                    },
                                    label = { Text("⭐ Preferiti") }
                                )

                                ElevatedButton(
                                    onClick = { showGroupDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.List,
                                        contentDescription = "Gruppi",
                                        modifier = Modifier.padding(end = 6.dp)
                                    )
                                    Text(
                                        text = selectedGroup ?: "Tutti i Gruppi",
                                        maxLines = 1
                                    )
                                }
                            }

                            if (currentChannels.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (statusMessage.isNotBlank()) statusMessage else if (showOnlyFavorites) "Nessun canale preferito." else "Nessun canale trovato.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(
                                        currentChannels,
                                        key = { it.id.ifEmpty { it.url + it.name } }
                                    ) { channel ->
                                        val isFav = favoriteIds.contains(channel.id)
                                        val isThisLoading = loadingChannelId == channel.id

                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .clickable(enabled = loadingChannelId == null) {
                                                    loadingChannelId = channel.id

                                                    scope.launch(Dispatchers.IO) {
                                                        try {
                                                            val isStalker = portalType.equals("STALKER", ignoreCase = true) || portalType.equals("MAG", ignoreCase = true)
                                                            val isXtream = portalType.equals("XTREAM", ignoreCase = true) || portalType.equals("XSTREAM", ignoreCase = true)

                                                            val playUrl = when {
                                                                isStalker || macAddress.isNotBlank() -> {
                                                                    val portalDraft = Portal(
                                                                        id = 0,
                                                                        server = server,
                                                                        macAddress = macAddress,
                                                                        useCustomUserAgent = !userAgent.isNullOrBlank(),
                                                                        userAgent = userAgent ?: ""
                                                                    )
                                                                    StalkerClient.getStreamUrl(portalDraft, channel.url) ?: channel.url
                                                                }
                                                                isXtream -> {
                                                                    if (channel.url.startsWith("http")) {
                                                                        channel.url
                                                                    } else {
                                                                        val cleanServer = server.trimEnd('/')
                                                                        val cleanFormat = if (format.isBlank()) "m3u8" else format
                                                                        "$cleanServer/live/$username/$password/${channel.id}.$cleanFormat"
                                                                    }
                                                                }
                                                                else -> channel.url
                                                            }

                                                            withContext(Dispatchers.Main) {
                                                                val finalUserAgent = if (userAgent.isNullOrBlank()) "MAG250" else userAgent
                                                                val intent = Intent(this@ChannelsActivity, PlayerActivity::class.java).apply {
                                                                    putExtra(PlayerActivity.EXTRA_STREAM_URL, playUrl)
                                                                    putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                                                                    putExtra(PlayerActivity.EXTRA_USER_AGENT, finalUserAgent)
                                                                }
                                                                startActivity(intent)
                                                            }
                                                        } catch (e: PortalException) {
                                                            withContext(Dispatchers.Main) {
                                                                Toast.makeText(this@ChannelsActivity, e.localizedMessage, Toast.LENGTH_LONG).show()
                                                            }
                                                        } catch (e: Exception) {
                                                            withContext(Dispatchers.Main) {
                                                                Toast.makeText(
                                                                    this@ChannelsActivity,
                                                                    "Errore apertura flusso: ${e.localizedMessage}",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        } finally {
                                                            withContext(Dispatchers.Main) {
                                                                loadingChannelId = null
                                                            }
                                                        }
                                                    }
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = channel.name,
                                                        style = MaterialTheme.typography.bodyLarge
                                                    )
                                                    if (channel.group.isNotBlank()) {
                                                        Text(
                                                            text = channel.group,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }

                                                if (isThisLoading) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(24.dp),
                                                        strokeWidth = 2.dp
                                                    )
                                                } else {
                                                    IconButton(
                                                        onClick = {
                                                            val newFavs = if (isFav) favoriteIds - channel.id else favoriteIds + channel.id
                                                            favoriteIds = newFavs
                                                            prefs.edit().putStringSet("favorite_ids", newFavs).apply()
                                                        }
                                                    ) {
                                                        Icon(
                                                            imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                            contentDescription = "Preferito",
                                                            tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
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
            }
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_PORTAL_TYPE = "extra_portal_type"
        const val EXTRA_SERVER = "extra_server"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_FORMAT = "extra_format"
        const val EXTRA_MAC = "extra_mac"
    }
}
