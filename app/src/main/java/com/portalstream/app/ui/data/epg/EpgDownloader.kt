package com.portalstream.app.data.epg

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class EpgDownloader(private val context: Context) {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // URL del CDN (più veloce della repo raw)
    private val EPG_CDN_URL = "https://epg.iptv-org.workers.dev"
    
    // EPG locale per paese (fallback se principal fallisce)
    private val EPG_GITHUB_URL = "https://raw.githubusercontent.com/iptv-org/epg/master/guides"

    suspend fun downloadEpg(countryCode: String = "it"): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Prova il CDN prima
            val epgContent = downloadFromCDN(countryCode) ?: downloadFromGithub(countryCode)
            
            if (epgContent != null) {
                // Salva localmente per non scaricarlo ogni volta
                saveEpgLocally(epgContent, countryCode)
                Log.d("EpgDownloader", "EPG scaricato con successo per: $countryCode")
                Result.success(epgContent)
            } else {
                // Se entrambi falliscono, usa quello cached
                val cachedEpg = loadCachedEpg(countryCode)
                if (cachedEpg != null) {
                    Log.w("EpgDownloader", "Usando EPG cached per: $countryCode")
                    Result.success(cachedEpg)
                } else {
                    Result.failure(Exception("Impossibile scaricare EPG per: $countryCode"))
                }
            }
        } catch (e: Exception) {
            Log.e("EpgDownloader", "Errore nel download EPG", e)
            Result.failure(e)
        }
    }

    private suspend fun downloadFromCDN(countryCode: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$EPG_CDN_URL/$countryCode.xml")
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            
            return@withContext if (response.isSuccessful) {
                response.body?.string()
            } else {
                Log.w("EpgDownloader", "CDN risposte ${response.code} per $countryCode")
                null
            }
        } catch (e: Exception) {
            Log.w("EpgDownloader", "Errore download CDN: ${e.message}")
            null
        }
    }

    private suspend fun downloadFromGithub(countryCode: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$EPG_GITHUB_URL/$countryCode.xml")
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            
            return@withContext if (response.isSuccessful) {
                response.body?.string()
            } else {
                Log.w("EpgDownloader", "GitHub risposta ${response.code} per $countryCode")
                null
            }
        } catch (e: Exception) {
            Log.w("EpgDownloader", "Errore download GitHub: ${e.message}")
            null
        }
    }

    private fun saveEpgLocally(content: String, countryCode: String) {
        try {
            val epgDir = File(context.cacheDir, "epg")
            epgDir.mkdirs()
            
            val epgFile = File(epgDir, "$countryCode.xml")
            epgFile.writeText(content)
            
            // Salva timestamp per sapere quando è stato scaricato
            val timestampFile = File(epgDir, "$countryCode.timestamp")
            timestampFile.writeText(System.currentTimeMillis().toString())
            
            Log.d("EpgDownloader", "EPG salvato localmente: ${epgFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("EpgDownloader", "Errore nel salvataggio EPG locale", e)
        }
    }

    private fun loadCachedEpg(countryCode: String): String? {
        return try {
            val epgFile = File(context.cacheDir, "epg/$countryCode.xml")
            
            if (epgFile.exists()) {
                val timestamp = File(context.cacheDir, "epg/$countryCode.timestamp")
                val lastUpdate = timestamp.readText().toLongOrNull() ?: 0L
                val ageHours = (System.currentTimeMillis() - lastUpdate) / (1000 * 60 * 60)
                
                Log.d("EpgDownloader", "EPG cached, age: $ageHours ore")
                epgFile.readText()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("EpgDownloader", "Errore nel caricamento EPG cached", e)
            null
        }
    }

    // Controlla se EPG è vecchio (>24 ore) e necessita refresh
    fun needsRefresh(countryCode: String): Boolean {
        return try {
            val timestampFile = File(context.cacheDir, "epg/$countryCode.timestamp")
            if (!timestampFile.exists()) return true
            
            val lastUpdate = timestampFile.readText().toLongOrNull() ?: 0L
            val ageHours = (System.currentTimeMillis() - lastUpdate) / (1000 * 60 * 60)
            
            ageHours > 24 // Refresh se più vecchio di 24 ore
        } catch (e: Exception) {
            true
        }
    }
}
