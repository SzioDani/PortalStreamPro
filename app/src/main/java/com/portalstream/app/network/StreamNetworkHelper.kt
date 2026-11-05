package com.portalstream.app.network

import android.util.Log
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
     * Rileva automaticamente se lo stream è HLS/Mediaset/DASH (ExoPlayer)
     * oppure un Portale MAC / MPEG-TS / RTSP (LibVLC).
     */
    suspend fun shouldUseExoPlayer(urlStr: String, userAgentStr: String?): Boolean = withContext(Dispatchers.IO) {
        val lower = urlStr.lowercase()
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
            }

            val code = conn.responseCode
            val contentType = conn.contentType?.lowercase() ?: ""
            val finalUrl = conn.url.toString().lowercase()

            if (finalUrl.contains(".m3u8") ||
                finalUrl.contains(".mpd") ||
                contentType.contains("mpegurl") ||
                contentType.contains("dash")
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
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            userAgentStr?.let { conn.setRequestProperty("User-Agent", it) }

            val responseCode = conn.responseCode
            message = when (responseCode) {
                200 -> "❌ Impossibile decodificare il flusso video."
                401, 403 -> "🔐 Accesso rifiutato (HTTP $responseCode). Verifica User-Agent / MAC."
                404 -> "🔍 Canale non trovato (HTTP 404)."
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
