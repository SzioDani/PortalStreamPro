package com.portalstream.app.ui.vod

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.portalstream.app.ui.player.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Divider
import java.util.concurrent.TimeUnit

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

        val title = intent.getStringExtra(EXTRA_TITLE) ?: "VOD - Film"
        val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        val portalType = intent.getStringExtra(EXTRA_PORTAL_TYPE) ?: ""
        val server = intent.getStringExtra(EXTRA_SERVER) ?: ""
        val username = intent.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent.getStringExtra(EXTRA_PASSWORD) ?: ""
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: "mp4"

        val prefs = getSharedPreferences("portal_stream_prefs", Context.MODE_PRIVATE)
        val cacheFile = File(filesDir, "vod_cache.json")

        setContent {
            MaterialTheme {
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
                    vodList.map { it.category }.distinct().filter { it.isNotBlank() }.sorted()
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
                            val fetched = mutableListOf<VodItem>()
                            
                            // 1. PROVIAMO A CARICARE PRIMA DALLA CACHE LOCALE (per zero attese)
                            if (cacheFile.exists()) {
                                try {
                                    val cachedJson = JSONArray(cacheFile.readText())
                                    for (i in 0 until cachedJson.length()) {
                                        val obj = cachedJson.getJSONObject(i)
                                        fetched.add(
                                            VodItem(
                                                id = obj.getString("id"),
                                                name = obj.getString("name"),
                                                streamUrl = obj.getString("streamUrl"),
                                                coverUrl = obj.optString("coverUrl", null),
                                                category = obj.getString("category"),
                                                rating = obj.optString("rating", null),
                                                year = obj.optString("year", null)
                                            )
                                        )
                                    }
                                    if (fetched.isNotEmpty()) {
                                        withContext(Dispatchers.Main) {
                                            vodList = fetched
                                            isLoading = false // Mostra subito i dati salvati!
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            val isXtream = portalType.equals("XTREAM", ignoreCase = true) || portalType.equals("XSTREAM", ignoreCase = true) || (username.isNotBlank() && password.isNotBlank())

                            if (isXtream && server.isNotBlank()) {
                                val cleanServer = server.trimEnd('/')
                                val apiUrl = "$cleanServer/player_api.php?username=$username&password=$password&action=get_vod_streams"
                                
                                // Usiamo OkHttp con supporto Gzip ed header personalizzati
                                val client = okhttp3.OkHttpClient.Builder()
                                    .connectTimeout(15, TimeUnit.SECONDS)
                                    .readTimeout(30, TimeUnit.SECONDS)
                                    .build()

                                val reqBuilder = okhttp3.Request.Builder()
                                    .url(apiUrl)
                                    .addHeader("Accept-Encoding", "gzip, deflate")

                                if (!userAgent.isNullOrBlank()) {
                                    reqBuilder.addHeader("User-Agent", userAgent)
                                } else {
                                    reqBuilder.addHeader("User-Agent", "PortalStreamPro/1.0")
                                }

                                client.newCall(reqBuilder.build()).execute().use { response ->
                                    if (response.isSuccessful) {
                                        val responseString = response.body?.string() ?: ""
                                        if (responseString.trim().startsWith("[")) {
                                            val freshFetched = mutableListOf<VodItem>()
                                            val jsonArray = JSONArray(responseString)
                                            val jsonCacheArray = JSONArray()

                                            for (i in 0 until jsonArray.length()) {
                                                val obj = jsonArray.getJSONObject(i)
                                                val streamId = obj.optString("stream_id", "")
                                                val name = obj.optString("name", "Sconosciuto")
                                                val cover = obj.optString("stream_icon", null)
                                                val category = obj.optString("category_name", "Generale")
                                                val rating = obj.optString("rating", null)
                                                val year = obj.optString("year", null)
                                                val container = obj.optString("container_extension", format.ifBlank { "mp4" })

                                                val streamUrl = "$cleanServer/movie/$username/$password/$streamId.$container"
                                                if (streamId.isNotBlank()) {
                                                    val vodItem = VodItem(
                                                        id = streamId,
                                                        name = name,
                                                        streamUrl = streamUrl,
                                                        coverUrl = cover?.takeIf { it.isNotBlank() },
                                                        category = category.ifBlank { "Generale" },
                                                        rating = rating?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" },
                                                        year = year?.takeIf { it.isNotBlank() && it != "0" }
                                                    )
                                                    freshFetched.add(vodItem)

                                                    // Salviamo anche nel JSON di cache
                                                    jsonCacheArray.put(
                                                        JSONObject().apply {
                                                            put("id", vodItem.id)
                                                            put("name", vodItem.name)
                                                            put("streamUrl", vodItem.streamUrl)
                                                            put("coverUrl", vodItem.coverUrl ?: "")
                                                            put("category", vodItem.category)
                                                            put("rating", vodItem.rating ?: "")
                                                            put("year", vodItem.year ?: "")
                                                        }
                                                    )
                                                }
                                            }

                                            if (freshFetched.isNotEmpty()) {
                                                // Aggiorniamo la cache sul file locale in background
                                                try {
                                                    cacheFile.writeText(jsonCacheArray.toString())
                                                } catch (_: Exception) {}

                                                withContext(Dispatchers.Main) {
                                                    vodList = freshFetched
                                                }
                                            }
                                        }
                                    }
                                }
                            } else if (fetched.isEmpty()) {
                                statusMessage = "Supporto VOD attivo solo per portali Xtream Codes."
                            }

                            if (vodList.isEmpty() && statusMessage.isBlank()) {
                                statusMessage = "Nessun VOD trovato sul server."
                            }
                        } catch (e: Exception) {
                            if (vodList.isEmpty()) {
                                statusMessage = "Errore di connessione."
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@VodActivity, "Errore: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            }
                        } finally {
                            withContext(Dispatchers.Main) {
                                isLoading = false
                            }
                        }
                    }
                }

                if (showCategoryDialog) {
                    AlertDialog(
                        onDismissRequest = { showCategoryDialog = false },
                        title = { Text("Seleziona Categoria") },
                        text = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                CompactSearchBar(
                                    value = categorySearchQuery,
                                    onValueChange = { categorySearchQuery = it },
                                    placeholderText = "Cerca...",
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
                                            headlineContent = { Text("Tutte le Categorie", fontWeight = FontWeight.Bold) },
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

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
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
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            CompactSearchBar(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholderText = "Cerca film...",
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
                                    label = { Text("Preferiti") },
                                    leadingIcon = {
                                        Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color.Red)
                                    }
                                )

                                FilterChip(
                                    selected = selectedCategory != null,
                                    onClick = { showCategoryDialog = true },
                                    label = {
                                        Text(text = selectedCategory ?: "Categorie", maxLines = 1)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp))
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
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 120.dp),
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(filteredVodList, key = { it.id }) { vod ->
                                        val isFav = favoriteVodIds.contains(vod.id)
                                        val isThisLoading = loadingVodId == vod.id

                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .aspectRatio(0.66f)
                                                .clickable(enabled = loadingVodId == null) {
                                                    loadingVodId = vod.id
                                                    val finalUserAgent = if (userAgent.isNullOrBlank()) "PortalStreamPro/1.0" else userAgent
                                                    val intent = Intent(this@VodActivity, PlayerActivity::class.java).apply {
                                                        putExtra(PlayerActivity.EXTRA_STREAM_URL, vod.streamUrl)
                                                        putExtra(PlayerActivity.EXTRA_CHANNEL_NAME, vod.name)
                                                        putExtra(PlayerActivity.EXTRA_USER_AGENT, finalUserAgent)
                                                    }
                                                    startActivity(intent)
                                                    loadingVodId = null
                                                },
                                            shape = RoundedCornerShape(8.dp),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                        ) {
                                            Box(modifier = Modifier.fillMaxSize()) {
                                                if (vod.coverUrl != null) {
                                                    AsyncImage(
                                                        model = vod.coverUrl,
                                                        contentDescription = "Locandina ${vod.name}",
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
                                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    }
                                                }

                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(
                                                            Brush.verticalGradient(
                                                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)),
                                                                startY = 200f
                                                            )
                                                        )
                                                )

                                                if (vod.rating != null) {
                                                    Row(
                                                        modifier = Modifier
                                                            .padding(6.dp)
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(Color.Black.copy(alpha = 0.6f))
                                                            .padding(horizontal = 4.dp, vertical = 2.dp)
                                                            .align(Alignment.TopStart),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(Icons.Default.Star, contentDescription = null, tint = Color.Yellow, modifier = Modifier.size(12.dp))
                                                        Spacer(modifier = Modifier.width(2.dp))
                                                        Text(text = vod.rating, color = Color.White, style = MaterialTheme.typography.labelSmall)
                                                    }
                                                }

                                                IconButton(
                                                    onClick = {
                                                        val newFavs = if (isFav) favoriteVodIds - vod.id else favoriteVodIds + vod.id
                                                        favoriteVodIds = newFavs
                                                        prefs.edit().putStringSet("favorite_vod_ids", newFavs).apply()
                                                    },
                                                    modifier = Modifier
                                                        .align(Alignment.TopEnd)
                                                        .size(36.dp)
                                                        .padding(4.dp)
                                                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                                ) {
                                                    Icon(
                                                        imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                        contentDescription = "Preferito VOD",
                                                        tint = if (isFav) Color.Red else Color.White,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }

                                                Column(
                                                    modifier = Modifier
                                                        .align(Alignment.BottomStart)
                                                        .fillMaxWidth()
                                                        .padding(8.dp)
                                                ) {
                                                    if (isThisLoading) {
                                                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary)
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                    }
                                                    Text(
                                                        text = vod.name,
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.labelLarge,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (vod.year != null) {
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

@Composable
fun CompactSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    placeholderText: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholderText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Cancella",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
