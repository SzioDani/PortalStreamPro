package com.portalstream.app.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume

data class ExtractedWebStream(
    val streamUrl: String,
    val title: String = "",
    val userAgent: String = DEFAULT_BROWSER_UA,
    val referer: String? = null,
    val origin: String? = null
)

object WebStreamExtractor {

    const val DEFAULT_BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }

    private val YOUTUBE_ID_REGEX = Regex(
        "(?:youtube\\.com/(?:[^/]+/.+/|(?:v|e(?:mbed)?|live|shorts)/|.*[?&]v=)|youtu\\.be/)([a-zA-Z0-9_-]{11})",
        RegexOption.IGNORE_CASE
    )

    // Lista domini pubblicitari e tracker bloccati a livello di rete
    private val AD_HOST_KEYWORDS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "popads.net", "popcash.net", "exoclick.com", "propellerads.com",
        "adsterra.com", "juicyads.com", "clickadu.com", "hilltopads.net",
        "ad-maven.com", "trafficjunky.net", "outbrain.com", "taboola.com",
        "yandex.ru/ads", "histats.com", "quantserve.com", "scorecardresearch.com"
    )

    fun isYoutubeUrl(url: String): Boolean {
        val s = url.trim().lowercase()
        return s.contains("youtube.com/") || s.contains("youtu.be/")
    }

    /**
     * Rileva se un URL è una pagina YouTube o un sito web di streaming da cui estrarre il flusso.
     */
    fun isWebExtractorSource(url: String): Boolean {
        val s = url.trim()
        if (s.isBlank()) return false
        if (TorrentStreamEngine.isTorrentSource(s)) return false
        if (isYoutubeUrl(s)) return true

        val lower = s.lowercase()
        // Esclude i flussi diretti IPTV/M3U/Xtream/MAG già pronti
        val isDirectIptv = lower.contains("player_api.php") ||
            lower.contains("get.php") ||
            lower.contains("stalker_portal") ||
            lower.contains("/portal.php") ||
            lower.contains("/server/load.php") ||
            lower.contains("live.php") ||
            lower.endsWith(".m3u") ||
            lower.endsWith(".m3u8") ||
            lower.endsWith(".ts") ||
            lower.endsWith(".mp4") ||
            lower.endsWith(".mkv")

        return !isDirectIptv && (lower.startsWith("http://") || lower.startsWith("https://"))
    }

    /**
     * Punto di ingresso principale: estrae lo stream diretto senza pubblicità da YouTube o da qualsiasi sito web.
     */
    suspend fun extractStream(
        context: Context,
        rawUrl: String,
        customUserAgent: String? = null
    ): Result<ExtractedWebStream> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = rawUrl.trim()
            val ua = customUserAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_BROWSER_UA

            // 1. GESTIONE YOUTUBE (Zero Ads, Direct Stream per ExoPlayer)
            if (isYoutubeUrl(cleanUrl)) {
                val videoId = extractYoutubeVideoId(cleanUrl)
                    ?: return@withContext Result.failure(IllegalArgumentException("ID video YouTube non trovato nel link"))

                val ytStream = extractYoutubeDirectStream(videoId)
                if (ytStream != null) {
                    return@withContext Result.success(ytStream)
                }
                return@withContext Result.failure(
                    IllegalStateException("Impossibile estrarre lo stream diretto da YouTube per questo video")
                )
            }

            // 2. SE IL LINK È GIÀ UN FLUSSO DIRETTO (.m3u8 / .mp4 / .mpd)
            if (isDirectMediaUrl(cleanUrl)) {
                val origin = extractOrigin(cleanUrl)
                return@withContext Result.success(
                    ExtractedWebStream(
                        streamUrl = cleanUrl,
                        title = "",
                        userAgent = ua,
                        referer = origin,
                        origin = origin
                    )
                )
            }

            // 3. ESTRAZIONE VELOCE HTML / JS UNPACKER (OkHttp + Gzip, segue anche gli iframe dei player)
            val staticResult = extractFromWebPageStatic(cleanUrl, ua, depth = 0)
            if (staticResult != null) {
                return@withContext Result.success(staticResult)
            }

            // 4. FALLBACK SNIFFER WEBVIEW INVISIBILE CON AD-BLOCKER INTEGRATO
            val sniffedResult = sniffStreamWithAdBlockWebView(context, cleanUrl, ua)
            if (sniffedResult != null) {
                return@withContext Result.success(sniffedResult)
            }

            Result.failure(
                IllegalStateException("Nessun flusso video (.m3u8 / .mp4) trovato nella pagina indicata")
            )
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    // =========================================================================
    // SEZIONE 1: ESTRATTORE YOUTUBE SENZA PUBBLICITÀ
    // =========================================================================

    private fun extractYoutubeVideoId(url: String): String? {
        val match = YOUTUBE_ID_REGEX.find(url)
        return match?.groupValues?.getOrNull(1)
    }

    private fun extractYoutubeDirectStream(videoId: String): ExtractedWebStream? {
        // Tentativo 1: InnerTube API (Profilo ANDROID_VR e IOS - restituiscono HLS .m3u8 e MP4 diretti senza pubblicità)
        val clients = listOf(
            Triple("ANDROID_VR", "1.56.21", "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12; Quest 2) gzip"),
            Triple("IOS", "19.29.1", "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)")
        )

        for ((clientName, clientVersion, clientUa) in clients) {
            try {
                val payload = JSONObject().apply {
                    put("videoId", videoId)
                    put("contentCheckOk", true)
                    put("racyCheckOk", true)
                    put(
                        "context",
                        JSONObject().put(
                            "client",
                            JSONObject().apply {
                                put("clientName", clientName)
                                put("clientVersion", clientVersion)
                                put("hl", "it")
                                put("gl", "IT")
                                if (clientName == "ANDROID_VR") {
                                    put("androidSdkVersion", 32)
                                }
                            }
                        )
                    )
                }

                val req = Request.Builder()
                    .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", clientUa)
                    .header("Accept-Encoding", "gzip")
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bodyStr = readBytesWithGzip(resp.body?.bytes() ?: ByteArray(0))
                        val json = JSONObject(bodyStr)
                        val title = json.optJSONObject("videoDetails")?.optString("title", "") ?: ""
                        val streamingData = json.optJSONObject("streamingData")

                        if (streamingData != null) {
                            // 1. Preferenza assoluta a hlsManifestUrl (.m3u8 adattivo con audio+video fino a 1080p/4K)
                            val hlsUrl = streamingData.optString("hlsManifestUrl", "")
                            if (hlsUrl.isNotBlank()) {
                                return ExtractedWebStream(
                                    streamUrl = hlsUrl,
                                    title = title,
                                    userAgent = clientUa
                                )
                            }

                            // 2. Formati progressivi completi (video + audio MP4)
                            val formats = streamingData.optJSONArray("formats")
                            if (formats != null && formats.length() > 0) {
                                var bestUrl = ""
                                var bestHeight = -1
                                for (i in 0 until formats.length()) {
                                    val f = formats.getJSONObject(i)
                                    val url = f.optString("url", "")
                                    val height = f.optInt("height", 0)
                                    if (url.isNotBlank() && height >= bestHeight) {
                                        bestHeight = height
                                        bestUrl = url
                                    }
                                }
                                if (bestUrl.isNotBlank()) {
                                    return ExtractedWebStream(
                                        streamUrl = bestUrl,
                                        title = title,
                                        userAgent = clientUa
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        // Tentativo 2: Fallback su nodi pubblici Piped API
        val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.moomoo.me"
        )
        for (instance in pipedInstances) {
            try {
                val req = Request.Builder()
                    .url("$instance/streams/$videoId")
                    .header("User-Agent", DEFAULT_BROWSER_UA)
                    .header("Accept-Encoding", "gzip")
                    .build()

                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(readBytesWithGzip(resp.body?.bytes() ?: ByteArray(0)))
                        val title = json.optString("title", "")
                        val hls = json.optString("hls", "")
                        if (hls.isNotBlank()) {
                            return ExtractedWebStream(
                                streamUrl = hls,
                                title = title,
                                userAgent = DEFAULT_BROWSER_UA
                            )
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        return null
    }

    // =========================================================================
    // SEZIONE 2: ESTRATTORE HTML / JS UNPACKER PER SITI STREAMING
    // =========================================================================

    private fun extractFromWebPageStatic(
        pageUrl: String,
        userAgent: String,
        referer: String? = null,
        depth: Int = 0
    ): ExtractedWebStream? {
        if (depth > 2) return null

        val pageOrigin = extractOrigin(pageUrl)
        val reqBuilder = Request.Builder()
            .url(pageUrl)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7")
            .header("Accept-Encoding", "gzip")

        if (!referer.isNullOrBlank()) {
            reqBuilder.header("Referer", referer)
        }

        val html: String
        val finalPageUrl: String
        okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val contentType = resp.header("Content-Type")?.lowercase() ?: ""
            finalPageUrl = resp.request.url.toString()

            // Se l'URL stesso ha risposto con un file video o una playlist HLS
            if (contentType.contains("mpegurl") || contentType.contains("video/") || isDirectMediaUrl(finalPageUrl)) {
                return ExtractedWebStream(
                    streamUrl = finalPageUrl,
                    title = "",
                    userAgent = userAgent,
                    referer = referer ?: pageOrigin,
                    origin = pageOrigin
                )
            }

            if (!resp.isSuccessful) return null
            html = readBytesWithGzip(resp.body?.bytes() ?: return null)
        }

        val pageTitle = extractHtmlTitle(html)

        // 1. De-offusca eventuali blocchi JS "eval(function(p,a,c,k,e,d)...)" (molto usati dai player web)
        val unpackedJs = unpackAllDeanEdwardsJs(html)
        val combinedSource = "$html\n$unpackedJs"

        // 2. Cerca URL media diretti (.m3u8, .mpd, .mp4) nel codice o nei blocchi de-offuscati
        val mediaUrl = findBestMediaUrlInText(combinedSource, finalPageUrl)
        if (mediaUrl != null) {
            return ExtractedWebStream(
                streamUrl = mediaUrl,
                title = pageTitle,
                userAgent = userAgent,
                referer = finalPageUrl,
                origin = extractOrigin(finalPageUrl)
            )
        }

        // 3. Se la pagina contiene un <iframe> verso l'embed del videoplayer, lo segue ignorando gli iframe di pubblicità
        val iframeUrls = extractValidIframeUrls(html, finalPageUrl)
        for (iframeUrl in iframeUrls) {
            val subResult = extractFromWebPageStatic(
                pageUrl = iframeUrl,
                userAgent = userAgent,
                referer = finalPageUrl,
                depth = depth + 1
            )
            if (subResult != null) {
                return subResult.copy(
                    title = subResult.title.ifBlank { pageTitle }
                )
            }
        }

        return null
    }

    private val MEDIA_URL_REGEX = Regex(
        """(https?:\\?/\\?/[^\s"'<>\\]+?\.(?:m3u8|mpd|mp4)(?:\?[^\s"'<>\\]*)?)""",
        RegexOption.IGNORE_CASE
    )

    private val ATOB_REGEX = Regex("""atob\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")

    private fun findBestMediaUrlInText(source: String, baseUrl: String): String? {
        val candidates = mutableListOf<String>()

        // Ricerca diretta .m3u8 / .mp4 / .mpd
        MEDIA_URL_REGEX.findAll(source).forEach { match ->
            val raw = match.groupValues[1]
                .replace("\\/", "/")
                .replace("&amp;", "&")
            if (!isAdUrl(raw)) {
                candidates.add(raw)
            }
        }

        // Ricerca stringhe codificate in Base64 con atob("...")
        ATOB_REGEX.findAll(source).forEach { match ->
            try {
                val decoded = String(Base64.getDecoder().decode(match.groupValues[1]), Charsets.UTF_8)
                if (isDirectMediaUrl(decoded) && !isAdUrl(decoded)) {
                    candidates.add(decoded)
                }
            } catch (_: Exception) {}
        }

        // Diamo priorità ai flussi .m3u8 (HLS adattivo) rispetto ai .mp4
        return candidates.firstOrNull { it.contains(".m3u8", ignoreCase = true) }
            ?: candidates.firstOrNull { it.contains(".mpd", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    /**
     * De-offuscatore nativo per script "eval(function(p,a,c,k,e,d)...)" usati dai siti di streaming.
     */
    private fun unpackAllDeanEdwardsJs(html: String): String {
        val packerRegex = Regex(
            """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\(\s*'((?:\\'|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\'|[^'])*)'\.split\('\|'\)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val sb = StringBuilder()
        packerRegex.findAll(html).forEach { match ->
            try {
                val payload = match.groupValues[1].replace("\\'", "'")
                val radix = match.groupValues[2].toIntOrNull() ?: 36
                val count = match.groupValues[3].toIntOrNull() ?: 0
                val symtab = match.groupValues[4].split("|")

                var unpacked = payload
                for (i in (count - 1) downTo 0) {
                    val replacement = symtab.getOrNull(i)
                    if (!replacement.isNullOrEmpty()) {
                        val token = i.toString(radix)
                        unpacked = unpacked.replace(Regex("\\b$token\\b"), replacement)
                    }
                }
                sb.append(unpacked).append("\n")
            } catch (_: Exception) {}
        }
        return sb.toString()
    }

    private fun extractValidIframeUrls(html: String, baseUrl: String): List<String> {
        val regex = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val result = mutableListOf<String>()
        regex.findAll(html).forEach { m ->
            val src = m.groupValues[1].trim().replace("&amp;", "&")
            val resolved = resolveRelativeUrl(baseUrl, src)
            if (resolved != null && !isAdUrl(resolved) && !resolved.endsWith(".html.ad")) {
                result.add(resolved)
            }
        }
        return result.distinct().take(4)
    }

    // =========================================================================
    // SEZIONE 3: SNIFFER WEBVIEW INVISIBILE CON BLOCCO TOTALE PUBBLICITÀ
    // =========================================================================

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun sniffStreamWithAdBlockWebView(
        context: Context,
        targetUrl: String,
        userAgent: String
    ): ExtractedWebStream? = withTimeoutOrNull(14_000L) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var webView: WebView? = WebView(context.applicationContext)
                var completed = false

                fun finishAndCleanUp(result: ExtractedWebStream?) {
                    if (completed) return
                    completed = true
                    try {
                        webView?.stopLoading()
                        webView?.loadUrl("about:blank")
                        webView?.destroy()
                        webView = null
                    } catch (_: Throwable) {}
                    if (cont.isActive) {
                        cont.resume(result)
                    }
                }

                cont.invokeOnCancellation {
                    Handler(Looper.getMainLooper()).post {
                        finishAndCleanUp(null)
                    }
                }

                webView?.settings?.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    blockNetworkImage = true // Non carica immagini pesanti o banner pubblicitari!
                    setSupportMultipleWindows(true) // Impedisce l'apertura di finestre popup!
                    javaScriptCanOpenWindowsAutomatically = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    this.userAgentString = userAgent
                }

                webView?.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        // Blocca qualsiasi redirect verso pubblicità, store o schemi esterni
                        if (!url.startsWith("http://") && !url.startsWith("https://")) return true
                        if (isAdUrl(url)) return true
                        return false
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null

                        // 1. Blocca immediatamente i domini pubblicitari
                        if (isAdUrl(reqUrl)) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        // 2. Intercetta il flusso video (.m3u8 / .mpd / .mp4) appena il player della pagina lo richiede!
                        if (isDirectMediaUrl(reqUrl)) {
                            val headers = request.requestHeaders ?: emptyMap()
                            val ref = headers["Referer"] ?: headers["referer"] ?: targetUrl
                            val orig = headers["Origin"] ?: headers["origin"] ?: extractOrigin(targetUrl)

                            Handler(Looper.getMainLooper()).post {
                                val pageTitle = view?.title?.let { cleanWebTitle(it) } ?: ""
                                finishAndCleanUp(
                                    ExtractedWebStream(
                                        streamUrl = reqUrl,
                                        title = pageTitle,
                                        userAgent = userAgent,
                                        referer = ref,
                                        origin = orig
                                    )
                                )
                            }
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // Simula il click sul pulsante play del player web senza toccare i banner
                        view?.evaluateJavascript(
                            """
                            (function() {
                                var videos = document.getElementsByTagName('video');
                                for (var i = 0; i < videos.length; i++) {
                                    if (videos[i].src && videos[i].src.indexOf('http') === 0) {
                                        var img = new Image();
                                        img.src = videos[i].src;
                                    }
                                    try { videos[i].play(); } catch(e) {}
                                }
                                var playBtns = document.querySelectorAll('.vjs-big-play-button, .jw-icon-display, [aria-label="Play"]');
                                for (var j = 0; j < playBtns.length; j++) {
                                    try { playBtns[j].click(); } catch(e) {}
                                }
                            })();
                            """.trimIndent(),
                            null
                        )
                    }
                }

                webView?.loadUrl(targetUrl)
            }
        }
    }

    // =========================================================================
    // UTILITY
    // =========================================================================

    private fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".ts") && !lower.contains(".m3u8")) return false
        return lower.contains(".m3u8") ||
            lower.contains(".mpd") ||
            lower.matches(Regex(".*\\.(mp4|m4v|webm)(\\?.*)?$"))
    }

    private fun isAdUrl(url: String): Boolean {
        val lower = url.lowercase()
        return AD_HOST_KEYWORDS.any { lower.contains(it) }
    }

    private fun extractOrigin(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            "$scheme://$host"
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, relativeOrFull: String): String? {
        return try {
            if (relativeOrFull.startsWith("//")) {
                "https:$relativeOrFull"
            } else {
                URI(baseUrl).resolve(relativeOrFull).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractHtmlTitle(html: String): String {
        val ogMatch = Regex("""<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)
        val rawTitle = ogMatch?.groupValues?.getOrNull(1)
            ?: Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.getOrNull(1)
            ?: return ""

        return cleanWebTitle(rawTitle)
    }

    private fun cleanWebTitle(raw: String): String {
        return raw
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace(Regex("(?i)\\s*[-|•]\\s*(streaming|guarda|watch|online|ita|hd|gratis|sub).*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun readBytesWithGzip(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val isGzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val raw = if (isGzip) {
            try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            } catch (_: Exception) {
                bytes
            }
        } else {
            bytes
        }
        return String(raw, Charsets.UTF_8)
    }
}
