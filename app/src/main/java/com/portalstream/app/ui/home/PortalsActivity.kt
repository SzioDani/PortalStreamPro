package com.portalstream.app.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.portalstream.app.data.Portal
import com.portalstream.app.data.PortalStore
import com.portalstream.app.data.PortalType
import com.portalstream.app.streaming.M3UParser
import com.portalstream.app.ui.channels.ChannelsActivity
import timber.log.Timber

class PortalsActivity : ComponentActivity() {

    private lateinit var store: PortalStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PortalStore(this)
        setContentView(ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    PortalsScreen()
                }
            }
        })
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
            Text("PortalStream Pro", style = MaterialTheme.typography.headlineSmall)
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
                                // FIX: senza nome mostra SOLO il numero, mai l'URL
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
                    val finalType = if (detected != PortalType.UNKNOWN) detected else type
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
                    val detected = PortalType.detect(updated.url)
                    val finalType = if (detected != PortalType.UNKNOWN) detected else updated.type
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
        startActivity(
            Intent(this, ChannelsActivity::class.java).apply {
                putExtra(ChannelsActivity.EXTRA_TITLE, portal.name.ifBlank { "Portale ${portal.id}" })
                putExtra(ChannelsActivity.EXTRA_URL, portal.url)
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
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK_FILE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val content = contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                        ?: return@let
                    val channels = M3UParser.parse(content)
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

// Cache per passare la lista canali senza superare i limiti degli intent
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
