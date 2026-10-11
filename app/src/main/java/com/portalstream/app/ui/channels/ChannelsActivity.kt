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
import java.net.URL

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
                            if (localChannels.isEmpty()) {
                                Log.d(TAG, "Inizio caricamento canali. Tipo portale: $portalType, MAC: $macAddress")

                                val isStalker = portalType.equals("STALKER", ignoreCase = true) || 
                                              portalType.equals("MAG", ignoreCase = true)
                                val isXtream = portalType.equals("XTREAM", ignoreCase = true) || 
                                             portalType.equals("XSTREAM", ignoreCase = true)

                                val fetched = when {
                                    isStalker || macAddress.isNotBlank() -> {
                                        Log.d(TAG, "Modalità STALKER/MAC rilevata")
                                        if (server.isBlank() || macAddress.isBlank()) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                    this@ChannelsActivity,
                                                    "⚠️ Server o MAC vuoti!",
                                                    Toast.LENGTH_LONG
                                                ).show()
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
                                            Log.d(TAG, "Fetching Stalker da: $server")
                                            StalkerClient.fetchChannels(portalDraft)
                                        }
                                    }

                                    isXtream || (username.isNotBlank() && password.isNotBlank()) -> {
                                        Log.d(TAG, "Modalità XTREAM rilevata")
                                        val portalDraft = Portal(
                                            id = 0,
                                            server = server,
                                            username = username,
                                            password = password,
                                            streamFormat = format,
                                            useCustomUserAgent = !userAgent.isNullOrBlank(),
                                            userAgent = userAgent ?: ""
                                        )
                                        Log.d(TAG, "Fetching Xtream da: $server")
                                        XtreamClient.fetchLiveChannels(portalDraft)
                                    }

                                    portalUrl.isNotEmpty() -> {
                                        Log.d(TAG, "Modalità M3U rilevata")
                                        var formattedUrl = portalUrl.trim()
                                        if (!formattedUrl.startsWith("http://") && 
                                            !formattedUrl.startsWith("https://")) {
                                            formattedUrl = "http://$formattedUrl"
                                        }
                                        Log.d(TAG, "Fetching M3U da: $formattedUrl")
                                        
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

                                    else -> {
                                        Log.e(TAG, "Nessun tipo di portale valido configurato")
                                        emptyList()
                                    }
                                }

                                if (fetched.isNotEmpty()) {
                                    Log.d(TAG, "Canali caricati: ${fetched.size}")
                                    channelDao.clearAll()
                                    channelDao.insertChannels(fetched)
                                } else {
                                    statusMessage = "❌ Nessun canale caricato. Verifica server e credenziali."
                                    Log.w(TAG, statusMessage)
                                }
                            } else {
                                Log.d(TAG, "Canali già in cache locale: ${localChannels.size}")
                            }

                        } catch (e: PortalException) {
                            statusMessage = e.localizedMessage ?: "Errore del portale"
                            Log.e(TAG, "PortalException: $statusMessage", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@ChannelsActivity, statusMessage, Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            statusMessage = "Errore rete: ${e.localizedMessage}"
                            Log.e(TAG, "Exception: $statusMessage", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@ChannelsActivity,
                                    "❌ Errore: ${e.localizedMessage}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                isLoading = false
                            }
                        }
                    }

                    scope.launch(Dispatchers.IO) {
                        try {
                            val isXtreamServer = portalType.equals("XTREAM", ignoreCase = true) ||
                                    portalType.equals("XSTREAM", ignoreCase = true) ||
                                    (username.isNotBlank() && password.isNotBlank() && server.isNotBlank())

                            if (isXtreamServer) {
                                Log.d(TAG, "Avvio fetch EPG in background")
                                fetchEpgData(server, username, password, userAgent) { newEpgMap ->
                                    epgMap = newEpgMap
                                    lastEpgUpdate = System.currentTimeMillis()
                                    Log.d(TAG, "EPG aggiornato: ${newEpgMap.size} canali")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Errore EPG iniziale (non critico)", e)
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    while (isActive) {
                        delay(60000)
                        val timeSinceLastUpdate = System.currentTimeMillis() - lastEpgUpdate
                        if (timeSinceLastUpdate > 60000) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val isXtreamServer = portalType.equals("XTREAM", ignoreCase = true) ||
                                            portalType.equals("XSTREAM", ignoreCase = true) ||
                                            (username.isNotBlank() && password.isNotBlank() && server.isNotBlank())

                                    if (isXtreamServer && server.isNotBlank()) {
                                        fetchEpgData(server, username, password, userAgent) { newEpgMap ->
                                            epgMap = newEpgMap
                                            lastEpgUpdate = System.currentTimeMillis()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Errore durante refresh EPG", e)
                                }
                            }
                        }
                    }
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
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .padding(bottom = 8.dp),
                                    placeholder = { 
                                        Text("Cerca categoria...", style = MaterialTheme.typography.bodyMedium) 
                                    },
                                    leadingIcon = { 
                                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp)) 
                                    },
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

                                    baseList.sortedWith(
                                        compareByDescending<String> { favoriteGroups.contains(it) }
                                            .thenBy { it }
                                    )
                                }

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 320.dp)
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
                                        Divider()
                                    }

                                    items(filteredGroups) { group ->
                                        val isFavGroup = favoriteGroups.contains(group)

                                        ListItem(
                                            headlineContent = { Text(group.ifBlank { "Generale" }) },
                                            trailingContent = {
                                                IconButton(
                                                    onClick = {
                                                        val newFavs = if (isFavGroup) 
                                                            favoriteGroups - group 
                                                        else 
                                                            favoriteGroups + group
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
                                Text("⏳ Caricamento canali dal server...")
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
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .padding(bottom = 8.dp),
                                placeholder = { Text("Cerca canale...", style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(Icons.Default.Search, contentDescription = "Cerca", modifier = Modifier.size(20.dp))
                                },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Cancella", modifier = Modifier.size(20.dp))
                                        }
                                    }
                                },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium
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

                                FilterChip(
                                    selected = selectedGroup != null,
                                    onClick = { showGroupDialog = true },
                                    label = {
                                        Text(
                                            text = selectedGroup ?: "≡ Gruppi",
                                            maxLines = 1
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.List,
                                            contentDescription = "Gruppi",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }

                            if (currentChannels.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (statusMessage.isNotBlank()) 
                                            statusMessage 
                                        else if (showOnlyFavorites) 
                                            "⭐ Nessun canale preferito" 
                                        else 
                                            "🔍 Nessun canale trovato",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
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
                                        val epgInfo = epgMap[channel.id] ?: EpgInfo()

                                        ChannelCard(
                                            channel = channel,
                                            epgInfo = epgInfo,
                                            isFavorite = isFav,
                                            isLoading = isThisLoading,
                                            onFavoriteToggle = {
                                                val newFavs = if (isFav) 
                                                    favoriteIds - channel.id 
                                                else 
                                                    favoriteIds + channel.id
                                                favoriteIds = newFavs
                                                prefs.edit().putStringSet("favorite_ids", newFavs).apply()
                                            },
                                            onPlayClick = {
                                                loadingChannelId = channel.id

                                                scope.launch(Dispatchers.IO) {
                                                    try {
                                                        val isStalker = portalType.equals("STALKER", ignoreCase = true) ||
                                                                portalType.equals("MAG", ignoreCase = true)
                                                        val isXtream = portalType.equals("XTREAM", ignoreCase = true) ||
                                                                portalType.equals("XSTREAM", ignoreCase = true)

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
                                                                    "$cleanServer/live/${username}/${password}/${channel.id}.$cleanFormat"
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
                                                                "❌ Errore apertura stream: ${e.localizedMessage}",
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable(enabled = !isLoading, onClick = onPlayClick),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!channel.logoUrl.isNullOrBlank() && isValidUrl(channel.logoUrl)) {
                        AsyncImage(
                            model = channel.logoUrl,
                            contentDescription = channel.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            text = "📺",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (channel.group.isNotBlank()) {
                        Text(
                            text = channel.group,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (epgInfo.currentProgram.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "▶ ${epgInfo.currentProgram}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(
                        onClick = onFavoriteToggle,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Preferito",
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

    private suspend fun fetchEpgData(
        server: String,
        username: String,
        password: String,
        userAgent: String?,
        onUpdate: (Map<String, EpgInfo>) -> Unit
    ) {
        val cleanServer = server.trimEnd('/')
        val epgUrl = "$cleanServer/player_api.php?username=$username&password=$password&action=get_live_streams"
        
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(epgUrl)
                    .header("User-Agent", userAgent ?: "PortalStreamPro/1.0")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext

                val responseBody = response.body?.string() ?: return@withContext
                if (responseBody.isEmpty()) return@withContext

                val jsonArray = try {
                    JSONArray(responseBody)
                } catch (e: Exception) {
                    return@withContext
                }

                val map = mutableMapOf<String, EpgInfo>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val streamId = obj.optString("stream_id", "")
                    val nowPlaying = obj.optJSONObject("epg_now")?.optString("title", null)
                    val nextPlaying = obj.optJSONObject("epg_next")?.optString("title", null)
                    val timeshift = obj.optInt("timeshift_available_hours", 0) / 24

                    if (streamId.isNotBlank() && !nowPlaying.isNullOrBlank()) {
                        map[streamId] = EpgInfo(
                            currentProgram = cleanHtmlIfNeeded(nowPlaying),
                            nextProgram = if (!nextPlaying.isNullOrBlank()) cleanHtmlIfNeeded(nextPlaying) else "",
                            timeshift = timeshift
                        )
                    }
                }
                onUpdate(map)
            } catch (_: Exception) {}
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
