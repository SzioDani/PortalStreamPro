package com.portalstream.app.network

import android.util.Log
import com.portalstream.app.data.Portal
import com.portalstream.app.domain.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object XtreamClient {

    private const val TAG = "XtreamClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchLiveChannels(portal: Portal): List<Channel> = withContext(Dispatchers.IO) {
        var rawServer = portal.server.trim()
        if (rawServer.isBlank() || portal.username.isBlank() || portal.password.isBlank()) {
            Log.e(TAG, "Credenziali Xtream mancanti")
            throw PortalException.InvalidCredentials()
        }

        if (!rawServer.startsWith("http://") && !rawServer.startsWith("https://")) {
            rawServer = "http://$rawServer"
        }
        val baseUrl = rawServer.trimEnd('/')

        val userAgent = if (portal.useCustomUserAgent && portal.userAgent.isNotBlank()) {
            portal.userAgent
        } else {
            "PortalStreamPro/1.0"
        }

        val categoriesMap = fetchCategories(baseUrl, portal.username, portal.password, userAgent)

        val apiUrl = "$baseUrl/player_api.php?username=${portal.username}&password=${portal.password}&action=get_live_streams"
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", userAgent)

        val channels = mutableListOf<Channel>()
        val format = if (portal.streamFormat.isBlank()) "m3u8" else portal.streamFormat

        try {
            val response = client.newCall(requestBuilder.build()).execute()

            when (response.code) {
                401, 403 -> throw PortalException.UserAgentBlocked()
                429 -> throw PortalException.MaxConnectionsReached()
            }

            val responseBody = response.body?.string() ?: return@withContext emptyList()

            if (responseBody.trim().startsWith("{")) {
                val lower = responseBody.lowercase()
                if (lower.contains("auth_failed") || lower.contains("user_info\":null")) {
                    throw PortalException.InvalidCredentials()
                }
                if (lower.contains("max_connections") || lower.contains("limit")) {
                    throw PortalException.MaxConnectionsReached()
                }
                return@withContext emptyList()
            }

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
        } catch (e: PortalException) {
            throw e
        } catch (e: UnknownHostException) {
            throw PortalException.ServerUnreachable()
        } catch (e: SocketTimeoutException) {
            throw PortalException.ServerUnreachable()
        } catch (e: Exception) {
            Log.e(TAG, "Errore Xtream: ${e.localizedMessage}", e)
            throw e
        }

        return@withContext channels
    }

    private fun fetchCategories(baseUrl: String, user: String, pass: String, userAgent: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val catUrl = "$baseUrl/player_api.php?username=$user&password=$pass&action=get_live_categories"
        try {
            val request = Request.Builder()
                .url(catUrl)
                .header("User-Agent", userAgent)
                .build()

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
