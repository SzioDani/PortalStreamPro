package com.portalstream.app.data.epg

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class EpgDownloader(private val context: Context) {

    private val TAG = "EpgDownloader"
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val EPG_CDN_URL = "https://epg.iptv-org.workers.dev"
    private val EPG_GITHUB_URL = "https://raw.githubusercontent.com/iptv-org/epg/master/guides"

    suspend fun downloadEpg(countryCode: String = "it"): Result<String> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Tentativo download EPG per: $countryCode")
            val epgContent = downloadFromCDN(countryCode) ?: downloadFromGithub(countryCode)
            
            if (epgContent != null) {
                Log.d(TAG, "EPG scaricato con successo! Dimensioni: ${epgContent.length} caratteri")
                saveEpgLocally(epgContent, countryCode)
                Result.success(epgContent)
            } else {
                Log.w(TAG, "Download fallito da CDN e GitHub. Provo caricamento da cache...")
                val cachedEpg = loadCachedEpg(countryCode)
                if (cachedEpg != null) {
                    Log.d(TAG, "Usando EPG in cache locale.")
                    Result.success(cachedEpg)
                } else {
                    Log.e(TAG, "Impossibile scaricare o trovare EPG in cache per: $countryCode")
                    Result.failure(Exception("EPG non disponibile"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eccezione critica nel download EPG", e)
            Result.failure(e)
        }
    }

    private suspend fun downloadFromCDN(countryCode: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$EPG_CDN_URL/$countryCode.xml").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) response.body?.string() else {
                Log.w(TAG, "CDN risposto con codice ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Errore connessione CDN: ${e.message}")
            null
        }
    }

    private suspend fun downloadFromGithub(countryCode: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$EPG_GITHUB_URL/$countryCode.xml").build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) response.body?.string() else {
                Log.w(TAG, "GitHub risposto con codice ${response.code}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Errore connessione GitHub: ${e.message}")
            null
        }
    }

    private fun saveEpgLocally(content: String, countryCode: String) {
        try {
            val epgDir = File(context.cacheDir, "epg")
            epgDir.mkdirs()
            File(epgDir, "$countryCode.xml").writeText(content)
            File(epgDir, "$countryCode.timestamp").writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Log.e(TAG, "Errore salvataggio cache locale", e)
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
