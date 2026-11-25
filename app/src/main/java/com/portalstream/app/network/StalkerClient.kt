package com.portalstream.app.network

import android.util.Log
import com.portalstream.app.data.Portal
import com.portalstream.app.domain.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
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
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private data class SessionData(
        val baseUrl: String,
        val refererUrl: String,
        val token: String,
        val cookie: String,
        val profileJson: String = "",
        val useBearerHeader: Boolean = true,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val sessionCache = mutableMapOf<String, SessionData>()
    private val sessionMutex = Mutex()

    private fun portalCacheKey(portal: Portal): String {
        val raw = portal.server.ifBlank { portal.url }.trim().lowercase(Locale.ROOT)
        val mac = portal.macAddress.trim().uppercase(Locale.ROOT)
        return "$raw|$mac"
    }

    private const val FALLBACK_USER_AGENT =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

    private fun resolveUserAgent(portal: Portal): String {
        val ua = if (portal.useCustomUserAgent && portal.userAgent.isNotBlank()) {
            portal.userAgent.trim()
        } else {
            FALLBACK_USER_AGENT
        }
        Log.d(TAG, "🌐 [User-Agent Selezionato] -> $ua (Personalizzato: ${portal.useCustomUserAgent})")
        return ua
    }

    private const val FALLBACK_X_USER_AGENT = "Model: MAG250; Link: WiFi"

    private fun resolveXUserAgent(portal: Portal): String {
        if (portal.useCustomUserAgent && portal.userAgent.isNotBlank()) {
            val modelMatch = Regex("(MAG\\d+[A-Za-z0-9]*)", RegexOption.IGNORE_CASE)
                .find(portal.userAgent)
                ?.value
                ?.uppercase(Locale.ROOT)
            if (!modelMatch.isNullOrBlank()) {
                return "Model: $modelMatch; Link: WiFi"
            }
        }
        return FALLBACK_X_USER_AGENT
    }

    /**
     * Genera Serial Number (sn), device_id, device_id2 e signature deterministci dal MAC
     * esattamente come fanno IPTV Extreme Pro e STB Emulator.
     */
    private fun md5Hex(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private fun sha256Hex(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private fun generateMagSerial(mac: String): String = md5Hex(mac).take(13)
    private fun generateMagDeviceId(mac: String): String = sha256Hex(mac)
    private fun generateMagDeviceId2(mac: String): String = sha256Hex(mac + "_dev2")
    private fun generateMagSignature(mac: String, sn: String): String = sha256Hex(sn + mac)

    /**
     * Verifica se il JSON restituito da get_profile rappresenta un profilo REALE autenticato
     * (es. "id":"8380") e NON un profilo ospite rifiutato ("id":null).
     */
    private fun isProfileAuthenticated(profileJson: String): Boolean {
        if (profileJson.isBlank() || !profileJson.contains("{")) return false
        return try {
            val root = JSONObject(profileJson)
            val js = root.optJSONObject("js") ?: return false
            if (js.isNull("id")) return false
            val idStr = js.optString("id", "").trim()
            idStr.isNotBlank() && !idStr.equals("null", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    private fun getCandidateBaseUrls(portal: Portal): List<String> {
        var rawServer = portal.server.trim()
        if (rawServer.isBlank()) {
            rawServer = portal.url.trim()
        }
        if (rawServer.isBlank()) return emptyList()

        if (!rawServer.startsWith("http://") && !rawServer.startsWith("https://")) {
            rawServer = "http://$rawServer"
        }

        val cleaned = rawServer.trimEnd('/')

        if (cleaned.endsWith(".php", ignoreCase = true)) {
            return listOf(cleaned)
        }

        val rootUrl = when {
            cleaned.endsWith("/stalker_portal/c", ignoreCase = true) ->
                cleaned.removeSuffix("/c").removeSuffix("/C")
            cleaned.endsWith("/c", ignoreCase = true) ->
                cleaned.substring(0, cleaned.length - 2).trimEnd('/')
            else -> cleaned
        }

        return listOf(
            "$rootUrl/portal.php",
            "$rootUrl/c/portal.php",
            "$rootUrl/stalker_portal/server/load.php",
            "$rootUrl/server/load.php"
        ).distinct()
    }

    private fun buildReferer(baseUrl: String): String {
        val root = baseUrl
            .substringBefore("/portal.php")
            .substringBefore("/server/load.php")
            .trimEnd('/')
        return if (root.endsWith("/c")) "$root/" else "$root/c/"
    }

    private fun estraiCampoTestuale(json: String, campo: String): String {
        if (json.isBlank()) return "-"
        return try {
            var m = Regex("\"" + Regex.escape(campo) + "\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(json)
            if (m != null) {
                val found = m.groupValues[1].trim()
                if (found.isNotBlank() && !found.equals("null", ignoreCase = true)) return found
            }
            m = Regex("\"" + Regex.escape(campo) + "\"\\s*:\\s*([^\",}\\]]+)", RegexOption.IGNORE_CASE).find(json)
            val found2 = m?.groupValues?.get(1)?.trim()?.trim('"') ?: "-"
            if (found2.isBlank() || found2.equals("null", ignoreCase = true)) "-" else found2
        } catch (e: Exception) {
            "-"
        }
    }

    private fun parseToken(jsonStr: String?): String {
        if (jsonStr.isNullOrBlank()) return ""
        return try {
            Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(jsonStr)?.groupValues?.get(1)?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun isPlaceholderDate(raw: String): Boolean {
        val t = raw.trim().lowercase(Locale.ROOT)
        return t.isEmpty() ||
            t == "-" ||
            t == "0" ||
            t == "null" ||
            t.startsWith("0000-00-00") ||
            t.startsWith("0000/00/00") ||
            t.startsWith("00.00.0000")
    }

    private fun extractDateFromCustomString(text: String): Date? {
        var cleaned = text.trim()
        if (isPlaceholderDate(cleaned)) return null

        cleaned = cleaned
            .replace(Regex("\\b(am)\\b", RegexOption.IGNORE_CASE), "AM")
            .replace(Regex("\\b(pm)\\b", RegexOption.IGNORE_CASE), "PM")
            .replace(Regex("(\\d+)(st|nd|rd|th)\\b", RegexOption.IGNORE_CASE), "$1")
            .replace(Regex("\\s+"), " ")

        val formats = listOf(
            SimpleDateFormat("MMMM d, yyyy, h:mm a", Locale.ENGLISH),
            SimpleDateFormat("MMMM d, yyyy h:mm a", Locale.ENGLISH),
            SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH),
            SimpleDateFormat("MMM d, yyyy, h:mm a", Locale.ENGLISH),
            SimpleDateFormat("MMM d, yyyy", Locale.ENGLISH),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH),
            SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH),
            SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ITALIAN),
            SimpleDateFormat("dd.MM.yyyy", Locale.ITALIAN),
            SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.ITALIAN),
            SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN),
            SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN),
            SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.ITALIAN),
            SimpleDateFormat("dd-MM-yyyy", Locale.ITALIAN)
        )
        for (fmt in formats) {
            try {
                fmt.isLenient = true
                val parsed = fmt.parse(cleaned)
                if (parsed != null) return parsed
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun checkHttpResponseCode(code: Int) {
        when (code) {
            401, 403 -> throw PortalException.UserAgentBlocked()
            424, 456 -> throw PortalException.VpnOrSessionBlocked()
            429, 458, 459, 462 -> throw PortalException.MaxConnectionsReached()
        }
    }

    private fun checkPayloadForErrors(body: String) {
        val lower = body.lowercase(Locale.ROOT)
        if (
            lower.contains("limit_reached") ||
            lower.contains("max_connections") ||
            lower.contains("connection_limit") ||
            lower.contains("stream_limit") ||
            lower.contains("already_playing")
        ) {
            throw PortalException.MaxConnectionsReached()
        }
        if (lower.contains("424") || lower.contains("failed dependency")) {
            throw PortalException.VpnOrSessionBlocked()
        }
        if (
            lower.contains("access_denied") ||
            lower.contains("auth_error") ||
            lower.contains("account_expired")
        ) {
            throw PortalException.InvalidCredentials()
        }
    }

    private fun executeStalkerGet(
        url: String,
        userAgent: String,
        xUserAgent: String,
        referer: String,
        cookie: String,
        token: String,
        useBearerHeader: Boolean = true
    ): Pair<Int, String> {
        val reqBuilder = Request.Builder()
            .url(url)
            .addHeader("User-Agent", userAgent)
            .addHeader("X-User-Agent", xUserAgent)
            .addHeader("Referer", referer)
            .addHeader("Cookie", cookie)

        if (useBearerHeader && token.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $token")
        }

        return client.newCall(reqBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            response.code to body
        }
    }

    private fun doHandshakeRequest(
        candidateUrl: String,
        userAgent: String,
        xUserAgent: String,
        referer: String,
        initialCookie: String
    ): Triple<Int, String, String> {
        val handshakeUrl = "$candidateUrl?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
        val handshakeReq = Request.Builder()
            .url(handshakeUrl)
            .addHeader("User-Agent", userAgent)
            .addHeader("X-User-Agent", xUserAgent)
            .addHeader("Referer", referer)
            .addHeader("Cookie", initialCookie)
            .build()

        var updatedCookie = initialCookie
        var httpCode = 200
        val body = client.newCall(handshakeReq).execute().use { resp ->
            httpCode = resp.code
            val setCookieHeaders = resp.headers("Set-Cookie")
            if (setCookieHeaders.isNotEmpty()) {
                val extraCookies = setCookieHeaders.mapNotNull { it.split(";").firstOrNull()?.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("mac=", ignoreCase = true) }
                if (extraCookies.isNotEmpty()) {
                    updatedCookie += "; " + extraCookies.joinToString("; ")
                }
            }
            resp.body?.string().orEmpty()
        }
        val token = parseToken(body)
        Log.d(TAG, "📥 [Handshake HTTP $httpCode] token='$token' | cookie='$updatedCookie' | body=${body.take(200)}")
        return Triple(httpCode, token, updatedCookie)
    }

    /**
     * Testa automaticamente più profili di get_profile finché il server non restituisce un "id" valido (non null).
     */
    private suspend fun getOrEstablishSession(
        portal: Portal,
        forceRefresh: Boolean = false
    ): SessionData? = sessionMutex.withLock {
        val cacheKey = portalCacheKey(portal)
        val cached = sessionCache[cacheKey]
        val now = System.currentTimeMillis()

        if (!forceRefresh && cached != null && cached.token.isNotBlank() && (now - cached.timestamp) < 120_000L) {
            return@withLock cached
        }

        val candidates = getCandidateBaseUrls(portal)
        val mac = portal.macAddress.trim().uppercase(Locale.ROOT)
        Log.d(
            TAG,
            "📋 [Portal Config] server='${portal.server}', url='${portal.url}', mac='$mac', customUA=${portal.useCustomUserAgent}, ua='${portal.userAgent}'"
        )

        if (candidates.isEmpty() || mac.isBlank()) return@withLock null

        val userAgent = resolveUserAgent(portal)
        val xUserAgent = resolveXUserAgent(portal)

        val sn = generateMagSerial(mac)
        val devId = generateMagDeviceId(mac)
        val devId2 = generateMagDeviceId2(mac)
        val sig = generateMagSignature(mac, sn)

        var lastHttpCode = 200
        var fallbackUnauthenticatedSession: SessionData? = null

        for (candidateUrl in candidates) {
            val referer = buildReferer(candidateUrl)
            val baseCookie = "mac=$mac; stb_lang=en; timezone=Europe/Rome"

            try {
                Log.d(TAG, "🚀 [Handshake] Provo endpoint: $candidateUrl")
                var (hsCode, token, sessionCookie) = doHandshakeRequest(
                    candidateUrl = candidateUrl,
                    userAgent = userAgent,
                    xUserAgent = xUserAgent,
                    referer = referer,
                    initialCookie = baseCookie
                )
                lastHttpCode = hsCode

                if (token.isBlank()) continue

                // Definiamo i 4 profili di get_profile da testare in sequenza:
                // Profilo 0: Minimal (nessun parametro extra che possa far fallire il controllo sn/device_id)
                // Profilo 1: Con SN, device_id, device_id2 e signature calcolati dal MAC (stile IPTV Extreme Pro / STB Emu)
                // Profilo 2: Base MAG250 senza sn/device_id
                // Profilo 3: Minimal con token dentro il Cookie e senza header Authorization Bearer
                val profileConfigs = listOf(
                    Triple(
                        "Profilo 0 (Minimal)",
                        "$candidateUrl?type=stb&action=get_profile&JsHttpRequest=1-xml&token=$token",
                        true
                    ),
                    Triple(
                        "Profilo 1 (Full MAG250 + SN/DeviceID generati)",
                        "$candidateUrl?type=stb&action=get_profile&hd=1&ver=ImageDescription:%200.2.18-r14-pub-250&num_banks=2&sn=$sn&stb_type=MAG250&image_version=218&device_id=$devId&device_id2=$devId2&signature=$sig&auth_second_step=0&hw_version=1.7-BD-00&not_valid_token=0&JsHttpRequest=1-xml&token=$token",
                        true
                    ),
                    Triple(
                        "Profilo 2 (MAG250 Base senza SN)",
                        "$candidateUrl?type=stb&action=get_profile&hd=1&stb_type=MAG250&image_version=218&not_valid_token=0&JsHttpRequest=1-xml&token=$token",
                        true
                    ),
                    Triple(
                        "Profilo 3 (Cookie Token senza Bearer)",
                        "$candidateUrl?type=stb&action=get_profile&JsHttpRequest=1-xml&token=$token",
                        false
                    )
                )

                for ((index, config) in profileConfigs.withIndex()) {
                    val (profileName, _, useBearer) = config

                    // Se non siamo al primo tentativo, rinegoziamo un token fresco per sicurezza
                    if (index > 0) {
                        val hsRetry = doHandshakeRequest(
                            candidateUrl = candidateUrl,
                            userAgent = userAgent,
                            xUserAgent = xUserAgent,
                            referer = referer,
                            initialCookie = baseCookie
                        )
                        if (hsRetry.second.isNotBlank()) {
                            token = hsRetry.second
                            sessionCookie = hsRetry.third
                        }
                    }

                    val activeCookie = if (!useBearer) "$sessionCookie; token=$token" else sessionCookie
                    val profileUrl = when (index) {
                        0 -> "$candidateUrl?type=stb&action=get_profile&JsHttpRequest=1-xml&token=$token"
                        1 -> "$candidateUrl?type=stb&action=get_profile&hd=1&ver=ImageDescription:%200.2.18-r14-pub-250&num_banks=2&sn=$sn&stb_type=MAG250&image_version=218&device_id=$devId&device_id2=$devId2&signature=$sig&auth_second_step=0&hw_version=1.7-BD-00&not_valid_token=0&JsHttpRequest=1-xml&token=$token"
                        2 -> "$candidateUrl?type=stb&action=get_profile&hd=1&stb_type=MAG250&image_version=218&not_valid_token=0&JsHttpRequest=1-xml&token=$token"
                        else -> "$candidateUrl?type=stb&action=get_profile&JsHttpRequest=1-xml&token=$token"
                    }

                    val (profCode, profileBody) = executeStalkerGet(
                        url = profileUrl,
                        userAgent = userAgent,
                        xUserAgent = xUserAgent,
                        referer = referer,
                        cookie = activeCookie,
                        token = token,
                        useBearerHeader = useBearer
                    )

                    val isAuth = isProfileAuthenticated(profileBody)
                    val extractedId = estraiCampoTestuale(profileBody, "id")
                    val extractedIp = estraiCampoTestuale(profileBody, "ip")
                    val extractedMac = estraiCampoTestuale(profileBody, "mac")

                    Log.d(
                        TAG,
                        "🔍 [$profileName] HTTP $profCode | Autenticato=$isAuth | id='$extractedId' | mac='$extractedMac' | ip='$extractedIp' | body=${profileBody.take(300)}"
                    )

                    val currentSession = SessionData(
                        baseUrl = candidateUrl,
                        refererUrl = referer,
                        token = token,
                        cookie = activeCookie,
                        profileJson = profileBody,
                        useBearerHeader = useBearer,
                        timestamp = System.currentTimeMillis()
                    )

                    if (isAuth) {
                        Log.d(TAG, "✅ [Sessione Autenticata con successo!] Usando $profileName (id=$extractedId)")
                        sessionCache[cacheKey] = currentSession
                        return@withLock currentSession
                    } else if (fallbackUnauthenticatedSession == null && profileBody.isNotBlank()) {
                        fallbackUnauthenticatedSession = currentSession
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Tentativo fallito su $candidateUrl: ${e.message}")
            }
        }

        if (fallbackUnauthenticatedSession != null) {
            Log.w(
                TAG,
                "⚠️ Nessun profilo ha restituito un ID != null. Uso la sessione di fallback per tentare comunque le chiamate."
            )
            sessionCache[cacheKey] = fallbackUnauthenticatedSession
            return@withLock fallbackUnauthenticatedSession
        }

        checkHttpResponseCode(lastHttpCode)
        return@withLock null
    }

    suspend fun fetchAccountInfo(portal: Portal): StalkerProfile = withContext(Dispatchers.IO) {
        try {
            val session = getOrEstablishSession(portal, forceRefresh = false)
                ?: return@withContext StalkerProfile(expireDate = "Non disponibile")

            val userAgent = resolveUserAgent(portal)
            val xUserAgent = resolveXUserAgent(portal)
            val profileBody = session.profileJson

            var mainInfoBody = ""
            for (azione in listOf("get_main_info", "get_info")) {
                val url = "${session.baseUrl}?type=account_info&action=$azione&JsHttpRequest=1-xml&token=${session.token}"
                val (code, body) = executeStalkerGet(
                    url = url,
                    userAgent = userAgent,
                    xUserAgent = xUserAgent,
                    referer = session.refererUrl,
                    cookie = session.cookie,
                    token = session.token,
                    useBearerHeader = session.useBearerHeader
                )
                Log.d(TAG, "📥 [AccountInfo $azione HTTP $code] -> $body")
                if (body.isNotBlank() && body.contains("{")) {
                    mainInfoBody += "\n$body"
                }
            }

            var tariffPlan = estraiCampoTestuale(mainInfoBody, "tariff_plan")
            if (tariffPlan == "-") tariffPlan = estraiCampoTestuale(profileBody, "tariff_plan")
            if (tariffPlan == "-") tariffPlan = estraiCampoTestuale(profileBody, "package_name")
            if (tariffPlan == "-" || tariffPlan == "0") tariffPlan = "Standard"

            val expireKeys = listOf("exp_date", "end_date", "expire_billing_date", "account_expire", "expire")
            val rawExpire = expireKeys.firstNotNullOfOrNull { key ->
                val v1 = estraiCampoTestuale(mainInfoBody, key)
                if (!isPlaceholderDate(v1)) return@firstNotNullOfOrNull v1
                val v2 = estraiCampoTestuale(profileBody, key)
                if (!isPlaceholderDate(v2)) return@firstNotNullOfOrNull v2
                null
            } ?: "-"

            var scadPhone = estraiCampoTestuale(mainInfoBody, "phone")
            if (isPlaceholderDate(scadPhone)) {
                scadPhone = estraiCampoTestuale(profileBody, "phone")
            }
            Log.d(TAG, "📅 [Scadenza Estratta] rawExpire='$rawExpire' | phone='$scadPhone'")

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
            } else if (!isPlaceholderDate(rawExpire)) {
                if (rawExpire.equals("unlimited", ignoreCase = true) || rawExpire.contains("illimitat", ignoreCase = true)) {
                    isExpired = false
                    formattedExpireDate = "Illimitato"
                } else {
                    val parsedExpire = extractDateFromCustomString(rawExpire)
                    if (parsedExpire != null) {
                        isExpired = parsedExpire.before(Date())
                        formattedExpireDate = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(parsedExpire)
                    } else {
                        formattedExpireDate = rawExpire
                    }
                }
            } else if (!isPlaceholderDate(scadPhone)) {
                val parsedPhoneDate = extractDateFromCustomString(scadPhone)
                if (parsedPhoneDate != null) {
                    isExpired = parsedPhoneDate.before(Date())
                    formattedExpireDate = SimpleDateFormat("dd/MM/yyyy", Locale.ITALIAN).format(parsedPhoneDate)
                } else if (scadPhone.matches(Regex(".*\\d{4}.*"))) {
                    formattedExpireDate = scadPhone
                }
            }

            var formattedActivationDate = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date())
            var createdTs = estraiCampoTestuale(profileBody, "created")
            if (isPlaceholderDate(createdTs)) createdTs = estraiCampoTestuale(mainInfoBody, "created")
            if (isPlaceholderDate(createdTs)) createdTs = estraiCampoTestuale(profileBody, "activation_date")

            if (!isPlaceholderDate(createdTs)) {
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
        try {
            var session = getOrEstablishSession(portal, forceRefresh = false)
                ?: return@withContext emptyList()

            val userAgent = resolveUserAgent(portal)
            val xUserAgent = resolveXUserAgent(portal)

            var categoriesMap = fetchGenres(
                baseUrl = session.baseUrl,
                userAgent = userAgent,
                xUserAgent = xUserAgent,
                referer = session.refererUrl,
                cookie = session.cookie,
                token = session.token,
                useBearerHeader = session.useBearerHeader
            )

            if (categoriesMap.isEmpty()) {
                Log.w(TAG, "🔄 Generi vuoti al primo tentativo, rinnovo sessione (handshake + get_profile)...")
                session = getOrEstablishSession(portal, forceRefresh = true) ?: return@withContext emptyList()
                categoriesMap = fetchGenres(
                    baseUrl = session.baseUrl,
                    userAgent = userAgent,
                    xUserAgent = xUserAgent,
                    referer = session.refererUrl,
                    cookie = session.cookie,
                    token = session.token,
                    useBearerHeader = session.useBearerHeader
                )
            }

            val channelsUrl = "${session.baseUrl}?type=itv&action=get_all_channels&JsHttpRequest=1-xml&token=${session.token}"
            Log.d(TAG, "📤 [Richiesta Canali URL] -> $channelsUrl")

            val (code, responseBody) = executeStalkerGet(
                url = channelsUrl,
                userAgent = userAgent,
                xUserAgent = xUserAgent,
                referer = session.refererUrl,
                cookie = session.cookie,
                token = session.token,
                useBearerHeader = session.useBearerHeader
            )
            checkHttpResponseCode(code)

            Log.d(TAG, "📥 [Risposta Canali Grezza - primi 500 char] -> ${responseBody.take(500)}")

            var channels = parseChannels(responseBody, categoriesMap)
            Log.d(TAG, "📊 Canali parsati con get_all_channels: ${channels.size}")

            if (channels.isEmpty()) {
                Log.w(TAG, "⚠️ Nessun canale trovato con get_all_channels, provo fallback get_ordered_channels...")
                channels = fetchOrderedChannels(
                    baseUrl = session.baseUrl,
                    userAgent = userAgent,
                    xUserAgent = xUserAgent,
                    referer = session.refererUrl,
                    cookie = session.cookie,
                    token = session.token,
                    useBearerHeader = session.useBearerHeader,
                    categoriesMap = categoriesMap
                )
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
        val cleanCmd = cmd.trim()
        if (cleanCmd.isBlank()) return@withContext null

        val userAgent = resolveUserAgent(portal)
        val xUserAgent = resolveXUserAgent(portal)
        val encodedCmd = URLEncoder.encode(cleanCmd, "UTF-8")

        fun executeCreateLink(activeSession: SessionData): Pair<Int, String> {
            val createLinkUrl =
                "${activeSession.baseUrl}?type=itv&action=create_link&cmd=$encodedCmd&series=&forced_storage=0&disable_ad=0&download=0&force_ch_link_check=0&JsHttpRequest=1-xml&token=${activeSession.token}"

            return executeStalkerGet(
                url = createLinkUrl,
                userAgent = userAgent,
                xUserAgent = xUserAgent,
                referer = activeSession.refererUrl,
                cookie = activeSession.cookie,
                token = activeSession.token,
                useBearerHeader = activeSession.useBearerHeader
            ).also { (code, body) ->
                Log.d(TAG, "📥 [create_link HTTP $code] -> ${body.take(250)}")
            }
        }

        try {
            var session = getOrEstablishSession(portal, forceRefresh = false)
                ?: return@withContext null

            var (code, bodyStr) = executeCreateLink(session)

            if (code == 401 || code == 424 || code == 429 ||
                bodyStr.isBlank() ||
                bodyStr.contains("Authorization failed", ignoreCase = true) ||
                (bodyStr.contains("token", ignoreCase = true) && bodyStr.contains("invalid", ignoreCase = true))
            ) {
                if (code == 424 || code == 429) {
                    delay(600L)
                }
                session = getOrEstablishSession(portal, forceRefresh = true)
                    ?: return@withContext null
                val retryResult = executeCreateLink(session)
                code = retryResult.first
                bodyStr = retryResult.second
            }

            checkHttpResponseCode(code)
            if (bodyStr.isBlank()) return@withContext null
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
        } catch (e: PortalException) {
            Log.e(TAG, "❌ PortalException durante getStreamUrl: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore risoluzione create_link Stalker: ${e.localizedMessage}", e)
            null
        }
    }

    private fun fetchGenres(
        baseUrl: String,
        userAgent: String,
        xUserAgent: String,
        referer: String,
        cookie: String,
        token: String,
        useBearerHeader: Boolean
    ): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val genresUrl = "$baseUrl?type=itv&action=get_genres&JsHttpRequest=1-xml&token=$token"

        try {
            val (code, body) = executeStalkerGet(
                url = genresUrl,
                userAgent = userAgent,
                xUserAgent = xUserAgent,
                referer = referer,
                cookie = cookie,
                token = token,
                useBearerHeader = useBearerHeader
            )
            Log.d(TAG, "📥 [get_genres HTTP $code] -> ${body.take(250)}")
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
            Log.d(TAG, "📂 Generi caricati: ${map.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Errore generi Stalker", e)
        }
        return map
    }

    private fun fetchOrderedChannels(
        baseUrl: String,
        userAgent: String,
        xUserAgent: String,
        referer: String,
        cookie: String,
        token: String,
        useBearerHeader: Boolean,
        categoriesMap: Map<String, String>
    ): List<Channel> {
        val url = "$baseUrl?type=itv&action=get_ordered_channels&genre=0&JsHttpRequest=1-xml&token=$token"

        try {
            val (_, body) = executeStalkerGet(
                url = url,
                userAgent = userAgent,
                xUserAgent = xUserAgent,
                referer = referer,
                cookie = cookie,
                token = token,
                useBearerHeader = useBearerHeader
            )
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
                val genreId = item.optString("tv_genre_id", "").ifBlank { item.optString("genre_id", "") }
                val categoryName = categoriesMap[genreId] ?: "Generale"

                var cmd = item.optString("cmd", "")
                if (cmd.isBlank()) {
                    val cmds = item.optJSONArray("cmds")
                    if (cmds != null && cmds.length() > 0) {
                        cmd = cmds.optJSONObject(0)?.optString("url", "").orEmpty()
                    }
                }

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
