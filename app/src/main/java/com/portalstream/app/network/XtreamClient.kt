package com.portalstream.app.network

import com.portalstream.app.data.Portal
import com.portalstream.app.domain.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object XtreamClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchLiveChannels(portal: Portal): List<Channel> = withContext(Dispatchers.IO) {
        val baseUrl = portal.server.trimEnd('/')
        if (baseUrl.isBlank() || portal.username.isBlank() || portal.password.isBlank()) {
            return@withContext emptyList()
        }

        // 1. Scarica le categorie per associare category_id -> category_name
        val categoriesMap = fetchCategories(baseUrl, portal.username, portal.password)

        // 2. Scarica i canali Live
        val apiUrl = "$baseUrl/player_api.php?username=${portal.username}&password=${portal.password}&action=get_live_streams"
        val requestBuilder = Request.Builder().url(apiUrl)
        
        if (portal.useCustomUserAgent && portal.userAgent.isNotBlank()) {
            requestBuilder.header("User-Agent", portal.userAgent)
        } else {
            requestBuilder.header("User-Agent", "PortalStreamPro/1.0")
        }

        val channels = mutableListOf<Channel>()
        val format = if (portal.streamFormat.isBlank()) "m3u8" else portal.streamFormat

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: return@withContext emptyList()

            val jsonArray = JSONArray(responseBody)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val streamId = obj.optInt("stream_id", -1)
                if (streamId == -1) continue

                val name = obj.optString("name", "Canale $streamId")
                val categoryId = obj.optString("category_id", "")
                val groupName = categoriesMap[categoryId] ?: "Generale"
                val logo = obj.optString("stream_icon", "")
                val epgChannelId = obj.optString("epg_channel_id", null)

                val streamUrl = "$baseUrl/live/${portal.username}/${portal.password}/$streamId.$format"

                channels.add(
                    Channel(
                        id = streamId.toString(),
                        name = name,
                        url = streamUrl,
                        group = groupName,
                        logoUrl = logo,
                        epgId = epgChannelId
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext channels
    }

    private fun fetchCategories(baseUrl: String, user: String, pass: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val catUrl = "$baseUrl/player_api.php?username=$user&password=$pass&action=get_live_categories"
        try {
            val request = Request.Builder().url(catUrl).build()
            val response = client.newCall(request).execute()
            val jsonStr = response.body?.string() ?: return map
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val cat = array.getJSONObject(i)
                val catId = cat.optString("category_id", "")
                val catName = cat.optString("category_name", "")
                if (catId.isNotBlank() && catName.isNotBlank()) {
                    map[catId] = catName
                }
            }
        } catch (_: Exception) { }
        return map
    }
}
