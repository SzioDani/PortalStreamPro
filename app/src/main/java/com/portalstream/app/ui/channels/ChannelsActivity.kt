package com.portalstream.app.ui.channels

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

        val portalUrl = intent.getStringExtra("PORTAL_URL") ?: ""
        val playlistId = intent.getIntExtra("PLAYLIST_ID", 1)
        val db = AppDatabase.getDatabase(this)
        val channelDao = db.channelDao()

        setContent {
            PortalStreamTheme {
                // Recupera la lista di tutti i gruppi
                val groups by channelDao.getGroups().collectAsState(initial = emptyList())
                var selectedGroup by remember { mutableStateOf<String?>(null) }
                var isLoading by remember { mutableStateOf(false) }

                // Se c'è un gruppo selezionato carica i suoi canali, altrimenti lista vuota
                val channels by if (selectedGroup != null) {
                    channelDao.getChannelsByGroup(selectedGroup!!).collectAsState(initial = emptyList())
                } else {
                    remember { mutableStateOf(emptyList()) }
                }

                // Carica la lista via rete solo se il DB non ha ancora gruppi
                LaunchedEffect(portalUrl) {
                    if (groups.isEmpty() && portalUrl.isNotEmpty()) {
                        isLoading = true
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val inputStream = URL(portalUrl).openStream()
                                val tempBuffer = mutableListOf<Channel>()

                                M3UParser.parseStreaming(inputStream) { channel ->
                                    // Assegniamo l'ID della playlist se necessario
                                    val channelWithPlaylist = channel.copy(playlistId = playlistId)
                                    tempBuffer.add(channelWithPlaylist)

                                    // Salva a blocchi di 100 canali tramite il tuo insertChannels
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
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            // Mostra i canali del gruppo corrente
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
}
