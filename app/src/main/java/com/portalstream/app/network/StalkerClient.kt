package com.portalstream.app.network

import com.portalstream.app.data.Portal
import com.portalstream.app.domain.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object StalkerClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

    suspend fun fetchChannels(portal: Portal): List<Channel> = withContext(Dispatchers.IO) {
        var baseUrl = portal.server.trimEnd('/')
        if (!baseUrl.endsWith("portal.php")) {
            baseUrl = if (baseUrl.endsWith("/c")) "$baseUrl/portal.php" else "$baseUrl/portal.php"
        }

        val mac = portal.macAddress.trim()
        if (baseUrl.isBlank() || mac.isBlank()) {
            return@withContext emptyList()
        }

        val userAgent = if (portal.useCustomUserAgent && portal.userAgent.isNotBlank()) {
            portal.userAgent
        } else {
            DEFAULT_USER_AGENT
        }

        val cookieHeader = "mac=$mac; stb_lang=en; timezone=Europe/Rome"

        try {
            // 1. Handshake Stalker
            val handshakeUrl = "$baseUrl?type=stb&action=handshake&JsHttpRequest=1-xml"
            val handshakeRequest = Request.Builder()
                .url(handshakeUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Cookie", cookieHeader)
                .build()

            val handshakeResponse = client.newCall(handshakeRequest).execute()
            val handshakeBody = handshakeResponse.body?.string() ?: return@withContext emptyList()
            val token = parseToken(handshakeBody)

            val authHeader = if (token.isNotBlank()) "Bearer $token" else null

            // 2. Recupero Categorie (Genres)
            val categoriesMap = fetchGenres(baseUrl, userAgent, cookieHeader, authHeader)

            // 3. Recupero Lista Canali (get_all_channels)
            val channelsUrl = "$baseUrl?type=itv&action=get_all_channels&JsHttpRequest=1-xml"
            val channelsRequestBuilder = Request.Builder()
                .url(channelsUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Cookie", cookieHeader)

            authHeader?.let { channelsRequestBuilder.addHeader("Authorization", it) }

            val response = client.newCall(channelsRequestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: return@withContext emptyList()

            return@withContext parseChannels(responseBody, categoriesMap)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext emptyList()
    }

    private fun parseToken(jsonStr: String): String {
        return try {
            val root = JSONObject(jsonStr)
            val jsObj = root.optJSONObject("js")
            jsObj?.optString("token", "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun fetchGenres(baseUrl: String, userAgent: String, cookie: String, auth: String?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val genresUrl = "$baseUrl?type=itv&action=get_genres&JsHttpRequest=1-xml"
        try {
            val reqBuilder = Request.Builder()
                .url(genresUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Cookie", cookie)

            auth?.let { reqBuilder.addHeader("Authorization", it) }

            val response = client.newCall(reqBuilder.build()).execute()
            val body = response.body?.string() ?: return map
            val root = JSONObject(body)
            val jsObj = root.optJSONObject("js")
            val jsArray = jsObj?.optJSONArray("data") ?: root.optJSONArray("js")

            if (jsArray != null) {
                for (i in 0 until jsArray.length()) {
                    val obj = jsArray.getJSONObject(i)
                    val id = obj.optString("id", "")
                    val title = obj.optString("title", "")
                    if (id.isNotBlank() && title.isNotBlank()) {
                        map[id] = title
                    }
                }
            }
        } catch (_: Exception) {}
        return map
    }

    private fun parseChannels(jsonStr: String, categoriesMap: Map<String, String>): List<Channel> {
        val channels = mutableListOf<Channel>()
        try {
            val root = JSONObject(jsonStr)
            val jsObj = root.optJSONObject("js")
            val dataArray = jsObj?.optJSONArray("data") ?: return emptyList()

            for (i in 0 until dataArray.length()) {
                val item = dataArray.getJSONObject(i)
                val id = item.optString("id", i.toString())
                val name = item.optString("name", "Canale $id")
                val genreId = item.optString("tv_genre_id", "")
                val categoryName = categoriesMap[genreId] ?: "Generale"
                val cmd = item.optString("cmd", "")
                val logo = item.optString("logo", "")

                val cleanUrl = cmd.replace("ffrt ", "").replace("ffmpeg ", "").trim()

                if (cleanUrl.isNotBlank()) {
                    channels.add(
                        Channel(
                            id = id,
                            name = name,
                            url = cleanUrl,
                            group = categoryName,
                            logoUrl = logo
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return channels
    }
}
