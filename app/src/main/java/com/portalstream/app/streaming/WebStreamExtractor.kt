package com.portalstream.app.streaming

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
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
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume

const val DEFAULT_BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

data class ExtractedWebStream(
    val streamUrl: String,
    val title: String = "",
    val userAgent: String = DEFAULT_BROWSER_UA,
    val referer: String? = null,
    val origin: String? = null,
    val cookies: String? = null,
    val isHls: Boolean = false
)

object WebStreamExtractor {

    /**
     * Memorizza l'ultimo stream estratto con successo (incluso il titolo pulito del film/serie),
     * così OpenSubtitlesManager e la UI possono leggerlo immediatamente.
     */
    @Volatile
    var lastExtractedStream: ExtractedWebStream? = null
        private set

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

    // Domini pubblicitari, analytics e popup bloccati a livello di rete
    private val AD_HOST_KEYWORDS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "popads.net", "popcash.net", "exoclick.com", "propellerads.com",
        "adsterra.com", "juicyads.com", "clickadu.com", "hilltopads.net",
        "ad-maven.com", "trafficjunky.net", "outbrain.com", "taboola.com",
        "yandex.ru/ads", "histats.com", "quantserve.com", "scorecardresearch.com",
        "acscdn.com", "usrpubtrk.com", "onclkds.com", "tsyndicate.com",
        "realsrv.com", "bidgear.com", "adsco.re", "pushame.com"
    )

    // Parole generiche da ignorare se trovate come titolo di un iframe player
    private val GENERIC_PLAYER_TITLES = setOf(
        "player", "embed", "video", "watch", "streaming", "vixcloud",
        "supervideo", "mixdrop", "streamtape", "doodstream", "dood",
        "filemoon", "voe", "uqload", "dropload", "about:blank", "untitled"
    )

    fun isYoutubeUrl(url: String): Boolean {
        val s = url.trim().lowercase()
        return s.contains("youtube.com/") || s.contains("youtu.be/")
    }

    fun isWebExtractorSource(url: String): Boolean {
        val s = url.trim()
        if (s.isBlank()) return false
        if (TorrentStreamEngine.isTorrentSource(s)) return false
        if (isYoutubeUrl(s)) return true

        val lower = s.lowercase()
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

    suspend fun extractStream(
        context: Context,
        rawUrl: String,
        customUserAgent: String? = null
    ): Result<ExtractedWebStream> = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = rawUrl.trim()
            val ua = customUserAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_BROWSER_UA
            val fallbackSlugTitle = extractTitleFromUrlSlug(cleanUrl)

            // 1. GESTIONE YOUTUBE
            if (isYoutubeUrl(cleanUrl)) {
                val videoId = extractYoutubeVideoId(cleanUrl)
                    ?: return@withContext Result.failure(IllegalArgumentException("ID video YouTube non trovato nel link"))

                val ytStream = extractYoutubeDirectStream(videoId)
                if (ytStream != null) {
                    lastExtractedStream = ytStream
                    return@withContext Result.success(ytStream)
                }
                return@withContext Result.failure(
                    IllegalStateException("Impossibile estrarre lo stream diretto da YouTube per questo video")
                )
            }

            // 2. SE IL LINK È GIÀ UN FLUSSO DIRETTO (.m3u8 / .mp4 / .mpd)
            if (isDirectMediaUrl(cleanUrl)) {
                val origin = extractOrigin(cleanUrl)
                val direct = ExtractedWebStream(
                    streamUrl = cleanUrl,
                    title = fallbackSlugTitle,
                    userAgent = ua,
                    referer = origin,
                    origin = origin,
                    isHls = isLikelyHlsUrl(cleanUrl)
                )
                lastExtractedStream = direct
                return@withContext Result.success(direct)
            }

            // 3. ESTRAZIONE VELOCE HTML / JS UNPACKER (OkHttp + Gzip, segue anche gli iframe dei player)
            var mainPageTitle = ""
            val staticResult = extractFromWebPageStatic(
                pageUrl = cleanUrl,
                userAgent = ua,
                depth = 0,
                onMainPageTitleFound = { if (mainPageTitle.isBlank()) mainPageTitle = it }
            )
            if (staticResult != null) {
                val finalTitle = pickBestTitle(staticResult.title, mainPageTitle, fallbackSlugTitle)
                val enriched = staticResult.copy(title = finalTitle)
                lastExtractedStream = enriched
                return@withContext Result.success(enriched)
            }

            // 4. SNIFFER WEBVIEW SILENZIATO CON HOOK JS (Fetch / XHR / #EXTM3U / Video Tag)
            val sniffedResult = sniffStreamWithAdBlockWebView(
                context = context,
                targetUrl = cleanUrl,
                userAgent = ua,
                fallbackTitle = pickBestTitle(mainPageTitle, fallbackSlugTitle)
            )
            if (sniffedResult != null) {
                val finalTitle = pickBestTitle(sniffedResult.title, mainPageTitle, fallbackSlugTitle)
                val enriched = sniffedResult.copy(title = finalTitle)
                lastExtractedStream = enriched
                return@withContext Result.success(enriched)
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
        return YOUTUBE_ID_REGEX.find(url)?.groupValues?.getOrNull(1)
    }

    private fun extractYoutubeDirectStream(videoId: String): ExtractedWebStream? {
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
                            val hlsUrl = streamingData.optString("hlsManifestUrl", "")
                            if (hlsUrl.isNotBlank()) {
                                return ExtractedWebStream(
                                    streamUrl = hlsUrl,
                                    title = title,
                                    userAgent = clientUa,
                                    isHls = true
                                )
                            }

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
                                        userAgent = clientUa,
                                        isHls = false
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

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
                                userAgent = DEFAULT_BROWSER_UA,
                                isHls = true
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
        depth: Int = 0,
        onMainPageTitleFound: (String) -> Unit = {}
    ): ExtractedWebStream? {
        if (depth > 3) return null

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
        val setCookiesHeader: String?
        okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            val contentType = resp.header("Content-Type")?.lowercase() ?: ""
            finalPageUrl = resp.request.url.toString()
            setCookiesHeader = resp.headers("Set-Cookie").joinToString("; ") { it.substringBefore(";") }.takeIf { it.isNotBlank() }

            if (contentType.contains("mpegurl") || contentType.contains("video/") || isDirectMediaUrl(finalPageUrl)) {
                return ExtractedWebStream(
                    streamUrl = finalPageUrl,
                    title = "",
                    userAgent = userAgent,
                    referer = referer ?: pageOrigin,
                    origin = pageOrigin,
                    cookies = setCookiesHeader,
                    isHls = contentType.contains("mpegurl") || isLikelyHlsUrl(finalPageUrl)
                )
            }

            if (!resp.isSuccessful) return null
            html = readBytesWithGzip(resp.body?.bytes() ?: return null)
        }

        // Se la risposta stessa è un file M3U8 mascherato (inizia con #EXTM3U)
        if (html.trimStart().startsWith("#EXTM3U")) {
            return ExtractedWebStream(
                streamUrl = finalPageUrl,
                title = "",
                userAgent = userAgent,
                referer = referer ?: pageOrigin,
                origin = pageOrigin,
                cookies = setCookiesHeader,
                isHls = true
            )
        }

        val pageTitle = extractHtmlTitle(html)
        if (depth == 0 && isUsefulTitle(pageTitle)) {
            onMainPageTitleFound(pageTitle)
        }

        // 1. De-offusca blocchi JS "eval(function(p,a,c,k,e,d)...)"
        val unpackedJs = unpackAllDeanEdwardsJs(html)
        val combinedSource = "$html\n$unpackedJs"

        // 2. Cerca configurazioni VixCloud / JWPlayer / VideoJS / URL diretti
        val mediaUrl = findBestMediaUrlInText(combinedSource, finalPageUrl)
        if (mediaUrl != null) {
            return ExtractedWebStream(
                streamUrl = mediaUrl,
                title = if (isUsefulTitle(pageTitle)) pageTitle else "",
                userAgent = userAgent,
                referer = finalPageUrl,
                origin = extractOrigin(finalPageUrl),
                cookies = setCookiesHeader,
                isHls = isLikelyHlsUrl(mediaUrl)
            )
        }

        // 3. Segue gli <iframe> dei player ignorando pubblicità
        val iframeUrls = extractValidIframeUrls(html, finalPageUrl)
        for (iframeUrl in iframeUrls) {
            val subResult = extractFromWebPageStatic(
                pageUrl = iframeUrl,
                userAgent = userAgent,
                referer = finalPageUrl,
                depth = depth + 1,
                onMainPageTitleFound = onMainPageTitleFound
            )
            if (subResult != null) {
                return subResult.copy(
                    title = pickBestTitle(subResult.title, pageTitle)
                )
            }
        }

        return null
    }

    private val MEDIA_URL_REGEX = Regex(
        """(https?:\\?/\\?/[^\s"'<>\\]+?\.(?:m3u8|mpd|mp4|m4v)(?:\?[^\s"'<>\\]*)?)""",
        RegexOption.IGNORE_CASE
    )

    // Intercetta anche playlist senza estensione .m3u8 (es. VixCloud /playlist/12345?..., master.txt, ecc.)
    private val PLAYER_SOURCE_REGEX = Regex(
        """(?:file|src|url|hls|masterPlaylist)\s*[:=]\s*["'](https?:\\?/\\?/[^"'\s<>]+(?:/playlist/\d+|\.m3u8|master\.txt|index\.txt|videoplayback\?)[^"'\s<>]*)["']""",
        RegexOption.IGNORE_CASE
    )

    // Specifica per VixCloud / StreamingCommunity (window.masterPlaylist = { params: { 'token': '...', 'expires': '...' }, url: 'https://vixcloud.co/playlist/...' })
    private val VIXCLOUD_URL_REGEX = Regex(
        """url\s*:\s*['"](https?://[^'"]+/playlist/\d+[^'"]*)['"]""",
        RegexOption.IGNORE_CASE
    )

    private val ATOB_REGEX = Regex("""atob\(\s*["']([A-Za-z0-9+/=]+)["']\s*\)""")

    private fun findBestMediaUrlInText(source: String, baseUrl: String): String? {
        val candidates = mutableListOf<String>()

        // 1. Controllo specifico VixCloud con ricostruzione token/expires se presenti
        VIXCLOUD_URL_REGEX.find(source)?.let { match ->
            var basePlaylistUrl = match.groupValues[1].replace("\\/", "/")
            val token = Regex("""['"]token['"]\s*:\s*['"]([^'"]+)['"]""").find(source)?.groupValues?.getOrNull(1)
            val expires = Regex("""['"]expires['"]\s*:\s*['"]([^'"]+)['"]""").find(source)?.groupValues?.getOrNull(1)
            if (!token.isNullOrBlank() && !expires.isNullOrBlank() && !basePlaylistUrl.contains("token=")) {
                val sep = if (basePlaylistUrl.contains("?")) "&" else "?"
                basePlaylistUrl = "${basePlaylistUrl}${sep}token=$token&expires=$expires&h=1"
            }
            candidates.add(basePlaylistUrl)
        }

        // 2. Ricerca pattern player (file: "...", url: "...", ecc.)
        PLAYER_SOURCE_REGEX.findAll(source).forEach { match ->
            val raw = cleanExtractedUrl(match.groupValues[1])
            if (isValidStreamCandidate(raw)) {
                candidates.add(raw)
            }
        }

        // 3. Ricerca diretta .m3u8 / .mp4 / .mpd
        MEDIA_URL_REGEX.findAll(source).forEach { match ->
            val raw = cleanExtractedUrl(match.groupValues[1])
            if (isValidStreamCandidate(raw)) {
                candidates.add(raw)
            }
        }

        // 4. Ricerca stringhe Base64 in atob("...")
        ATOB_REGEX.findAll(source).forEach { match ->
            try {
                val decoded = cleanExtractedUrl(
                    String(Base64.getDecoder().decode(match.groupValues[1]), Charsets.UTF_8)
                )
                if (isDirectMediaUrl(decoded) && isValidStreamCandidate(decoded)) {
                    candidates.add(decoded)
                }
            } catch (_: Exception) {}
        }

        return candidates.firstOrNull { isLikelyHlsUrl(it) }
            ?: candidates.firstOrNull { it.contains(".mpd", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    private fun cleanExtractedUrl(raw: String): String {
        return raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
            .trim()
    }

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
        val regex = Regex("""<iframe[^>]+(?:data-)?src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val result = mutableListOf<String>()
        regex.findAll(html).forEach { m ->
            val src = m.groupValues[1].trim().replace("&amp;", "&")
            if (src.isBlank() || src.startsWith("javascript:") || src == "about:blank") return@forEach
            val resolved = resolveRelativeUrl(baseUrl, src)
            if (resolved != null && !isAdUrl(resolved) && !resolved.endsWith(".html.ad")) {
                result.add(resolved)
            }
        }
        return result.distinct().take(5)
    }

    // =========================================================================
    // SEZIONE 3: SNIFFER WEBVIEW COMPLETAMENTE SILENZIATO + JS BRIDGE
    // =========================================================================

    /**
     * Script JS iniettato nella WebView:
     * 1. Silenzia IMMEDIATAMENTE qualsiasi elemento <video> e <audio> (così non si sente mai l'audio in background!).
     * 2. Intercetta XMLHttpRequest e window.fetch: se la risposta inizia con #EXTM3U (anche su URL mascherati!),
     *    invia subito l'URL ad Android tramite JSBridge.
     * 3. Intercetta HTMLMediaElement.prototype.play e <video>.src.
     * 4. Cerca eventuali <iframe> di player video se la pagina principale è un contenitore.
     */
    private val JS_STREAM_SNIFFER_SCRIPT = """
        (function() {
            if (window.__portalStreamHooked) return;
            window.__portalStreamHooked = true;

            function muteAllMedia() {
                try {
                    var media = document.querySelectorAll('video, audio');
                    for (var i = 0; i < media.length; i++) {
                        media[i].muted = true;
                        media[i].defaultMuted = true;
                        media[i].volume = 0;
                        var src = media[i].currentSrc || media[i].src || '';
                        if (src && src.indexOf('http') === 0) {
                            window.PortalBridge.onMediaFound(src, document.title || '', false);
                        }
                    }
                } catch(e) {}
            }

            // 1. Intercetta play() su qualsiasi video/audio per silenziarlo all'istante e leggerne il src
            try {
                var origPlay = HTMLMediaElement.prototype.play;
                HTMLMediaElement.prototype.play = function() {
                    try {
                        this.muted = true;
                        this.defaultMuted = true;
                        this.volume = 0;
                        var s = this.currentSrc || this.src || '';
                        if (s && s.indexOf('http') === 0) {
                            window.PortalBridge.onMediaFound(s, document.title || '', false);
                        }
                        this.pause();
                    } catch(e) {}
                    return origPlay.apply(this, arguments);
                };
            } catch(e) {}

            // 2. Intercetta fetch() per catturare manifest HLS (#EXTM3U) anche con URL mascherati
            try {
                var origFetch = window.fetch;
                window.fetch = function(input, init) {
                    var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                    if (url && url.indexOf('http') === 0) {
                        var low = url.toLowerCase();
                        if (low.indexOf('.m3u8') !== -1 || low.indexOf('/playlist/') !== -1 || low.indexOf('master.txt') !== -1) {
                            window.PortalBridge.onMediaFound(url, document.title || '', true);
                        }
                    }
                    return origFetch.apply(this, arguments).then(function(response) {
                        try {
                            var respUrl = response.url || url;
                            var ct = (response.headers && response.headers.get('content-type')) ? response.headers.get('content-type').toLowerCase() : '';
                            if (ct.indexOf('mpegurl') !== -1) {
                                window.PortalBridge.onMediaFound(respUrl, document.title || '', true);
                            } else {
                                response.clone().text().then(function(txt) {
                                    if (txt && txt.trim().indexOf('#EXTM3U') === 0) {
                                        window.PortalBridge.onMediaFound(respUrl, document.title || '', true);
                                    }
                                }).catch(function(){});
                            }
                        } catch(e) {}
                        return response;
                    });
                };
            } catch(e) {}

            // 3. Intercetta XMLHttpRequest per hls.js / JWPlayer / VideoJS
            try {
                var origOpen = XMLHttpRequest.prototype.open;
                XMLHttpRequest.prototype.open = function(method, url) {
                    this.__psUrl = url;
                    if (url && typeof url === 'string' && url.indexOf('http') === 0) {
                        var low = url.toLowerCase();
                        if (low.indexOf('.m3u8') !== -1 || low.indexOf('/playlist/') !== -1 || low.indexOf('master.txt') !== -1) {
                            window.PortalBridge.onMediaFound(url, document.title || '', true);
                        }
                    }
                    this.addEventListener('load', function() {
                        try {
                            var body = (this.responseType === '' || this.responseType === 'text') ? this.responseText : '';
                            if (body && body.trim().indexOf('#EXTM3U') === 0) {
                                var finalUrl = this.responseURL || this.__psUrl || '';
                                if (finalUrl) {
                                    window.PortalBridge.onMediaFound(finalUrl, document.title || '', true);
                                }
                            }
                        } catch(e) {}
                    });
                    return origOpen.apply(this, arguments);
                };
            } catch(e) {}

            // 4. Loop periodico: mantiene mutati i video, clicca il play per innescare la richiesta di rete e controlla iframe
            var ticks = 0;
            var timer = setInterval(function() {
                ticks++;
                muteAllMedia();
                try {
                    var playBtns = document.querySelectorAll('.vjs-big-play-button, .jw-icon-display, .plyr__control--overlaid, [aria-label="Play"], .play-button');
                    for (var j = 0; j < playBtns.length; j++) {
                        playBtns[j].click();
                    }
                } catch(e) {}

                // Se dopo ~2.5 secondi siamo su una pagina contenitore con un iframe player, segnala l'iframe al bridge
                if (ticks === 5) {
                    try {
                        var iframes = document.querySelectorAll('iframe');
                        for (var k = 0; k < iframes.length; k++) {
                            var isrc = iframes[k].src || iframes[k].getAttribute('data-src') || '';
                            if (isrc && isrc.indexOf('http') === 0) {
                                window.PortalBridge.onIframeFound(isrc, document.title || '');
                            }
                        }
                    } catch(e) {}
                }
                if (ticks > 30) clearInterval(timer);
            }, 450);

            muteAllMedia();
        })();
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun sniffStreamWithAdBlockWebView(
        context: Context,
        targetUrl: String,
        userAgent: String,
        fallbackTitle: String
    ): ExtractedWebStream? = withTimeoutOrNull(15_000L) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var webView: WebView? = WebView(context.applicationContext)
                var completed = false
                var currentReferer = targetUrl
                var bestDetectedTitle = fallbackTitle
                val visitedIframes = mutableSetOf<String>()

                fun buildStreamResult(
                    rawStreamUrl: String,
                    rawTitle: String,
                    refererUrl: String,
                    forceHls: Boolean
                ): ExtractedWebStream {
                    val cookies = try {
                        CookieManager.getInstance().getCookie(rawStreamUrl)
                            ?: CookieManager.getInstance().getCookie(refererUrl)
                    } catch (_: Throwable) {
                        null
                    }
                    val cleanedTitle = cleanWebTitle(rawTitle)
                    val finalTitle = pickBestTitle(cleanedTitle, bestDetectedTitle, fallbackTitle)
                    return ExtractedWebStream(
                        streamUrl = rawStreamUrl,
                        title = finalTitle,
                        userAgent = userAgent,
                        referer = refererUrl,
                        origin = extractOrigin(refererUrl),
                        cookies = cookies,
                        isHls = forceHls || isLikelyHlsUrl(rawStreamUrl)
                    )
                }

                fun finishAndCleanUp(result: ExtractedWebStream?) {
                    if (completed) return
                    completed = true
                    try {
                        webView?.stopLoading()
                        webView?.onPause()
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

                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                webView?.let { cookieManager.setAcceptThirdPartyCookies(it, true) }

                // Bridge JS -> Kotlin per ricevere all'istante il flusso intercettato dal player
                val jsBridge = object {
                    @JavascriptInterface
                    fun onMediaFound(url: String?, pageTitle: String?, isHls: Boolean) {
                        val clean = url?.trim() ?: return
                        if (!isValidStreamCandidate(clean)) return
                        Handler(Looper.getMainLooper()).post {
                            if (!completed) {
                                finishAndCleanUp(
                                    buildStreamResult(
                                        rawStreamUrl = clean,
                                        rawTitle = pageTitle ?: "",
                                        refererUrl = currentReferer,
                                        forceHls = isHls
                                    )
                                )
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onIframeFound(iframeUrl: String?, pageTitle: String?) {
                        val cleanIframe = iframeUrl?.trim() ?: return
                        if (isAdUrl(cleanIframe) || visitedIframes.contains(cleanIframe)) return
                        visitedIframes.add(cleanIframe)
                        Handler(Looper.getMainLooper()).post {
                            if (!completed) {
                                val cleaned = cleanWebTitle(pageTitle ?: "")
                                if (isUsefulTitle(cleaned) && bestDetectedTitle.isBlank()) {
                                    bestDetectedTitle = cleaned
                                }
                                // Naviga dentro l'iframe del player passando il Referer della pagina principale
                                val prevUrl = webView?.url ?: targetUrl
                                currentReferer = prevUrl
                                webView?.loadUrl(cleanIframe, mapOf("Referer" to prevUrl))
                            }
                        }
                    }
                }

                webView?.addJavascriptInterface(jsBridge, "PortalBridge")

                webView?.settings?.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    blockNetworkImage = true
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    this.userAgentString = userAgent
                }

                // Silenzia eventuali popup JS (alert/confirm)
                webView?.webChromeClient = object : WebChromeClient() {}

                webView?.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        if (!url.startsWith("http://") && !url.startsWith("https://")) return true
                        if (isAdUrl(url)) return true
                        return false
                    }

                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null

                        // 1. Blocca domini pubblicitari e tracker
                        if (isAdUrl(reqUrl)) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        // 2. Intercetta richieste di rete per flussi video (.m3u8, .mp4, /playlist/, master.txt, ecc.)
                        val headers = request.requestHeaders ?: emptyMap()
                        val acceptHeader = (headers["Accept"] ?: headers["accept"] ?: "").lowercase()
                        val isHlsAccept = acceptHeader.contains("mpegurl")

                        if ((isDirectMediaUrl(reqUrl) || isHlsAccept) && isValidStreamCandidate(reqUrl)) {
                            val ref = headers["Referer"] ?: headers["referer"] ?: currentReferer

                            Handler(Looper.getMainLooper()).post {
                                if (!completed) {
                                    val vTitle = view?.title ?: ""
                                    finishAndCleanUp(
                                        buildStreamResult(
                                            rawStreamUrl = reqUrl,
                                            rawTitle = vTitle,
                                            refererUrl = ref,
                                            forceHls = isHlsAccept || isLikelyHlsUrl(reqUrl)
                                        )
                                    )
                                }
                            }
                            // Ritorna risposta vuota alla WebView così il player web NON scarica e NON riproduce l'audio!
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(JS_STREAM_SNIFFER_SCRIPT, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val title = cleanWebTitle(view?.title ?: "")
                        if (isUsefulTitle(title) && bestDetectedTitle.isBlank()) {
                            bestDetectedTitle = title
                        }
                        view?.evaluateJavascript(JS_STREAM_SNIFFER_SCRIPT, null)
                    }
                }

                webView?.loadUrl(targetUrl)
            }
        }
    }

    // =========================================================================
    // UTILITY E PULIZIA TITOLI PER OPENSUBTITLES
    // =========================================================================

    private fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        // Ignora i singoli segmenti .ts / .m4s / .aac
        if ((lower.contains(".ts") || lower.contains(".m4s")) && !lower.contains(".m3u8")) return false
        if (lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".woff") ||
            lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".webp") ||
            lower.endsWith(".svg") || lower.endsWith(".ico") || lower.endsWith(".vtt") ||
            lower.endsWith(".srt")
        ) return false

        return lower.contains(".m3u8") ||
            lower.contains(".mpd") ||
            lower.contains("/playlist/") ||
            lower.contains("master.txt") ||
            lower.contains("videoplayback?") ||
            lower.contains("mime=video") ||
            lower.matches(Regex(".*\\.(mp4|m4v|webm|mkv)(\\?.*)?$"))
    }

    private fun isLikelyHlsUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") ||
            lower.contains("/playlist/") ||
            lower.contains("master.txt") ||
            lower.contains("index.txt") ||
            lower.contains("mpegurl") ||
            lower.contains("hls")
    }

    private fun isValidStreamCandidate(url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        if (isAdUrl(url)) return false
        val lower = url.lowercase()
        // Evita falsi positivi come preview/trailer pubblicitari di 1 secondo o file statici
        if (lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".gif") || lower.endsWith(".js")) {
            return false
        }
        return true
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
        val ogMatch = Regex(
            """<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html) ?: Regex(
            """<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:title["']""",
            RegexOption.IGNORE_CASE
        ).find(html)

        val rawTitle = ogMatch?.groupValues?.getOrNull(1)
            ?: Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .find(html)?.groupValues?.getOrNull(1)
            ?: return ""

        return cleanWebTitle(rawTitle)
    }

    private fun isUsefulTitle(title: String): Boolean {
        val t = title.trim()
        if (t.length < 2) return false
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return false
        return !GENERIC_PLAYER_TITLES.contains(t.lowercase())
    }

    private fun pickBestTitle(vararg candidates: String): String {
        return candidates.firstOrNull { isUsefulTitle(it) }?.trim() ?: ""
    }

    /**
     * Estrae un titolo pulito anche direttamente dall'indirizzo web se la pagina non ha <title>
     * (es. "https://sito.it/film/1234-gladiator-ii-2024-streaming-ita" -> "Gladiator II 2024")
     */
    fun extractTitleFromUrlSlug(url: String): String {
        return try {
            val uri = Uri.parse(url)
            val segments = uri.pathSegments.orEmpty()
                .filter { seg ->
                    val s = seg.lowercase()
                    s.length > 2 &&
                        s !in listOf("film", "movie", "movies", "serie", "serie-tv", "series", "tv", "watch", "embed", "e", "v", "play", "playlist", "it", "en") &&
                        !s.all { it.isDigit() }
                }
            val rawSlug = segments.lastOrNull() ?: return ""
            val decoded = URLDecoder.decode(rawSlug, "UTF-8")
                .substringBeforeLast(".")
                .replace(Regex("^\\d+[-_]"), "") // Rimuove ID numerico iniziale tipo "1492-nome-film"
                .replace('-', ' ')
                .replace('_', ' ')

            cleanWebTitle(decoded).replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        } catch (_: Exception) {
            ""
        }
    }

    fun cleanWebTitle(raw: String): String {
        return raw
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&#8211;", "-")
            .replace("&#8217;", "'")
            // Rimuove prefissi tipici dei siti streaming ("Guarda ", "Watch ", ecc.)
            .replace(Regex("(?i)^\\s*(guarda|watch|vedere|streaming)\\s+"), "")
            // Converte "Stagione 1 Episodio 2" in "S01E02" così OpenSubtitles riconosce subito la serie!
            .replace(Regex("(?i)stagione\\s*(\\d{1,2})\\s*[-–•:]?\\s*episodio\\s*(\\d{1,2})")) { m ->
                val s = m.groupValues[1].padStart(2, '0')
                val e = m.groupValues[2].padStart(2, '0')
                "S${s}E${e}"
            }
            // Rimuove suffissi tipici dei siti di streaming
            .replace(
                Regex("(?i)\\s*[-|•:/]\\s*(streaming|guarda|watch|online|ita|sub\\s*ita|hd|gratis|altadefinizione|cb01|cineblog|eurostreaming|streamingcommunity|tantifilm|casacinema|guardaserie|ilgeniodellostreaming).*"),
                ""
            )
            .replace(
                Regex("(?i)\\b(streaming\\s+ita|streaming\\s+gratis|alta\\s*definizione|altadefinizione|cb01|eurostreaming|streamingcommunity|sub\\s+ita|in\\s+streaming)\\b.*"),
                ""
            )
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
