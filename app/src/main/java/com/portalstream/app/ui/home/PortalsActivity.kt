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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

                                    // 🚀 Mostriamo Attivazione e Scadenza incolonnate in formato Italiano (GG/MM/AAAA)
                                    if (portal.expireDate.isNotBlank() || portal.activationDate.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            if (portal.activationDate.isNotBlank()) {
                                                Text(
                                                    text = "🟢 Attivato: ${formatDateToItalian(portal.activationDate)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            if (portal.expireDate.isNotBlank()) {
                                                val formattedExpire = formatDateToItalian(portal.expireDate)
                                                Text(
                                                    text = "⏳ Scadenza: $formattedExpire",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (formattedExpire.contains("Illimitata", ignoreCase = true)) 
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

        // Salvataggio NUOVO portale con associazione corretta Attivazione e Scadenza
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
                            Timber.e(e, "Errore salvataggio info account, procedo comunque col salvataggio base")
                            store.add(portalToSave.copy(expireDate = "Non disponibile"))
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

        // Salvataggio MODIFICA portale esistente con associazione corretta Attivazione e Scadenza
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
                            val isStalker = finalType == PortalType.MAG || portalToUpdate.macAddress.isNotBlank()

                            var finalPortal = portalToUpdate

                            if (isStalker) {
                                val profile = StalkerClient.fetchAccountInfo(portalToUpdate)
                                finalPortal = portalToUpdate.copy(
                                    expireDate = if (profile.expireDate.isNotBlank()) profile.expireDate else portal.expireDate,
                                    activationDate = if (profile.activationDate.isNotBlank()) profile.activationDate else portal.activationDate
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
}

// 🟢 Funzione helper per formattare la data nel formato italiano GG/MM/AAAA
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

@Composable
fun AddPortalTypeDialog(
    onSelect: (PortalType) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi playlist") },
        text = {
            Column {
                TextButton(
                    onClick = { onSelect(PortalType.M3U) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("FILE O LINK PLAYLIST")
                }
                TextButton(
                    onClick = { onSelect(PortalType.MAG) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("MAG PORTALE")
                }
                TextButton(
                    onClick = { onSelect(PortalType.XSTREAM) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("XTREAM CODES PORTAL")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
    )
}

@Composable
fun ChannelDetailsDialog(
    channel: com.portalstream.app.domain.model.Channel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "ℹ️ Dettagli Tecnici Canale",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "• ID Canale: ${channel.id}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "• Numero: ${channel.number}", style = MaterialTheme.typography.bodyMedium)
                Text(text = "• Nome Grezzo: ${channel.name}", style = MaterialTheme.typography.bodyMedium)
                if (!channel.customSid.isNullOrBlank()) {
                    Text(text = "• Custom SID: ${channel.customSid}", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    text = "• CMD / Stream:\n${channel.cmd}", 
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(channel.cmd))
                    Toast.makeText(context, "CMD copiato negli appunti!", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("Copia CMD")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Chiudi")
            }
        }
    )
}

private data class UaPreset(val label: String, val value: String)

private val UA_PRESETS = listOf(
    UaPreset(
        "MAG200",
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG200 stbapp ver: 4.3.1939"
    ),
    UaPreset(
        "MAG250",
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG250 stbapp ver: 4.3.1939"
    ),
    UaPreset(
        "MAG322",
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG322 stbapp ver: 4.3.1939"
    ),
    UaPreset(
        "Browser generico",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    )
)

@Composable
fun PortalFormDialog(
    existing: Portal?,
    type: PortalType,
    onDismiss: () -> Unit,
    onSave: (Portal) -> Unit,
    onPickFile: () -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var mac by remember { mutableStateOf(existing?.macAddress ?: "") }
    var server by remember { mutableStateOf(existing?.server ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var streamFormat by remember { mutableStateOf(existing?.streamFormat ?: "m3u8") }
    var forceLink by remember { mutableStateOf(existing?.forceStreamLink ?: false) }
    var useVpn by remember { mutableStateOf(existing?.useVpn ?: false) }
    var profile by remember { mutableStateOf(existing?.profile ?: "") }
    var useCustomUa by remember { mutableStateOf(existing?.useCustomUserAgent ?: false) }
    var userAgent by remember { mutableStateOf(existing?.userAgent ?: "") }
    var uaMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val title = when (type) {
        PortalType.M3U -> "File o Link Playlist"
        PortalType.MAG -> "MAG Portale"
        PortalType.XSTREAM -> "Xtream Codes Portal"
        else -> "Portale"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome Playlist (opzionale)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))

                when (type) {
                    PortalType.M3U -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("Link della playlist") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 13.sp),
                            maxLines = 2
                        )
                        Spacer(Modifier.height(4.dp))
                        Row {
                            OutlinedButton(onClick = {
                                clipboard.getText()?.text?.let { url = it }
                            }) {
                                Text("Incolla")
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = onPickFile) {
                                Text("Sfoglia file")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    PortalType.MAG -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("Link Portale MAG") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 13.sp),
                            maxLines = 2
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = mac,
                            onValueChange = { mac = it },
                            label = { Text("MAC Address") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    PortalType.XSTREAM -> {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text("Server:porta") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Utente") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation =
                                if (showPassword) VisualTransformation.None
                                else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showPassword = !showPassword }) {
                                    Text(if (showPassword) "Nascondi" else "Mostra")
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Formato Streams",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            OutlinedButton(
                                onClick = { streamFormat = "ts" },
                                colors = if (streamFormat == "ts")
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("TS")
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { streamFormat = "m3u8" },
                                colors = if (streamFormat == "m3u8")
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("M3U8")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = forceLink, onCheckedChange = { forceLink = it })
                            Text("Forza utilizzo Link", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    else -> {}
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useVpn, onCheckedChange = { useVpn = it })
                    Text("Usa VPN", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = profile,
                    onValueChange = { profile = it },
                    label = { Text("Profilo") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useCustomUa, onCheckedChange = { useCustomUa = it })
                    Text("Usa User Agent", style = MaterialTheme.typography.bodyMedium)
                }
                if (useCustomUa) {
                    OutlinedTextField(
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        label = { Text("Inserisci o seleziona User-Agent") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 12.sp),
                        maxLines = 2
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { uaMenu = true }) {
                        Text("Seleziona preset")
                    }
                    DropdownMenu(expanded = uaMenu, onDismissRequest = { uaMenu = false }) {
                        UA_PRESETS.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.label) },
                                onClick = {
                                    userAgent = preset.value
                                    uaMenu = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val base = existing ?: Portal(id = 0)
                onSave(
                    base.copy(
                        name = name.trim(),
                        type = type,
                        url = url.trim(),
                        macAddress = mac.trim(),
                        server = server.trim(),
                        username = username.trim(),
                        password = password,
                        streamFormat = streamFormat,
                        forceStreamLink = forceLink,
                        useVpn = useVpn,
                        profile = profile.trim(),
                        useCustomUserAgent = useCustomUa,
                        userAgent = userAgent.trim()
                    )
                )
            }) {
                Text(if (existing == null) "Salva" else "OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
    )
}
