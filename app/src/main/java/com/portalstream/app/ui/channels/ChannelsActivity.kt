package com.portalstream.app.ui.channels

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.colorResource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.portalstream.app.R
import com.portalstream.app.data.Portal
import com.portalstream.app.data.epg.EpgChannelMapping
import com.portalstream.app.data.epg.EpgMappingStore
import com.portalstream.app.data.epg.EpgProgram
import com.portalstream.app.data.epg.PortalIdentity
import com.portalstream.app.data.local.AppDatabase
import com.portalstream.app.data.repository.EpgInfo
import com.portalstream.app.data.repository.EpgRepository
import com.portalstream.app.domain.model.Channel
import com.portalstream.app.domain.DttOrder
import com.portalstream.app.network.StalkerClient
import com.portalstream.app.network.XtreamClient
import com.portalstream.app.streaming.M3UParser
import com.portalstream.app.ui.home.ChannelCache
import com.portalstream.app.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.time.format.DateTimeFormatter
import java.time.LocalDateTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
class ChannelsActivity : ComponentActivity() {

    private val TAG = "ChannelsActivity"
    private val epgRepository by lazy { EpgRepository(this) }
    private val epgMappingStore by lazy { EpgMappingStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val portalId = intent.getIntExtra(EXTRA_PORTAL_ID, 0)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Canali"
        val portalUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        val portalType = intent.getStringExtra(EXTRA_PORTAL_TYPE) ?: ""
        val server = intent.getStringExtra(EXTRA_SERVER) ?: ""
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: "m3u8"
        val macAddress = intent.getStringExtra(EXTRA_MAC) ?: ""
        val expireDate = intent.getStringExtra(EXTRA_EXPIRE_DATE) ?: ""

        val portalIdentity = PortalIdentity(
            portalId = portalId,
            name = title,
            type = portalType,
            url = portalUrl,
            server = server,
            mac = macAddress,
            username = username,
            password = password,
            expireDate = expireDate
        )

        val portalPrefix = "ch_${portalId}_${portalIdentity.signature.hashCode()}_"

        val db = AppDatabase.getDatabase(this)
        val channelDao = db.channelDao()
        val prefs = getSharedPreferences("portal_stream_prefs", Context.MODE_PRIVATE)
        val appPrefs = getSharedPreferences("app_settings", Context.MODE_PRIVATE)

        setContent {
            // Gestiamo la preferenza salvata: se prima era "light", la convertiamo o la impostiamo sul secondo tema scuro (es. "slate")
            var appTheme by remember { mutableStateOf(appPrefs.getString("app_theme", "dark") ?: "dark") }
            // Adesso controlliamo se il tema attivo è il primo (Deep Blue) oppure il secondo (Slate Antracite)
            val isDeepBlueTheme = appTheme == "dark" || appTheme == "deep_blue" // Adatta alla tua chiave

            var channelTextSize by remember { mutableStateOf(appPrefs.getFloat("channel_text_size", 16f)) }
            var logoSize by remember { mutableStateOf(appPrefs.getInt("logo_size", 48)) }
            var epgTextSize by remember { mutableStateOf(appPrefs.getFloat("epg_text_size", 14f)) }
            
            // TEMA SCURO 1: Deep Blue Notturno (Il tuo principale)
            val customDeepBlueScheme = darkColorScheme(
                primary = colorResource(id = R.color.primary),
                secondary = colorResource(id = R.color.secondary),
                background = Color(0xFF0B0F19),
                surface = Color(0xFF131B2E),
                onBackground = Color(0xFFF8FAFC),
                onSurface = Color(0xFFF8FAFC),
                surfaceVariant = Color(0xFF1E293B),
                outline = Color(0xFF2E3F5C)
            )

            // TEMA SCURO 2: Slate Antracite (Il secondo tema scuro moderno)
            val customSlateScheme = darkColorScheme(
                primary = colorResource(id = R.color.primary),
                secondary = colorResource(id = R.color.secondary),
                background = Color(0xFF0B0F17), // Sfondo ardesia scuro profondo
                surface = Color(0xFF111827),     // Superficie ardesia
                onBackground = Color(0xFFF9FAFB),
                onSurface = Color(0xFFF9FAFB),
                surfaceVariant = Color(0xFF1F2937),
                outline = Color(0xFF374151)      // Bordo ardesia neutro
            )

            MaterialTheme(
                colorScheme = if (isDeepBlueTheme) customDeepBlueScheme else customSlateScheme
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
                var successBannerText by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(successBannerText) {
                    if (successBannerText != null) {
                        kotlinx.coroutines.delay(2500)
                        successBannerText = null
                    }
                }    
                var loadingChannelId by remember { mutableStateOf<String?>(null) }
                var statusMessage by remember { mutableStateOf("") }
                var showGroupDialog by remember { mutableStateOf(false) }
                var groupSearchQuery by remember { mutableStateOf("") }
                var epgInfoMap by remember { mutableStateOf<Map<String, EpgInfo>>(emptyMap()) }
                var epgProgramsMap by remember { mutableStateOf<Map<String, List<EpgProgram>>>(emptyMap()) }

                val portalChannelsOnly = remember(allChannels, portalPrefix, portalId) {
                    allChannels.filter { channel ->
                        channel.id.startsWith(portalPrefix) || (portalId == 0 && !channel.id.startsWith("ch_"))
                    }
                }

                val allGroups = remember(portalChannelsOnly) {
                    portalChannelsOnly.map { it.group }.distinct().filter { it.isNotBlank() }.sorted()
                }

                // ORDINAMENTO CANALI STILE DTT
                val currentChannels = remember(
                    portalChannelsOnly, selectedGroup, searchQuery, showOnlyFavorites, showOnlyEpg, favoriteIds
                ) {
                    portalChannelsOnly
                        .filter { channel ->
                            val matchesGroup = selectedGroup == null || channel.group == selectedGroup
                            val matchesSearch = searchQuery.isBlank() ||
                                    channel.name.contains(searchQuery, ignoreCase = true)
                            val matchesFavorites = !showOnlyFavorites || favoriteIds.contains(channel.id)
                            val matchesEpg = !showOnlyEpg ||
                                    channel.tvgId.isNotBlank() ||
                                    channel.customSid.isNotBlank()

                            matchesGroup && matchesSearch && matchesFavorites && matchesEpg
                        }
                        // Calcolo la posizione DTT una sola volta per canale (più veloce con migliaia di canali)
                        .map { channel -> channel to (DttOrder.rankOf(channel.name) ?: Int.MAX_VALUE) }
                        .sortedWith(
                            compareBy<Pair<Channel, Int>>(
                                { it.second },                                                    // 1) posizione DTT
                                { if (it.first.number > 0) it.first.number else Int.MAX_VALUE }, // 2) numero del portale
                                { it.first.name.lowercase() }                                     // 3) ordine alfabetico
                            )
                        )
                        .map { it.first }
                }
                val fetchChannelsAction = { isManualRefresh: Boolean ->
                    scope.launch(Dispatchers.IO) {
                        try {
                            withContext(Dispatchers.Main) {
                                isLoading = true
                                statusMessage = ""
                            }

                            val savedJsonMappings = epgMappingStore.getSavedMappings(portalIdentity)
                            val existingPortalChannels = channelDao.getAllChannelsList().filter {
                                it.id.startsWith(portalPrefix)
                            }

                            val portalChannelsToUse: List<Channel> = if (!isManualRefresh && existingPortalChannels.isNotEmpty() && !savedJsonMappings.isNullOrEmpty()) {
                                Log.d(TAG, "⚡ Lista canali e ID EPG già pronti per il portale $portalId! Caricamento istantaneo.")
                                existingPortalChannels.map { ch ->
                                    val jsonEpgId = savedJsonMappings[ch.id]
                                    if (!jsonEpgId.isNullOrBlank() && ch.tvgId != jsonEpgId) {
                                        ch.copy(tvgId = jsonEpgId)
                                    } else ch
                                }
                            } else {
                                Log.d(TAG, "📥 Scaricamento canali dal server per il portale: ${portalIdentity.signature}")
                                val isStalker = portalType.equals("STALKER", ignoreCase = true) || portalType.equals("MAG", ignoreCase = true)
                                val isXtream = portalType.equals("XTREAM", ignoreCase = true) || portalType.equals("XSTREAM", ignoreCase = true)

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
                                                id = portalId, server = portalServer, macAddress = macAddress,
                                                useCustomUserAgent = !userAgent.isNullOrBlank(), userAgent = userAgent ?: ""
                                            )
                                            StalkerClient.fetchChannels(portalDraft)
                                        }
                                    }
                                    isXtream || (username.isNotBlank() && password.isNotBlank()) -> {
                                        val portalDraft = Portal(
                                            id = portalId, server = server, username = username, password = password,
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
                                        val rawPart = if (channel.id.isBlank()) "${index}_${channel.name.hashCode()}" else channel.id
                                        val safeId = "$portalPrefix$rawPart"
                                        val savedTvgId = savedJsonMappings?.get(safeId) ?: channel.tvgId
                                        channel.copy(
                                            id = safeId,
                                            playlistId = portalId,
                                            tvgId = savedTvgId
                                        )
                                    }
                                    channelDao.deleteChannelsByPlaylist(portalId)
                                    channelDao.deleteChannelsByPrefix("ch_${portalId}_")
                                    channelDao.insertChannels(validFetched)
                                    validFetched
                                } else {
                                    emptyList()
                                }
                            }

                            if (portalChannelsToUse.isNotEmpty()) {
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    if (isManualRefresh) {
                                        successBannerText = "Lista canali aggiornata con successo!"
                                    }
                                }

                                withContext(Dispatchers.Main) { isEpgLoading = true }
                                try {
                                    val countryToUse = appPrefs.getString("epg_country_filter", "IT") ?: "IT"

                                    val finalPortalChannels = if (savedJsonMappings == null) {
                                        Log.d(TAG, "🔍 Prima configurazione EPG per portale $portalId: avvio ricerca automatica e salvataggio su JSON...")
                                        val associated = epgRepository.autoAssociatePortalChannels(
                                            portalChannels = portalChannelsToUse,
                                            countryCode = countryToUse,
                                            forceOverwrite = false
                                        )
                                        channelDao.insertChannels(associated)
                                        epgMappingStore.savePortalMappings(portalIdentity, associated)
                                        associated
                                    } else {
                                        Log.d(TAG, "⚡ ID EPG già presenti in epg_mappings.json! Salto la ricerca lenta.")
                                        portalChannelsToUse
                                    }

                                    val newInfoMap = epgRepository.getAllCurrentEpg(finalPortalChannels, countryToUse)
                                    val newProgramsMap = epgRepository.getProgramsForChannels(finalPortalChannels, countryToUse)

                                    withContext(Dispatchers.Main) {
                                        epgInfoMap = newInfoMap
                                        epgProgramsMap = newProgramsMap
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Errore EPG in background", e)
                                } finally {
                                    withContext(Dispatchers.Main) { isEpgLoading = false }
                                }
                            } else {
                                withContext(Dispatchers.Main) {
                                    statusMessage = "Nessun canale caricato. Verifica server e credenziali."
                                }
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                statusMessage = "Errore: ${e.localizedMessage}"
                            }
                            Log.e(TAG, "Errore generale caricamento canali", e)
                        } finally {
                            withContext(Dispatchers.Main) {
                                isLoading = false
                                isEpgLoading = false
                            }
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    fetchChannelsAction(false)
                }
                
                if (showSettingsDialog) {
                                        // 🎨 COLORI DEDICATI SOLO A QUESTO MENÙ (niente più bianco sparato!)
                    // Nel tema chiaro usa un Grigio-Ardesia morbido (0xFFD4DEE8) che fa risaltare i bottoni Neon.
                    // (Se invece vuoi questo menù SEMPRE scuro anche col tema chiaro, metti Color(0xFF182234) al posto di 0xFFD4DEE8)
                    val menuBgColor = if (darkTheme) Color(0xFF111827) else Color(0xFFA9B1B9)
                    val menuCardColor = if (darkTheme) Color(0xFF1B253B) else Color(0xFFE4ECF4)
                    val menuCardBorder = if (darkTheme) Color(0xFF2E3F5C) else Color(0xFFB4C4D8)
                    val menuTextColor = if (darkTheme) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                    val menuSubTextColor = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF475569)
                    val accentCyan = if (darkTheme) Color(0xFF00E5FF) else Color(0xFF0088A3)

                    Dialog(onDismissRequest = { showSettingsDialog = false }) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth(0.92f)
                                .wrapContentHeight(),
                            shape = RoundedCornerShape(24.dp),
                            color = menuBgColor,
                            contentColor = menuTextColor,
                            border = BorderStroke(2.dp, Color(0xFF00E5FF))
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(20.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // --- INTESTAZIONE ---
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = accentCyan
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Opzioni Lista Canali",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = menuTextColor
                                    )
                                }

                                Divider(color = menuCardBorder)

                                // --- SEZIONE TEMA ---
                                Text(
                                    text = "Tema della Lista Canali",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = menuTextColor
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    NeonCyberChip(
                                        onClick = {
                                            appTheme = "dark"
                                            appPrefs.edit().putString("app_theme", "dark").apply()
                                        },
                                        isActive = darkTheme,
                                        activeColor = Color(0xFF00E5FF),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Scuro",
                                            color = if (darkTheme) Color.White else Color.LightGray,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    NeonCyberChip(
                                        onClick = {
                                            appTheme = "light"
                                            appPrefs.edit().putString("app_theme", "light").apply()
                                        },
                                        isActive = !darkTheme,
                                        activeColor = Color(0xFF00E5FF),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = "Chiaro",
                                            color = if (!darkTheme) Color.White else Color.LightGray,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Divider(color = menuCardBorder)

                                // --- SEZIONE GESTIONE CANALI ---
                                Text(
                                    text = "Gestione Canali",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = menuTextColor
                                )
                                NeonCyberChip(
                                    onClick = {
                                        showSettingsDialog = false
                                        fetchChannelsAction(true)
                                    },
                                    isActive = true,
                                    activeColor = Color(0xFF00E5FF),
                                    height = 42.dp,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Aggiorna Lista Canali", color = Color.White, fontWeight = FontWeight.Bold)
                                }

                                Divider(color = menuCardBorder)

                                // --- SEZIONE GUIDA TV (EPG) ---
                                Text(
                                    text = "Guida TV (EPG)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = menuTextColor
                                )
                                Text(
                                    text = "Nazionalità predefinita EPG (escludi le altre nazioni)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = menuSubTextColor
                                )
                                var selectedCountry by remember {
                                    mutableStateOf(appPrefs.getString("epg_country_filter", "IT") ?: "IT")
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf("IT", "UK", "FR", "DE", "ES").forEach { country ->
                                        val isSelected = selectedCountry.equals(country, ignoreCase = true)
                                        NeonCyberChip(
                                            onClick = {
                                                selectedCountry = country
                                                appPrefs.edit().putString("epg_country_filter", country).apply()
                                            },
                                            isActive = isSelected,
                                            activeColor = Color(0xFF00E5FF),
                                            cornerRadius = 10.dp,
                                            height = 36.dp,
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = country,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) Color.White else Color.LightGray,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                NeonCyberChip(
                                    onClick = {
                                        showSettingsDialog = false
                                        val countryToUse = appPrefs.getString("epg_country_filter", "IT") ?: "IT"
                                        scope.launch(Dispatchers.IO) {
                                            withContext(Dispatchers.Main) {
                                                isEpgLoading = true
                                            }
                                            try {
                                                val currentPortalList = channelDao.getAllChannelsList().filter {
                                                    it.id.startsWith(portalPrefix)
                                                }
                                                val updated = epgRepository.autoAssociatePortalChannels(
                                                    portalChannels = currentPortalList,
                                                    countryCode = countryToUse,
                                                    forceOverwrite = true
                                                )
                                                channelDao.insertChannels(updated)
                                                epgMappingStore.savePortalMappings(portalIdentity, updated)
                                                val matchedCount = updated.count { it.tvgId.isNotBlank() }
                                                val newInfoMap = epgRepository.getAllCurrentEpg(updated, countryToUse)
                                                val newProgramsMap = epgRepository.getProgramsForChannels(updated, countryToUse)
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(this@ChannelsActivity, "Salvati $matchedCount canali per [$countryToUse]!", Toast.LENGTH_LONG).show()
                                                    epgInfoMap = newInfoMap
                                                    epgProgramsMap = newProgramsMap
                                                }
                                            } finally {
                                                withContext(Dispatchers.Main) {
                                                    isEpgLoading = false
                                                }
                                            }
                                        }
                                    },
                                    isActive = true,
                                    activeColor = Color(0xFFFF6D00),
                                    height = 42.dp,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Tv, contentDescription = null, tint = Color(0xFFFF6D00), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Aggiorna EPG  [ $selectedCountry ]", color = Color.White, fontWeight = FontWeight.Bold)
                                }

                                Divider(color = menuCardBorder)

                                // --- SEZIONE PERSONALIZZAZIONE GRAFICA RINNOVATA ---
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Personalizzazione Grafica",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = menuTextColor
                                    )
                                    // Pulsante rapido per ripristinare le dimensioni di default
                                    Surface(
                                        onClick = {
                                            channelTextSize = 16f
                                            logoSize = 48
                                            epgTextSize = 14f
                                            appPrefs.edit()
                                                .putFloat("channel_text_size", 16f)
                                                .putInt("logo_size", 48)
                                                .putFloat("epg_text_size", 14f)
                                                .apply()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (darkTheme) Color(0xFF161922) else Color(0xFF3B4D63),
                                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f))
                                    ) {
                                        Text(
                                            text = "Reset",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (darkTheme) Color(0xFF00E5FF) else Color(0xFFFFFFFF),
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                // 1. Card Regolazione Testo Canali
                                GraphicControlCard(
                                    title = "Testo Nome Canale",
                                    valueLabel = "${channelTextSize.toInt()} sp",
                                    value = channelTextSize,
                                    valueRange = 12f..24f,
                                    steps = 11,
                                    cardBg = menuCardColor,
                                    cardBorder = menuCardBorder,
                                    textColor = menuTextColor,
                                    onValueChange = { newValue ->
                                        val rounded = kotlin.math.round(newValue)
                                        channelTextSize = rounded
                                        appPrefs.edit().putFloat("channel_text_size", rounded).apply()
                                    },
                                    onStepChange = { delta ->
                                        val next = (channelTextSize + delta).coerceIn(12f, 24f)
                                        channelTextSize = next
                                        appPrefs.edit().putFloat("channel_text_size", next).apply()
                                    }
                                )

                                // 2. Card Regolazione Loghi
                                GraphicControlCard(
                                    title = "Dimensione Loghi",
                                    valueLabel = "$logoSize dp",
                                    value = logoSize.toFloat(),
                                    valueRange = 32f..72f,
                                    steps = 7,
                                    cardBg = menuCardColor,
                                    cardBorder = menuCardBorder,
                                    textColor = menuTextColor,
                                    onValueChange = { newValue ->
                                        val rounded = newValue.toInt()
                                        logoSize = rounded
                                        appPrefs.edit().putInt("logo_size", rounded).apply()
                                    },
                                    onStepChange = { delta ->
                                        val next = (logoSize + delta.toInt() * 4).coerceIn(32, 72)
                                        logoSize = next
                                        appPrefs.edit().putInt("logo_size", next).apply()
                                    }
                                )

                                // 3. Card Regolazione Testo EPG
                                GraphicControlCard(
                                    title = "Testo Guida TV (EPG)",
                                    valueLabel = "${epgTextSize.toInt()} sp",
                                    value = epgTextSize,
                                    valueRange = 10f..20f,
                                    steps = 9,
                                    cardBg = menuCardColor,
                                    cardBorder = menuCardBorder,
                                    textColor = menuTextColor,
                                    onValueChange = { newValue ->
                                        val rounded = kotlin.math.round(newValue)
                                        epgTextSize = rounded
                                        appPrefs.edit().putFloat("epg_text_size", rounded).apply()
                                    },
                                    onStepChange = { delta ->
                                        val next = (epgTextSize + delta).coerceIn(10f, 20f)
                                        epgTextSize = next
                                        appPrefs.edit().putFloat("epg_text_size", next).apply()
                                    }
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                // --- PULSANTE CHIUDI ROSSO NEON ---
                                NeonCyberChip(
                                    onClick = { showSettingsDialog = false },
                                    isActive = true,
                                    activeColor = Color(0xFFEF4444),
                                    height = 42.dp,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Chiudi",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                if (showGroupDialog) {
				    Dialog(onDismissRequest = { showGroupDialog = false }) {
				        Surface(
				            modifier = Modifier
				                .fillMaxWidth(0.88f)
				                .wrapContentHeight(),
				            shape = RoundedCornerShape(24.dp),
				            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(2.dp, Color(0xFF00E5FF)) // 👈 Bordo neon ciano brillante
				        ) {
				            Column(
				                modifier = Modifier
				                    .padding(20.dp)
				                    .verticalScroll(rememberScrollState()),
				                verticalArrangement = Arrangement.spacedBy(14.dp)
				            ) {
				                // Intestazione del Dialog
				                Row(verticalAlignment = Alignment.CenterVertically) {
				                    Icon(Icons.Default.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
				                    Spacer(modifier = Modifier.width(8.dp))
				                    Text(
				                        text = "Seleziona Gruppo",
				                        style = MaterialTheme.typography.titleMedium,
				                        fontWeight = FontWeight.Bold
				                    )
				                }
				
				                Divider(modifier = Modifier.padding(vertical = 2.dp))
				
				                // Barra di ricerca della categoria
				                Surface(
				                    modifier = Modifier
				                        .fillMaxWidth()
				                        .height(44.dp),
				                    shape = RoundedCornerShape(12.dp),
				                    color = MaterialTheme.colorScheme.surfaceVariant,
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
				
				                // Lista dei gruppi con feedback visivo al click
				                Column(
				                    modifier = Modifier
				                        .fillMaxWidth()
				                        .heightIn(max = 260.dp)
				                        .verticalScroll(rememberScrollState()),
				                    verticalArrangement = Arrangement.spacedBy(4.dp)
				                ) {
				                    // Opzione "Tutti i Gruppi" con effetto ripple/colore al tocco
				                    Surface(
				                        modifier = Modifier
				                            .fillMaxWidth()
				                            .clip(RoundedCornerShape(8.dp))
				                            .clickable {
				                                selectedGroup = null
				                                showOnlyFavorites = false
				                                showOnlyEpg = false
				                                showGroupDialog = false
				                            },
				                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
				                    ) {
				                        Row(
                                            modifier = Modifier.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ){
                                            Text(
                                                text = "Tutti i Gruppi", 
                                                style = MaterialTheme.typography.bodyMedium, 
                                                fontWeight = FontWeight.SemiBold
                                            )
				                        }
				                    }
				
				                    Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
				
				                    // Elementi della lista filtrata
                                    filteredGroups.forEach { group ->
				                        val isFavGroup = favoriteGroups.contains(group)
				                        Surface(
				                            modifier = Modifier
				                                .fillMaxWidth()
				                                .clip(RoundedCornerShape(8.dp))
				                                .clickable {
				                                    selectedGroup = group
				                                    showOnlyFavorites = false
				                                    showOnlyEpg = false
				                                    showGroupDialog = false
				                                },
				                            color = Color.Transparent
				                        ) {
				                            Row(
				                                modifier = Modifier
				                                    .fillMaxWidth()
				                                    .padding(horizontal = 12.dp, vertical = 10.dp),
				                                verticalAlignment = Alignment.CenterVertically,
				                                horizontalArrangement = Arrangement.SpaceBetween
				                            ) {
				                                Text(
				                                    text = group.ifBlank { "Generale" },
				                                    style = MaterialTheme.typography.bodyMedium,
				                                    modifier = Modifier.weight(1f)
				                                )
				                                IconButton(
				                                    onClick = {
				                                        val newFavs = if (isFavGroup) favoriteGroups - group else favoriteGroups + group
                                                        favoriteGroups = newFavs
				                                        prefs.edit().putStringSet("favorite_groups", newFavs).apply()
				                                    },
				                                    modifier = Modifier.size(32.dp)
				                                ) {
				                                    Icon(
				                                        imageVector = if (isFavGroup) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
				                                        contentDescription = null,
				                                        tint = if (isFavGroup) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant,
				                                        modifier = Modifier.size(18.dp)
				                                    )
				                                }
				                            }
				                        }
				                    }
				                }
				
				                Spacer(modifier = Modifier.height(4.dp))
				
				                // Pulsante Chiudi in basso
				                Button(
				                    onClick = { showGroupDialog = false },
				                    modifier = Modifier.fillMaxWidth(),
				                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
				                ) {
				                    Text(
				                        text = "Chiudi",
				                        color = Color(0xFFEF4444),
				                        fontWeight = FontWeight.Bold
				                    )
				                }
				            }
				        }
				    }
				}

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val configuration = LocalConfiguration.current
                    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                val composition by rememberLottieComposition(
                                    LottieCompositionSpec.RawRes(R.raw.hourglass)
                                )
                                LottieAnimation(
                                    composition = composition,
                                    iterations = LottieConstants.IterateForever,
                                    modifier = Modifier.size(160.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Caricamento canali...", color = MaterialTheme.colorScheme.onBackground)
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
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onBackground
                                )
                                IconButton(
                                    onClick = { showSettingsDialog = true },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = "Impostazioni",
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }

                            val isSearching = searchQuery.isNotEmpty()
                            val searchBarBg = if (darkTheme) Color(0xFF161922) else Color(0xFFD4DEE8)
                            val searchBarBorder = if (isSearching) {
                                Color(0xFF00E5FF)
                            } else {
                                if (darkTheme) Color(0xFF2E3F5C) else Color(0xFFA3B4C8)
                            }
                            val searchTextColor = if (darkTheme) Color(0xFFF8FAFC) else Color(0xFF0F172A)
                            val searchHintColor = if (darkTheme) Color(0xFF94A3B8) else Color(0xFF475569)

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .padding(bottom = if (isLandscape) 4.dp else 8.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = searchBarBg,
                                border = BorderStroke(if (isSearching) 1.5.dp else 1.dp, searchBarBorder)
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
                                        tint = if (isSearching) Color(0xFF00E5FF) else searchHintColor
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
                                                color = searchHintColor
                                            )
                                        }
                                        BasicTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = searchTextColor
                                            ),
                                            cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFF00E5FF)),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    if (isSearching) {
                                        IconButton(
                                            onClick = { searchQuery = "" },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Clear,
                                                contentDescription = "Cancella",
                                                modifier = Modifier.size(16.dp),
                                                tint = Color(0xFFEF4444)
                                            )
                                        }
                                    }
                                }
                            }

                            // --- I 3 BOTTONI FILTRO IN STILE NEON CYBER ---
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = if (isLandscape) 6.dp else 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Bottone PREFERITI (Rosa/Fucsia Neon)
                                NeonCyberChip(
                                    onClick = {
                                        showOnlyFavorites = !showOnlyFavorites
                                        if (showOnlyFavorites) {
                                            selectedGroup = null
                                            showOnlyEpg = false
                                        }
                                    },
                                    isActive = showOnlyFavorites,
                                    activeColor = Color(0xFFFF2DAA),
                                    cornerRadius = 12.dp,
                                    height = 38.dp,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Favorite,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = if (showOnlyFavorites) Color(0xFFFF2DAA) else Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "Preferiti",
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (showOnlyFavorites) Color.White else Color.LightGray,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // 2. Bottone GRUPPI (verde)
                                val isGroupActive = selectedGroup != null || showGroupDialog
                                NeonCyberChip(
                                    onClick = { showGroupDialog = true },
                                    isActive = isGroupActive,
                                    activeColor = Color(0xFF00E676),   // 👈 Verde Neon brillante
                                    cornerRadius = 12.dp,
                                    height = 38.dp,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.List,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = if (isGroupActive) Color(0xFF00E676) else Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = selectedGroup ?: "Gruppi",
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isGroupActive) Color.White else Color.LightGray,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // 3. Bottone GUIDA TV (Arancione Neon)
                                NeonCyberChip(
                                    onClick = {
                                        showOnlyEpg = !showOnlyEpg
                                        if (showOnlyEpg) {
                                            showOnlyFavorites = false
                                            selectedGroup = null
                                        }
                                    },
                                    isActive = showOnlyEpg,
                                    activeColor = Color(0xFFFF6D00),
                                    cornerRadius = 12.dp,
                                    height = 38.dp,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tv,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = if (showOnlyEpg) Color(0xFFFF6D00) else Color.LightGray
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "Guida TV",
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (showOnlyEpg) Color.White else Color.LightGray,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            // --- BANNER CON ICONA VERA QUANDO LA LISTA È AGGIORNATA ---
                            successBannerText?.let { message ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (darkTheme) Color(0xFF161922) else Color(0xFF2B3B4E),
                                    border = BorderStroke(1.5.dp, Color(0xFF00E676)) // Bordo Verde Neon
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF00E676), // Icona Check Verde Neon
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = message,
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            // --- BANNER CYBER RICERCA EPG IN BACKGROUND ---
                            if (isEpgLoading) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xFF161922),
                                    border = BorderStroke(1.5.dp, Color(0xFFFF6D00).copy(alpha = 0.85f))
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 9.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Icona TV Ciano dentro l'anello rotante Arancione Neon
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(34.dp),
                                                    strokeWidth = 2.5.dp,
                                                    color = Color(0xFFFF6D00),
                                                    trackColor = Color(0xFF2E3F5C)
                                                )
                                                Icon(
                                                    imageVector = Icons.Default.Tv,
                                                    contentDescription = null,
                                                    tint = Color(0xFF00E5FF),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(12.dp))

                                            // Testo su due livelli (Titolo + Sottotitolo)
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = "Ricerca Guida TV (EPG) in background...",
                                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                                    color = Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Sincronizzazione palinsesti in corso",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                                                    color = Color(0xFF94A3B8),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            // Mini-Badge "SYNC"
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = Color(0xFF1E293B),
                                                border = BorderStroke(1.dp, Color(0xFF00E5FF))
                                            ) {
                                                Text(
                                                    text = "SYNC",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                    color = Color(0xFF00E5FF),
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                )
                                            }
                                        }

                                        // Striscia luminosa di scansione sul fondo del banner
                                        LinearProgressIndicator(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(3.dp),
                                            color = Color(0xFF00E5FF),
                                            trackColor = Color(0xFF1E293B)
                                        )
                                    }
                                }
                            }

                            // --- CARD CENTRALE SE L'UTENTE APRE "GUIDA TV" MENTRE CARICA ---
                            if (showOnlyEpg && isEpgLoading && epgProgramsMap.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        modifier = Modifier
                                            .fillMaxWidth(0.85f)
                                            .padding(24.dp),
                                        shape = RoundedCornerShape(20.dp),
                                        color = Color(0xFF161922),
                                        border = BorderStroke(1.5.dp, Color(0xFFFF6D00))
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.size(56.dp)
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(56.dp),
                                                    strokeWidth = 3.dp,
                                                    color = Color(0xFFFF6D00),
                                                    trackColor = Color(0xFF2E3F5C)
                                                )
                                                Icon(
                                                    imageVector = Icons.Default.Tv,
                                                    contentDescription = null,
                                                    tint = Color(0xFF00E5FF),
                                                    modifier = Modifier.size(26.dp)
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(16.dp))

                                            Text(
                                                text = "Sincronizzazione Guida TV",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                textAlign = TextAlign.Center
                                            )

                                            Spacer(modifier = Modifier.height(6.dp))

                                            Text(
                                                text = "Sto cercando e associando i palinsesti EPG...\nAttendi qualche istante.",
                                                style = MaterialTheme.typography.bodySmall,
                                                textAlign = TextAlign.Center,
                                                color = Color(0xFF94A3B8)
                                            )

                                            Spacer(modifier = Modifier.height(16.dp))

                                            LinearProgressIndicator(
                                                modifier = Modifier
                                                    .fillMaxWidth(0.75f)
                                                    .height(4.dp)
                                                    .clip(RoundedCornerShape(2.dp)),
                                                color = Color(0xFF00E5FF),
                                                trackColor = Color(0xFF1E293B)
                                            )
                                        }
                                    }
                                }                     
                            } else if (currentChannels.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    if (statusMessage.isNotBlank()) {
                                        // Contenitore centrale con stile coerente e pulito
                                        Surface(
                                            modifier = Modifier
                                                .fillMaxWidth(0.85f)
                                                .padding(24.dp),
                                            shape = RoundedCornerShape(20.dp),
                                            color = Color(0xFF161922), // Sfondo scuro coordinato (CardBg)
                                            border = BorderStroke(1.5.dp, Color(0xFFEF4444).copy(alpha = 0.6f)) // Bordo rosso errore delicato
                                        ) {
                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(20.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                                verticalArrangement = Arrangement.Center
                                            ) {
                                                // Icona posizionata SOPRA il testo e centrata, con il colore rosso errore
                                                Icon(
                                                    imageVector = Icons.Default.ErrorOutline,
                                                    contentDescription = null,
                                                    tint = Color(0xFFEF4444), // Rosso errore coerente con l'app
                                                    modifier = Modifier.size(38.dp)
                                                )
                                                
                                                Spacer(modifier = Modifier.height(12.dp))
                                                
                                                Text(
                                                    text = statusMessage,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Medium
                                                    ),
                                                    color = Color.White,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    } else {
                                        Text(
                                            text = "Nessun canale trovato",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.Center
                                        )
                                    }
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
                                                                id = portalId, server = portalServer, macAddress = macAddress,
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
                                                        Toast.makeText(this@ChannelsActivity, "Errore: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
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
                                                logoSize = logoSize,
                                                isDark = darkTheme,
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
                                                isDark = darkTheme,
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
                                epgMappingStore.saveSingleChannelMapping(portalIdentity, updatedChannel, newTvgId)

                                val countryToUse = appPrefs.getString("epg_country_filter", "IT") ?: "IT"
                                val refreshedPortalChannels = channelDao.getAllChannelsList().filter {
                                    it.id.startsWith(portalPrefix)
                                }
                                val newInfoMap = epgRepository.getAllCurrentEpg(refreshedPortalChannels, countryToUse)
                                val newProgramsMap = epgRepository.getProgramsForChannels(refreshedPortalChannels, countryToUse)
                                withContext(Dispatchers.Main) {
                                    epgInfoMap = newInfoMap
                                    epgProgramsMap = newProgramsMap
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
        isDark: Boolean, // true = Deep Blue Notturno, false = Slate Antracite Scuro
        onFavoriteToggle: () -> Unit,
        onPlayClick: () -> Unit,
        onLongClick: () -> Unit
    ) {
        // Palette basata su DUE temi scuri distinti (Deep Blue vs Slate Antracite)
        val outerCardBg = if (isDark) Color(0xFF131B2E) else Color(0xFF111827)
        val innerFrameBg = if (isDark) Color(0xFF1B253B) else Color(0xFF1F2937)

        val externalBorderColor = when {
            isLoading -> Color(0xFF00E5FF)
            isFavorite -> Color(0xFFFF2DAA).copy(alpha = 0.65f)
            isDark -> Color(0xFF2E3F5C)
            else -> Color(0xFF374151)
        }

        val innerFrameBorder = if (isDark) Color(0xFF2E3F5C) else Color(0xFF374151)
        val channelTitleColor = Color(0xFFFFFFFF) // Sempre bianco brillante nei temi scuri

        // Sfondo dinamico per le righe dei programmi (Sempre toni scuri coerenti)
        val programRowBg = if (isDark) Color(0xFF26354D) else Color(0xFF253347)

        // Colori e dimensioni per il cuore
        val heartIconSize = 22.dp
        val heartIconTint = when {
            isFavorite -> Color(0xFFFF2DAA)
            isDark -> Color(0xFF94A3B8)
            else -> Color(0xFF9CA3AF)
        }

        val epgMainSize = (channelTextSize - 4f).coerceAtLeast(10f).sp
        val epgTimeSize = (channelTextSize - 5f).coerceAtLeast(9f).sp

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 0.dp, vertical = 3.dp)
                .combinedClickable(
                    onClick = onPlayClick,
                    onLongClick = onLongClick
                ),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = outerCardBg,
                contentColor = channelTitleColor
            ),
            border = BorderStroke(
                width = if (isLoading || isFavorite) 1.5.dp else 1.dp,
                color = externalBorderColor
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            // CORNICE INTERNA
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(5.dp),
                shape = RoundedCornerShape(12.dp),
                color = innerFrameBg,
                border = BorderStroke(1.dp, innerFrameBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 1. LOGO A SINISTRA
                    if (!channel.logoUrl.isNullOrBlank()) {
                        ChannelLogo(
                            logoUrl = channel.logoUrl,
                            channelName = channel.name,
                            logoSize = logoSize
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }

                    // 2. BLOCCO DESTRO (Nome + Cuore in alto, EPG sotto)
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        // Riga superiore: Nome Canale + Cuore ingrandito (senza box)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = channel.name,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontSize = channelTextSize.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = channelTitleColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            if (isLoading) {
                                Box(
                                    modifier = Modifier.size(heartIconSize),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Color(0xFF00E5FF)
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(heartIconSize + 10.dp)
                                        .clip(RoundedCornerShape(50))
                                        .clickable(
                                            onClick = onFavoriteToggle,
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ),         
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                        contentDescription = "Preferito",
                                        tint = heartIconTint,
                                        modifier = Modifier.size(heartIconSize)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Riga inferiore: CORNICE UNICA CONTINUA per ORA e per DOPO
                        if (epgInfo != null && epgInfo.currentProgram.isNotBlank()) {
                            // --- CORNICE UNICA "ORA" ---
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(6.dp),
                                color = programRowBg,
                                border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.8f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = Color(0xFF00E5FF)
                                    )
                                    Text(
                                        text = "",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        ),
                                        color = Color(0xFF00E5FF)
                                    )
                                    Text(
                                        text = " │ ",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                        color = Color(0xFF00E5FF).copy(alpha = 0.45f)
                                    )
                                    Text(
                                        text = epgInfo.currentProgram,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = epgMainSize
                                        ),
                                        color = Color(0xFF00E5FF),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (epgInfo.timeRange.isNotBlank()) {
                                        Text(
                                            text = " │ ",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            color = Color(0xFF00E5FF).copy(alpha = 0.45f)
                                        )
                                        Text(
                                            text = epgInfo.timeRange,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = epgTimeSize
                                            ),
                                            color = Color(0xFF00E5FF),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }

                            // --- CORNICE UNICA "DOPO" ---
                            if (epgInfo.nextProgram.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(6.dp),
                                    color = programRowBg,
                                    border = BorderStroke(1.dp, Color(0xFFFF2DAA).copy(alpha = 0.7f))
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 6.dp, vertical = 3.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FastForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = Color(0xFFFF77C6)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = "",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            ),
                                            color = Color(0xFFFF77C6)
                                        )
                                        Text(
                                            text = " │ ",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                            color = Color(0xFFFF77C6).copy(alpha = 0.45f)
                                        )
                                        Text(
                                            text = epgInfo.nextProgram,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = epgMainSize
                                            ),
                                            color = Color(0xFFFF77C6),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (epgInfo.nextTimeRange.isNotBlank()) {
                                            Text(
                                                text = " │ ",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                color = Color(0xFFFF77C6).copy(alpha = 0.45f)
                                            )
                                            Text(
                                                text = epgInfo.nextTimeRange,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = epgTimeSize
                                                ),
                                                color = Color(0xFFFF77C6),
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            // Nessun EPG: Badge Categoria
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = programRowBg.copy(alpha = 0.85f),
                                border = BorderStroke(1.dp, Color(0xFF475569))
                            ) {
                                Text(
                                    text = channel.group.ifBlank { "Canale TV" },
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = epgTimeSize),
                                    color = channelTitleColor,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
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
        epgPrograms: List<EpgProgram>,
        epgTextSize: Float,
        logoSize: Int,
        isDark: Boolean,
        onPlayClick: () -> Unit,
        onLongClick: () -> Unit
    ) {
        val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
        val now = remember { LocalDateTime.now(ZoneId.of("Europe/Rome")) }
        var expandedProgramKey by remember { mutableStateOf<String?>(null) }

        // Palette ad alto contrasto (niente bianco sparato nel tema chiaro)
        val outerCardBg = if (isDark) Color(0xFF131B2E) else Color(0xFFD4DEE8)
        val outerBorderColor = if (isDark) Color(0xFF2E3F5C) else Color(0xFF94A3B8)
        val innerContainerBg = if (isDark) Color(0xFF1B253B) else Color(0xFFE4ECF4)
        val titleTextColor = if (isDark) Color(0xFFF8FAFC) else Color(0xFF0F172A)
        val subTextColor = if (isDark) Color(0xFF94A3B8) else Color(0xFF475569)
        val currentAccent = if (isDark) Color(0xFF00E5FF) else Color(0xFF007799)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .combinedClickable(
                    onClick = onPlayClick,
                    onLongClick = onLongClick
                ),
            colors = CardDefaults.cardColors(containerColor = outerCardBg),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.5.dp, outerBorderColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // --- INTESTAZIONE CANALE (Logo + Nome + Bottone Avvia Neon) ---
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!channel.logoUrl.isNullOrBlank()) {
                        ChannelLogo(
                            logoUrl = channel.logoUrl,
                            channelName = channel.name,
                            logoSize = (logoSize - 6).coerceAtLeast(34)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = (epgTextSize + 2f).sp),
                            fontWeight = FontWeight.Bold,
                            color = titleTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (channel.group.isNotBlank()) {
                            Text(
                                text = channel.group,
                                style = MaterialTheme.typography.labelSmall,
                                color = subTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Badge/Bottone "AVVIA" in stile Neon
                    Surface(
                        onClick = onPlayClick,
                        shape = RoundedCornerShape(10.dp),
                        color = if (isDark) Color(0xFF161922) else Color(0xFF3B4D63),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF00E5FF)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "AVVIA",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                Divider(
                    color = outerBorderColor.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 10.dp)
                )

                // --- LISTA PROGRAMMI EPG ---
                if (epgPrograms.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = innerContainerBg
                    ) {
                        Text(
                            text = "Nessun palinsesto disponibile al momento.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = epgTextSize.sp),
                            color = subTextColor,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        epgPrograms.forEach { prog ->
                            val isCurrent = !now.isBefore(prog.startTime) && !now.isAfter(prog.endTime)
                            val timeRange = "${prog.startTime.format(timeFormatter)} - ${prog.endTime.format(timeFormatter)}"
                            val progKey = "${prog.title}_${prog.startTime}"
                            val isExpanded = expandedProgramKey == progKey

                            // Calcolo percentuale avanzamento per il programma in corso
                            val progress = if (isCurrent) {
                                val totalMin = java.time.Duration.between(prog.startTime, prog.endTime).toMinutes().coerceAtLeast(1)
                                val elapsedMin = java.time.Duration.between(prog.startTime, now).toMinutes().coerceIn(0, totalMin)
                                (elapsedMin.toFloat() / totalMin.toFloat()).coerceIn(0f, 1f)
                            } else 0f

                            val itemBg = if (isCurrent) {
                                if (isDark) Color(0xFF162B3D) else Color(0xFFCDE4EE)
                            } else {
                                innerContainerBg
                            }

                            val itemBorder = if (isCurrent) {
                                BorderStroke(1.5.dp, Color(0xFF00E5FF))
                            } else {
                                BorderStroke(1.dp, outerBorderColor.copy(alpha = 0.5f))
                            }

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        expandedProgramKey = if (isExpanded) null else progKey
                                    },
                                shape = RoundedCornerShape(12.dp),
                                color = itemBg,
                                border = itemBorder
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    // --- CORNICE UNICA CONTINUA (Stato + Titolo Programma + Orario) ---
									val badgeColor = if (isCurrent) Color(0xFF00E5FF) else Color(0xFFFF77C6)
									val rowBorder = if (isCurrent) Color(0xFF00E5FF).copy(alpha = 0.85f) else Color(0xFFFF2DAA).copy(alpha = 0.65f)
									val rowBg = if (isDark) Color(0xFF0D1424) else Color(0xFF434E67)
									
									// COLORI SEPARATI PER TITOLO E ORARIO
                                    val titleColor = if (isCurrent) Color(0xFF00E5FF) else Color(0xFFFFFFFF)
                                    val timeColor = if (isCurrent) Color(0xFF00E5FF) else Color(0xFF94A3B8)

									Surface(
									    modifier = Modifier.fillMaxWidth(),
									    shape = RoundedCornerShape(6.dp),
									    color = rowBg,
									    border = BorderStroke(1.dp, rowBorder)
									) {
									    Row(
									        modifier = Modifier
									            .fillMaxWidth()
									            .padding(horizontal = 8.dp, vertical = 5.dp),
									        verticalAlignment = Alignment.CenterVertically
									    ) {
									        // 1. Etichetta a sinistra ("● IN ONDA" se attivo)
									        if (isCurrent) {
									            Text(
									                text = "● IN ONDA",
									                style = MaterialTheme.typography.labelSmall.copy(
									                    fontSize = 9.sp,
									                    fontWeight = FontWeight.ExtraBold
									                ),
									                color = badgeColor
									            )
									            Text(
									                text = " │ ",
									                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
									                color = badgeColor.copy(alpha = 0.45f)
									            )
									        }
									
									        // 2. Titolo del programma al centro (usa titleColor)
									        Text(
									            text = prog.title,
									            style = MaterialTheme.typography.bodyMedium.copy(
									                fontSize = epgTextSize.sp,
									                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold
									            ),
									            color = titleColor, // <-- Usiamo il colore dedicato al titolo
									            maxLines = if (isExpanded) 2 else 1,
									            overflow = TextOverflow.Ellipsis,
									            modifier = Modifier.weight(1f)
									        )
									
									        // 3. Divisore + Orario di inizio e fine a destra
									        Text(
									            text = " │ ",
									            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
									            color = badgeColor.copy(alpha = 0.45f)
									        )
									        Text(
									            text = timeRange,
									            style = MaterialTheme.typography.labelSmall.copy(
									                fontSize = (epgTextSize - 2f).coerceAtLeast(10f).sp,
									                fontWeight = FontWeight.Bold
									            ),
									            color = timeColor, // <-- Usiamo il colore dedicato all'orario
									            maxLines = 1
									        )
									    }
                                    }                               
                                    // Barra di avanzamento luminosa per il programma in corso
                                    if (isCurrent) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        LinearProgressIndicator(
                                            progress = progress,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp)),
                                            color = Color(0xFF00E5FF),
                                            trackColor = Color(0xFF161922).copy(alpha = 0.5f)
                                        )
                                    }

                                    // Descrizione programma (espandibile con un tocco)
                                    if (!prog.description.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = prog.description.replace("\\t", " "),
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = (epgTextSize - 2f).coerceAtLeast(10f).sp),
                                            color = subTextColor,
                                            maxLines = if (isExpanded) Int.MAX_VALUE else 2,
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
    }

    @Composable
	private fun ChannelLogo(
	    logoUrl: String?,
	    channelName: String,
	    logoSize: Int,
	    modifier: Modifier = Modifier
    ) {
	    val isDark = isSystemInDarkTheme()
	    
	    val containerBgDark = 0xFFC6C8C9
	    val containerBgLight = 0xFFA3AAB4
	    
	    val containerBg = if (isDark) Color(containerBgDark) else Color(containerBgLight)
	    val borderColor = if (isDark) Color(0xFF94A3B8) else Color(0xFFCBD5E1)
	
	    Box(
	        modifier = modifier
	            .size(logoSize.dp)
	            .clip(RoundedCornerShape(10.dp))
	            .background(containerBg)
	            .border(
	                width = 1.5.dp,
	                color = borderColor,
	                shape = RoundedCornerShape(10.dp)
	            )
	            .padding(4.dp),
	        contentAlignment = Alignment.Center
	    ) {
	        AsyncImage(
	            model = ImageRequest.Builder(LocalContext.current)
	                .data(logoUrl)
	                .crossfade(true)
	                .build(),
	            contentDescription = "Logo $channelName",
	            modifier = Modifier.fillMaxSize(),
	            contentScale = ContentScale.Fit
	        )
	    }
    }     

    @Composable
	fun NeonCyberChip(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        isActive: Boolean = true,
        activeColor: Color = Color(0xFF00E5FF), // Ciano neon di default
        inactiveColor: Color = Color(0xFF4A5060), // Grigio spento
        cornerRadius: Dp = 16.dp,
        height: Dp = 38.dp,
        content: @Composable RowScope.() -> Unit
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        val isPressed by interactionSource.collectIsPressedAsState()
        val controlBgColor = if (MaterialTheme.colorScheme.background == Color(0xFF0B0F19)) Color(0xFF161922) else Color(0xFF3B4D63)
        val currentNeonColor by animateColorAsState(
            targetValue = if (isActive || isPressed) activeColor else inactiveColor,
            animationSpec = tween(durationMillis = 300),
            label = "neonColor"
        )

        val borderWidth by animateDpAsState(
            targetValue = if (isPressed) 2.5.dp else if (isActive) 1.5.dp else 1.dp,
            label = "borderWidth"
        )

        val glowAlpha by animateFloatAsState(
            targetValue = if (isPressed) 1f else if (isActive) 0.8f else 0.4f,
            label = "glowAlpha"
        )

        val scale by animateFloatAsState(
            targetValue = if (isPressed) 0.95f else 1f,
            label = "scale"
        )

        val blurRadius by animateDpAsState(
            targetValue = if (isPressed) 16.dp else if (isActive) 10.dp else 0.dp,
            label = "blurRadius"
        )

        Surface(
            onClick = onClick,
            color = if (MaterialTheme.colorScheme.background == Color(0xFF0B0F19)) Color(0xFF161922) else Color(0xFF3B4D63),
            shape = RoundedCornerShape(cornerRadius),
            border = BorderStroke(
                width = borderWidth,
                color = currentNeonColor.copy(alpha = glowAlpha)
            ),
            interactionSource = interactionSource,
            modifier = modifier
                .height(height)
                .graphicsLayer(scaleX = scale, scaleY = scale)
                .drawBehind {
                    if (isActive || isPressed) {
                        // 1. Salviamo tutte le metriche qui dentro (dove DrawScope è attivo)
                        val w = size.width
                        val h = size.height
                        val cornerPx = cornerRadius.toPx()
                        val offsetPx = 2.dp.toPx()
                        val blurPx = blurRadius.toPx().coerceAtLeast(1f)

                        drawIntoCanvas { canvas ->
                            val nativePaint = android.graphics.Paint().apply {
                                isAntiAlias = true
                                color = android.graphics.Color.argb(
                                    (currentNeonColor.alpha * (if (isPressed) 0.65f else 0.35f) * 255).toInt(),
                                    (currentNeonColor.red * 255).toInt(),
                                    (currentNeonColor.green * 255).toInt(),
                                    (currentNeonColor.blue * 255).toInt()
                                )
                                maskFilter = android.graphics.BlurMaskFilter(
                                    blurPx,
                                    android.graphics.BlurMaskFilter.Blur.NORMAL
                                )
                            }
                            canvas.nativeCanvas.drawRoundRect(
                                -offsetPx,
                                -offsetPx,
                                w + offsetPx,
                                h + offsetPx,
                                cornerPx,
                                cornerPx,
                                nativePaint
                            )
                        }
                    }
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                content = content
            )
        }
    }           
        @Composable
    private fun GraphicControlCard(
        title: String,
        valueLabel: String,
        value: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        steps: Int,
        cardBg: Color,
        cardBorder: Color,
        textColor: Color,
        onValueChange: (Float) -> Unit,
        onStepChange: (Float) -> Unit
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = BorderStroke(1.dp, cardBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                // Riga superiore: Titolo a sinistra + Badge Neon del valore a destra
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (MaterialTheme.colorScheme.background == Color(0xFF0B0F19)) Color(0xFF161922) else Color(0xFF3B4D63),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF))
                    ) {
                        Text(
                            text = valueLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E5FF),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Riga inferiore: Pulsante [-] + Slider Neon + Pulsante [+]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Pulsante "-"
                    Surface(
                        onClick = { onStepChange(-1f) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (MaterialTheme.colorScheme.background == Color(0xFF0B0F19)) Color(0xFF161922) else Color(0xFF3B4D63),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.7f)),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "−",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Slider con colori Ciano Neon
                    Slider(
                        value = value,
                        onValueChange = onValueChange,
                        valueRange = valueRange,
                        steps = steps,
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E5FF),
                            activeTrackColor = Color(0xFF00E5FF),
                            inactiveTrackColor = Color(0xFF3B4D63).copy(alpha = 0.45f),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        )
                    )

                    // Pulsante "+"
                    Surface(
                        onClick = { onStepChange(1f) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (MaterialTheme.colorScheme.background == Color(0xFF0B0F19)) Color(0xFF161922) else Color(0xFF3B4D63),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.7f)),
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "+",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
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

        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .wrapContentHeight(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(2.dp, Color(0xFF00E5FF)) // 👈 Bordo neon ciano brillante
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Intestazione con icona e titolo
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Dettagli & Assegnazione EPG",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Divider(modifier = Modifier.padding(vertical = 2.dp))

                    // Info Canale
                    Text(text = "• Canale: ${channel.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = "• Stream ID: ${channel.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(modifier = Modifier.height(2.dp))

                    OutlinedTextField(
                        value = editableTvgId,
                        onValueChange = { editableTvgId = it },
                        label = { Text("ID EPG Associato") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "Cerca ID nell'EPG ufficiale:",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { 
                            searchQuery = it 
                            isDropdownExpanded = it.isNotBlank()
                        },
                        placeholder = { Text("Es. Sky Sport, Rai 1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
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

                    Spacer(modifier = Modifier.height(8.dp))

                    // Pulsanti di azione in basso (Chiudi, Copia, Salva ID)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Chiudi",
                                color = Color(0xFFEF4444),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        
                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(fullDetailsText))
                                Toast.makeText(context, "Dettagli copiati!", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "Copia",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Button(
                        onClick = {
                            onSaveTvgId(editableTvgId.trim())
                            Toast.makeText(context, "ID EPG salvato con successo!", Toast.LENGTH_SHORT).show()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Salva ID",
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
    
    private fun channelSortNumber(channel: Channel): Int {
        if (channel.number > 0) return channel.number
        val fromName: Int? = extractChannelNumber(channel.name)
        return fromName ?: Int.MAX_VALUE   // Senza numero finisce in fondo alla lista
    }
    
    private fun extractChannelNumber(name: String): Int {
        val regex = Regex("""\b(\d+)\b""")
        val match = regex.find(name)
        return match?.value?.toIntOrNull() ?: 9999
    }

    private fun extractRawId(safeId: String): String {
        return if (safeId.startsWith("ch_")) safeId.substringAfterLast("_") else safeId
    }
    
    companion object {
        const val EXTRA_PORTAL_ID = "extra_portal_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_PORTAL_TYPE = "extra_portal_type"
        const val EXTRA_SERVER = "extra_server"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_FORMAT = "extra_format"
        const val EXTRA_MAC = "extra_mac"
        const val EXTRA_EXPIRE_DATE = "extra_expire_date"
    }
}
