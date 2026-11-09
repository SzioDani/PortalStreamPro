package com.portalstream.app.network

import android.util.Log
import android.webkit.CookieManager
import com.portalstream.app.streaming.WebStreamExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class UiAudioTrack(
    val id: Int,
    val name: String,
    val formattedInfo: String,
    val exoGroupIndex: Int = -1,
    val exoTrackIndex: Int = -1
)

object StreamNetworkHelper {

    /**
     * Rileva automaticamente se lo stream è HLS/WebStream/MP4/DASH (ExoPlayer)
     * oppure un Portale MAC / MPEG-TS / RTSP (LibVLC).
     */
    suspend fun shouldUseExoPlayer(urlStr: String, userAgentStr: String?): Boolean = withContext(Dispatchers.IO) {
        val lower = urlStr.lowercase()

        // Se è l'ultimo stream estratto da WebStreamExtractor, usa sempre ExoPlayer (che supporta Referer/Origin/Cookies)
        val lastWeb = WebStreamExtractor.lastExtractedStream
        if (lastWeb != null && lastWeb.streamUrl == urlStr) {
            return@withContext true
        }

        if (lower.contains("live.php") ||
            lower.contains("extension=ts") ||
            lower.endsWith(".ts") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("udp://") ||
            lower.startsWith("rtp://")
        ) {
            return@withContext false
        }

        if (lower.contains(".m3u8") ||
            lower.contains(".mpd") ||
            lower.contains(".mp4") ||
            lower.contains(".m4v") ||
            lower.contains(".webm") ||
            lower.contains("/playlist/") ||
            lower.contains("master.txt") ||
            lower.contains("videoplayback") ||
            lower.contains(".isml") ||
            lower.contains("mediaset") ||
            lower.contains("manifest")
        ) {
            return@withContext true
        }

        try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 2500
                readTimeout = 2500
                userAgentStr?.let { setRequestProperty("User-Agent", it) }
                try {
                    CookieManager.getInstance().getCookie(urlStr)?.let {
                        setRequestProperty("Cookie", it)
                    }
                } catch (_: Throwable) {}
            }

            val code = conn.responseCode
            val contentType = conn.contentType?.lowercase() ?: ""
            val finalUrl = conn.url.toString().lowercase()

            if (finalUrl.contains(".m3u8") ||
                finalUrl.contains(".mpd") ||
                finalUrl.contains(".mp4") ||
                contentType.contains("mpegurl") ||
                contentType.contains("dash") ||
                contentType.contains("video/mp4") ||
                contentType.contains("video/webm")
            ) {
                conn.disconnect()
                return@withContext true
            }

            if (code == 200) {
                val firstBytes = ByteArray(7)
                val read = conn.inputStream.read(firstBytes)
                conn.disconnect()
                if (read >= 7) {
                    val headerStr = String(firstBytes, Charsets.UTF_8)
                    if (headerStr.startsWith("#EXTM3U")) {
                        return@withContext true
                    }
                }
            } else {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w("PlayerDebug", "Controllo tipo stream fallito, fallback su LibVLC", e)
        }

        return@withContext false
    }

    /**
     * Verifica il codice di errore HTTP quando uno stream fallisce.
     */
    suspend fun checkHttpError(urlStr: String, userAgentStr: String?): String = withContext(Dispatchers.IO) {
        var message = "❌ Errore durante la riproduzione del flusso multimediale."
        try {
            val lastWeb = WebStreamExtractor.lastExtractedStream?.takeIf { it.streamUrl == urlStr }
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            userAgentStr?.let { conn.setRequestProperty("User-Agent", it) }
            lastWeb?.referer?.let { conn.setRequestProperty("Referer", it) }
            lastWeb?.origin?.let { conn.setRequestProperty("Origin", it) }
            lastWeb?.cookies?.let { conn.setRequestProperty("Cookie", it) }

            val responseCode = conn.responseCode
            message = when (responseCode) {
                200 -> "❌ Impossibile decodificare il flusso video."
                401, 403 -> "🔐 Accesso rifiutato (HTTP $responseCode). Token scaduto o protezione CDN."
                404 -> "🔍 Flusso o canale non trovato (HTTP 404)."
                456 -> "🚫 IP rifiutato (HTTP 456) Errore geolocalizzazione (VPN)."
                459 -> "📊 Troppi utenti collegati (HTTP $responseCode)."
                429, 458, 462 -> "⏱️ Limite raggiunto (HTTP $responseCode)."
                in 500..504 -> "🔧 Server non disponibile (HTTP $responseCode)."
                else -> "❌ Errore HTTP $responseCode."
            }
            conn.disconnect()
        } catch (e: Exception) {
            message = "🌐 Impossibile connettersi al server."
            Log.e("PlayerDebug", "Errore HTTP check", e)
        }
        return@withContext message
    }
}
