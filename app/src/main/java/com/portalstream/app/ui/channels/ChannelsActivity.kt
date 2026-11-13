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
import androidx.compose.material3.Icon
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import coil.compose.AsyncImage
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
import com.portalstream.app.R
import androidx.compose.foundation.isSystemInDarkTheme
import coil.request.ImageRequest

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
            var appTheme by remember { mutableStateOf(appPrefs.getString("app_theme", "dark") ?: "dark") }
            val darkTheme = appTheme == "dark"

            var channelTextSize by remember { mutableStateOf(appPrefs.getFloat("channel_text_size", 16f)) }
            var logoSize by remember { mutableStateOf(appPrefs.getInt("logo_size", 52)) }
            var epgTextSize by remember { mutableStateOf(appPrefs.getFloat("epg_text_size", 14f)) }
            
            // TEMA CHIARO PROFESSIONALE
            val customLightScheme = lightColorScheme(
                primary = colorResource(id = R.color.primary),
                secondary = colorResource(id = R.color.secondary),
                background = Color(0xFFD8DCE0),
                surface = Color(0xFFFFFFFF),
                onBackground = Color(0xFF0F172A),
                onSurface = Color(0xFF0F172A),
                surfaceVariant = Color(0xFFE2E8F0),
                outline = Color(0xFFA2AAB4)
            )

            // TEMA SCURO OTTIMIZZATO
            val customDarkScheme = darkColorScheme(
                primary = colorResource(id = R.color.primary),       // Colore primario (pulsanti principali, accenti)
                secondary = colorResource(id = R.color.secondary),   // Colore secondario (es. pulsante EPG)
                background = Color(0xFF0B0F19),                      // Sfondo generale dell'Activity
                surface = Color(0xFF131B2E),                         // Sfondo delle card, dei dialoghi e delle superfici
                onBackground = Color(0xFFF8FAFC),                    // Colore del testo/icone sopra il background
                onSurface = Color(0xFFF8FAFC),                       // Colore del testo/icone sopra le superfici e i dialoghi
                surfaceVariant = Color(0xFF1E293B),                  // Sfondo alternativo per elementi secondari (es. pulsanti non attivi)
                outline = Color(0xFF334155)                          // Colore dei bordi e delle linee divisorie (divider)
            )

            MaterialTheme(
                colorScheme = if (darkTheme) customDarkScheme else customLightScheme
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
                                        Toast.makeText(this@ChannelsActivity, "✅ Lista aggiornata!", Toast.LENGTH_SHORT).show()
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
					Dialog(onDismissRequest = { showSettingsDialog = false }) {
						Surface(
							modifier = Modifier
								.fillMaxWidth(0.88f)
								.wrapContentHeight(),
							shape = RoundedCornerShape(24.dp),
							color = MaterialTheme.colorScheme.surface,
							border = BorderStroke(2.dp, Color(0xFF00E5FF))
						) {
							Column(
								modifier = Modifier
									.padding(20.dp)
									.verticalScroll(rememberScrollState()),
								verticalArrangement = Arrangement.spacedBy(16.dp)
							) {
								// Intestazione personalizzata pulita
								Row(verticalAlignment = Alignment.CenterVertically) {
									Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
									Spacer(modifier = Modifier.width(8.dp))
									Text("Opzioni Lista Canali", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
								}

								Divider()

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
											containerColor = if (darkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
											contentColor = if (darkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
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
											containerColor = if (!darkTheme) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
											contentColor = if (!darkTheme) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
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
									Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
									Spacer(modifier = Modifier.width(8.dp))
									Text("Aggiorna Lista Canali", fontWeight = FontWeight.Bold)
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
										Surface(
											modifier = Modifier
											    .weight(1f)
												.height(36.dp)
												.clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    selectedCountry = country
                                                    appPrefs.edit().putString("epg_country_filter", country).apply()
                                                },
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                            shape = RoundedCornerShape(8.dp),
                                            border = BorderStroke(
                                                width = 1.dp,
                                                color = if (isSelected) Color(0xFF00E5FF) else MaterialTheme.colorScheme.outline
                                            )
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = country,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isSelected) Color(0xFF000000) else MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }

								Spacer(modifier = Modifier.height(4.dp))

								Button(
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
									modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
								) {
									Icon(Icons.Default.Tv, contentDescription = null, modifier = Modifier.size(18.dp))
									Spacer(modifier = Modifier.width(8.dp))
									Text("Aggiorna EPG  [ $selectedCountry ]", fontWeight = FontWeight.Bold)
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

								Spacer(modifier = Modifier.height(8.dp))

								// Pulsante di chiusura in fondo al contenuto scorrevole
								Button(
									onClick = { showSettingsDialog = false },
									modifier = Modifier.fillMaxWidth(),
									colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface)
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

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .padding(bottom = if (isLandscape) 4.dp else 8.dp),
                                shape = RoundedCornerShape(8.dp),
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

                            if (isEpgLoading) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 8.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Ricerca Guida TV (EPG) in background...",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            if (showOnlyEpg && isEpgLoading && epgProgramsMap.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text(
                                            text = "Sto cercando la Guida TV (EPG)...\nAttendi qualche istante.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = TextAlign.Center,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
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
        isDark: Boolean,
        onFavoriteToggle: () -> Unit,
	    onPlayClick: () -> Unit,
	    onLongClick: () -> Unit
	) {
        // 👈 Colore del bordo esterno: più chiaro in dark mode, più scuro nel tema chiaro
        val externalBorderColor = if (isDark) {
            Color(0xFF475569) // Un grigio-azzurro più chiaro e visibile in dark mode (puoi alzarlo o abbassarlo a piacimento)
        } else {
            Color(0xFF64748B) // Un grigio più scuro e marcato per il tema chiaro
        }
	
	    // Colore del nome canale dinamico in base al tema
	    val channelTitleColor = if (isDark) Color(0xFFFFFFFF) else Color(0xFF0F172A)
	
	    // Calibrazione colori cornice interna per Tema Chiaro e Tema Scuro
	    val innerFrameBg = if (isDark) {
	        Color(0xFF383E4B) // Sfondo scuro del fondo 
	    } else {
	        Color(0xFFC8C8C8) // Sfondo chiaro pulito del fondo 
	    }
	
	    val innerFrameBorder = if (isDark) {
	        Color(0xFF425069) // Bordo definito e visibile in dark mode
	    } else {
	        Color(0xFFA3ABB5) // Bordo grigio chiaro pulito in light mode
	    }
	
	    // Colori EPG calibrati per essere leggibili sia su chiaro che su scuro
	    val currentProgColor = if (isDark) colorResource(id = R.color.text_neonciano) else Color(0xFF0284C7)
	    val nextProgColor = if (isDark) colorResource(id = R.color.text_pinkcyber) else Color(0xFFC026D3)
	
	    Card(
	        modifier = Modifier
	            .fillMaxWidth()
	            .padding(horizontal = 0.dp, vertical = 2.dp) // 👈 CORRETTO: rimossa la virgola di troppo che c'era prima
	            .combinedClickable(
	                onClick = onPlayClick,
	                onLongClick = onLongClick
	            ),
	        shape = RoundedCornerShape(14.dp),
	        colors = CardDefaults.cardColors(
	            containerColor = MaterialTheme.colorScheme.surface,
	            contentColor = MaterialTheme.colorScheme.onSurface
	        ),
            // 👈 Usiamo la variabile personalizzata per il bordo esterno
	        border = BorderStroke(
	            width = 1.dp,
	            color = externalBorderColor
	        ),
	        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
	    ) {
	        // CORNICE INTERNA UNICA: ingloba Logo, Nome Canale, Cuore e Testi EPG
	        Surface(
	            modifier = Modifier
	                .fillMaxWidth()
	                .padding(5.dp),
	            shape = RoundedCornerShape(10.dp),
	            color = innerFrameBg,
	            border = BorderStroke(1.dp, innerFrameBorder)
	        ) {
	            Row(
	                modifier = Modifier
	                    .fillMaxWidth()
	                    .padding(horizontal = 8.dp, vertical = 4.dp),
	                verticalAlignment = Alignment.CenterVertically
	            ) {
	                // 1. LOGO A SINISTRA
	                if (!channel.logoUrl.isNullOrBlank()) {  
                        ChannelLogo(
                            logoUrl = channel.logoUrl,
                            channelName = channel.name,
                            logoSize = logoSize
                        )   
	                    Spacer(modifier = Modifier.width(10.dp))
	                }
	
	                // 2. BLOCCO DESTRO (Nome + Cuore in alto, EPG sotto)
	                Column(
	                    modifier = Modifier.weight(1f)
	                ) {
	                    // Riga superiore: Nome Canale + Cuore allineati
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
	
	                        // Cuore integrato nella riga del titolo
	                        if (isLoading) {
	                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
	                        } else {
	                            IconButton(
	                                onClick = onFavoriteToggle,
	                                modifier = Modifier.size(36.dp)
	                            ) {
	                                Icon(
	                                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
	                                    contentDescription = null,
	                                    tint = if (isFavorite) {
                                            Color(0xFFFF4081)
                                        } else {
                                            if (isDark) Color(0xFF94A3B8) else Color(0xFF64748B)
                                        },
	                                    modifier = Modifier.size(22.dp)
	                                )
	                            }
	                        }
	                    }
	
	                    Spacer(modifier = Modifier.height(4.dp))
	
	                    // Dimensioni dinamiche proporzionate
	                    val epgMainSize = (channelTextSize - 4f).coerceAtLeast(8f).sp
	                    val epgTimeSize = (channelTextSize - 5f).coerceAtLeast(8f).sp
	
	                    // Riga inferiore: EPG a tutta larghezza
	                    if (epgInfo != null && epgInfo.currentProgram.isNotBlank()) {
	                        // Programma in corso
	                        Row(
	                            verticalAlignment = Alignment.CenterVertically,
	                            modifier = Modifier.fillMaxWidth()
	                        ) {
	                            Icon(
	                                imageVector = Icons.Default.PlayArrow,
	                                contentDescription = null,
	                                modifier = Modifier.size(12.dp),
	                                tint = currentProgColor
	                            )
	                            Spacer(modifier = Modifier.width(4.dp))
	                            Text(
	                                text = epgInfo.currentProgram,
	                                style = MaterialTheme.typography.labelSmall.copy(
	                                    fontWeight = FontWeight.Bold,
	                                    fontSize = epgMainSize
	                                ),
	                                color = currentProgColor,
	                                maxLines = 1,
	                                overflow = TextOverflow.Ellipsis,
	                                modifier = Modifier.weight(1f)
	                            )
	                            if (epgInfo.timeRange.isNotBlank()) {
	                                Spacer(modifier = Modifier.width(6.dp))
	                                Text(
	                                    text = epgInfo.timeRange,
	                                    style = MaterialTheme.typography.labelSmall.copy(
	                                        fontWeight = FontWeight.SemiBold,
	                                        fontSize = epgTimeSize
	                                    ),
	                                    color = currentProgColor,
	                                    maxLines = 1
	                                )
	                            }
	                        }
	
	                        // Programma successivo
	                        if (epgInfo.nextProgram.isNotBlank()) {
	                            Spacer(modifier = Modifier.height(3.dp))
	                            Row(
	                                verticalAlignment = Alignment.CenterVertically,
	                                modifier = Modifier.fillMaxWidth()
	                            ) {
	                                Icon(
	                                    imageVector = Icons.Default.FastForward,
	                                    contentDescription = null,
	                                    modifier = Modifier.size(12.dp),
	                                    tint = nextProgColor
	                                )
	                                Spacer(modifier = Modifier.width(4.dp))
	                                Text(
	                                    text = epgInfo.nextProgram,
	                                    style = MaterialTheme.typography.labelSmall.copy(
	                                        fontWeight = FontWeight.Bold,
	                                        fontSize = epgMainSize
	                                    ),
	                                    color = nextProgColor,
	                                    maxLines = 1,
	                                    overflow = TextOverflow.Ellipsis,
	                                    modifier = Modifier.weight(1f)
	                                )
	                                if (epgInfo.nextTimeRange.isNotBlank()) {
	                                    Spacer(modifier = Modifier.width(6.dp))
	                                    Text(
	                                        text = epgInfo.nextTimeRange,
	                                        style = MaterialTheme.typography.labelSmall.copy(
	                                            fontWeight = FontWeight.SemiBold,
	                                            fontSize = epgTimeSize
	                                        ),
	                                        color = nextProgColor,
	                                        maxLines = 1
	                                    )
	                                }
	                            }
	                        }
	                    } else {
	                        Text(
	                            text = channel.group.ifBlank { "Altro" },
	                            style = MaterialTheme.typography.bodySmall.copy(fontSize = epgMainSize),
	                            color = MaterialTheme.colorScheme.onSurfaceVariant,
	                            maxLines = 1,
	                            overflow = TextOverflow.Ellipsis
	                        )
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
                .border(width = 1.dp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), shape = RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = onPlayClick,
                    onLongClick = onLongClick
                ),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Avvia",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                            val progKey = "${prog.title}_${prog.startTime}"
                            val isExpanded = expandedProgramKey == progKey

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        color = if (isCurrent) 
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) 
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
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
                                            text = prog.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = epgTextSize.sp),
                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
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
	private fun ChannelLogo(
	    logoUrl: String?,
	    channelName: String,
	    logoSize: Int,
	    modifier: Modifier = Modifier
	) {
	    val isDark = isSystemInDarkTheme()
	    
	    val containerBgDark = 0xFFB2B4B4
	    val containerBgLight = 0xFF9299A2
	    
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
