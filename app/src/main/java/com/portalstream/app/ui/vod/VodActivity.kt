package com.portalstream.app.ui.vod

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.portalstream.app.data.Portal
import com.portalstream.app.network.PortalException
import com.portalstream.app.network.StalkerClient
import com.portalstream.app.network.XtreamClient
import com.portalstream.app.ui.channels.CompactSearchBar
import com.portalstream.app.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class VodItem(
    val id: String,
    val name: String,
    val streamUrl: String,
    val coverUrl: String?,
    val category: String,
    val rating: String? = null,
    val year: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
class VodActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "VOD - Film & Serie"
        val portalUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        val portalType = intent.getStringExtra(EXTRA_PORTAL_TYPE) ?: ""
        val server = intent.getStringExtra(EXTRA_SERVER) ?: ""
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: "mp4"
        val macAddress = intent.getStringExtra(EXTRA_MAC) ?: ""

        val prefs = getSharedPreferences("portal_stream_prefs", Context.MODE_PRIVATE)

        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()

                var vodList by remember { mutableStateOf<List<VodItem>>(emptyList()) }
                var selectedCategory by remember { mutableStateOf<String?>(null) }
                var searchQuery by remember { mutableStateOf("") }
                var showOnlyFavorites by remember { mutableStateOf(false) }

                var favoriteVodIds by remember {
                    mutableStateOf(prefs.getStringSet("favorite_vod_ids", emptySet())?.toSet() ?: emptySet())
                }

                var isLoading by remember { mutableStateOf(true) }
                var loadingVodId by remember { mutableStateOf<String?>(null) }
                var statusMessage by remember { mutableStateOf("") }

                var showCategoryDialog by remember { mutableStateOf(false) }
                var categorySearchQuery by remember { mutableStateOf("") }

                val allCategories = remember(vodList) {
                    vodList.map { it.category }.distinct().filter { it.isNotBlank() }
                }

                val filteredVodList = remember(
                    vodList, selectedCategory, searchQuery, showOnlyFavorites, favoriteVodIds
                ) {
                    vodList.filter { vod ->
                        val matchesCategory = selectedCategory == null || vod.category == selectedCategory
                        val matchesSearch = searchQuery.isBlank() ||
                                vod.name.contains(searchQuery, ignoreCase = true)
                        val matchesFavorites = !showOnlyFavorites || favoriteVodIds.contains(vod.id)
                        matchesCategory && matchesSearch && matchesFavorites
                    }
                }

                LaunchedEffect(Unit) {
                    withContext(Dispatchers.IO) {
                        try {
                            val isStalker = portalType.equals("STALKER", ignoreCase = true) || portalType.equals("MAG", ignoreCase = true)
                            val isXtream = portalType.equals("XTREAM", ignoreCase = true) || portalType.equals("XSTREAM", ignoreCase = true)

                            val fetched = when {
                                isStalker || macAddress.isNotBlank() -> {
                                    if (server.isBlank() || macAddress.isBlank()) {
                                        emptyList()
                                    } else {
                                        val portalDraft = Portal(
                                            id = 0,
                                            server = server,
                                            macAddress = macAddress,
                                            useCustomUserAgent = !userAgent.isNullOrBlank(),
                                            userAgent = userAgent ?: ""
                                        )
                                        StalkerClient.fetchVodMovies(portalDraft).map {
                                            VodItem(
                                                id = it.id,
                                                name = it.name,
                                                streamUrl = it.url,
                                                coverUrl = it.cover,
                                                category = it.group.ifBlank { "Film" },
                                                rating = it.rating,
                                                year = it.year
                                            )
                                        }
                                    }
                                }

                                isXtream || (username.isNotBlank() && password.isNotBlank()) -> {
                                    val portalDraft = Portal(
                                        id = 0,
                                        server = server,
                                        username = username,
                                        password = password,
                                        streamFormat = format,
                                        useCustomUserAgent = !userAgent.isNullOrBlank(),
                                    )
                                    XtreamClient.fetchVodStreams(portalDraft).map {
                                        VodItem(
                                            id = it.id,
                                            name = it.name,
                                            streamUrl = it.url,
                                            coverUrl = it.cover,
                                            category = it.category.ifBlank { "Film" },
                                            rating = it.rating,
                                            year = it.year
                                        )
                                    }
                                }

                                else -> emptyList()
                            }

                            if (fetched.isNotEmpty()) {
                                vodList = fetched
                            } else {
                                statusMessage = "Nessun VOD trovato sul server."
                            }
                        } catch (e: PortalException) {
                            statusMessage = e.localizedMessage ?: "Errore del portale"
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@VodActivity, e.localizedMessage, Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            statusMessage = "Errore: ${e.localizedMessage}"
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    this@VodActivity,
                                    "Errore Rete VOD: ${e.localizedMessage}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                isLoading = false
                            }
                        }
                    }
                }

                // Dialogo Selezione Categorie VOD
                if (showCategoryDialog) {
                    AlertDialog(
                        onDismissRequest = { showCategoryDialog = false },
                        title = { Text("Seleziona Categoria VOD") },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                CompactSearchBar(
                                    value = categorySearchQuery,
                                    onValueChange = { categorySearchQuery = it },
                                    placeholderText = "Cerca categoria...",
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                val filteredCategories = remember(allCategories, categorySearchQuery) {
                                    if (categorySearchQuery.isBlank()) allCategories
                                    else allCategories.filter { it.contains(categorySearchQuery, ignoreCase = true) }
                                }

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 320.dp)
                                ) {
                                    item {
                                        ListItem(
                                            headlineContent = { Text("Tutte le Categorie", style = MaterialTheme.typography.bodyLarge) },
                                            modifier = Modifier.clickable {
                                                selectedCategory = null
                                                showOnlyFavorites = false
                                                showCategoryDialog = false
                                            }
                                        )
                                        Divider()
                                    }
                                    items(filteredCategories) { cat ->
                                        ListItem(
                                            headlineContent = { Text(cat) },
                                            modifier = Modifier.clickable {
                                                selectedCategory = cat
                                                showOnlyFavorites = false
                                                showCategoryDialog = false
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showCategoryDialog = false }) {
                                Text("Chiudi")
                            }
                        }
                    )
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Caricamento catalogo VOD...")
                            }
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

                            CompactSearchBar(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholderText = "Cerca film o serie...",
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = showOnlyFavorites,
                                    onClick = {
                                        showOnlyFavorites = !showOnlyFavorites
                                        if (showOnlyFavorites) selectedCategory = null
                                    },
                                    label = { Text("⭐ Preferiti") }
                                )

                                FilterChip(
                                    selected = selectedCategory != null,
                                    onClick = { showCategoryDialog = true },
                                    label = {
                                        Text(
                                            text = selectedCategory ?: "≡ Categorie",
                                            maxLines = 1
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.List,
                                            contentDescription = "Categorie",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                )
                            }

                            if (filteredVodList.isEmpty()) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (statusMessage.isNotBlank()) statusMessage else "Nessun contenuto trovato.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(2),
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(filteredVodList, key = { it.id }) { vod ->
                                        val isFav = favoriteVodIds.contains(vod.id)
                                        val isThisLoading = loadingVodId == vod.id

                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(240.dp)
                                                .clickable(enabled = loadingVodId == null) {
                                                    loadingVodId = vod.id
                                                    scope.launch(Dispatchers.IO) {
                                                        try {
                                                            val isStalker = portalType.equals("STALKER", ignoreCase = true) || portalType.equals("MAG", ignoreCase = true)
                                                            val playUrl = if (isStalker || macAddress.isNotBlank()) {
                                                                val portalDraft = Portal(
                                                                    id = 0,
                                                                    server = server,
                                                                    macAddress = macAddress,
                                                                    useCustomUserAgent = !userAgent.isNullOrBlank(),
                                                                    userAgent = userAgent ?: ""
                                                                )
                                                                StalkerClient.getVodStreamUrl(portalDraft, vod.id) ?: vod.streamUrl
                                                            } else {
                                                                vod.streamUrl
                                                            }

                                                            withContext(Dispatchers.Main) {
                                                                val finalUserAgent = if (userAgent.isNullOrBlank()) "MAG250" else userAgent
                                                                val intent = Intent(this@VodActivity, PlayerActivity::class.java).apply {
                                                                    putExtra(PlayerActivity.EXTRA_STREAM_URL, playUrl)
                                                                    putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, vod.name)
                                                                    putExtra(PlayerActivity.EXTRA_USER_AGENT, finalUserAgent)
                                                                }
                                                                startActivity(intent)
                                                            }
                                                        } catch (e: Exception) {
                                                            withContext(Dispatchers.Main) {
                                                                Toast.makeText(
                                                                    this@VodActivity,
                                                                    "Errore riproduzione VOD: ${e.localizedMessage}",
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        } finally {
                                                            withContext(Dispatchers.Main) {
                                                                loadingVodId = null
                                                            }
                                                        }
                                                    }
                                                },
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Box(modifier = Modifier.fillMaxSize()) {
                                                if (!vod.coverUrl.isNullOrBlank()) {
                                                    AsyncImage(
                                                        model = vod.coverUrl,
                                                        contentDescription = vod.name,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = "🎬",
                                                            style = MaterialTheme.typography.headlineLarge
                                                        )
                                                    }
                                                }

                                                // Gradient overlay for text readability
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(
                                                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                                                startY = 100f
                                                            )
                                                        )
                                                )

                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(10.dp),
                                                    verticalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.End
                                                    ) {
                                                        IconButton(
                                                            onClick = {
                                                                val newFavs = if (isFav) favoriteVodIds - vod.id else favoriteVodIds + vod.id
                                                                favoriteVodIds = newFavs
                                                                prefs.edit().putStringSet("favorite_vod_ids", newFavs).apply()
                                                            },
                                                            modifier = Modifier.size(32.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                                contentDescription = "Preferito VOD",
                                                                tint = if (isFav) Color.Red else Color.White
                                                            )
                                                        }
                                                    }

                                                    Column {
                                                        if (isThisLoading) {
                                                            CircularProgressIndicator(
                                                                modifier = Modifier
                                                                    .size(24.dp)
                                                                    .align(Alignment.CenterHorizontally),
                                                                color = Color.White,
                                                                strokeWidth = 2.dp
                                                            )
                                                            Spacer(modifier = Modifier.height(4.dp))
                                                        }
                                                        Text(
                                                            text = vod.name,
                                                            color = Color.White,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        if (!vod.year.isNullOrBlank()) {
                                                            Text(
                                                                text = vod.year,
                                                                color = Color.LightGray,
                                                                style = MaterialTheme.typography.labelSmall
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
        }
    }

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_PORTAL_TYPE = "extra_portal_type"
        const val EXTRA_SERVER = "extra_server"
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_PASSWORD = "extra_password"
        const val EXTRA_FORMAT = "extra_format"
        const val EXTRA_MAC = "extra_mac"
    }
}
