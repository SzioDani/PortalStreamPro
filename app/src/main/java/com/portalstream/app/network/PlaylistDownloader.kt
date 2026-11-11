package com.portalstream.app.network

import com.portalstream.app.domain.model.Channel
import com.portalstream.app.streaming.M3UParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class PlaylistDownloader {

    // Configuriamo OkHttp con timeout lunghi per file molto grandi
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Scarica la playlist da un URL e la processa riga per riga in tempo reale.
     * @param playlistUrl Il link .m3u o .m3u8
     * @param customUserAgent Lo User-Agent personalizzato (opzionale) scelto dai preset o inserito manualmente
     * @param onChannelExtracted Callback eseguita ogni volta che viene estratto un singolo canale
     */
    suspend fun downloadAndParse(
        playlistUrl: String,
        customUserAgent: String? = null,
        onChannelExtracted: (Channel) -> Unit
    ) {
        // Spostiamo l'operazione sul thread di Input/Output per non bloccare l'interfaccia utente
        withContext(Dispatchers.IO) {
            // Se l'utente ha impostato uno User-Agent personalizzato usiamo quello,
            // altrimenti usiamo di default il MAG250 che è compatibile con la maggior parte dei portali.
            val userAgentToUse = if (!customUserAgent.isNullOrBlank()) {
                customUserAgent
            } else {
                "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG250 stbapp ver: 2.20.06"
            }

            val request = Request.Builder()
                .url(playlistUrl)
                .header("User-Agent", userAgentToUse)
                .build()

            try {
                // Eseguiamo la chiamata. Il blocco ".use" assicura la chiusura della connessione
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Errore del server: ${response.code}")
                    }

                    // Questo è il punto chiave: prendiamo lo stream grezzo (byteStream)
                    val inputStream = response.body?.byteStream() 
                        ?: throw IOException("Il corpo della risposta è vuoto")

                    // Passiamo il flusso di rete in tempo reale al nostro parser
                    M3UParser.parseStreaming(inputStream) { channel ->
                        // Rimbalziamo il canale trovato alla callback
                        onChannelExtracted(channel)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                throw Exception("Impossibile scaricare o parsare la playlist: ${e.message}")
            }
        }
    }
}
