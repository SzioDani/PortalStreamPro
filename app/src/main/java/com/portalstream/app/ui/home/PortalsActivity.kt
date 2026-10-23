package com.portalstream.app.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalstream.app.data.Portal
import com.portalstream.app.data.PortalStore
import com.portalstream.app.data.PortalType
import com.portalstream.app.network.StalkerClient
import com.portalstream.app.streaming.M3UParser
import com.portalstream.app.ui.channels.ChannelsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class PortalsActivity : ComponentActivity() {

    private lateinit var store: PortalStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PortalStore(this)
        setContentView(ComposeView(this).apply {
            setContent {
                val prefs = remember { getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
                var isDarkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }

                MaterialTheme(
                    colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()
                ) {
                    PortalsScreen(
                        onThemeChanged = { darkMode ->
                            isDarkMode = darkMode
                        }
                    )
                }
            }
        })
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun PortalsScreen(onThemeChanged: (Boolean) -> Unit) {
        var portals by remember { mutableStateOf(store.load()) }
        var showTypeChooser by remember { mutableStateOf(false) }
        var formType by remember { mutableStateOf<PortalType?>(null) }
        var editing by remember { mutableStateOf<Portal?>(null) }
        var showSettings by remember { mutableStateOf(false) }
        
        val scope = rememberCoroutineScope()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("PortalStream Pro") },
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
                    text = "portali salvati: ${portals.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = { showTypeChooser = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("AGGIUNGI PLAYLIST")
                }
                Spacer(Modifier.height(12.dp))

                if (portals.isEmpty()) {
                    Column {
                        Text(
                            text = "Nessun portale salvato.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Premi AGGIUNGI PLAYLIST per iniziare.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn {
                        items(portals, key = { it.id }) { portal ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { openChannels(portal) }
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        text = "[${portal.id}]" +
                                            if (portal.name.isBlank()) "" else " ${portal.name}",
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = portal.type.label + " - " +
                                            if (portal.url.isBlank()) portal.server else portal.url,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    // 🚀 Mostriamo Attivazione e Scadenza se presenti
                                    if (portal.expireDate.isNotBlank() || portal.activationDate.isNotBlank()) {
                                        Spacer(Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            if (portal.activationDate.isNotBlank()) {
                                                Text(
                                                    text = "🟢 Attivato: ${portal.activationDate}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            if (portal.expireDate.isNotBlank()) {
                                                Text(
                                                    text = "⏳ Scadenza: ${portal.expireDate}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (portal.expireDate.contains("Illimitato", ignoreCase = true)) 
                                                        MaterialTheme.colorScheme.primary 
                                                    else MaterialTheme.colorScheme.error
                                                )
                                            }
                                        }
                                    }

                                    Row {
                                        TextButton(onClick = { editing = portal }) {
                                            Text("Modifica")
                                        }
                                        Spacer(Modifier.width(4.dp))
                                        TextButton(onClick = {
                                            store.delete(portal.id)
                                            portals = store.load()
                                        }) {
                                            Text(
                                                text = "Elimina",
                                                color = MaterialTheme.colorScheme.error
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

        if (showTypeChooser) {
            AddPortalTypeDialog(
                onSelect = { type ->
                    formType = type
                    showTypeChooser = false
                },
                onDismiss = { showTypeChooser = false }
            )
        }

        // Salvataggio NUOVO portale con associazione Attivazione e Scadenza
        formType?.let { type ->
            PortalFormDialog(
                existing = null,
                type = type,
                onDismiss = { formType = null },
                onSave = { draft ->
                    val detected = PortalType.detect(draft.url)
                    val finalType = if (detected != PortalType.UNKNOWN) detected else type
                    val portalToSave = draft.copy(type = finalType)

                    scope.launch(Dispatchers.IO) {
                        try {
                            val isStalker = finalType.name.equals("STALKER", true) || 
                                          finalType.name.equals("MAG", true) || 
                                          portalToSave.macAddress.isNotBlank()

                            var finalPortal = portalToSave

                            if (isStalker) {
                                val profile = StalkerClient.fetchAccountInfo(portalToSave)
                                finalPortal = portalToSave.copy(
                                    expireDate = profile.expireDate,
                                    activationDate = profile.activationDate
                                )
                            }

                            store.add(finalPortal)
                            withContext(Dispatchers.Main) {
                                portals = store.load()
                                formType = null
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Errore salvataggio info account")
                            store.add(portalToSave)
                            withContext(Dispatchers.Main) {
                                portals = store.load()
                                formType = null
                            }
                        }
                    }
                },
                onPickFile = { pickLocalFile() }
            )
        }

        // Salvataggio MODIFICA portale esistente con associazione Attivazione e Scadenza
        editing?.let { portal ->
            PortalFormDialog(
                existing = portal,
                type = portal.type,
                onDismiss = { editing = null },
                onSave = { updated ->
                    val detected = PortalType.detect(updated.url)
                    val finalType = if (detected != PortalType.UNKNOWN) detected else updated.type
                    val portalToUpdate = updated.copy(type = finalType)

                    scope.launch(Dispatchers.IO) {
                        try {
                            val isStalker = finalType.name.equals("STALKER", true) || 
                                          finalType.name.equals("MAG", true) || 
                                          portalToUpdate.macAddress.isNotBlank()

                            var finalPortal = portalToUpdate

                            if (isStalker) {
                                val profile = StalkerClient.fetchAccountInfo(portalToUpdate)
                                finalPortal = portalToUpdate.copy(
                                    expireDate = profile.expireDate,
                                    activationDate = profile.activationDate
                                )
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
                onPickFile = { pickLocalFile() }
            )
        }

        if (showSettings) {
            SettingsBottomSheet(
                context = this,
                onDismiss = { showSettings = false },
                onThemeChanged = onThemeChanged
            )
        }
    }

    private fun openChannels(portal: Portal) {
        val ua = if (portal.useCustomUserAgent) portal.userAgent else null
        startActivity(
            Intent(this, ChannelsActivity::class.java).apply {
                putExtra(ChannelsActivity.EXTRA_TITLE, portal.name.ifBlank { "Portale ${portal.id}" })
                putExtra(ChannelsActivity.EXTRA_URL, portal.url)
                putExtra(ChannelsActivity.EXTRA_USER_AGENT, ua)
                putExtra(ChannelsActivity.EXTRA_PORTAL_TYPE, portal.type.name)
                putExtra(ChannelsActivity.EXTRA_SERVER, portal.server)
                putExtra(ChannelsActivity.EXTRA_USERNAME, portal.username)
                putExtra(ChannelsActivity.EXTRA_PASSWORD, portal.password)
                putExtra(ChannelsActivity.EXTRA_FORMAT, portal.streamFormat)
                putExtra(ChannelsActivity.EXTRA_MAC, portal.macAddress)
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
                }
            }
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
    onDismiss: () -> Unit,
    onThemeChanged: (Boolean) -> Unit
) {
    val prefs = remember { context.getSharedPreferences("app_settings", Context.MODE_PRIVATE) }
    
    var isDarkMode by remember { mutableStateOf(prefs.getBoolean("dark_mode", true)) }
    var playerMode by remember { mutableStateOf(prefs.getString("player_mode", "miniplayer") ?: "miniplayer") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = "⚙️ Impostazioni Generali",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Tema Scuro", style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = isDarkMode,
                    onCheckedChange = { checked ->
                        isDarkMode = checked
                        prefs.edit().putBoolean("dark_mode", checked).apply()
                        onThemeChanged(checked)
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Text("Modalità Video Ridotto", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))

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
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (playerMode == "miniplayer") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (playerMode == "miniplayer") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Set Miniplayer")
                    }

                    Button(
                        onClick = {
                            playerMode = "pip"
                            prefs.edit().putString("player_mode", "pip").apply()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (playerMode == "pip") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (playerMode == "pip") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text("Set PiP")
                    }
                }

                Button(
                    onClick = {
                        playerMode = "both"
                        prefs.edit().putString("player_mode", "both").apply()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (playerMode == "both") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (playerMode == "both") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("Set Miniplayer + PiP")
                }
            }
            Spacer(modifier = Modifier.height(40.dp))
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
    private fun formatDateToItalian(dateStr: String): String {
        if (dateStr.isBlank()) return ""
        if (dateStr.contains("Illimitato", ignoreCase = true) || dateStr.contains("Unlimited", ignoreCase = true)) {
            return "Non definita / Illimitata"
        }
    
        return try {
        // Separa eventuale orario "YYYY-MM-DD HH:mm:ss" -> parti: "YYYY-MM-DD", "HH:mm:ss"
            val parts = dateStr.trim().split(" ")
            val dateParts = parts[0].split("-")
        
            if (dateParts.size == 3) {
                val year = dateParts[0]
                val month = dateParts[1]
                val day = dateParts[2]
            
                val formattedDate = "$day/$month/$year"
                if (parts.size > 1) {
                // Mantiene anche l'ora se presente (es. "18/02/2022 14:40")
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
}

