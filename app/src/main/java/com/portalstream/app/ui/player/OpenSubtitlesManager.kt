package com.portalstream.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class OpenSubtitleItem(
    val id: String,
    val title: String,
    val languageCode: String,
    val downloadUrl: String,
    val isApiV1: Boolean = false
)

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object OpenSubtitlesManager {

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .build()
    }

    private data class ParsedMediaQuery(
        val cleanTitle: String,
        val season: Int? = null,
        val episode: Int? = null,
        val year: Int? = null
    )

    /**
     * Pulisce il titolo grezzo del Torrent (es. "Nome.Film.2023.854x480.AVC.AAC" o "Serie.S01E02.720p")
     * per ottenere una ricerca precisa su OpenSubtitles.
     */
    private fun parseMediaQuery(rawTitle: String): ParsedMediaQuery {
        val normalized = rawTitle
            .replace('.', ' ')
            .replace('_', ' ')
            .replace('-', ' ')

        // Rileva eventuale Serie TV (es. S01E02 o 1x02)
        val seRegex = Regex("(?i)\\bS(\\d{1,2})E(\\d{1,2})\\b|\\b(\\d{1,2})x(\\d{1,2})\\b")
        val seMatch = seRegex.find(normalized)
        val season = seMatch?.let {
            (it.groupValues[1].ifBlank { it.groupValues[3] }).toIntOrNull()
        }
        val episode = seMatch?.let {
            (it.groupValues[2].ifBlank { it.groupValues[4] }).toIntOrNull()
        }

        // Rileva eventuale Anno (es. 1999, 2024)
        val yearRegex = Regex("\\b(19\\d{2}|20\\d{2})\\b")
        val yearMatch = yearRegex.find(normalized)
        val year = yearMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

        // Taglia tutto ciò che viene dopo SxxExx, l'anno o i tag tecnici video/audio
        val cutRegex = Regex(
            "(?i)\\b(S\\d{1,2}E\\d{1,2}|\\d{1,2}x\\d{1,2}|19\\d{2}|20\\d{2}|1080p|720p|480p|2160p|4k|854x480|" +
                "x264|x265|h264|h265|hevc|avc|aac|ac3|eac3|dts|bluray|bdrip|webrip|web dl|dvdrip|hdrip|ita|eng|multi|sub)\\b.*"
        )
        val cleaned = normalized.replace(cutRegex, "").replace(Regex("\\s+"), " ").trim()
        val finalTitle = cleaned.ifBlank {
            normalized.take(35).trim()
        }

        return ParsedMediaQuery(
            cleanTitle = finalTitle,
            season = season,
            episode = episode,
            year = year
        )
    }

    private fun toIso639_2(lang2: String): String = when (lang2.lowercase()) {
        "it" -> "ita"
        "en" -> "eng"
        "es" -> "spa"
        "fr" -> "fre"
        "de" -> "ger"
        else -> lang2.lowercase()
    }

    /**
     * Cerca i sottotitoli su OpenSubtitles (supporta sia API v1 ufficiale opensubtitles.com
     * sia i nodi OpenSubtitles v3 / REST pubblici con compressione Gzip).
     */
    suspend fun searchSubtitles(
        rawTorrentTitle: String,
        languageCode2: String,
        openSubtitlesComApiKey: String = ""
    ): List<OpenSubtitleItem> = withContext(Dispatchers.IO) {
        if (rawTorrentTitle.isBlank() || rawTorrentTitle.startsWith("Caricamento")) {
            return@withContext emptyList()
        }

        val parsed = parseMediaQuery(rawTorrentTitle)
        val lang3 = toIso639_2(languageCode2)
        val results = mutableListOf<OpenSubtitleItem>()

        // 1. Se è presente una API Key per api.opensubtitles.com/api/v1, interroga l'endpoint v1
        if (openSubtitlesComApiKey.isNotBlank()) {
            try {
                results.addAll(searchOpenSubtitlesComV1(parsed, languageCode2.lowercase(), openSubtitlesComApiKey.trim()))
            } catch (_: Throwable) {}
        }

        // 2. Interroga il nodo OpenSubtitles v3 (risolvendo l'IMDb ID tramite Cinemeta)
        if (results.isEmpty()) {
            try {
                results.addAll(searchViaOpenSubtitlesV3(parsed, lang3, languageCode2.uppercase()))
            } catch (_: Throwable) {}
        }

        // 3. Fallback sul nodo REST OpenSubtitles (.gz Gzip nativo)
        if (results.isEmpty()) {
            try {
                results.addAll(searchViaRestOpenSubtitlesOrg(parsed, lang3, languageCode2.uppercase()))
            } catch (_: Throwable) {}
        }

        results
    }

    private fun searchOpenSubtitlesComV1(
        parsed: ParsedMediaQuery,
        lang2: String,
        apiKey: String
    ): List<OpenSubtitleItem> {
        val encodedQuery = URLEncoder.encode(parsed.cleanTitle, "UTF-8")
        val sb = StringBuilder("https://api.opensubtitles.com/api/v1/subtitles?query=$encodedQuery&languages=$lang2")
        if (parsed.season != null) sb.append("&season_number=${parsed.season}")
        if (parsed.episode != null) sb.append("&episode_number=${parsed.episode}")
        if (parsed.year != null && parsed.season == null) sb.append("&year=${parsed.year}")

        val req = Request.Builder()
            .url(sb.toString())
            .header("Api-Key", apiKey)
            .header("User-Agent", "PortalStreamPro v1.0")
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .build()

        val list = mutableListOf<OpenSubtitleItem>()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val bodyStr = readResponseAsStringWithGzip(resp.body?.bytes() ?: return emptyList())
            val json = JSONObject(bodyStr)
            val data = json.optJSONArray("data") ?: return emptyList()
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val attrs = item.optJSONObject("attributes") ?: continue
                val files = attrs.optJSONArray("files") ?: continue
                if (files.length() == 0) continue
                val fileObj = files.getJSONObject(0)
                val fileId = fileObj.optInt("file_id", -1)
                if (fileId == -1) continue
                val release = attrs.optString("release").ifBlank {
                    fileObj.optString("file_name", "${parsed.cleanTitle} (${lang2.uppercase()})")
                }
                list.add(
                    OpenSubtitleItem(
                        id = fileId.toString(),
                        title = "${lang2.uppercase()} • $release",
                        languageCode = lang2,
                        downloadUrl = fileId.toString(),
                        isApiV1 = true
                    )
                )
            }
        }
        return list
    }

    private fun searchViaOpenSubtitlesV3(
        parsed: ParsedMediaQuery,
        lang3: String,
        langDisplay: String
    ): List<OpenSubtitleItem> {
        val isSeries = parsed.season != null && parsed.episode != null
        val catalogType = if (isSeries) "series" else "movie"
        val encodedName = URLEncoder.encode(parsed.cleanTitle, "UTF-8").replace("+", "%20")

        val metaUrl = "https://v3-cinemeta.strem.io/catalog/$catalogType/top/search=$encodedName.json"
        val imdbId = fetchFirstImdbId(metaUrl, parsed.year) ?: return emptyList()

        val subUrl = if (isSeries) {
            "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:${parsed.season}:${parsed.episode}.json"
        } else {
            "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
        }

        val req = Request.Builder()
            .url(subUrl)
            .header("User-Agent", "PortalStreamPro/1.0")
            .header("Accept-Encoding", "gzip")
            .build()

        val list = mutableListOf<OpenSubtitleItem>()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val bodyStr = readResponseAsStringWithGzip(resp.body?.bytes() ?: return emptyList())
            val json = JSONObject(bodyStr)
            val subs = json.optJSONArray("subtitles") ?: return emptyList()

            var index = 1
            for (i in 0 until subs.length()) {
                val s = subs.getJSONObject(i)
                val subLang = s.optString("lang", "").lowercase()
                val url = s.optString("url", "")
                if (url.isNotBlank() && (subLang == lang3 || subLang.startsWith(langDisplay.lowercase()))) {
                    val label = buildString {
                        append(parsed.cleanTitle)
                        if (isSeries) append(" S${parsed.season}E${parsed.episode}")
                        append(" • OpenSubtitles #$index ($langDisplay)")
                    }
                    list.add(
                        OpenSubtitleItem(
                            id = s.optString("id", "os_$i"),
                            title = label,
                            languageCode = langDisplay,
                            downloadUrl = url,
                            isApiV1 = false
                        )
                    )
                    index++
                    if (list.size >= 12) break
                }
            }
        }
        return list
    }

    private fun fetchFirstImdbId(metaUrl: String, targetYear: Int?): String? {
        val req = Request.Builder()
            .url(metaUrl)
            .header("User-Agent", "PortalStreamPro/1.0")
            .header("Accept-Encoding", "gzip")
            .build()

        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bodyStr = readResponseAsStringWithGzip(resp.body?.bytes() ?: return null)
            val metas = JSONObject(bodyStr).optJSONArray("metas") ?: return null
            if (metas.length() == 0) return null

            if (targetYear != null) {
                for (i in 0 until metas.length()) {
                    val m = metas.getJSONObject(i)
                    val releaseInfo = m.optString("releaseInfo", "")
                    if (releaseInfo.startsWith(targetYear.toString())) {
                        return m.optString("imdb_id").ifBlank { m.optString("id") }
                    }
                }
            }
            val first = metas.getJSONObject(0)
            return first.optString("imdb_id").ifBlank { first.optString("id") }.takeIf { it.startsWith("tt") }
        }
    }

    private fun searchViaRestOpenSubtitlesOrg(
        parsed: ParsedMediaQuery,
        lang3: String,
        langDisplay: String
    ): List<OpenSubtitleItem> {
        val encoded = URLEncoder.encode(parsed.cleanTitle.lowercase(), "UTF-8")
        val url = "https://rest.opensubtitles.org/search/query-$encoded/sublanguageid-$lang3"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "TemporaryUserAgent")
            .header("Accept-Encoding", "gzip")
            .build()

        val list = mutableListOf<OpenSubtitleItem>()
        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val bodyStr = readResponseAsStringWithGzip(resp.body?.bytes() ?: return emptyList())
            if (!bodyStr.trim().startsWith("[")) return emptyList()
            val arr = JSONArray(bodyStr)
            for (i in 0 until minOf(arr.length(), 12)) {
                val obj = arr.getJSONObject(i)
                val dlLink = obj.optString("SubDownloadLink", "")
                val fileName = obj.optString("SubFileName", "${parsed.cleanTitle} #${i + 1}")
                if (dlLink.isNotBlank()) {
                    list.add(
                        OpenSubtitleItem(
                            id = obj.optString("IDSubtitleFile", "rest_$i"),
                            title = "$langDisplay • $fileName",
                            languageCode = langDisplay,
                            downloadUrl = dlLink,
                            isApiV1 = false
                        )
                    )
                }
            }
        }
        return list
    }

    /**
     * Scarica e decomprime via Gzip il file .srt selezionato da OpenSubtitles,
     * restituendo la lista di SubtitleCue ordinata per millisecondi.
     */
    suspend fun downloadAndParseSubtitle(
        item: OpenSubtitleItem,
        openSubtitlesComApiKey: String = ""
    ): List<SubtitleCue> = withContext(Dispatchers.IO) {
        try {
            val finalDownloadUrl = if (item.isApiV1 && openSubtitlesComApiKey.isNotBlank()) {
                resolveApiV1DownloadLink(item.downloadUrl, openSubtitlesComApiKey.trim()) ?: return@withContext emptyList()
            } else {
                item.downloadUrl
            }

            val req = Request.Builder()
                .url(finalDownloadUrl)
                .header("User-Agent", "PortalStreamPro/1.0")
                .header("Accept-Encoding", "gzip")
                .build()

            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val rawBytes = resp.body?.bytes() ?: return@withContext emptyList()
                val srtText = readResponseAsStringWithGzip(rawBytes)
                parseSrtContent(srtText)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun resolveApiV1DownloadLink(fileId: String, apiKey: String): String? {
        val jsonBody = JSONObject().put("file_id", fileId.toIntOrNull() ?: return null).toString()
        val req = Request.Builder()
            .url("https://api.opensubtitles.com/api/v1/download")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("Api-Key", apiKey)
            .header("User-Agent", "PortalStreamPro v1.0")
            .header("Accept", "application/json")
            .build()

        okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val str = readResponseAsStringWithGzip(resp.body?.bytes() ?: return null)
            return JSONObject(str).optString("link").takeIf { it.isNotBlank() }
        }
    }

    private fun readResponseAsStringWithGzip(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val isGzip = bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
        val decompressed = if (isGzip) {
            try {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            } catch (_: Exception) {
                bytes
            }
        } else {
            bytes
        }

        // Rimuove eventuale BOM UTF-8 e gestisce fallback ISO-8859-1 per gli accenti italiani
        val utf8 = String(decompressed, Charsets.UTF_8).removePrefix("\uFEFF")
        return if (utf8.contains('\uFFFD')) {
            String(decompressed, Charset.forName("ISO-8859-1"))
        } else {
            utf8
        }
    }

    private val SRT_TIME_REGEX = Regex(
        "(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})\\s*-->\\s*(\\d{1,2}):(\\d{2}):(\\d{2})[,.](\\d{3})"
    )
    private val HTML_TAG_REGEX = Regex("<[^>]*>|\\{\\\\[^}]*\\}")

    fun parseSrtContent(srtText: String): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        val normalized = srtText.replace("\r\n", "\n").replace("\r", "\n")
        val blocks = normalized.split(Regex("\n\\s*\n"))

        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            val timeLineIndex = lines.indexOfFirst { SRT_TIME_REGEX.containsMatchIn(it) }
            if (timeLineIndex == -1) continue

            val match = SRT_TIME_REGEX.find(lines[timeLineIndex]) ?: continue
            val startMs = toMillis(
                match.groupValues[1],
                match.groupValues[2],
                match.groupValues[3],
                match.groupValues[4]
            )
            val endMs = toMillis(
                match.groupValues[5],
                match.groupValues[6],
                match.groupValues[7],
                match.groupValues[8]
            )

            if (endMs <= startMs) continue

            val textLines = lines.drop(timeLineIndex + 1)
                .joinToString("\n") { line ->
                    line.replace(HTML_TAG_REGEX, "").trim()
                }
                .trim()

            if (textLines.isNotEmpty()) {
                cues.add(SubtitleCue(startMs = startMs, endMs = endMs, text = textLines))
            }
        }
        return cues
    }

    private fun toMillis(h: String, m: String, s: String, ms: String): Long {
        return (h.toLongOrNull() ?: 0L) * 3600_000L +
            (m.toLongOrNull() ?: 0L) * 60_000L +
            (s.toLongOrNull() ?: 0L) * 1000L +
            (ms.toLongOrNull() ?: 0L)
    }

    fun findActiveText(cues: List<SubtitleCue>, positionMs: Long): String? {
        if (cues.isEmpty() || positionMs <= 0L) return null
        // Ricerca binaria veloce
        var low = 0
        var high = cues.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cue = cues[mid]
            when {
                positionMs < cue.startMs -> high = mid - 1
                positionMs > cue.endMs -> low = mid + 1
                else -> return cue.text
            }
        }
        return null
    }
}

/**
 * Overlay Sottotitoli in Jetpack Compose:
 * Applica istantaneamente e in tempo reale le modifiche di Dimensione (subtitleSizeScale)
 * e di Colore/Stile (subtitleStyle) senza appesantire LibVLC.
 */
@Composable
fun SubtitleOverlay(
    text: String?,
    sizeScale: Float,
    stylePreset: SubtitleStylePreset,
    modifier: Modifier = Modifier
) {
    if (text.isNullOrBlank()) return

    val computedFontSize = (19f * sizeScale).coerceIn(12f, 38f).sp
    val hasDarkBox = stylePreset == SubtitleStylePreset.DARK_BOX

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = if (hasDarkBox) {
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(stylePreset.bgColor)
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            } else {
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            }
        ) {
            Text(
                text = text,
                color = stylePreset.textColor,
                fontSize = computedFontSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = (computedFontSize.value * 1.22f).sp,
                style = TextStyle(
                    shadow = if (!hasDarkBox) {
                        Shadow(
                            color = Color.Black,
                            offset = Offset(2.5f, 2.5f),
                            blurRadius = 5f
                        )
                    } else {
                        null
                    }
                )
            )
        }
    }
}
                
