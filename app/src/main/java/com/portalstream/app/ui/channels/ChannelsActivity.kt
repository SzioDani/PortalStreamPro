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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Search
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

@OptIn(ExperimentalMaterial3Api::class)
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
                var searchQuery by remember { mutableStateOf("") }
                var showOnlyFavorites by remember { mutableStateOf(false) }
                var favoriteIds by remember { mutableStateOf(setOf<String>()) }
                var isLoading by remember { mutableStateOf(false) }

                // Lista delle categorie disponibili
                val allGroups = remember(localChannels, dbGroups) {
                    if (localChannels.isNotEmpty()) {
                        localChannels.map { it.group }.distinct().filter { it.isNotBlank() }
                    } else {
                        dbGroups.distinct().filter { it.isNotBlank() }
                    }
                }

                val dbChannels by if (selectedGroup != null) {
                    channelDao.getChannelsByGroup(selectedGroup!!).collectAsState(initial = emptyList())
                } else {
                    remember { mutableStateOf(emptyList()) }
                }

                // Filtraggio dei canali per Gruppo, Preferiti, Ricerca testuale e Deduplicazione
                val currentChannels = remember(localChannels, dbChannels, selectedGroup, searchQuery, showOnlyFavorites, favoriteIds) {
                    val rawList = if (localChannels.isNotEmpty()) {
                        if (selectedGroup != null) localChannels.filter { it.group == selectedGroup } else localChannels
                    } else {
                        dbChannels
                    }

                    rawList
                        .distinctBy { it.name.trim() }
                        .filter { channel ->
                            val matchesSearch = searchQuery.isBlank() || channel.name.contains(searchQuery, ignoreCase = true)
                            val matchesFavorites = !showOnlyFavorites || favoriteIds.contains(channel.id)
                            matchesSearch && matchesFavorites
                        }
                }

                // Download da rete se lista vuota
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
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            // Campo di Ricerca
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                placeholder = { Text("Cerca canale...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Cerca") },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Cancella")
                                        }
                                    }
                                },
                                singleLine = true
                            )

                            // Barra Categorie + Chip Preferiti
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                            ) {
                                item {
                                    FilterChip(
                                        selected = showOnlyFavorites,
                                        onClick = { showOnlyFavorites = !showOnlyFavorites },
                                        label = { Text("⭐ Preferiti") },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                                        )
                                    )
                                }

                                items(allGroups) { group ->
                                    FilterChip(
                                        selected = selectedGroup == group && !showOnlyFavorites,
                                        onClick = {
                                            selectedGroup = group
                                            showOnlyFavorites = false
                                        },
                                        label = { Text(group.ifBlank { "Generale" }) }
                                    )
                                }
                            }

                            // Lista Canali
                            if (currentChannels.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (showOnlyFavorites) "Nessun canale preferito." else "Nessun canale trovato.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(currentChannels, key = { it.url + it.name }) { channel ->
                                        val isFav = favoriteIds.contains(channel.id)

                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 4.dp)
                                                .clickable {
                                                    val intent = Intent(this@ChannelsActivity, PlayerActivity::class.java).apply {
                                                        putExtra(PlayerActivity.EXTRA_STREAM_URL, channel.url)
                                                        putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
                                                        putExtra(PlayerActivity.EXTRA_USER_AGENT, userAgent)
                                                    }
                                                    startActivity(intent)
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(16.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = channel.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    modifier = Modifier.weight(1f)
                                                )

                                                IconButton(
                                                    onClick = {
                                                        favoriteIds = if (isFav) {
                                                            favoriteIds - channel.id
                                                        } else {
                                                            favoriteIds + channel.id
                                                        }
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                        contentDescription = "Preferito",
                                                        tint = if (isFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_USER_AGENT = "extra_user_agent"
    }
}
