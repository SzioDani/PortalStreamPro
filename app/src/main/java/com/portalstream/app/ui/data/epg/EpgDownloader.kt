package com.portalstream.app.data.epg

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class EpgDownloader(private val context: Context) {

    private val TAG = "EpgDownloader"
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // 🚀 Sorgente EPG XMLTV Italia diretta e sempre attiva
    private val EPG_PRIMARY_IT = "https://www.open-epg.com/files/italy3.xml"
    private val EPG_BACKUP_IT = "https://epg.open-epg.com/files/italy3.xml"

    suspend fun downloadEpg(countryCode: String = "it"): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Tentativo download EPG Italia da fonte Open-EPG...")
            
            val epgContent = fetchXml(EPG_PRIMARY_IT) 
                ?: fetchXml(EPG_BACKUP_IT)

            if (epgContent != null) {
                Log.d(TAG, "EPG scaricato con successo! Dimensioni: ${epgContent.length} caratteri")
                saveEpgLocally(epgContent, countryCode)
                Result.success(epgContent)
            } else {
                Log.w(TAG, "Download fallito. Provo caricamento da cache locale...")
                val cachedEpg = loadCachedEpg(countryCode)
                if (cachedEpg != null) {
                    Log.d(TAG, "Usando EPG presente in cache locale.")
                    Result.success(cachedEpg)
                } else {
                    Log.e(TAG, "Impossibile scaricare o trovare EPG in cache.")
                    Result.failure(Exception("EPG non disponibile"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eccezione critica nel download EPG", e)
            Result.failure(e)
        }
    }

    private suspend fun fetchXml(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    Log.d(TAG, "Risposta OK da $url")
                    return@withContext body
                }
            }
            Log.w(TAG, "Server $url ha risposto con codice ${response.code}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Errore connessione a $url: ${e.message}")
            null
        }
    }

    private fun saveEpgLocally(content: String, countryCode: String) {
        try {
            val epgDir = File(context.cacheDir, "epg")
            epgDir.mkdirs()
            File(epgDir, "$countryCode.xml").writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Errore salvataggio cache locale EPG", e)
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
