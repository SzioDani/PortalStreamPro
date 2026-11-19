package com.portalstream.app.ui.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalstream.app.R
import com.portalstream.app.data.Portal
import com.portalstream.app.data.PortalStore
import com.portalstream.app.data.PortalType
import com.portalstream.app.data.epg.EpgMappingStore
import com.portalstream.app.data.local.AppDatabase
import com.portalstream.app.network.StalkerClient
import com.portalstream.app.streaming.M3UParser
import com.portalstream.app.streaming.TorrentStreamEngine
import com.portalstream.app.ui.channels.ChannelsActivity
import com.portalstream.app.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PortalsActivity : ComponentActivity() {
    private lateinit var store: PortalStore
    private lateinit var epgMappingStore: EpgMappingStore

    // Stato condiviso per passare il file selezionato tramite "Sfoglia file" al dialogo
    private var activeDialogType: PortalType? = null
    private var pickedFileUrlState by mutableStateOf<String?>(null)
    private var pickedFileNameState by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.portalstream.app.utils.ExitReasonLogger.logLastExits(applicationContext)
        store = PortalStore(this)
        epgMappingStore = EpgMappingStore(this)
        setContentView(ComposeView(this).apply {
            setContent {
                val prefs = remember { getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
                // true = Deep Blue Notturno ("dark"), false = Slate Antracite ("slate")
                var isDeepBlue by remember {
                    mutableStateOf((prefs.getString("app_theme", "dark") ?: "dark") != "slate")
                }

                // TEMA SCURO 1: Deep Blue Notturno (principale)
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

                // TEMA SCURO 2: Slate Antracite (secondo tema scuro)
                val customSlateScheme = darkColorScheme(
                    primary = colorResource(id = R.color.primary),
                    secondary = colorResource(id = R.color.secondary),
                    background = Color(0xFF0B0F17),
                    surface = Color(0xFF111827),
                    onBackground = Color(0xFFF9FAFB),
                    onSurface = Color(0xFFF9FAFB),
                    surfaceVariant = Color(0xFF1F2937),
                    outline = Color(0xFF374151)
                )

                MaterialTheme(
                    colorScheme = if (isDeepBlue) customDeepBlueScheme else customSlateScheme
                ) {
                    PortalsScreen(
                        isDeepBlue = isDeepBlue,
                        onThemeChanged = { deepBlueSelected ->
                            isDeepBlue = deepBlueSelected
                            prefs.edit()
                                .putString("app_theme", if (deepBlueSelected) "dark" else "slate")
                                .putBoolean("dark_mode", true)
                                .apply()
                        }
                    )
                }
            }
        })
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun PortalsScreen(
        isDeepBlue: Boolean,
        onThemeChanged: (Boolean) -> Unit
    ) {
        var portals by remember { mutableStateOf(store.load()) }
        var showTypeChooser by remember { mutableStateOf(false) }
        var formType by remember { mutableStateOf<PortalType?>(null) }
        var editing by remember { mutableStateOf<Portal?>(null) }
        var showSettings by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        // Palette coordinata sui due temi scuri (isDeepBlue vs Slate Antracite)
        val outerCardBg = if (isDeepBlue) Color(0xFF131B2E) else Color(0xFF111827)
        val innerFrameBg = if (isDeepBlue) Color(0xFF1B253B) else Color(0xFF1F2937)
        val cardBorderColor = if (isDeepBlue) Color(0xFF2E3F5C) else Color(0xFF374151)
        val subTextColor = if (isDeepBlue) Color(0xFF94A3B8) else Color(0xFF9CA3AF)
        val neonCyan = Color(0xFF00E5FF)

        LaunchedEffect(formType, editing) {
            activeDialogType = formType ?: editing?.type
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("PortalStream Pro", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = Color.White,
                        actionIconContentColor = neonCyan
                    ),
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Impostazioni"
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp)
            ) {
                Text(
                    text = "Portali salvati: ${portals.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = neonCyan
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showTypeChooser = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorResource(id = R.color.text_neongreen),
                        contentColor = colorResource(id = R.color.neutral_900)
                    )
                ) {
                    Text(
                        text = "AGGIUNGI PLAYLIST",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(12.dp))

                if (portals.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Nessun portale salvato.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = neonCyan
                        )
                        Text(
                            text = "Premi AGGIUNGI PLAYLIST per iniziare.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = neonCyan
                        )
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 600.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(portals, key = { it.id }) { portal ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable { openChannels(portal) },
                                colors = CardDefaults.cardColors(
                                    containerColor = outerCardBg,
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.5.dp, cardBorderColor),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                // Cornice interna in stile ChannelCard / StandardChannelCard
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    color = innerFrameBg,
                                    border = BorderStroke(1.dp, cardBorderColor)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "${portal.id}" +
                                                if (portal.name.isBlank()) "" else " ${portal.name}",
                                            fontSize = 15.sp,
                                            lineHeight = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = Color.White
                                        )
                                        Text(
                                            text = portal.type.label + " - " +
                                                if (portal.url.isBlank()) portal.server else portal.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            lineHeight = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = subTextColor
                                        )
                                        if (portal.expireDate.isNotBlank() || portal.activationDate.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(1.dp))
                                            Column(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalArrangement = Arrangement.spacedBy(0.dp)
                                            ) {
                                                if (portal.activationDate.isNotBlank()) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Default.CheckCircle,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(12.dp),
                                                            tint = colorResource(id = R.color.text_neongreen)
                                                        )
                                                        Spacer(modifier = Modifier.width(5.dp))
                                                        Text(
                                                            text = "Attivato: ${formatDateToItalian(portal.activationDate)}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            lineHeight = 14.sp,
                                                            color = Color.White,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }
                                                }
                                                if (portal.expireDate.isNotBlank()) {
                                                    val formattedExpire = formatDateToItalian(portal.expireDate)
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(
                                                            imageVector = Icons.Default.Schedule,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(12.dp),
                                                            tint = colorResource(id = R.color.text_neonred)
                                                        )
                                                        Spacer(modifier = Modifier.width(5.dp))
                                                        Text(
                                                            text = "Scadenza: $formattedExpire",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            lineHeight = 14.sp,
                                                            color = Color.White,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Row(modifier = Modifier.padding(top = 0.dp)) {
                                            TextButton(
                                                onClick = { editing = portal },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                modifier = Modifier.height(22.dp),
                                                colors = ButtonDefaults.textButtonColors(
                                                    contentColor = neonCyan
                                                )
                                            ) {
                                                Text("Modifica", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            TextButton(onClick = {
                                                val idToDelete = portal.id
                                                store.delete(idToDelete)
                                                portals = store.load()
                                                scope.launch(Dispatchers.IO) {
                                                    epgMappingStore.deletePortalMappings(idToDelete)
                                                    val dao = AppDatabase.getDatabase(this@PortalsActivity).channelDao()
                                                    dao.deleteChannelsByPlaylist(idToDelete)
                                                    dao.deleteChannelsByPrefix("ch_${idToDelete}_")
                                                }
                                            },
                                              contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                              modifier = Modifier.height(22.dp)
                                            ) {   
                                                Text(
                                                    text = "Elimina",
                                                    fontSize = 13.sp,
                                                    color = Color(0xFFEF4444),
                                                    fontWeight = FontWeight.SemiBold
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
        if (showTypeChooser) {
            AddPortalTypeDialog(
                onSelect = { type ->
                    formType = type
                    showTypeChooser = false
                },
                onDismiss = { showTypeChooser = false }
            )
        }
        formType?.let { type ->
            PortalFormDialog(
                existing = null,
                type = type,
                onDismiss = { formType = null },
                onSave = { draft ->
                    val detected = PortalType.detect(draft.url)
                    val finalType = if (type == PortalType.TORRENT) PortalType.TORRENT
                    else if (detected != PortalType.UNKNOWN) detected else type
                    val portalToSave = draft.copy(type = finalType)
                    scope.launch(Dispatchers.IO) {
                        try {
                            val isStalker = finalType == PortalType.MAG || portalToSave.macAddress.isNotBlank()
                            var finalPortal = portalToSave

                            if (isStalker) {
                                val profile = StalkerClient.fetchAccountInfo(portalToSave)
                                finalPortal = portalToSave.copy(
                                    expireDate = if (profile.expireDate.isNotBlank()) profile.expireDate else "Illimitato",
                                    activationDate = if (profile.activationDate.isNotBlank()) profile.activationDate else SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(Date())
                                )
                            }
                            store.add(finalPortal)
                            withContext(Dispatchers.Main) {
                                portals = store.load()
                                formType = null
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Errore salvataggio info account")
                            store.add(portalToSave.copy(expireDate = if (finalType == PortalType.TORRENT) "" else "Non disponibile"))
                            withContext(Dispatchers.Main) {
                                portals = store.load()
                                formType = null
                            }
                        }
                    }
                },
                onPickFile = { pickLocalFile() },
                pickedFileUrl = pickedFileUrlState,
                pickedFileName = pickedFileNameState,
                onPickedFileConsumed = {
                    pickedFileUrlState = null
                    pickedFileNameState = null
                }
            )
        }
        editing?.let { portal ->
            PortalFormDialog(
                existing = portal,
                type = portal.type,
                onDismiss = { editing = null },
                onSave = { updated ->
                    val detected = PortalType.detect(updated.url)
                    val finalType = if (updated.type == PortalType.TORRENT) PortalType.TORRENT
                    else if (detected != PortalType.UNKNOWN) detected else updated.type
                    val portalToUpdate = updated.copy(type = finalType)
                    scope.launch(Dispatchers.IO) {
                        try {
                            val isStalker = finalType == PortalType.MAG || portalToUpdate.macAddress.isNotBlank()
                            var finalPortal = portalToUpdate

                            if (isStalker) {
                                val profile = StalkerClient.fetchAccountInfo(portalToUpdate)
                                finalPortal = portalToUpdate.copy(
                                    expireDate = if (profile.expireDate.isNotBlank()) profile.expireDate else portal.expireDate,
                                    activationDate = if (profile.activationDate.isNotBlank()) profile.activationDate else portal.activationDate
                                )
                            }
                            val credentialsChanged = portal.macAddress.trim() != finalPortal.macAddress.trim() ||
                                portal.url.trim() != finalPortal.url.trim() ||
                                portal.server.trim() != finalPortal.server.trim() ||
                                portal.username.trim() != finalPortal.username.trim() ||
                                portal.password != finalPortal.password

                            if (credentialsChanged) {
                                epgMappingStore.deletePortalMappings(finalPortal.id)
                                val dao = AppDatabase.getDatabase(this@PortalsActivity).channelDao()
                                dao.deleteChannelsByPlaylist(finalPortal.id)
                                dao.deleteChannelsByPrefix("ch_${finalPortal.id}_")
                            }
                            store.update(finalPortal)
                            withContext(Dispatchers.Main) {
                                portals = store.load()
                                editing = null
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Errore aggiornamento info account")
                            store.update(portalToUpdate)
                            withContext(Dispatchers.Main) {
                                portals = store.load()
                                editing = null
                            }
                        }
                    }
                },
                onPickFile = { pickLocalFile() },
                pickedFileUrl = pickedFileUrlState,
                pickedFileName = pickedFileNameState,
                onPickedFileConsumed = {
                    pickedFileUrlState = null
                    pickedFileNameState = null
                }
            )
        }
        if (showSettings) {
            SettingsBottomSheet(
                context = this,
                isDeepBlue = isDeepBlue,
                onDismiss = { showSettings = false },
                onThemeChanged = onThemeChanged
            )
        }
    }

    private fun openChannels(portal: Portal) {
        // Se è un TORRENT (o contiene un link magnet/infohash/.torrent), avvia subito la riproduzione!
        if (portal.type == PortalType.TORRENT || TorrentStreamEngine.isTorrentSource(portal.url)) {
            startActivity(
                Intent(this, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_STREAM_URL, portal.url)
                    putExtra(
                        PlayerActivity.EXTRA_CHANNEL_NAME,
                        portal.name.ifBlank { "Torrent ${portal.id}" }
                    )
                }
            )
            return
        }

        val ua = if (portal.useCustomUserAgent) portal.userAgent else null
        startActivity(
            Intent(this, ChannelsActivity::class.java).apply {
                putExtra(ChannelsActivity.EXTRA_PORTAL_ID, portal.id)
                putExtra(ChannelsActivity.EXTRA_TITLE, portal.name.ifBlank { "Portale ${portal.id}" })
                putExtra(ChannelsActivity.EXTRA_URL, portal.url)
                putExtra(ChannelsActivity.EXTRA_USER_AGENT, ua)
                putExtra(ChannelsActivity.EXTRA_PORTAL_TYPE, portal.type.name)
                putExtra(ChannelsActivity.EXTRA_SERVER, portal.server)
                putExtra(ChannelsActivity.EXTRA_USERNAME, portal.username)
                putExtra(ChannelsActivity.EXTRA_PASSWORD, portal.password)
                putExtra(ChannelsActivity.EXTRA_FORMAT, portal.streamFormat)
                putExtra(ChannelsActivity.EXTRA_MAC, portal.macAddress)
                putExtra(ChannelsActivity.EXTRA_EXPIRE_DATE, portal.expireDate)
            }
        )
    }

    private fun pickLocalFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_PICK_FILE)
    }

    @Deprecated("Usato per il picker file senza activity-compose")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val fileName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "file"

                    // Gestione selezione file per TORRENT (file .torrent con dentro magnet o binario)
                    if (activeDialogType == PortalType.TORRENT || fileName.endsWith(".torrent", ignoreCase = true)) {
                        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes == null || bytes.isEmpty()) {
                            Toast.makeText(this, "File .torrent vuoto o illeggibile", Toast.LENGTH_SHORT).show()
                            return
                        }

                        val cleanDisplayName = fileName.removeSuffix(".torrent").removeSuffix(".TORRENT")
                        val extractedMagnet = TorrentStreamEngine.extractMagnetOrHashFromBytes(bytes)

                        if (extractedMagnet != null) {
                            // Il file .torrent conteneva un magnet o un infohash testuale: lo inseriamo nel campo!
                            pickedFileNameState = cleanDisplayName
                            pickedFileUrlState = extractedMagnet
                        } else {
                            // È un file .torrent binario standard: lo salviamo nella cartella interna dell'app
                            val torrentsDir = File(getExternalFilesDir(null) ?: filesDir, "torrents").apply {
                                if (!exists()) mkdirs()
                            }
                            val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                .let { if (it.endsWith(".torrent", true)) it else "$it.torrent" }
                            val destFile = File(torrentsDir, "${System.currentTimeMillis()}_$safeName")
                            destFile.writeBytes(bytes)

                            pickedFileNameState = cleanDisplayName
                            pickedFileUrlState = destFile.absolutePath
                        }
                        return
                    }

                    // Gestione originale per file M3U locali
                    contentResolver.openInputStream(uri)?.let { inputStream ->
                        val channels = mutableListOf<com.portalstream.app.domain.model.Channel>()
                        M3UParser.parseStreaming(inputStream) { channels.add(it) }
                        if (channels.isNotEmpty()) {
                            ChannelCache.hold(channels)
                            startActivity(
                                Intent(this, ChannelsActivity::class.java).apply {
                                    putExtra(
                                        ChannelsActivity.EXTRA_TITLE,
                                        uri.lastPathSegment ?: "Playlist locale"
                                    )
                                }
                            )
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Lettura file locale fallita")
                    Toast.makeText(this, "Errore lettura file: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val REQ_PICK_FILE = 1001
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    context: Context,
    isDeepBlue: Boolean,
    onDismiss: () -> Unit,
    onThemeChanged: (Boolean) -> Unit
) {
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    var selectedDeepBlue by remember(isDeepBlue) { mutableStateOf(isDeepBlue) }
    var playerMode by remember { mutableStateOf(prefs.getString("player_mode", "miniplayer") ?: "miniplayer") }

    val neonCyan = Color(0xFF00E5FF)
    val sheetBg = if (selectedDeepBlue) Color(0xFF111827) else Color(0xFF0F1722)
    val cardBg = if (selectedDeepBlue) Color(0xFF1B253B) else Color(0xFF1F2937)
    val cardBorder = if (selectedDeepBlue) Color(0xFF2E3F5C) else Color(0xFF374151)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = sheetBg,
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = neonCyan
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Impostazioni Generali",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            HorizontalDivider(color = cardBorder)

            // Scelta tra i due temi scuri (Deep Blue Notturno vs Slate Antracite)
            Text(
                text = "Stile Tema Scuro",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    onClick = {
                        selectedDeepBlue = true
                        onThemeChanged(true)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = if (selectedDeepBlue) neonCyan.copy(alpha = 0.2f) else cardBg,
                    border = BorderStroke(1.5.dp, if (selectedDeepBlue) neonCyan else cardBorder)
                ) {
                    Text(
                        text = "Deep Blue",
                        color = if (selectedDeepBlue) Color.White else Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                    )
                }

                Surface(
                    onClick = {
                        selectedDeepBlue = false
                        onThemeChanged(false)
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    color = if (!selectedDeepBlue) neonCyan.copy(alpha = 0.2f) else cardBg,
                    border = BorderStroke(1.5.dp, if (!selectedDeepBlue) neonCyan else cardBorder)
                ) {
                    Text(
                        text = "Slate Antracite",
                        color = if (!selectedDeepBlue) Color.White else Color(0xFF94A3B8),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .wrapContentWidth(Alignment.CenterHorizontally)
                    )
                }
            }

            HorizontalDivider(color = cardBorder)

            Text(
                text = "Modalità Video Ridotto",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            playerMode = "miniplayer"
                            prefs.edit().putString("player_mode", "miniplayer").apply()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (playerMode == "miniplayer") neonCyan else cardBorder
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (playerMode == "miniplayer") neonCyan.copy(alpha = 0.22f) else cardBg,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Set Miniplayer", fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = {
                            playerMode = "pip"
                            prefs.edit().putString("player_mode", "pip").apply()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            1.dp,
                            if (playerMode == "pip") neonCyan else cardBorder
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (playerMode == "pip") neonCyan.copy(alpha = 0.22f) else cardBg,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Set PiP", fontWeight = FontWeight.Bold)
                    }
                }
                Button(
                    onClick = {
                        playerMode = "both"
                        prefs.edit().putString("player_mode", "both").apply()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (playerMode == "both") neonCyan else cardBorder
                    ),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (playerMode == "both") neonCyan.copy(alpha = 0.22f) else cardBg,
                        contentColor = Color.White
                    )
                ) {
                    Text("Set Miniplayer + PiP", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

object ChannelCache {
    private var channels: List<com.portalstream.app.domain.model.Channel> = emptyList()
    fun hold(list: List<com.portalstream.app.domain.model.Channel>) {
        channels = list
    }
    fun take(): List<com.portalstream.app.domain.model.Channel> {
        val result = channels
        channels = emptyList()
        return result
    }
}

private fun formatDateToItalian(dateStr: String): String {
    if (dateStr.isBlank()) return ""
    if (dateStr.contains("Illimitato", ignoreCase = true) || dateStr.contains("Unlimited", ignoreCase = true)) {
        return "Non definita / Illimitata"
    }
    return try {
        val parts = dateStr.trim().split(" ")
        val dateParts = parts[0].split("-")
        if (dateParts.size == 3) {
            val year = dateParts[0]
            val month = dateParts[1]
            val day = dateParts[2]
            val formattedDate = "$day/$month/$year"
            if (parts.size > 1) {
                val timeParts = parts[1].split(":")
                val time = if (timeParts.size >= 2) "${timeParts[0]}:${timeParts[1]}" else parts[1]
                "$formattedDate $time"
            } else {
                formattedDate
            }
        } else {
            dateStr
        }
    } catch (e: Exception) {
        dateStr
    }
}
                                
