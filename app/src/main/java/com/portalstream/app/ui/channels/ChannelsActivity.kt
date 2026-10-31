package com.portalstream.app.ui.channels

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.portalstream.app.data.Portal
import com.portalstream.app.data.epg.EpgChannelMapping
import com.portalstream.app.data.epg.EpgProgram
import com.portalstream.app.data.local.AppDatabase
import com.portalstream.app.data.repository.EpgInfo
import com.portalstream.app.data.repository.EpgRepository
import com.portalstream.app.domain.model.Channel
import com.portalstream.app.network.StalkerClient
import com.portalstream.app.network.XtreamClient
import com.portalstream.app.streaming.M3UParser
import com.portalstream.app.ui.home.ChannelCache
import com.portalstream.app.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.net.URL
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
class ChannelsActivity : ComponentActivity() {

    private val TAG = "ChannelsActivity"
    private val client = OkHttpClient()
    private val epgRepository by lazy { EpgRepository(this) }

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

        val portalIdentifier = server.ifBlank { portalUrl }

        val db = AppDatabase.getDatabase(this)
        val channelDao = db.channelDao()
        val prefs = getSharedPreferences("portal_stream_prefs", Context.MODE_PRIVATE)
        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val epgCacheFile = File(filesDir, "epg_iptv_org_cache.json")

        setContent {
            var appTheme by remember { mutableStateOf(appPrefs.getString("app_theme", "dark") ?: "dark") }
            val darkTheme = appTheme == "dark"

            var channelTextSize by remember { mutableStateOf(appPrefs.getFloat("channel_text_size", 16f)) }
            var logoSize by remember { mutableStateOf(appPrefs.getInt("logo_size", 48)) }
            var epgTextSize by remember { mutableStateOf(appPrefs.getFloat("epg_text_size", 14f)) }

            MaterialTheme(
                colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme()
            ) {
                val dbChannels by channelDao.getAllChannels().collectAsState(initial = emptyList())
                val localChannels = remember { ChannelCache.take() }

                val allChannels = if (localChannels.isNotEmpty()) localChannels else dbChannels
                val scope = rememberCoroutineScope()

                var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
                var searchQuery by rememberSaveable { mutableStateOf("") }
                var showOnlyFavorites by rememberSaveable { mutableStateOf(false) }
                var showOnlyEpg by rememberSaveable { mutableStateOf(false) }

                var showSettingsDialog by remember { mutableStateOf(false) }
                var channelForDetails by remember { mutableStateOf<Channel?>(null) }

                var favoriteIds by remember {
                    mutableStateOf(prefs.getStringSet("favorite_ids", emptySet())?.toSet() ?: emptySet())
                }

                var favoriteGroups by remember {
                    mutableStateOf(prefs.getStringSet("favorite_groups", emptySet())?.toSet() ?: emptySet())
                }

                var isLoading by remember { mutableStateOf(true) }
                var isEpgLoading by remember { mutableStateOf(false) }
                var loadingChannelId by remember { mutableStateOf<String?>(null) }
                var statusMessage by remember { mutableStateOf("") }

                var showGroupDialog by remember { mutableStateOf(false) }
                var groupSearchQuery by remember { mutableStateOf("") }

                var epgInfoMap by remember { mutableStateOf<Map<String, EpgInfo>>(emptyMap()) }
                var epgProgramsMap by remember { mutableStateOf<Map<String, List<EpgProgram>>>(emptyMap()) }

                val allGroups = remember(allChannels, portalIdentifier) {
                    val portalPrefix = "ch_${portalIdentifier.hashCode()}_"
                    allChannels.filter { channel ->
                        channel.id.startsWith(portalPrefix) || !channel.id.startsWith("ch_")
                    }.map { it.group }.distinct().filter { it.isNotBlank() }.sorted()
                }      

                val currentChannels = remember(
                    allChannels, selectedGroup, searchQuery, showOnlyFavorites, showOnlyEpg, favoriteIds, portalIdentifier
                ) {
                    val portalPrefix = "ch_${portalIdentifier.hashCode()}_"
                    allChannels.filter { channel ->
                        val matchesPortal = channel.id.startsWith(portalPrefix) || !channel.id.startsWith("ch_")
                        val matchesGroup = selectedGroup == null || channel.group == selectedGroup
                        val matchesSearch = searchQuery.isBlank() ||
                                channel.name.contains(searchQuery, ignoreCase = true)
                        val matchesFavorites = !showOnlyFavorites || favoriteIds.contains(channel.id)
                        val matchesEpg = !showOnlyEpg || channel.tvgId.isNotBlank() || channel.customSid.isNotBlank()

                        matchesPortal && matchesGroup && matchesSearch && matchesFavorites && matchesEpg
                    }
                }

                val fetchChannelsAction = { isManualRefresh: Boolean ->
                    scope.launch(Dispatchers.IO) {
                        try {
                            withContext(Dispatchers.Main) { 
                                isLoading = true 
                                statusMessage = ""
                            }
                            Log.d(TAG, "Inizio caricamento canali per il portale: $portalIdentifier (Tipo: $portalType)")
                            
                            val isStalker = portalType.equals("STALKER", ignoreCase = true) ||
                                    portalType.equals("MAG", ignoreCase = true)
                            val isXtream = portalType.equals("XTREAM", ignoreCase = true) ||
                                   portalType.equals("XSTREAM", ignoreCase = true)
                            val fetched = when {
                                isStalker || macAddress.isNotBlank() -> {
                                    val portalServer = if (server.isNotBlank()) server else portalUrl
                                    if (portalServer.isBlank() || macAddress.isBlank()) {
                                        withContext(Dispatchers.Main) {
                                            Toast.makeText(this@ChannelsActivity, "⚠️ Server o MAC vuoti!", Toast.LENGTH_LONG).show()
                                        }
                                        emptyList()
                                    } else {
                                        val portalDraft = Portal(
                                            id = 0, server = portalServer, macAddress = macAddress,
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
                                val existingChannels = channelDao.getAllChannelsList()
                                val savedTvgMap = existingChannels.associate { "${portalIdentifier}_${it.name}" to it.tvgId }
                                val savedSidMap = existingChannels.associate { "${portalIdentifier}_${it.name}" to it.customSid }             
                                val portalPrefix = "ch_${portalIdentifier.hashCode()}_"
                                
                                val validFetched = fetched.mapIndexed { index, channel ->
                                    val safeId = "ch_${portalIdentifier.hashCode()}_${if (channel.id.isBlank()) "${index}_${channel.name.hashCode()}" else channel.id}"
                                    val lookupKey = "${portalIdentifier}_${channel.name}"
                                    val preservedTvgId = savedTvgMap[lookupKey] ?: channel.tvgId
                                    val preservedSid = savedSidMap[lookupKey] ?: channel.customSid
                                    channel.copy(id = safeId, tvgId = preservedTvgId, customSid = preservedSid)
                                }
                                
                                channelDao.deleteChannelsByPrefix(portalPrefix)
                                channelDao.insertChannels(validFetched)
                                Log.d(TAG, "Canali salvati correttamente.")
                                
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    if (isManualRefresh) {
                                        Toast.makeText(this@ChannelsActivity, "✅ Lista canali caricata!", Toast.LENGTH_SHORT).show()
                                    }
                                }

                                try {
                                    epgRepository.autoAssociateAllChannels(channelDao)
                                    val refreshedChannels = channelDao.getAllChannelsList()
                                    val countryToUse = appPrefs.getString("epg_country_filter", "IT") ?: "IT"
                                    val newInfoMap = epgRepository.getAllCurrentEpg(refreshedChannels, countryToUse)
                                    val newProgramsMap = epgRepository.getProgramsForChannels(refreshedChannels, countryToUse)
                                    withContext(Dispatchers.Main) {
                                        if (newInfoMap.isNotEmpty()) epgInfoMap = newInfoMap
                                        if (newProgramsMap.isNotEmpty()) epgProgramsMap = newProgramsMap
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Errore caricamento EPG", e)
                                }

                            } else {
                                withContext(Dispatchers.Main) {
                                    statusMessage = "❌ Nessun canale caricato. Verifica server e credenziali."
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                statusMessage = "Errore: ${e.localizedMessage}"
                            }
                            Log.e(TAG, "Errore caricamento canali", e)
                        } finally {
                            withContext(Dispatchers.Main) { 
                                isLoading = false 
                                isEpgLoading = false 
                            }
                        }
                    }
                }

                if (showSettingsDialog) {
                    AlertDialog(
                        onDismissRequest = { showSettingsDialog = false },
                        title = { Text("⚙️ Opzioni Lista Canali") },
                        text = {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("Tema della Lista Canali", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            appTheme = "dark"
                                            appPrefs.edit().putString("app_theme", "dark").apply()
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (darkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Text("Scuro")
                                    }
                                    Button(
                                        onClick = {
                                            appTheme = "light"
                                            appPrefs.edit().putString("app_theme", "light").apply()
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (!darkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Text("Chiaro")
                                    }
                                }

                                Divider()

                                Text("Gestione Canali", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Button(
                                    onClick = {
                                        showSettingsDialog = false
                                        fetchChannelsAction(true)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("🔄 Aggiorna Lista Canali dal Server")
                                }

                                Divider()

                                Text("Guida TV (EPG)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                
                                Text("Nazionalità predefinita EPG (escludi le altre nazioni)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                var selectedCountry by remember { 
                                    mutableStateOf(appPrefs.getString("epg_country_filter", "IT") ?: "IT") 
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("IT", "UK", "FR", "DE", "ES").forEach { country ->
                                        val isSelected = selectedCountry.equals(country, ignoreCase = true)
                                        FilterChip(
                                            selected = isSelected,
                                            onClick = {
                                                selectedCountry = country
                                                appPrefs.edit().putString("epg_country_filter", country).apply()
                                            },
                                            label = { Text(country, style = MaterialTheme.typography.labelMedium) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Button(
                                    onClick = {
                                        showSettingsDialog = false
                                        val countryToUse = appPrefs.getString("epg_country_filter", "IT") ?: "IT"
                                        scope.launch(Dispatchers.IO) {
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@ChannelsActivity, "⏳ Ricerca EPG per [$countryToUse] in corso...", Toast.LENGTH_SHORT).show()
                                            }
                                            val count = epgRepository.autoAssociateAllChannels(channelDao, countryToUse)
                                            withContext(Dispatchers.Main) {
                                                Toast.makeText(this@ChannelsActivity, "✅ Associati con successo $count canali per [$countryToUse]!", Toast.LENGTH_LONG).show()
                                                val refreshedChannels = channelDao.getAllChannelsList()
                                                val newInfoMap = epgRepository.getAllCurrentEpg(refreshedChannels, countryToUse)
                                                val newProgramsMap = epgRepository.getProgramsForChannels(refreshedChannels, countryToUse)
                                                if (newInfoMap.isNotEmpty()) epgInfoMap = newInfoMap
                                                if (newProgramsMap.isNotEmpty()) epgProgramsMap = newProgramsMap
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                ) {
                                    Text("🔄 Auto-Associa EPG [$selectedCountry]")
                                }

                                Divider()

                                Text("Personalizzazione Grafica", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                
                                Text("Dimensione Testo Canali: ${channelTextSize.toInt()}sp", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = channelTextSize,
                                    onValueChange = { 
                                        channelTextSize = it
                                        appPrefs.edit().putFloat("channel_text_size", it).apply()
                                    },
                                    valueRange = 12f..24f,
                                    steps = 11
                                )

                                Text("Dimensione Loghi: ${logoSize}dp", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = logoSize.toFloat(),
                                    onValueChange = { 
                                        logoSize = it.toInt()
                                        appPrefs.edit().putInt("logo_size", it.toInt()).apply()
                                    },
                                    valueRange = 32f..72f,
                                    steps = 8
                                )

                                Text("Dimensione Testo Guida TV: ${epgTextSize.toInt()}sp", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = epgTextSize,
                                    onValueChange = { 
                                        epgTextSize = it
                                        appPrefs.edit().putFloat("epg_text_size", it).apply()
                                    },
                                    valueRange = 10f..20f,
                                    steps = 9
                                )
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
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .padding(bottom = 8.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier.weight(1f),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (groupSearchQuery.isEmpty()) {
                                                Text(
                                                    text = "Cerca categoria...",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                                )
                                            }
                                            BasicTextField(
                                                value = groupSearchQuery,
                                                onValueChange = { groupSearchQuery = it },
                                                singleLine = true,
                                                textStyle = MaterialTheme.typography.bodySmall.copy(
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        if (groupSearchQuery.isNotEmpty()) {
                                            IconButton(
                                                onClick = { groupSearchQuery = "" },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Clear,
                                                    contentDescription = "Cancella",
                                                    modifier = Modifier.size(16.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                                val filteredGroups = remember(allGroups, groupSearchQuery, favoriteGroups) {
                                    val baseList = if (groupSearchQuery.isBlank()) allGroups
                                    else allGroups.filter { it.contains(groupSearchQuery, ignoreCase = true) }
                                    baseList.sortedWith(compareByDescending<String> { favoriteGroups.contains(it) }.thenBy { it })
                                }

                                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                                    item {
                                        ListItem(
                                            headlineContent = { Text("Tutti i Gruppi", style = MaterialTheme.typography.bodyMedium) },
                                            modifier = Modifier.clickable {
                                                selectedGroup = null
                                                showOnlyFavorites = false
                                                showOnlyEpg = false
                                                showGroupDialog = false
                                            }
                                        )
                                        Divider()
                                    }
                                    items(filteredGroups) { group ->
                                        val isFavGroup = favoriteGroups.contains(group)
                                        ListItem(
                                            headlineContent = { Text(group.ifBlank { "Generale" }, style = MaterialTheme.typography.bodyMedium) },
                                            trailingContent = {
                                                IconButton(onClick = {
                                                    val newFavs = if (isFavGroup) favoriteGroups - group else favoriteGroups + group
                                                    favoriteGroups = newFavs
                                                    prefs.edit().putStringSet("favorite_groups", newFavs).apply()
                                                }, modifier = Modifier.size(24.dp)) {
                                                    Icon(
                                                        imageVector = if (isFavGroup) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                        contentDescription = null,
                                                        tint = if (isFavGroup) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            },
                                            modifier = Modifier.clickable {
                                                selectedGroup = group
                                                showOnlyFavorites = false
                                                showOnlyEpg = false
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
                    val configuration = LocalConfiguration.current
                    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("⏳ Caricamento canali...")
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = if (isLandscape) 8.dp else 16.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = if (isLandscape) 4.dp else 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = { showSettingsDialog = true },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = "Impostazioni",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .padding(bottom = if (isLandscape) 4.dp else 8.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (searchQuery.isEmpty()) {
                                            Text(
                                                text = "Cerca canale...",
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                        }
                                        BasicTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { searchQuery = "" },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Cancella",
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = if (isLandscape) 6.dp else 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = showOnlyFavorites,
                                    onClick = {
                                        showOnlyFavorites = !showOnlyFavorites
                                        if (showOnlyFavorites) {
                                            selectedGroup = null
                                            showOnlyEpg = false
                                        }
                                    },
                                    label = { 
                                        Text("Preferiti", maxLines = 1, style = MaterialTheme.typography.labelMedium, overflow = TextOverflow.Ellipsis)
                                    },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    modifier = Modifier.weight(1f)     
                                )

                                FilterChip(
                                    selected = selectedGroup != null,
                                    onClick = { showGroupDialog = true },
                                    label = { 
                                        Text(text = selectedGroup ?: "Gruppi", maxLines = 1, style = MaterialTheme.typography.labelMedium, overflow = TextOverflow.Ellipsis)
                                    },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Default.List, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    modifier = Modifier.weight(1f)
                                )

                                FilterChip(
                                    selected = showOnlyEpg,
                                    onClick = {
                                        showOnlyEpg = !showOnlyEpg
                                        if (showOnlyEpg) {
                                            showOnlyFavorites = false
                                            selectedGroup = null
                                        }
                                    },
                                    label = { 
                                        Text("Guida TV", maxLines = 1, style = MaterialTheme.typography.labelMedium, overflow = TextOverflow.Ellipsis)
                                    },
                                    leadingIcon = {
                                        Icon(imageVector = Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(16.dp))
                                    },
                                    modifier = Modifier.weight(1f)
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
                                        val playAction: () -> Unit = {
                                            loadingChannelId = channel.id
                                            scope.launch(Dispatchers.IO) {
                                                try {
                                                    val isStalker = portalType.equals("STALKER", true) || portalType.equals("MAG", true)
                                                    val isXtream = portalType.equals("XTREAM", true) || portalType.equals("XSTREAM", true)

                                                    val playUrl = when {
                                                        isStalker || macAddress.isNotBlank() -> {
                                                            val portalServer = if (server.isNotBlank()) server else portalUrl
                                                            val portalDraft = Portal(
                                                                id = 0, server = portalServer, macAddress = macAddress,
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
                                                } catch (e: Exception) {
                                                    withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@ChannelsActivity, "❌ Errore: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                                                    }
                                                } finally {
                                                    withContext(Dispatchers.Main) { loadingChannelId = null }
                                                }
                                            }
                                        }

                                        if (showOnlyEpg) {
                                            ChannelCard(
                                                channel = channel,
                                                epgPrograms = epgProgramsMap[channel.id] ?: emptyList(),
                                                epgTextSize = epgTextSize,
                                                onPlayClick = playAction,
                                                onLongClick = {
                                                    channelForDetails = channel
                                                }
                                            )
                                        } else {
                                            StandardChannelCard(
                                                channel = channel,
                                                epgInfo = epgInfoMap[channel.id],
                                                isFavorite = favoriteIds.contains(channel.id),
                                                isLoading = loadingChannelId == channel.id,
                                                channelTextSize = channelTextSize,
                                                logoSize = logoSize,
                                                onFavoriteToggle = {
                                                    val newFavs = if (favoriteIds.contains(channel.id)) favoriteIds - channel.id else favoriteIds + channel.id
                                                    favoriteIds = newFavs
                                                    prefs.edit().putStringSet("favorite_ids", newFavs).apply()
                                                },
                                                onPlayClick = playAction,
                                                onLongClick = {
                                                    channelForDetails = channel
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                channelForDetails?.let { selectedChannel ->
                    ChannelDetailsDialog(
                        channel = selectedChannel,
                        availableEpgMappings = epgRepository.getMapper().getAllMappings(),
                        onDismiss = { channelForDetails = null },
                        onSaveTvgId = { newTvgId ->
                            scope.launch(Dispatchers.IO) {
                                val updatedChannel = selectedChannel.copy(tvgId = newTvgId)
                                channelDao.insertChannel(updatedChannel)
                                val refreshedChannels = channelDao.getAllChannelsList()
                                val newInfoMap = epgRepository.getAllCurrentEpg(refreshedChannels)
                                val newProgramsMap = epgRepository.getProgramsForChannels(refreshedChannels)
                                withContext(Dispatchers.Main) {
                                    if (newInfoMap.isNotEmpty()) epgInfoMap = newInfoMap
                                    if (newProgramsMap.isNotEmpty()) epgProgramsMap = newProgramsMap
                                }
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val epgCacheFile = File(filesDir, "epg_iptv_org_cache.json")
        if (epgCacheFile.exists()) {
            epgCacheFile.delete()
        }
    }

    @Composable
    private fun StandardChannelCard(
        channel: Channel,
        epgInfo: EpgInfo?,
        isFavorite: Boolean,
        isLoading: Boolean,
        channelTextSize: Float,
        logoSize: Int,
        onFavoriteToggle: () -> Unit,
        onPlayClick: () -> Unit,
        onLongClick: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .combinedClickable(
                    onClick = onPlayClick,
                    onLongClick = onLongClick
                ),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)),
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!channel.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(logoSize.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = (channelTextSize + 1f).sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    Text(
                        text = channel.group.ifBlank { "Altro" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (epgInfo != null && epgInfo.currentProgram.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "▶ ${epgInfo.currentProgram}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (epgInfo.timeRange.isNotBlank()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = epgInfo.timeRange,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onFavoriteToggle) {
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

    @Composable
    private fun ChannelCard(
        channel: Channel,
        epgPrograms: List<EpgProgram>,
        epgTextSize: Float,
        onPlayClick: () -> Unit,
        onLongClick: () -> Unit
    ) {
        val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
        val now = remember { LocalDateTime.now(ZoneId.of("Europe/Rome")) }
        
        var expandedProgramKey by remember { mutableStateOf<String?>(null) }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .combinedClickable(
                    onClick = onPlayClick,
                    onLongClick = onLongClick
                ),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = (epgTextSize + 2f).sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "▶ Avvia",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Divider(modifier = Modifier.padding(vertical = 8.dp))

                if (epgPrograms.isEmpty()) {
                    Text(
                        text = "Nessun palinsesto caricato per oggi.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = epgTextSize.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        epgPrograms.forEach { prog ->
                            val isCurrent = !now.isBefore(prog.startTime) && !now.isAfter(prog.endTime)
                            val timeRange = "${prog.startTime.format(timeFormatter)} - ${prog.endTime.format(timeFormatter)}"
                            
                            val mainTitle = prog.title
                            val subTitle = prog.subTitle ?: "" 
                            val episodeInfo = prog.episodeNum ?: ""
                            
                            val progKey = "${prog.title}_${prog.startTime}"
                            val isExpanded = expandedProgramKey == progKey

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        color = if (isCurrent) 
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) 
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
                                    )
                                    .clickable {
                                        expandedProgramKey = if (isExpanded) null else progKey
                                    }
                                    .padding(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = mainTitle,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = epgTextSize.sp),
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        
                                        if (episodeInfo.isNotBlank()) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = " $episodeInfo ",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }

                                        if (isCurrent) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                color = MaterialTheme.colorScheme.primary,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = " IN CORSO ",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    
                                    Spacer(modifier = Modifier.width(8.dp))
                                    
                                    Text(
                                        text = timeRange,
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = (epgTextSize - 2f).sp),
                                        color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }

                                if (subTitle.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "• $subTitle",
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = (epgTextSize - 1f).sp),
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.secondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (!prog.description.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = prog.description.replace("\\t", " "),
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = (epgTextSize - 2f).sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                                        overflow = if (isExpanded) TextOverflow.Clip else TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ChannelDetailsDialog(
        channel: Channel,
        availableEpgMappings: List<EpgChannelMapping>,
        onDismiss: () -> Unit,
        onSaveTvgId: (String) -> Unit
    ) {
        val context = LocalContext.current
        val clipboardManager = LocalClipboardManager.current

        var editableTvgId by remember { mutableStateOf(channel.tvgId.ifBlank { channel.customSid }) }
        var searchQuery by remember { mutableStateOf("") }
        var isDropdownExpanded by remember { mutableStateOf(false) }

        val scrollState = rememberScrollState()

        val filteredMappings = remember(searchQuery, availableEpgMappings) {
            if (searchQuery.isBlank()) emptyList()
            else {
                availableEpgMappings.filter { 
                    it.name.contains(searchQuery, ignoreCase = true) || 
                    it.siteId.contains(searchQuery, ignoreCase = true)
                }.take(8)
            }
        }

        val fullDetailsText = buildString {
            appendLine("• ID Canale: ${channel.id}")
            appendLine("• Numero: ${channel.number}")
            appendLine("• Nome Grezzo: ${channel.name}")
            appendLine("• TVG-ID Attuale: $editableTvgId")
            appendLine("• CMD / Stream: ${channel.cmd.ifBlank { channel.url }}")
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = "ℹ️ Dettagli & Assegnazione EPG",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "• Canale: ${channel.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = "• Stream ID: ${channel.id}", style = MaterialTheme.typography.bodySmall)

                    Divider(modifier = Modifier.padding(vertical = 4.dp))

                    OutlinedTextField(
                        value = editableTvgId,
                        onValueChange = { editableTvgId = it },
                        label = { Text("ID EPG Associato carico il palinsesto...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "🔍 Cerca ID nell'EPG ufficiale:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { 
                            searchQuery = it 
                            isDropdownExpanded = it.isNotBlank()
                        },
                        placeholder = { Text("Es. Sky Sport, Rai 1, Movistar...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (isDropdownExpanded && filteredMappings.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            LazyColumn(modifier = Modifier.padding(4.dp)) {
                                items(filteredMappings) { mapping: EpgChannelMapping ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                editableTvgId = mapping.siteId
                                                searchQuery = ""
                                                isDropdownExpanded = false
                                            }
                                            .padding(8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = mapping.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = mapping.siteId,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(fullDetailsText))
                            Toast.makeText(context, "Dettagli copiati!", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Copia")
                    }
                    Button(
                        onClick = {
                            onSaveTvgId(editableTvgId.trim())
                            Toast.makeText(context, "✅ ID EPG salvato con successo!", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }
                    ) {
                        Text("Salva ID")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Chiudi")
                }
            }
        )
    }

    private fun isValidUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith("http://") || url.startsWith("https://")
    }

    private fun extractRawId(safeId: String): String {
        return if (safeId.startsWith("ch_")) safeId.substringAfterLast("_") else safeId
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
