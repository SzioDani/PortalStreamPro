package com.portalstream.app.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalstream.app.data.Portal
import com.portalstream.app.data.PortalStore
import com.portalstream.app.data.PortalType
import com.portalstream.app.ui.channels.ChannelsActivity
import timber.log.Timber

class PortalsActivity : ComponentActivity() {

    private lateinit var store: PortalStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PortalStore(this)

        setContent {
            MaterialTheme {
                PortalsScreen()
            }
        }
    }

    @Composable
    fun PortalsScreen() {
        var portals by remember { mutableStateOf(store.load()) }
        var showTypeChooser by remember { mutableStateOf(false) }
        var formType by remember { mutableStateOf<PortalType?>(null) }
        var editing by remember { mutableStateOf<Portal?>(null) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                "PortalStream Pro",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "${portals.size} portali salvati",
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
                        "Nessun portale salvato.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "Premi AGGIUNGI PLAYLIST per iniziare.",
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
                                    text = "[${portal.id}] " + portal.name.ifBlank { "Portale ${portal.id}" },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                val displayAddress = portal.server.ifBlank { portal.url }
                                Text(
                                    text = "${portal.type.label} - $displayAddress",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row {
                                    TextButton(onClick = { editing = portal }) {
                                        Text("Modifica")
                                    }

                                    Spacer(Modifier.width(4.dp))

                                    TextButton(
                                        onClick = {
                                            store.delete(portal.id)
                                            portals = store.load()
                                        }
                                    ) {
                                        Text(
                                            "Elimina",
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
                    // Mantiene il tipo esplicito scelto dall'utente se non è UNKNOWN
                    val finalType = if (type != PortalType.UNKNOWN) type else PortalType.detect(draft.url)

                    store.add(draft.copy(type = finalType))
                    portals = store.load()
                    formType = null
                },
                onPickFile = { pickLocalFile() }
            )
        }

        editing?.let { portal ->
            PortalFormDialog(
                existing = portal,
                type = portal.type,
                onDismiss = { editing = null },
                onSave = { updated ->
                    val finalType = if (portal.type != PortalType.UNKNOWN) portal.type else PortalType.detect(updated.url)

                    store.update(updated.copy(type = finalType))
                    portals = store.load()
                    editing = null
                },
                onPickFile = { pickLocalFile() }
            )
        }
    }

    private fun openChannels(portal: Portal) {
        val ua = if (portal.useCustomUserAgent) portal.userAgent else null
        val effectiveServer = portal.server.ifBlank { portal.url }

        startActivity(
            Intent(this, ChannelsActivity::class.java).apply {
                putExtra(ChannelsActivity.EXTRA_TITLE, portal.name.ifBlank { "Portale ${portal.id}" })
                putExtra(ChannelsActivity.EXTRA_PORTAL_TYPE, portal.type.name)
                putExtra(ChannelsActivity.EXTRA_SERVER, effectiveServer)
                putExtra(ChannelsActivity.EXTRA_MAC, portal.macAddress)
                putExtra(ChannelsActivity.EXTRA_USERNAME, portal.username)
                putExtra(ChannelsActivity.EXTRA_PASSWORD, portal.password)
                putExtra(ChannelsActivity.EXTRA_FORMAT, portal.streamFormat)
                putExtra(ChannelsActivity.EXTRA_URL, portal.url.ifBlank { effectiveServer })
                putExtra(ChannelsActivity.EXTRA_USER_AGENT, ua)
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
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val content = contentResolver.openInputStream(uri)
                        ?.bufferedReader()
                        ?.readText()
                        ?: return@let

                    val channels = mutableListOf<com.portalstream.app.domain.model.Channel>()

                    var currentName = ""
                    var currentGroup = ""
                    var currentLogo = ""

                    content.lines().forEach { line ->
                        if (line.startsWith("#EXTINF:")) {
                            currentName = line.substringAfterLast(",").trim()
                            currentGroup = Regex("group-title=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: ""
                            currentLogo = Regex("tvg-logo=\"([^\"]+)\"").find(line)?.groupValues?.get(1) ?: ""
                        } else if (line.isNotBlank() && !line.startsWith("#")) {
                            channels.add(
                                com.portalstream.app.domain.model.Channel(
                                    id = line.hashCode().toString(),
                                    name = currentName,
                                    url = line.trim(),
                                    group = currentGroup,
                                    logoUrl = currentLogo,
                                    epgId = null
                                )
                            )

                            currentName = ""
                            currentGroup = ""
                            currentLogo = ""
                        }
                    }

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
