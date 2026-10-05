package com.portalstream.app.network

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerCollector
import com.chuckerteam.chucker.api.ChuckerInterceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class NetworkSniffer(private val context: Context) {
    
    fun getSnifferInterceptor(): ChuckerInterceptor {
        return ChuckerInterceptor.Builder(context)
            .collector(ChuckerCollector(context, showNotification = true))
            .maxContentLength(250_000L)
            .redactHeaders("Authorization", "X-Authorization")
            .build()
    }
    
    fun createOkHttpClientWithSniffer(): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(getSnifferInterceptor())
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }
}
