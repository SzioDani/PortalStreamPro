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
     * @param onChannelExtracted Callback eseguita ogni volta che viene estratto un singolo canale
     */
    suspend fun downloadAndParse(
        playlistUrl: String,
        onChannelExtracted: (Channel) -> Unit
    ) {
        // Spostiamo l'operazione sul thread di Input/Output per non bloccare l'interfaccia utente
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(playlistUrl)
                // Inseriamo un User-Agent standard per evitare che alcuni server ci blocchino
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
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
                // Qui in futuro potremo aggiungere la logica di Auto-Retry
                throw Exception("Impossibile scaricare o parsare la playlist: ${e.message}")
            }
        }
    }
}
