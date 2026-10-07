package com.portalstream.app.network

import android.util.Log
import com.portalstream.app.data.Portal
import com.portalstream.app.domain.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object StalkerClient {

    private const val TAG = "StalkerClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

    suspend fun fetchChannels(portal: Portal): List<Channel> = withContext(Dispatchers.IO) {
        var rawServer = portal.server.trim()
        if (rawServer.isBlank()) {
            Log.e(TAG, "Server URL vuoto")
            return@withContext emptyList()
        }

        if (!rawServer.startsWith("http://") && !rawServer.startsWith("https://")) {
            rawServer = "http://$rawServer"
        }

        var baseUrl = rawServer.trimEnd('/')
        if (!baseUrl.endsWith("portal.php")) {
            baseUrl = if (baseUrl.endsWith("/c")) "$baseUrl/portal.php" else "$baseUrl/portal.php"
        }

        val mac = portal.macAddress.trim()
        if (mac.isBlank()) {
            Log.e(TAG, "MAC Address mancante")
            return@withContext emptyList()
        }

        val userAgent = if (portal.useCustomUserAgent && portal.userAgent.isNotBlank()) {
            portal.userAgent
        } else {
            DEFAULT_USER_AGENT
        }

        var sessionCookie = "mac=$mac; stb_lang=en; timezone=Europe/Rome"

        try {
            val handshakeUrl = "$baseUrl?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
            Log.d(TAG, "Handshake URL: $handshakeUrl")

            val handshakeRequest = Request.Builder()
                .url(handshakeUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("X-User-Agent", "Model: MAG250; Link: WiFi")
                .addHeader("Cookie", sessionCookie)
                .build()

            val handshakeResponse = client.newCall(handshakeRequest).execute()

            val setCookieHeaders = handshakeResponse.headers("Set-Cookie")
            if (setCookieHeaders.isNotEmpty()) {
                val phpSessId = setCookieHeaders.firstOrNull { it.contains("PHPSESSID") }
                if (phpSessId != null) {
                    val cookieValue = phpSessId.split(";").firstOrNull() ?: ""
                    if (cookieValue.isNotBlank()) {
                        sessionCookie += "; $cookieValue"
                    }
                }
            }

            val handshakeBody = handshakeResponse.body?.string() ?: ""
            val token = parseToken(handshakeBody)

            val categoriesMap = fetchGenres(baseUrl, userAgent, sessionCookie, token)

            val channelsUrl = "$baseUrl?type=itv&action=get_all_channels&JsHttpRequest=1-xml" +
                    if (token.isNotBlank()) "&token=$token" else ""

            val channelsRequest = Request.Builder()
                .url(channelsUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Cookie", sessionCookie)
                .build()

            val response = client.newCall(channelsRequest).execute()
            val responseBody = response.body?.string() ?: ""

            var channels = parseChannels(responseBody, categoriesMap)

            if (channels.isEmpty()) {
                channels = fetchOrderedChannels(baseUrl, userAgent, sessionCookie, token, categoriesMap)
            }

            return@withContext channels
        } catch (e: Exception) {
            Log.e(TAG, "Errore Stalker: ${e.localizedMessage}", e)
            throw e
        }
    }

    private fun parseToken(jsonStr: String): String {
        return try {
            val root = JSONObject(jsonStr)
            val jsObj = root.optJSONObject("js")
            jsObj?.optString("token", "") ?: root.optString("token", "")
        } catch (e: Exception) {
            ""
        }
    }

    private fun fetchGenres(baseUrl: String, userAgent: String, cookie: String, token: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val genresUrl = "$baseUrl?type=itv&action=get_genres&JsHttpRequest=1-xml" +
                if (token.isNotBlank()) "&token=$token" else ""

        try {
            val reqBuilder = Request.Builder()
                .url(genresUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Cookie", cookie)

            val response = client.newCall(reqBuilder.build()).execute()
            val body = response.body?.string() ?: return map
            val dataArray = extractDataArray(body) ?: return map

            for (i in 0 until dataArray.length()) {
                val obj = dataArray.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                val title = obj.optString("title", "")
                if (id.isNotBlank() && title.isNotBlank()) {
                    map[id] = title
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore generi Stalker", e)
        }
        return map
    }

    private fun fetchOrderedChannels(
        baseUrl: String,
        userAgent: String,
        cookie: String,
        token: String,
        categoriesMap: Map<String, String>
    ): List<Channel> {
        val url = "$baseUrl?type=itv&action=get_ordered_channels&genre=0&JsHttpRequest=1-xml" +
                if (token.isNotBlank()) "&token=$token" else ""

        try {
            val reqBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", userAgent)
                .addHeader("Cookie", cookie)

            val response = client.newCall(reqBuilder.build()).execute()
            val body = response.body?.string() ?: return emptyList()

            return parseChannels(body, categoriesMap)
        } catch (e: Exception) {
            Log.e(TAG, "Errore fallback Stalker", e)
        }
        return emptyList()
    }

    private fun parseChannels(jsonStr: String, categoriesMap: Map<String, String>): List<Channel> {
        val channels = mutableListOf<Channel>()
        try {
            val dataArray = extractDataArray(jsonStr) ?: return emptyList()

            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                val id = item.optString("id", i.toString())
                val name = item.optString("name", "Canale $id")
                val genreId = item.optString("tv_genre_id", "")
                val categoryName = categoriesMap[genreId] ?: "Generale"
                val cmd = item.optString("cmd", "")
                val logo = item.optString("logo", "")
                val epgId = if (item.has("custom_sid") && !item.isNull("custom_sid")) item.optString("custom_sid") else null

                val cleanUrl = cmd.replace("ffrt ", "").replace("ffmpeg ", "").trim()

                if (cleanUrl.isNotBlank()) {
                    channels.add(
                        Channel(
                            id = id,
                            name = name,
                            url = cleanUrl,
                            group = categoryName,
                            logoUrl = logo,
                            epgId = epgId
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore parse canali Stalker", e)
        }
        return channels
    }

    private fun extractDataArray(jsonStr: String): JSONArray? {
        return try {
            val root = JSONObject(jsonStr)
            when {
                root.has("js") -> {
                    val js = root.get("js")
                    when (js) {
                        is JSONArray -> js
                        is JSONObject -> js.optJSONArray("data")
                        else -> null
                    }
                }
                root.has("data") -> root.optJSONArray("data")
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
