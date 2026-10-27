package com.portalstream.app.data.epg

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

class EpgDownloader(private val context: Context) {

    private val TAG = "EpgDownloader"
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // Aumentato leggermente per permettere il download del file completo
        .build()

    // 🚀 Sorgente Primaria Completa (Sky, DAZN, DTT) e Backup Open-EPG
    private val EPG_PRIMARY_IT = "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz"
    private val EPG_BACKUP_IT = "https://www.open-epg.com/files/italy3.xml"

    suspend fun downloadEpg(countryCode: String = "it"): Result<String> = withContext(Dispatchers.IO) {
        try {
            val country = countryCode.lowercase()
            Log.d(TAG, "🌍 Avvio download EPG per la nazione: [$country]...")

            // 🔗 URL dinamici basati sulla nazione selezionata
            val primaryUrl = when (country) {
                "uk" -> "https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz"
                "fr" -> "https://epgshare01.online/epgshare01/epg_ripper_FR1.xml.gz"
                "de" -> "https://epgshare01.online/epgshare01/epg_ripper_DE1.xml.gz"
                "es" -> "https://epgshare01.online/epgshare01/epg_ripper_ES1.xml.gz"
                else -> "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz" // Default IT
            }

            val backupUrl = when (country) {
                "uk" -> "https://www.open-epg.com/files/uk3.xml"
                "fr" -> "https://www.open-epg.com/files/france3.xml"
                "de" -> "https://www.open-epg.com/files/germany3.xml"
                "es" -> "https://www.open-epg.com/files/spain3.xml"
                else -> "https://www.open-epg.com/files/italy3.xml" // Default IT
            }

            val epgContent = fetchXmlOrGzip(primaryUrl) 
                ?: fetchXmlOrGzip(backupUrl)

            if (!epgContent.isNullOrBlank()) {
                Log.d(TAG, "✅ EPG per [$country] scaricato con successo! Dimensioni: ${epgContent.length} caratteri")
                
                // Salva nella cache locale nominandola con la nazione (es. uk.xml, it.xml)
                saveEpgLocallyOverwrite(epgContent, country)
                
                Result.success(epgContent)
            } else {
                Log.w(TAG, "⚠️ Download fallito da entrambe le sorgenti per [$country]. Tentativo recupero da cache locale...")
                val cachedEpg = loadCachedEpg(country)
                if (cachedEpg != null) {
                    Log.d(TAG, "📦 Usando EPG presente in cache locale per [$country] come fallback.")
                    Result.success(cachedEpg)
                } else {
                    Log.e(TAG, "❌ Impossibile scaricare o trovare EPG in cache per [$country].")
                    Result.failure(Exception("EPG non disponibile per $country"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Eccezione critica nel download EPG", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchXmlOrGzip(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept-Encoding", "gzip, deflate")
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body ?: return@withContext null

                // Se l'URL finisce in .gz, decompone lo stream usando GZIPInputStream
                val xmlContent = if (url.endsWith(".gz", ignoreCase = true)) {
                    Log.d(TAG, "Decompressione dello stream GZIP da $url in corso...")
                    GZIPInputStream(responseBody.byteStream()).use { gzipStream ->
                        BufferedReader(InputStreamReader(gzipStream, Charsets.UTF_8)).use { reader ->
                            reader.readText()
                        }
                    }
                } else {
                    responseBody.string()
                }

                if (xmlContent.isNotBlank()) {
                    Log.d(TAG, "Risposta ricevuta con successo da $url")
                    return@withContext xmlContent
                }
            }
            Log.w(TAG, "Server $url ha risposto con codice ${response.code}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Errore durante la connessione/decompressione a $url: ${e.message}")
            null
        }
    }

    /**
     * Salva il file nella cache locale sovrascrivendo qualsiasi versione precedente.
     */
    private fun saveEpgLocallyOverwrite(content: String, countryCode: String) {
        try {
            val epgDir = File(context.cacheDir, "epg")
            if (!epgDir.exists()) {
                epgDir.mkdirs()
            }
            val targetFile = File(epgDir, "$countryCode.xml")
            if (targetFile.exists()) {
                targetFile.delete() // Cancella il vecchio file per garantire una sovrascrittura pulita
            }
            targetFile.writeText(content)
            Log.d(TAG, "Cache locale EPG sovrascritta con successo ($countryCode.xml)")
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante il salvataggio della cache locale EPG", e)
        }
    }

    private fun loadCachedEpg(countryCode: String): String? {
        return try {
            val file = File(context.cacheDir, "epg/$countryCode.xml")
            if (file.exists()) file.readText() else null
        } catch (e: Exception) {
            null
        }
    }
}
