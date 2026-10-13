package com.portalstream.app.ui.channels

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.portalstream.app.data.Portal
import com.portalstream.app.data.local.AppDatabase
import com.portalstream.app.domain.model.Channel
import com.portalstream.app.network.PortalException
import com.portalstream.app.network.StalkerClient
import com.portalstream.app.network.XtreamClient
import com.portalstream.app.streaming.M3UParser
import com.portalstream.app.torrent.TorrentStreamManager
import com.portalstream.app.torrent.TorrentUtils
import com.portalstream.app.ui.home.ChannelCache
import com.portalstream.app.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class EpgInfo(
    val currentProgram: String = "",
    val nextProgram: String = "",
    val timeshift: Int = 0
)

@OptIn(ExperimentalMaterial3Api::class)
class ChannelsActivity : ComponentActivity() {

    private val TAG = "ChannelsActivity"
    private val client = OkHttpClient()

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

                // Stato per il fuso orario EPG
                var showSettingsDialog by remember { mutableStateOf(false) }
                var epgOffsetHours by remember {
                    mutableStateOf(prefs.getInt("epg_offset_hours", 0))
                }

                var favoriteIds by remember {
                    mutableStateOf(prefs.getStringSet("favorite_ids", emptySet())?.toSet() ?: emptySet())
                }

                var favoriteGroups by remember {
                    mutableStateOf(prefs.getStringSet("favorite_groups", emptySet())?.toSet() ?: emptySet())
                }

                var isLoading by remember { mutableStateOf(true) }
                var loadingChannelId by remember { mutableStateOf<String?>(null) }
                var statusMessage by remember { mutableStateOf("") }

                var showGroupDialog by remember { mutableStateOf(false) }
                var groupSearchQuery by remember { mutableStateOf("") }

                var epgMap by remember { mutableStateOf<Map<String, EpgInfo>>(emptyMap()) }
                var lastEpgUpdate by remember { mutableStateOf(0L) }

                val allGroups = remember(allChannels) {
                    allChannels.map { it.group }.distinct().filter { it.isNotBlank() }.sorted()
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
                            channelDao.clearAll()
                            Log.d(TAG, "Inizio caricamento. Tipo portale: $portalType, URL: $portalUrl, Titolo: $title")

                            // 1. Controlliamo se è un portale Torrent o se l'URL è un Magnet / InfoHash     
                            val directMagnet = TorrentUtils.parseToMagnetUri(portalUrl) ?: TorrentUtils.parseToMagnetUri(title)
                            if (directMagnet != null || portalType.equals("TORRENT", ignoreCase = true)) {
                                val torrentUrl = directMagnet ?: portalUrl
                                val directChannel = Channel(
                                    id = "torrent_direct_${torrentUrl.hashCode()}",
                                    name = if (title != "Canali" && title.isNotBlank()) title else "Stream Torrent Diretto",
                                    url = torrentUrl,
                                    group = "Torrent Diretti",   
                                    logoUrl = null,
                                    epgId = null
                                )
                                channelDao.insertChannels(listOf(directChannel))
                                withContext(Dispatchers.Main) {
                             
                                    isLoading = false
                                }
                                return@withContext
                            }

                            // 2. LOGICA ESISTENTE PER GLI ALTRI PORTALI (M3U, Xtream, Stalker)
                            val isStalker = portalType.equals("STALKER", ignoreCase = true) || 
                                          portalType.equals("MAG", ignoreCase = true)
                            val isXtream = portalType.equals("XTREAM", ignoreCase = true) || 
                                         portalType.equals("XSTREAM", ignoreCase = true)

                            val fetched = when {
                                isStalker || macAddress.isNotBlank() -> {
                                    if (server.isBlank() || macAddress.isBlank()) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@ChannelsActivity, "⚠️ Server o MAC vuoti!", Toast.LENGTH_LONG).show()
                                        }
                                        emptyList()
                                    } else {
                                        val portalDraft = Portal(
                                            id = 0, server = server, macAddress = macAddress,
                                            useCustomUserAgent = !userAgent.isNullOrBlank(), userAgent = userAgent ?: ""
                                        )
                                        StalkerClient.fetchChannels(portalDraft)
                                    }
                                }

                                isXtream || (username.isNotBlank() && password.isNotBlank()) -> {
                                    val portalDraft = Portal(
                                        id = 0, server = server, username = username, password = password,
                                        streamFormat = format, useCustomUserAgent = !userAgent.isNullOrBlank(), userAgent = userAgent ?: ""
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
                                    M3UParser.parseStreaming(inputStream) { tempBuffer.add(it) }
                                    tempBuffer
                                }

                                else -> emptyList()
                            }

                            if (fetched.isNotEmpty()) {
                                val validFetched = fetched.mapIndexed { index, channel ->
                                    val safeId = if (channel.id.isBlank()) {
                                        "ch_${index}_${channel.name.hashCode()}"
                                    } else {
                                        "ch_${index}_${channel.id}"
                                    }
                                    channel.copy(id = safeId)
                                }
                                channelDao.insertChannels(validFetched)
                            } else {
                                statusMessage = "❌ Nessun canale caricato. Verifica server e credenziali."
                            }

                        } catch (e: Exception) {
                            statusMessage = "Errore: ${e.localizedMessage}"
                        } finally {
                            withContext(Dispatchers.Main) { isLoading = false }
                        }
                    }

                    // Caricamento EPG in background
                    scope.launch(Dispatchers.IO) {
                        try {
                            val isStalker = portalType.equals("STALKER", ignoreCase = true) || 
                                          portalType.equals("MAG", ignoreCase = true) || macAddress.isNotBlank()
                            val isXtream = portalType.equals("XTREAM", ignoreCase = true) ||
                                          portalType.equals("XSTREAM", ignoreCase = true) ||
                                          (username.isNotBlank() && password.isNotBlank() && server.isNotBlank())

                            if (isXtream || isStalker) {
                                fetchUnifiedEpgData(
                                    server = server, username = username, password = password,
                                    macAddress = macAddress, isStalker = isStalker, userAgent = userAgent,
                                    channels = dbChannels, offsetHours = epgOffsetHours
                                ) { newEpgMap ->
                                    epgMap = newEpgMap
                                    lastEpgUpdate = System.currentTimeMillis()
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Dialog Impostazioni Fuso Orario EPG
                if (showSettingsDialog) {
                    AlertDialog(
                        onDismissRequest = { showSettingsDialog = false },
                        title = { Text("🕒 Regola Orario EPG") },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "Offset attuale: ${if (epgOffsetHours >= 0) "+$epgOffsetHours" else epgOffsetHours} ore",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Usa i pulsanti se la guida TV risulta in anticipo o in ritardo.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Button(onClick = {
                                        epgOffsetHours -= 1
                                        prefs.edit().putInt("epg_offset_hours", epgOffsetHours).apply()
                                        scope.launch(Dispatchers.IO) {
                                            fetchUnifiedEpgData(server, username, password, macAddress, portalType.contains("STALKER", true) || macAddress.isNotBlank(), userAgent, dbChannels, epgOffsetHours) { epgMap = it }
                                        }
                                    }) {
                                        Text("-1 Ora")
                                    }
                                    Button(onClick = {
                                        epgOffsetHours = 0
                                        prefs.edit().putInt("epg_offset_hours", epgOffsetHours).apply()
                                        scope.launch(Dispatchers.IO) {
                                            fetchUnifiedEpgData(server, username, password, macAddress, portalType.contains("STALKER", true) || macAddress.isNotBlank(), userAgent, dbChannels, epgOffsetHours) { epgMap = it }
                                        }
                                    }) {
                                        Text("Reset")
                                    }
                                    Button(onClick = {
                                        epgOffsetHours += 1
                                        prefs.edit().putInt("epg_offset_hours", epgOffsetHours).apply()
                                        scope.launch(Dispatchers.IO) {
                                            fetchUnifiedEpgData(server, username, password, macAddress, portalType.contains("STALKER", true) || macAddress.isNotBlank(), userAgent, dbChannels, epgOffsetHours) { epgMap = it }
                                        }
                                    }) {
                                        Text("+1 Ora")
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showSettingsDialog = false }) {
                                Text("Chiudi")
                            }
                        }
                    )
                }

                if (showGroupDialog) {
                    AlertDialog(
                        onDismissRequest = { showGroupDialog = false },
                        title = { Text("Seleziona Gruppo") },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = groupSearchQuery,
                                    onValueChange = { groupSearchQuery = it },
                                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 8.dp),
                                    placeholder = { Text("Cerca categoria...", style = MaterialTheme.typography.bodyMedium) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                     trailingIcon = {
                                        if (groupSearchQuery.isNotEmpty()) {
                                            IconButton(onClick = { groupSearchQuery = "" }) {
                                                Icon(Icons.Default.Clear, contentDescription = "Cancella", modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )

                                val filteredGroups = remember(allGroups, groupSearchQuery, favoriteGroups) {
                                    val baseList = if (groupSearchQuery.isBlank()) allGroups
                                    else allGroups.filter { it.contains(groupSearchQuery, ignoreCase = true) }
                                    baseList.sortedWith(compareByDescending<String> { favoriteGroups.contains(it) }.thenBy { it })
                                }

                                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                                    item {
                                        ListItem(
                                            headlineContent = { Text("Tutti i Gruppi", style = MaterialTheme.typography.bodyLarge) },
                                            modifier = Modifier.clickable {
                                                selectedGroup = null
                                                showOnlyFavorites = false
                                                showGroupDialog = false
                                            }
                                        )
                                        Divider()
                                    }
                                    items(filteredGroups) { group ->
                                        val isFavGroup = favoriteGroups.contains(group)
                                        ListItem(
                                            headlineContent = { Text(group.ifBlank { "Generale" }) },
                                            trailingContent = {
                                                IconButton(onClick = {
                                                    val newFavs = if (isFavGroup) favoriteGroups - group else favoriteGroups + group
                                                    favoriteGroups = newFavs
                                                    prefs.edit().putStringSet("favorite_groups", newFavs).apply()
                                                }) {
                                                    Icon(
                                                        imageVector = if (isFavGroup) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                        contentDescription = null,
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
                            TextButton(onClick = { showGroupDialog = false }) { Text("Chiudi") }
                        }
                    )
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (isLoading && allChannels.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("⏳ Caricamento canali dal server...")
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 8.dp),
                                placeholder = { Text("Cerca canale...", style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium
                            )

                            // Riga dei bottoni di filtro
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
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

                                FilterChip(
                                    selected = selectedGroup != null,
                                    onClick = { showGroupDialog = true },
                                    label = { Text(text = selectedGroup ?: "≡ Gruppi", maxLines = 1) },
                                    leadingIcon = { Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp)) }
                                )

                                FilterChip(
                                    selected = false,
                                    onClick = { showSettingsDialog = true },
                                    label = {
                                        val labelText = if (epgOffsetHours == 0) "🕒 EPG Time" else "🕒 ${if (epgOffsetHours > 0) "+$epgOffsetHours" else epgOffsetHours}h"
                                        Text(labelText, maxLines = 1)
                                    }
                                )
                            }

                            if (currentChannels.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (statusMessage.isNotBlank()) statusMessage else "🔍 Nessun canale trovato",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(currentChannels, key = { it.id.ifEmpty { it.url + it.name } }) { channel ->
                                        val isFav = favoriteIds.contains(channel.id)
                                        val isThisLoading = loadingChannelId == channel.id
                                        val epgInfo = epgMap[channel.id] ?: epgMap[extractRawId(channel.id)] ?: EpgInfo()

                                        ChannelCard(
                                            channel = channel,
                                            epgInfo = epgInfo,
                                            isFavorite = isFav,
                                            isLoading = isThisLoading,
                                            onFavoriteToggle = {
                                                val newFavs = if (isFav) favoriteIds - channel.id else favoriteIds + channel.id
                                                favoriteIds = newFavs
                                                prefs.edit().putStringSet("favorite_ids", newFavs).apply()
                                            },
                                            onPlayClick = {
                                                loadingChannelId = channel.id
                                                scope.launch(Dispatchers.IO) {
                                                    try {
                                                        // Controllo se il canale/link corrisponde a un Torrent / Magnet / InfoHash
                                                        val rawSource = channel.url.ifBlank { channel.id }
                                                        val parsedMagnetUri = TorrentUtils.parseToMagnetUri(rawSource)

                                                        if (parsedMagnetUri != null) {
                                                            withContext(Dispatchers.Main) {
                                                                Toast.makeText(this@ChannelsActivity, "🔄 Avvio download sequenziale torrent...", Toast.LENGTH_SHORT).show()
                                                            }

                                                            val torrentManager = TorrentStreamManager(this@ChannelsActivity)
                                                            torrentManager.prepareStream(
                                                                magnetUri = parsedMagnetUri,
                                                                onReady = { localStreamUrl ->
                                                                    scope.launch(Dispatchers.Main) {
                                                                        val intent = Intent(this@ChannelsActivity, PlayerActivity::class.java).apply {
                                                                            putExtra(PlayerActivity.EXTRA_STREAM_URL, localStreamUrl)
                                                                            putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                                                                            putExtra(PlayerActivity.EXTRA_USER_AGENT, "PortalStreamPro")
                                                                        }
                                                                        startActivity(intent)
                                                                    }
                                                                },
                                                                onError = { errorMsg ->
                                                                    scope.launch(Dispatchers.Main) {
                                                                        Toast.makeText(this@ChannelsActivity, "❌ Errore Torrent: $errorMsg", Toast.LENGTH_LONG).show()
                                                                    }
                                                                }
                                                            )
                                                        } else {
                                                            // Logica standard per Stalker, Xtream o M3U
                                                            val isStalker = portalType.equals("STALKER", true) || portalType.equals("MAG", true)
                                                            val isXtream = portalType.equals("XTREAM", true) || portalType.equals("XSTREAM", true)

                                                            val playUrl = when {
                                                                isStalker || macAddress.isNotBlank() -> {
                                                                    val portalDraft = Portal(
                                                                        id = 0, server = server, macAddress = macAddress,
                                                                        useCustomUserAgent = !userAgent.isNullOrBlank(), userAgent = userAgent ?: ""
                                                                    )
                                                                    StalkerClient.getStreamUrl(portalDraft, channel.url) ?: channel.url
                                                                }
                                                                isXtream -> {
                                                                    if (channel.url.startsWith("http")) channel.url
                                                                    else {
                                                                        val cleanServer = server.trimEnd('/')
                                                                        val cleanFormat = if (format.isBlank()) "m3u8" else format
                                                                        val rawId = extractRawId(channel.id)
                                                                        "$cleanServer/live/${username}/${password}/${rawId}.$cleanFormat"
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
                                                        }
                                                    } catch (e: Exception) {
                                                        withContext(Dispatchers.Main) {
                                                            Toast.makeText(this@ChannelsActivity, "❌ Errore: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                                        }
                                                    } finally {
                                                        withContext(Dispatchers.Main) { loadingChannelId = null }
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
        }
    }

    @Composable
    private fun ChannelCard(
        channel: Channel,
        epgInfo: EpgInfo,
        isFavorite: Boolean,
        isLoading: Boolean,
        onFavoriteToggle: () -> Unit,
        onPlayClick: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(enabled = !isLoading, onClick = onPlayClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!channel.logoUrl.isNullOrBlank() && isValidUrl(channel.logoUrl)) {
                        AsyncImage(model = channel.logoUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    } else {
                        Text(text = "📺", style = MaterialTheme.typography.titleMedium)
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(text = channel.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (channel.group.isNotBlank()) {
                        Text(text = channel.group, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (epgInfo.currentProgram.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = "▶ ${epgInfo.currentProgram}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    private fun isValidUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun extractRawId(safeId: String): String {
        return if (safeId.startsWith("ch_")) safeId.substringAfterLast("_") else safeId
    }

    private suspend fun fetchUnifiedEpgData(
        server: String,
        username: String,
        password: String,
        macAddress: String,
        isStalker: Boolean,
        userAgent: String?,
        channels: List<Channel>,
        offsetHours: Int,
        onUpdate: (Map<String, EpgInfo>) -> Unit
    ) {
        withContext(Dispatchers.IO) {
            val resultMap = mutableMapOf<String, EpgInfo>()
            try {
                if (isStalker && macAddress.isNotBlank()) {
                    val cleanServer = server.trimEnd('/')
                    val url = "$cleanServer/server/load.php?type=itv&action=get_epg_info&mac=$macAddress"
                    val request = Request.Builder().url(url).header("User-Agent", userAgent ?: "MAG250").header("Cookie", "mac=$macAddress").build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        if (body.isNotBlank()) {
                            val jsonRoot = JSONObject(body)
                            val jsObj = jsonRoot.optJSONObject("js")
                            val dataObj = jsObj?.optJSONObject("data") ?: jsonRoot.optJSONObject("js")
                            val nowSec = System.currentTimeMillis() / 1000

                            dataObj?.keys()?.forEach { chId ->
                                val arr = dataObj.optJSONArray(chId)
                                if (arr != null && arr.length() > 0) {
                                    var currentTitle = ""
                                    var nextTitle = ""
                                    for (i in 0 until arr.length()) {
                                        val prog = arr.getJSONObject(i)
                                        val start = parseStalkerTimestamp(prog.optString("time", "")) ?: prog.optLong("start_timestamp", 0L)
                                        val stop = parseStalkerTimestamp(prog.optString("time_to", "")) ?: prog.optLong("stop_timestamp", 0L)
                                        if (start > 0 && stop > 0) {
                                            if (nowSec in start..stop) {
                                                currentTitle = prog.optString("name", "")
                                                if (i + 1 < arr.length()) nextTitle = arr.getJSONObject(i + 1).optString("name", "")
                                                break
                                            }
                                        }
                                    }
                                    if (currentTitle.isBlank() && arr.length() > 0) {
                                        currentTitle = arr.getJSONObject(0).optString("name", "")
                                        if (arr.length() > 1) nextTitle = arr.getJSONObject(1).optString("name", "")
                                    }
                                    if (currentTitle.isNotBlank()) {
                                        resultMap[chId] = EpgInfo(cleanHtmlIfNeeded(currentTitle), cleanHtmlIfNeeded(nextTitle))
                                    }
                                }
                            }
                        }
                    }
                } else if (username.isNotBlank() && password.isNotBlank()) {
                    val cleanServer = server.trimEnd('/')
                    val url = "$cleanServer/player_api.php?username=$username&password=$password&action=get_live_streams"
                    val request = Request.Builder().url(url).header("User-Agent", userAgent ?: "PortalStreamPro/1.0").build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        if (body.isNotBlank()) {
                            val jsonArray = JSONArray(body)
                            for (i in 0 until jsonArray.length()) {
                                val obj = jsonArray.getJSONObject(i)
                                val streamId = obj.optString("stream_id", "")
                                val epgNow = obj.optJSONObject("epg_now")
                                val epgNext = obj.optJSONObject("epg_next")
                                val nowPlaying = epgNow?.optString("title", null)
                                val nextPlaying = epgNext?.optString("title", null)

                                val formattedCurrent = if (!nowPlaying.isNullOrBlank()) {
                                    val startFormatted = formatEpgTime(epgNow.optString("start", ""), epgNow.optLong("start_timestamp", 0L), offsetHours)
                                    if (startFormatted.isNotBlank()) "[$startFormatted] $nowPlaying" else nowPlaying
                                } else null

                                if (streamId.isNotBlank() && !formattedCurrent.isNullOrBlank()) {
                                    resultMap[streamId] = EpgInfo(cleanHtmlIfNeeded(formattedCurrent), if (!nextPlaying.isNullOrBlank()) cleanHtmlIfNeeded(nextPlaying) else "")
                                }
                            }
                        }
                    }
                }

                channels.forEach { ch ->
                    val rawId = extractRawId(ch.id)
                    val info = resultMap[rawId]
                    if (info != null) resultMap[ch.id] = info
                }

                onUpdate(resultMap)
            } catch (_: Exception) {}
        }
    }

    private fun formatEpgTime(timeStr: String, timestampSec: Long, offsetHours: Int): String {
        return try {
            val adjustedTimestamp = if (timestampSec > 0) timestampSec + (offsetHours * 3600L) else 0L
            if (adjustedTimestamp > 0) {
                val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
                sdf.timeZone = TimeZone.getDefault()
                sdf.format(Date(adjustedTimestamp * 1000))
            } else if (timeStr.isNotBlank()) {
                val inputFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                inputFormat.timeZone = TimeZone.getTimeZone("UTC")
                val date = inputFormat.parse(timeStr)
                if (date != null) {
                    val adjustedDate = Date(date.time + (offsetHours * 3600000L))
                    val outputFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                    outputFormat.timeZone = TimeZone.getDefault()
                    outputFormat.format(adjustedDate)
                } else ""
            } else ""
        } catch (_: Exception) { "" }
    }

    private fun parseStalkerTimestamp(timeStr: String): Long? {
        if (timeStr.isBlank()) return null
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            format.timeZone = TimeZone.getTimeZone("UTC")
            val date = format.parse(timeStr)
            date?.time?.div(1000)
        } catch (_: Exception) {
            timeStr.toLongOrNull()
        }
    }

    private fun cleanHtmlIfNeeded(text: String): String {
        return if (text.contains("&") && (text.contains("quot") || text.contains("amp") || text.contains("lt"))) {
            android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        } else {
            text
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
