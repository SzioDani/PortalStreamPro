package com.portalstream.app.ui.home

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.portalstream.app.data.Portal
import com.portalstream.app.data.PortalType
import com.portalstream.app.data.local.AppDatabase
import com.portalstream.app.domain.model.Playlist
import com.portalstream.app.ui.channels.ChannelsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

class PortalsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = AppDatabase.getDatabase(this)
        val playlistDao = db.playlistDao()
        val channelDao = db.channelDao()

        setContent {
            MaterialTheme {
                val playlists by playlistDao.getAllPlaylists().collectAsState(initial = emptyList())
                var showTypeChooser by remember { mutableStateOf(false) }
                var formType by remember { mutableStateOf<PortalType?>(null) }
                var editing by remember { mutableStateOf<Portal?>(null) }
                var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text("PortalStream Pro", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "${playlists.size} portali salvati",
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

                    if (playlists.isEmpty()) {
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
                            items(playlists, key = { it.id }) { playlist ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clickable { openChannels(playlist) }
                                ) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            text = "[${playlist.id}] " + playlist.name.ifBlank { "Portale ${playlist.id}" },
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = playlist.url,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Row {
                                            TextButton(onClick = {
                                                editing = Portal(
                                                    id = playlist.id,
                                                    name = playlist.name,
                                                    url = playlist.url,
                                                    type = PortalType.detect(playlist.url)
                                                )
                                            }) {
                                                Text("Modifica")
                                            }
                                            Spacer(Modifier.width(4.dp))
                                            TextButton(onClick = { playlistToDelete = playlist }) {
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
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val newPlaylist = Playlist(
                                        name = draft.name,
                                        url = draft.url
                                    )
                                    playlistDao.insertPlaylist(newPlaylist)
                                    withContext(Dispatchers.Main) {
                                        formType = null
                                        Toast.makeText(this@PortalsActivity, "Portale salvato!", Toast.LENGTH_SHORT).show()
                                    }
                                }
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
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val updatedPlaylist = Playlist(
                                        id = updated.id,
                                        name = updated.name,
                                        url = updated.url
                                    )
                                    playlistDao.insertPlaylist(updatedPlaylist)
                                    withContext(Dispatchers.Main) {
                                        editing = null
                                        Toast.makeText(this@PortalsActivity, "Portale aggiornato!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            onPickFile = { pickLocalFile() }
                        )
                    }

                    playlistToDelete?.let { playlist ->
                        AlertDialog(
                            onDismissRequest = { playlistToDelete = null },
                            title = { Text("Elimina Portale") },
                            text = { Text("Vuoi eliminare '${playlist.name}' e tutti i suoi canali memorizzati?") },
                            confirmButton = {
                                TextButton(onClick = {
                                    lifecycleScope.launch(Dispatchers.IO) {
                                        channelDao.deleteChannelsByPlaylist(playlist.id)
                                        playlistDao.deletePlaylist(playlist)
                                        withContext(Dispatchers.Main) {
                                            playlistToDelete = null
                                            Toast.makeText(this@PortalsActivity, "Portale eliminato", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }) {
                                    Text("Elimina", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { playlistToDelete = null }) {
                                    Text("Annulla")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun openChannels(playlist: Playlist) {
        startActivity(
            Intent(this, ChannelsActivity::class.java).apply {
                putExtra(ChannelsActivity.EXTRA_TITLE, playlist.name.ifBlank { "Portale ${playlist.id}" })
                putExtra(ChannelsActivity.EXTRA_URL, playlist.url)
                putExtra("PLAYLIST_ID", playlist.id)
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
