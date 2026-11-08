package com.portalstream.app.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ExitReasonLogger {
    fun logLastExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALIAN)
            val sb = StringBuilder("=== ULTIME CHIUSURE PROCESSO ===\n")
            for (e in exits) {
                val reason = when (e.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "CRASH_JAVA"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVO"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                    ApplicationExitInfo.REASON_ANR -> "ANR"
                    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                    ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
                    else -> "ALTRO(${e.reason})"
                }
                sb.append("${fmt.format(Date(e.timestamp))} reason=$reason status=${e.status} desc=${e.description}\n")
            }
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, "torrent_exit.log").writeText(sb.toString())
        } catch (_: Throwable) {}
    }
}
