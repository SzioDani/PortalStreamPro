package com.portalstream.app.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class PlaylistDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun download(url: String, userAgent: String? = null): String =
        withContext(Dispatchers.IO) {
            val requestBuilder = Request.Builder().url(url).get()
            if (!userAgent.isNullOrBlank()) {
                requestBuilder.header("User-Agent", userAgent)
            }
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.string() ?: throw IOException("Risposta vuota")
            }
        }
}
