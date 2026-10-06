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
import com.portalstream.app.ui.home.ChannelCache
import com.portalstream.app.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class ChannelsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val portalUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Canali"
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)

        val db = AppDatabase.getDatabase(this)
        val channelDao = db.channelDao()

        setContent {
            MaterialTheme {
                var localChannels by remember { mutableStateOf(ChannelCache.take()) }
                val dbGroups by channelDao.getGroups().collectAsState(initial = emptyList())
                var selectedGroup by remember { mutableStateOf<String?>(null) }
                var isLoading by remember { mutableStateOf(false) }

                // Determina le categorie disponibili (file locale o DB)
                val allGroups = if (localChannels.isNotEmpty()) {
                    localChannels.map { it.group }.distinct().filter { it.isNotBlank() }
                } else {
                    dbGroups
                }

                // Canali scaricati da Room per il gruppo attivo
                val dbChannels by if (selectedGroup != null) {
                    channelDao.getChannelsByGroup(selectedGroup!!).collectAsState(initial = emptyList())
                } else {
                    remember { mutableStateOf(emptyList()) }
                }

                // Canali correnti da mostrare
                val currentChannels = if (localChannels.isNotEmpty()) {
                    if (selectedGroup != null) localChannels.filter { it.group == selectedGroup } else localChannels
                } else {
                    dbChannels
                }

                // Parsing streaming via rete se non sono presenti canali in locale o DB
                LaunchedEffect(portalUrl) {
                    if (localChannels.isEmpty() && dbGroups.isEmpty() && portalUrl.isNotEmpty()) {
                        isLoading = true
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val connection = URL(portalUrl).openConnection()
                                if (!userAgent.isNullOrBlank()) {
                                    connection.setRequestProperty("User-Agent", userAgent)
                                }
                                val inputStream = connection.getInputStream()
                                val tempBuffer = mutableListOf<Channel>()

                                M3UParser.parseStreaming(inputStream) { channel ->
                                    tempBuffer.add(channel)

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

                // Selezione automatica prima categoria disponibile
                LaunchedEffect(allGroups) {
                    if (allGroups.isNotEmpty() && selectedGroup == null) {
                        selectedGroup = allGroups.first()
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (isLoading && allGroups.isEmpty() && localChannels.isEmpty()) {
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
                            Text(
                                text = title,
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            if (allGroups.isNotEmpty()) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 12.dp)
                                ) {
                                    items(allGroups) { group ->
                                        FilterChip(
                                            selected = selectedGroup == group,
                                            onClick = { selectedGroup = group },
                                            label = { Text(group.ifBlank { "Generale" }) }
                                        )
                                    }
                                }
                            }

                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(currentChannels) { channel ->
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
