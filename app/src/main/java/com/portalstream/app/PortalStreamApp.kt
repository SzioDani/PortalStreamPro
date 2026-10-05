package com.portalstream.app

import android.app.Application
import timber.log.Timber

class PortalStreamApp : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Timber logging
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        
        Timber.d("PortalStreamPro v${BuildConfig.VERSION_NAME} started")
    }
}
