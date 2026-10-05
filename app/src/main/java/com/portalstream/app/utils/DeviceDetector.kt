package com.portalstream.app.utils

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import com.google.android.gms.common.GooglePlayServicesUtil
import timber.log.Timber

enum class DeviceType {
    PHONE, TABLET, TV, FIRESTICK, BOX_ANDROID, UNKNOWN
}

class DeviceDetector(private val context: Context) {
    
    fun detectDevice(): DeviceType {
        return when {
            isFirestick() -> DeviceType.FIRESTICK
            isAndroidTV() -> DeviceType.TV
            isAndroidBox() -> DeviceType.BOX_ANDROID
            isTablet() -> DeviceType.TABLET
            isPhone() -> DeviceType.PHONE
            else -> DeviceType.UNKNOWN
        }
    }
    
    private fun isFirestick(): Boolean {
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        val device = Build.DEVICE.lowercase()
        
        return (brand.contains("amazon") || 
                model.contains("firestick") ||
                device.contains("montoya"))
    }
    
    private fun isAndroidTV(): Boolean {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        return uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
    
    private fun isAndroidBox(): Boolean {
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        
        return (brand.contains("tanix") ||
                brand.contains("sunvell") ||
                brand.contains("beelink") ||
                brand.contains("ugoos") ||
                model.contains("box"))
    }
    
    private fun isTablet(): Boolean {
        val screenSize = context.resources.configuration.screenLayout and 
                        Configuration.SCREENLAYOUT_SIZE_MASK
        return screenSize >= Configuration.SCREENLAYOUT_SIZE_LARGE
    }
    
    private fun isPhone(): Boolean {
        return !isTablet()
    }
    
    fun getDeviceInfo(): String {
        return """
            Device: ${detectDevice()}
            Brand: ${Build.BRAND}
            Model: ${Build.MODEL}
            Device: ${Build.DEVICE}
            Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
        """.trimIndent()
    }
}
