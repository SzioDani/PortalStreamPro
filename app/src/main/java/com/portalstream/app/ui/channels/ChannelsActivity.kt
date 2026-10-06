package com.portalstream.app.ui.channels

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.portalstream.app.data.local.AppDatabase
import com.portalstream.app.domain.model.Channel
import com.portalstream.app.streaming.M3UParser
import com.portalstream.app.ui.player.PlayerActivity
import com.portalstream.app.ui.theme.PortalStreamTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class ChannelsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val portalUrl = intent.getStringExtra(EXTRA_URL) 
            ?: intent.getStringExtra("PORTAL_URL") 
            ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Canali"
        val playlistId = intent.getIntExtra("PLAYLIST_ID", 1)

        val db = AppDatabase.getDatabase(this)
        val channelDao = db.channelDao()

        setContent {
            PortalStreamTheme {
                // Recupera la lista di tutti i gruppi memorizzati nel DB
                val groups by channelDao.getGroups().collectAsState(initial = emptyList())
                var selectedGroup by remember { mutableStateOf<String?>(null) }
                var isLoading by remember { mutableStateOf(false) }

                // Se c'è un gruppo selezionato carica i suoi canali, altrimenti lista vuota
                val channels by if (selectedGroup != null) {
                    channelDao.getChannelsByGroup(selectedGroup!!).collectAsState(initial = emptyList())
                } else {
                    remember { mutableStateOf(emptyList()) }
                }

                // Carica la lista via rete solo se il DB non ha ancora gruppi memorizzati
                LaunchedEffect(portalUrl) {
                    if (groups.isEmpty() && portalUrl.isNotEmpty()) {
                        isLoading = true
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val inputStream = URL(portalUrl).openStream()
                                val tempBuffer = mutableListOf<Channel>()

                                M3UParser.parseStreaming(inputStream) { channel ->
                                    val channelWithPlaylist = channel.copy(playlistId = playlistId)
                                    tempBuffer.add(channelWithPlaylist)

                                    // Salva a blocchi di 100 canali
                                    if (tempBuffer.size >= 100) {
                                        val batch = tempBuffer.toList()
                                        tempBuffer.clear()
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            channelDao.insertChannels(batch)
                                        }
                                    }
                                }

                                if (tempBuffer.isNotEmpty()) {
                                    channelDao.insertChannels(tempBuffer)
                                }

                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    Toast.makeText(this@ChannelsActivity, "Errore caricamento lista", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                }

                // Se non è ancora selezionato nessun gruppo, seleziona automaticamente il primo disponibile
                LaunchedEffect(groups) {
                    if (groups.isNotEmpty() && selectedGroup == null) {
                        selectedGroup = groups.first()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (isLoading && groups.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                        ) {
                            // Titolo Portale / Playlist
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            // Selettore orizzontale dei Gruppi / Categorie
                            if (groups.isNotEmpty()) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                ) {
                                    items(groups) { group ->
                                        FilterChip(
                                            selected = selectedGroup == group,
                                            onClick = { selectedGroup = group },
                                            label = { Text(group.ifBlank { "Generale" }) }
                                        )
                                    }
                                }
                            }

                            // Mostra i canali appartenenti al gruppo selezionato
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(channels) { channel ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable {
                                                val intent = Intent(this@ChannelsActivity, PlayerActivity::class.java).apply {
                                                    putExtra("STREAM_URL", channel.url)
                                                    putExtra("CHANNEL_NAME", channel.name)
                                                }
                                                startActivity(intent)
                                            }
                                    ) {
                                        Text(
                                            text = channel.name,
                                            modifier = Modifier.padding(16.dp),
                                            style = MaterialTheme.typography.bodyLarge
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

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_USER_AGENT = "extra_user_agent"
    }
}
