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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

// Modello di supporto esteso per le info profilo/scadenza e attivazione
data class StalkerProfile(
    val expireDate: String = "N/D",
    val activationDate: String = "N/D",
    val tariffPlan: String = "Standard",
    val isExpired: Boolean = false
)

object StalkerClient {

    private const val TAG = "StalkerClient"

    // OkHttp configurato con timeout e supporto Gzip nativo
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stb app version: 2f rev: 1812 Safari/533.3"

    private fun getPortalBaseUrl(portal: Portal): String? {
        var rawServer = portal.server.trim()
        if (rawServer.isBlank()) {
            rawServer = portal.url.trim()
        }
        if (rawServer.isBlank()) return null

        if (!rawServer.startsWith("http://") && !rawServer.startsWith("https://")) {
            rawServer = "http://$rawServer"
        }

        var baseUrl = rawServer.trimEnd('/')
        if (!baseUrl.endsWith("portal.php")) {
            baseUrl = if (baseUrl.endsWith("/c")) "$baseUrl/portal.php" else "$baseUrl/portal.php"
        }
        return baseUrl
    }

    /**
     * Interroga il portale Stalker per estrarre la data di scadenza, attivazione e piano tariffario.
     * Viene chiamata immediatamente al salvataggio del portale.
     */
    suspend fun fetchAccountInfo(portal: Portal): StalkerProfile = withContext(Dispatchers.IO) {
        val baseUrl = getPortalBaseUrl(portal) ?: return@withContext StalkerProfile()
        val mac = portal.macAddress.trim()
        if (mac.isBlank()) return@withContext StalkerProfile()

        val userAgent = if (portal.useCustomUserAgent && portal.userAgent.isNotBlank()) {
            portal.userAgent
        } else {
            DEFAULT_USER_AGENT
        }

        var sessionCookie = "mac=$mac; stb_lang=en; timezone=Europe/Rome"

        try {
            // 1. Handshake unico e pulito
            val handshakeUrl = "$baseUrl?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
            val handshakeReq = Request.Builder()
                .url(handshakeUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("X-User-Agent", "Model: MAG250; Link: WiFi")
                .addHeader("Accept-Encoding", "gzip, deflate")
                .addHeader("Cookie", sessionCookie)
                .build()

            val handshakeResp = client.newCall(handshakeReq).execute()
            val setCookieHeaders = handshakeResp.headers("Set-Cookie")
            if (setCookieHeaders.isNotEmpty()) {
                val phpSessId = setCookieHeaders.firstOrNull { it.contains("PHPSESSID") }
                if (phpSessId != null) {
                    val cookieValue = phpSessId.split(";").firstOrNull() ?: ""
                    if (cookieValue.isNotBlank()) sessionCookie += "; $cookieValue"
                }
            }

            val handshakeBody = handshakeResp.body?.string() ?: ""
            val token = parseToken(handshakeBody)

            // 2. Tentativi sequenziali con azioni di profilo standard Ministra
            val actions = listOf("get_profile", "account_info", "get_info", "get_main_info")
            var jsonResponseStr = ""

            for (action in actions) {
                val profileUrl = "$baseUrl?type=stb&action=$action&JsHttpRequest=1-xml" +
                        if (token.isNotBlank()) "&token=$token" else ""

                val profileReq = Request.Builder()
                    .url(profileUrl)
                    .addHeader("User-Agent", userAgent)
                    .addHeader("X-User-Agent", "Model: MAG250; Link: WiFi")
                    .addHeader("Accept-Encoding", "gzip, deflate")
                    .addHeader("Cookie", sessionCookie)
                    .build()

                try {
                    client.newCall(profileReq).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            if (body.contains("exp_date") || body.contains("phone") || body.contains("tariff_plan") || body.contains("created")) {
                                jsonResponseStr = body
                                break
                            } else if (body.isNotBlank() && jsonResponseStr.isBlank()) {
                                jsonResponseStr = body
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Tentativo account info fallito con action=$action: ${e.message}")
                }
            }

            if (jsonResponseStr.isBlank()) {
                return@withContext StalkerProfile(expireDate = "Non disponibile")
            }

            // 3. Parsing della risposta JSON
            val cleanJson = extractJsHttpPayload(jsonResponseStr)
            val root = JSONObject(cleanJson)
            val js = if (root.has("js")) root.optJSONObject("js") ?: root else root

            val tariffPlan = js.optString("tariff_plan", js.optString("package_name", "Standard"))

            // 4. Estrazione data di scadenza (Standard exp_date / expire_date)
            var formattedExpireDate = "Illimitato"
            var isExpired = false

            val expDateRaw = js.opt("exp_date") ?: js.opt("expire_date")
            if (expDateRaw != null && expDateRaw.toString() != "null" && expDateRaw.toString().isNotBlank()) {
                val timestampSec = expDateRaw.toString().toLongOrNull()
                if (timestampSec != null && timestampSec > 0) {
                    val millis = timestampSec * 1000
                    val date = Date(millis)
                    isExpired = date.before(Date())
                    formattedExpireDate = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(date)
                } else {
                    formattedExpireDate = expDateRaw.toString()
                }
            }

            // 5. Fallback sul campo "phone" per portali custom
            if (formattedExpireDate == "Illimitato" || formattedExpireDate == "N/D") {
                val phoneField = js.optString("phone", "")
                if (phoneField.isNotBlank()) {
                    val dateFromPhone = extractDateFromCustomString(phoneField)
                    if (dateFromPhone != null) {
                        isExpired = dateFromPhone.before(Date())
                        formattedExpireDate = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(dateFromPhone)
                    } else {
                        formattedExpireDate = phoneField
                    }
                }
            }

            // 6. Estrazione data di attivazione / creazione (created)
            var formattedActivationDate = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(Date())
            val createdRaw = js.opt("created") ?: js.opt("activation_date")
            if (createdRaw != null && createdRaw.toString() != "null" && createdRaw.toString().isNotBlank()) {
                val timestampSec = createdRaw.toString().toLongOrNull()
                if (timestampSec != null && timestampSec > 0) {
                    formattedActivationDate = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(Date(timestampSec * 1000))
                } else {
                    formattedActivationDate = createdRaw.toString()
                }
            }

            return@withContext StalkerProfile(
                expireDate = formattedExpireDate,
                activationDate = formattedActivationDate,
                tariffPlan = tariffPlan,
                isExpired = isExpired
            )

        } catch (e: Exception) {
            Log.e(TAG, "Errore recupero info account Stalker", e)
            StalkerProfile(expireDate = "Errore di connessione")
        }
    }

    private fun extractDateFromCustomString(text: String): Date? {
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH),
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
            SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH),
            SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN)
        )
        for (fmt in formats) {
            try {
                fmt.isLenient = true
                val parsed = fmt.parse(text)
                if (parsed != null) return parsed
            } catch (_: Exception) {}
        }
        return null
    }

    private fun extractJsHttpPayload(raw: String): String {
        return if (raw.contains("JsHttpRequest") && raw.contains("\"js\":")) {
            raw
        } else {
            raw
        }
    }

    // [Il resto dei metodi esistenti per fetchChannels, getStreamUrl, fetchGenres, ecc. rimangono invariati]
    
    suspend fun fetchChannels(portal: Portal): List<Channel> = withContext(Dispatchers.IO) {
        val baseUrl = getPortalBaseUrl(portal) ?: return@withContext emptyList()
        val mac = portal.macAddress.trim()
        if (mac.isBlank()) return@withContext emptyList()

        val userAgent = if (portal.useCustomUserAgent && portal.userAgent.isNotBlank()) {
            portal.userAgent
        } else {
            DEFAULT_USER_AGENT
        }

        var sessionCookie = "mac=$mac; stb_lang=en; timezone=Europe/Rome"

        try {
            val handshakeUrl = "$baseUrl?type=stb&action=handshake&token=&JsHttpRequest=1-xml"

            val handshakeRequest = Request.Builder()
                .url(handshakeUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("X-User-Agent", "Model: MAG250; Link: WiFi")
                .addHeader("Accept-Encoding", "gzip, deflate")
                .addHeader("Cookie", sessionCookie)
                .build()

            val handshakeResponse = client.newCall(handshakeRequest).execute()
            checkHttpResponseCode(handshakeResponse.code)

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
            checkPayloadForErrors(handshakeBody)

            val token = parseToken(handshakeBody)
            val categoriesMap = fetchGenres(baseUrl, userAgent, sessionCookie, token)

            val channelsUrl = "$baseUrl?type=itv&action=get_all_channels&JsHttpRequest=1-xml" +
                    if (token.isNotBlank()) "&token=$token" else ""

            val channelsRequest = Request.Builder()
                .url(channelsUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Accept-Encoding", "gzip, deflate")
                .addHeader("Cookie", sessionCookie)
                .build()

            val response = client.newCall(channelsRequest).execute()
            checkHttpResponseCode(response.code)

            val responseBody = response.body?.string() ?: ""
            checkPayloadForErrors(responseBody)

            var channels = parseChannels(responseBody, categoriesMap)

            if (channels.isEmpty()) {
                channels = fetchOrderedChannels(baseUrl, userAgent, sessionCookie, token, categoriesMap)
            }

            return@withContext channels
        } catch (e: PortalException) {
            throw e
        } catch (e: UnknownHostException) {
            throw PortalException.ServerUnreachable()
        } catch (e: SocketTimeoutException) {
            throw PortalException.ServerUnreachable()
        } catch (e: Exception) {
            Log.e(TAG, "Errore Stalker: ${e.localizedMessage}", e)
            throw e
        }
    }

    suspend fun getStreamUrl(portal: Portal, cmd: String): String? = withContext(Dispatchers.IO) {
        val baseUrl = getPortalBaseUrl(portal) ?: return@withContext null
        val mac = portal.macAddress.trim()
        if (mac.isBlank()) return@withContext null

        val userAgent = if (portal.useCustomUserAgent && portal.userAgent.isNotBlank()) {
            portal.userAgent
        } else {
            DEFAULT_USER_AGENT
        }

        var sessionCookie = "mac=$mac; stb_lang=en; timezone=Europe/Rome"

        try {
            val handshakeUrl = "$baseUrl?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
            val handshakeRequest = Request.Builder()
                .url(handshakeUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("X-User-Agent", "Model: MAG250; Link: WiFi")
                .addHeader("Accept-Encoding", "gzip, deflate")
                .addHeader("Cookie", sessionCookie)
                .build()

            val handshakeResponse = client.newCall(handshakeRequest).execute()
            checkHttpResponseCode(handshakeResponse.code)

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

            val cleanCmd = cmd.trim()
            val encodedCmd = URLEncoder.encode(cleanCmd, "UTF-8")
            val createLinkUrl = "$baseUrl?type=itv&action=create_link&cmd=$encodedCmd&series_id=0&JsHttpRequest=1-xml" +
                    if (token.isNotBlank()) "&token=$token" else ""

            val request = Request.Builder()
                .url(createLinkUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("Accept-Encoding", "gzip, deflate")
                .addHeader("Cookie", sessionCookie)
                .build()

            client.newCall(request).execute().use { response ->
                checkHttpResponseCode(response.code)

                val bodyStr = response.body?.string() ?: return@withContext null
                checkPayloadForErrors(bodyStr)

                val json = JSONObject(bodyStr)
                val js = json.optJSONObject("js") ?: return@withContext null
                var streamCmd = js.optString("cmd", "")

                if (streamCmd.startsWith("ffrt ")) streamCmd = streamCmd.removePrefix("ffrt ")
                if (streamCmd.startsWith("ffmpeg ")) streamCmd = streamCmd.removePrefix("ffmpeg ")

                if (streamCmd.contains("http")) {
                    val index = streamCmd.indexOf("http")
                    streamCmd = streamCmd.substring(index)
                }

                val finalUrl = streamCmd.trim()
                return@withContext if (finalUrl.isNotBlank()) finalUrl else null
            }
        } catch (e: PortalException) {
            throw e
        } catch (e: UnknownHostException) {
            throw PortalException.ServerUnreachable()
        } catch (e: SocketTimeoutException) {
            throw PortalException.ServerUnreachable()
        } catch (e: Exception) {
            Log.e(TAG, "Errore risoluzione create_link Stalker: ${e.localizedMessage}", e)
            null
        }
    }

    private fun checkHttpResponseCode(code: Int) {
        when (code) {
            401, 403 -> throw PortalException.UserAgentBlocked()
            429 -> throw PortalException.MaxConnectionsReached()
        }
    }

    private fun checkPayloadForErrors(body: String) {
        val lower = body.lowercase()
        if (lower.contains("limit_reached") || lower.contains("max_connections") || lower.contains("connection_limit")) {
            throw PortalException.MaxConnectionsReached()
        }
        if (lower.contains("access_denied") || lower.contains("auth_error") || lower.contains("account_expired")) {
            throw PortalException.InvalidCredentials()
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
                .addHeader("Accept-Encoding", "gzip, deflate")
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
                .addHeader("Accept-Encoding", "gzip, deflate")
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
                val genreId = item.opString("tv_genre_id", "")
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
                root.has("has") || root.has("js") -> {
                    val js = root.opt("js")
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
