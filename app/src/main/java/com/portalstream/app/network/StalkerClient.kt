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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class StalkerProfile(
    val expireDate: String = "N/D",
    val activationDate: String = "N/D",
    val tariffPlan: String = "Standard",
    val isExpired: Boolean = false
)

object StalkerClient {

    private const val TAG = "StalkerClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    private fun resolveUserAgent(portal: Portal): String {
        val ua = if (portal.useCustomUserAgent && portal.userAgent.isNotBlank()) {
            portal.userAgent
        } else {
            FALLBACK_USER_AGENT
        }
        Log.d(TAG, "🌐 [User-Agent Selezionato] -> $ua (Personalizzato: ${portal.useCustomUserAgent})")
        return ua
    }

    private val FALLBACK_X_USER_AGENT = "Model: MAG250; Link: WiFi"

	private fun resolveXUserAgent(portal: Portal): String {
	    if (portal.useCustomUserAgent && portal.userAgent.isNotBlank()) {
	        val modelMatch = Regex("(MAG\\d+[A-Za-z0-9]*)", RegexOption.IGNORE_CASE)
	            .find(portal.userAgent)
	            ?.value
	            ?.uppercase(Locale.ROOT)
	        if (!modelMatch.isNullOrBlank()) {
	            val xUa = "Model: $modelMatch; Link: WiFi"
	            Log.d(TAG, "🖥️ [X-User-Agent Allineato] -> $xUa")
	            return xUa
	        }
	    }
	    Log.d(TAG, "🖥️ [X-User-Agent Fallback] -> $FALLBACK_X_USER_AGENT")
	    return FALLBACK_X_USER_AGENT
	}

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
        Log.d(TAG, "🔗 [Base URL Risolto] -> $baseUrl")
        return baseUrl
    }

    private fun estraiCampoTestuale(json: String, campo: String): String {
        return try {
            var m = Regex("\"" + campo + "\"\\s*:\\s*\"([^\"]+)\"").find(json)
            if (m != null) {
                val found = m.groupValues[1].trim()
                if (found.isNotBlank() && !found.equals("null", ignoreCase = true)) return found
            }
            m = Regex("\"" + campo + "\"\\s*:\\s*([^,}\\]]+)").find(json)
            val found2 = m?.groupValues?.get(1)?.trim()?.trim('"') ?: "-"
            if (found2.isBlank() || found2.equals("null", ignoreCase = true)) "-" else found2
        } catch (e: Exception) {
            "-"
        }
    }

    private fun parseToken(jsonStr: String?): String {
        if (jsonStr.isNullOrBlank()) return ""
        return try {
            Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(jsonStr)?.groupValues?.get(1) ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractDateFromCustomString(text: String): Date? {
        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH),
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
            SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ITALIAN),
            SimpleDateFormat("dd.MM.yyyy", Locale.ITALIAN),
            SimpleDateFormat("MMMM d, yyyy, h:mm a", Locale.ENGLISH),
            SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH),
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN),
            SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN)
        )
        for (fmt in formats) {
            try {
                fmt.isLenient = true
                val parsed = fmt.parse(text.trim())
                if (parsed != null) return parsed
            } catch (_: Exception) {
            }
        }
        return null
    }

    suspend fun fetchAccountInfo(portal: Portal): StalkerProfile = withContext(Dispatchers.IO) {
        val baseUrl = getPortalBaseUrl(portal) ?: return@withContext StalkerProfile()
        val mac = portal.macAddress.trim()
        if (mac.isBlank()) return@withContext StalkerProfile()

        val userAgent = resolveUserAgent(portal)
        val xUserAgent = resolveXUserAgent(portal)
        var sessionCookie = "mac=$mac; stb_lang=en; timezone=Europe/Rome"

        try {
            Log.d(TAG, "🚀 [AccountInfo] Inizio handshake per MAC: $mac")
            val handshakeUrl = "$baseUrl?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
            val handshakeReq = Request.Builder()
                .url(handshakeUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("X-User-Agent", xUserAgent)
                .addHeader("Cookie", sessionCookie)
                .build()

            val handshakeBody = client.newCall(handshakeReq).execute().use { handshakeResp ->
                val setCookieHeaders = handshakeResp.headers("Set-Cookie")
                if (setCookieHeaders.isNotEmpty()) {
                    val phpSessId = setCookieHeaders.firstOrNull { it.contains("PHPSESSID") }
                    if (phpSessId != null) {
                        val cookieValue = phpSessId.split(";").firstOrNull() ?: ""
                        if (cookieValue.isNotBlank()) sessionCookie += "; $cookieValue"
                    }
                }
                handshakeResp.body?.string() ?: ""
            }
            Log.d(TAG, "📥 [Handshake Response] -> $handshakeBody")

            val token = parseToken(handshakeBody)
            Log.d(TAG, "🔑 [Token Estratto] -> '$token'")

            val accountFields = listOf("exp_date", "end_date", "expire_billing_date", "account_expire", "expire", "phone", "created")
            var jsonResponseStr = ""

            fun faiRichiesta(urlParams: String): String {
                return try {
                    val reqUrl = "$baseUrl$urlParams" + if (token.isNotBlank()) "&token=$token" else ""
                    Log.d(TAG, "📤 [Richiesta Account] -> $reqUrl")
                    val reqBuilder = Request.Builder()
                        .url(reqUrl)
                        .addHeader("User-Agent", userAgent)
                        .addHeader("X-User-Agent", xUserAgent)
                        .addHeader("Cookie", sessionCookie)

                    if (token.isNotBlank()) {
                        reqBuilder.addHeader("Authorization", "Bearer $token")
                    }

                    client.newCall(reqBuilder.build()).execute().use { resp ->
                        val b = resp.body?.string() ?: ""
                        Log.d(TAG, "📥 [Risposta Account] -> $b")
                        b
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Errore richiesta account: ${e.message}")
                    ""
                }
            }

            fun hasUsefulAccountField(body: String): Boolean {
                return accountFields.any { body.contains("\"$it\"", ignoreCase = true) }
            }

            val req1 = faiRichiesta("?type=account_info&JsHttpRequest=1-xml")
            if (hasUsefulAccountField(req1)) {
                jsonResponseStr += req1
            }

            if (!hasUsefulAccountField(jsonResponseStr)) {
                for (azione in listOf("get_info", "get_main_info")) {
                    val req2 = faiRichiesta("?type=account_info&action=$azione&JsHttpRequest=1-xml")
                    if (hasUsefulAccountField(req2)) {
                        jsonResponseStr += req2
                        break
                    }
                }
            }

            val req3 = faiRichiesta("?type=stb&action=get_profile&hd=1&num_banks=2&not_valid_token=0&JsHttpRequest=1-xml")
            jsonResponseStr += req3

            if (jsonResponseStr.isBlank()) {
                Log.w(TAG, "⚠️ [AccountInfo] Risposta JSON vuota per il profilo!")
                return@withContext StalkerProfile(expireDate = "Non disponibile")
            }

            var tariffPlan = estraiCampoTestuale(jsonResponseStr, "tariff_plan")
            if (tariffPlan == "-") tariffPlan = estraiCampoTestuale(jsonResponseStr, "package_name")
            if (tariffPlan == "-") tariffPlan = "Standard"

            val expireKeys = listOf("exp_date", "end_date", "expire_billing_date", "account_expire", "expire")
            val rawExpire = expireKeys
                .map { estraiCampoTestuale(jsonResponseStr, it) }
                .firstOrNull { it != "-" && it != "0" }
                ?: "-"

            val scadPhone = estraiCampoTestuale(jsonResponseStr, "phone")

            var formattedExpireDate = "Illimitato"
            var isExpired = false

            if (rawExpire.matches(Regex("\\d+"))) {
                val timestampSec = rawExpire.toLongOrNull()
                if (timestampSec != null && timestampSec > 0) {
                    val millis = if (timestampSec > 10_000_000_000L) timestampSec else timestampSec * 1000L
                    val d = Date(millis)
                    isExpired = d.before(Date())
                    formattedExpireDate = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(d)
                }
            } else if (rawExpire != "-") {
                val parsedExpire = extractDateFromCustomString(rawExpire)
                if (parsedExpire != null) {
                    isExpired = parsedExpire.before(Date())
                    formattedExpireDate = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(parsedExpire)
                } else {
                    formattedExpireDate = rawExpire
                }
            } else if (scadPhone != "-" && scadPhone.matches(Regex(".*\\d{4}.*"))) {
                val parsedPhoneDate = extractDateFromCustomString(scadPhone)
                if (parsedPhoneDate != null) {
                    isExpired = parsedPhoneDate.before(Date())
                    formattedExpireDate = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(parsedPhoneDate)
                }
            }

            var formattedActivationDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date())
            var createdTs = estraiCampoTestuale(jsonResponseStr, "created")
            if (createdTs == "-") createdTs = estraiCampoTestuale(jsonResponseStr, "activation_date")

            if (createdTs != "-") {
                if (createdTs.matches(Regex("\\d+"))) {
                    val timestampSec = createdTs.toLongOrNull()
                    if (timestampSec != null && timestampSec > 0) {
                        val millis = if (timestampSec > 10_000_000_000L) timestampSec else timestampSec * 1000L
                        formattedActivationDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date(millis))
                    }
                } else {
                    formattedActivationDate = createdTs
                }
            }

            return@withContext StalkerProfile(
                expireDate = formattedExpireDate,
                activationDate = formattedActivationDate,
                tariffPlan = tariffPlan,
                isExpired = isExpired
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore recupero info account Stalker", e)
            return@withContext StalkerProfile(expireDate = "Errore di connessione")
        }
    }

    suspend fun fetchChannels(portal: Portal): List<Channel> = withContext(Dispatchers.IO) {
        val baseUrl = getPortalBaseUrl(portal) ?: return@withContext emptyList()
        val mac = portal.macAddress.trim()
        if (mac.isBlank()) return@withContext emptyList()

        val userAgent = resolveUserAgent(portal)
        val xUserAgent = resolveXUserAgent(portal)
        var sessionCookie = "mac=$mac; stb_lang=en; timezone=Europe/Rome"

        try {
            Log.d(TAG, "🚀 [FetchChannels] Inizio connessione per canali - MAC: $mac")
            val handshakeUrl = "$baseUrl?type=stb&action=handshake&token=&JsHttpRequest=1-xml"

            val handshakeRequest = Request.Builder()
                .url(handshakeUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("X-User-Agent", xUserAgent)
                .addHeader("Cookie", sessionCookie)
                .build()

            val handshakeBody = client.newCall(handshakeRequest).execute().use { handshakeResponse ->
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

                handshakeResponse.body?.string() ?: ""
            }
            Log.d(TAG, "📥 [Channels Handshake Body] -> $handshakeBody")

            val token = parseToken(handshakeBody)
            val categoriesMap = fetchGenres(baseUrl, userAgent, xUserAgent, sessionCookie, token)

            val channelsUrl = "$baseUrl?type=itv&action=get_all_channels&JsHttpRequest=1-xml" +
                if (token.isNotBlank()) "&token=$token" else ""

            Log.d(TAG, "📤 [Richiesta Canali URL] -> $channelsUrl")
            val reqBuilder = Request.Builder()
                .url(channelsUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("X-User-Agent", xUserAgent)
                .addHeader("Cookie", sessionCookie)

            if (token.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
            }

            val responseBody = client.newCall(reqBuilder.build()).execute().use { response ->
                checkHttpResponseCode(response.code)
                response.body?.string() ?: ""
            }
            Log.d(TAG, "📥 [Risposta Canali Grezza - primi 500 char] -> ${responseBody.take(500)}")

            var channels = parseChannels(responseBody, categoriesMap)
            Log.d(TAG, "📊 Canali parsati con get_all_channels: ${channels.size}")

            if (channels.isEmpty()) {
                Log.w(TAG, "⚠️ Nessun canale trovato con get_all_channels, provo fallback get_ordered_channels...")
                channels = fetchOrderedChannels(baseUrl, userAgent, xUserAgent, sessionCookie, token, categoriesMap)
                Log.d(TAG, "📊 Canali parsati con fallback: ${channels.size}")
            }

            return@withContext channels
        } catch (e: PortalException) {
            Log.e(TAG, "❌ PortalException durante fetchChannels: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore imprevisto fetchChannels: ${e.localizedMessage}", e)
            return@withContext emptyList()
        }
    }

    suspend fun getStreamUrl(portal: Portal, cmd: String): String? = withContext(Dispatchers.IO) {
        val baseUrl = getPortalBaseUrl(portal) ?: return@withContext null
        val mac = portal.macAddress.trim()
        if (mac.isBlank()) return@withContext null

        val userAgent = resolveUserAgent(portal)
        val xUserAgent = resolveXUserAgent(portal)
        var sessionCookie = "mac=$mac; stb_lang=en; timezone=Europe/Rome"

        try {
            val handshakeUrl = "$baseUrl?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
            val handshakeRequest = Request.Builder()
                .url(handshakeUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("X-User-Agent", xUserAgent)
                .addHeader("Accept-Encoding", "gzip, deflate")
                .addHeader("Cookie", sessionCookie)
                .build()

            val handshakeBody = client.newCall(handshakeRequest).execute().use { handshakeResponse ->
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

                handshakeResponse.body?.string() ?: ""
            }
            val token = parseToken(handshakeBody)

            val cleanCmd = cmd.trim()
            val encodedCmd = URLEncoder.encode(cleanCmd, "UTF-8")
            val createLinkUrl = "$baseUrl?type=itv&action=create_link&cmd=$encodedCmd&series_id=0&JsHttpRequest=1-xml" +
                if (token.isNotBlank()) "&token=$token" else ""

            val request = Request.Builder()
                .url(createLinkUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("X-User-Agent", xUserAgent)
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
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore risoluzione create_link Stalker: ${e.localizedMessage}", e)
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

    private fun fetchGenres(
        baseUrl: String,
        userAgent: String,
        xUserAgent: String,
        cookie: String,
        token: String
    ): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val genresUrl = "$baseUrl?type=itv&action=get_genres&JsHttpRequest=1-xml" +
            if (token.isNotBlank()) "&token=$token" else ""

        try {
            val reqBuilder = Request.Builder()
                .url(genresUrl)
                .addHeader("User-Agent", userAgent)
                .addHeader("X-User-Agent", xUserAgent)
                .addHeader("Cookie", cookie)

            if (token.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
            }

            val body = client.newCall(reqBuilder.build()).execute().use { response ->
                response.body?.string() ?: ""
            }
            if (body.isBlank()) return map

            val dataArray = extractDataArraySicuro(body) ?: return map

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
        xUserAgent: String,
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
                .addHeader("X-User-Agent", xUserAgent)
                .addHeader("Cookie", cookie)

            if (token.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
            }

            val body = client.newCall(reqBuilder.build()).execute().use { response ->
                response.body?.string() ?: ""
            }
            if (body.isBlank()) return emptyList()

            return parseChannels(body, categoriesMap)
        } catch (e: Exception) {
            Log.e(TAG, "Errore fallback Stalker", e)
        }
        return emptyList()
    }

    private fun parseChannels(jsonStr: String, categoriesMap: Map<String, String>): List<Channel> {
        val channels = mutableListOf<Channel>()
        try {
            val dataArray = extractDataArraySicuro(jsonStr) ?: return emptyList()

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

    private fun extractDataArraySicuro(jsonStr: String): JSONArray? {
        return try {
            val root = JSONObject(jsonStr)
            when {
                root.has("js") -> {
                    when (val js = root.opt("js")) {
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
