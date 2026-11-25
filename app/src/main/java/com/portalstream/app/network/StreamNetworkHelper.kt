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

/**
 * Tipi di errore mappati per associare facilmente le icone vettoriali nella UI (Jetpack Compose).
 */
enum class StreamErrorType {
    DECODE_ERROR,
    UNAUTHORIZED,      // 401, 403 (Es. MAC errato, abbonamento scaduto)
    NOT_FOUND,         // 404 (Canale inesistente)
    GEO_BLOCKED,       // 456 (Blocco geolocalizzazione / VPN)
    CONNECTION_LIMIT,  // 424, 429, 458, 459, 462 (Limite connessione / dipendenza / VPN)
    SERVER_ERROR,      // 500-504 (Problemi sul server)
    NETWORK_FAILURE,   // Errore di connessione / assenza internet
    UNKNOWN
}

data class StreamErrorResult(
    val message: String,
    val type: StreamErrorType,
    val httpCode: Int? = null
)

object StreamNetworkHelper {

    private const val FALLBACK_MAG_USER_AGENT =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

    /**
     * Rileva automaticamente se lo stream è HLS/Mediaset/DASH (ExoPlayer)
     * oppure un Portale MAC / MPEG-TS / RTSP (LibVLC).
     */
    suspend fun shouldUseExoPlayer(urlStr: String, userAgentStr: String?): Boolean = withContext(Dispatchers.IO) {
        val lower = urlStr.lowercase()
        // Riconosce subito tutti i flussi MAC/Stalker senza aprire connessioni sonda che bruciano il play_token!
        if (lower.contains("live.php") ||
            lower.contains("extension=ts") ||
            lower.contains("play_token=") ||
            lower.contains("/play/") ||
            lower.contains("/ch/") ||
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
            val effectiveUa = if (!userAgentStr.isNullOrBlank()) {
                userAgentStr.trim()
            } else {
                FALLBACK_MAG_USER_AGENT
            }

            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty("User-Agent", effectiveUa)
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
     * Verifica il codice di errore HTTP restituendo un oggetto strutturato (Messaggio + Tipo).
     */
    suspend fun checkHttpError(urlStr: String, userAgentStr: String?): StreamErrorResult = withContext(Dispatchers.IO) {
        try {
            val effectiveUa = if (!userAgentStr.isNullOrBlank()) {
                userAgentStr.trim()
            } else {
                FALLBACK_MAG_USER_AGENT
            }

            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", effectiveUa)

            val responseCode = conn.responseCode
            val (message, type) = when (responseCode) {
                200 -> Pair("Impossibile decodificare il flusso video.", StreamErrorType.DECODE_ERROR)
                401, 403 -> Pair("Accesso rifiutato (HTTP $responseCode). Verifica User-Agent, indirizzo MAC o VPN", StreamErrorType.UNAUTHORIZED)
                404 -> Pair("Canale non trovato (HTTP 404).", StreamErrorType.NOT_FOUND)
                424 -> Pair("Errore di dipendenza o connessione (HTTP 424). Possibile limite di connessione o blocco/conflitto VPN.", StreamErrorType.CONNECTION_LIMIT)
                456 -> Pair("IP rifiutato (HTTP 456). Possibile errore di geolocalizzazione o VPN attiva.", StreamErrorType.GEO_BLOCKED)
                459 -> Pair("Limite connessioni raggiunto: troppi utenti collegati (HTTP $responseCode).", StreamErrorType.CONNECTION_LIMIT)
                429, 458, 462 -> Pair("Limite di richieste raggiunto (HTTP $responseCode).", StreamErrorType.CONNECTION_LIMIT)
                in 500..504 -> Pair("Server non disponibile o temporaneamente offline (HTTP $responseCode).", StreamErrorType.SERVER_ERROR)
                else -> Pair("Errore HTTP inatteso ($responseCode).", StreamErrorType.UNKNOWN)
            }
            conn.disconnect()
            return@withContext StreamErrorResult(message, type, responseCode)
        } catch (e: Exception) {
            Log.e("PlayerDebug", "Errore HTTP check", e)
            return@withContext StreamErrorResult(
                message = "Impossibile stabilire una connessione con il server.",
                type = StreamErrorType.NETWORK_FAILURE
            )
        }
    }
}
