package com.portalstream.app.torrent

import android.content.Context
import android.util.Log
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TorrentStreamManager(private val context: Context) {

    private val TAG = "TorrentStreamManager"
    private val sessionManager = SessionManager()
    private var activeTorrentHandle: TorrentHandle? = null

    fun startSession() {
        if (!sessionManager.isRunning) {
            sessionManager.start()
        }
    }

    fun prepareStream(magnetUri: String, onReady: (filePath: String) -> Unit, onError: (String) -> Unit) {
        try {
            startSession()

            val saveDir = File(context.cacheDir, "torrent_cache")
            if (!saveDir.exists()) saveDir.mkdirs()

            val latch = CountDownLatch(1)
            var videoFile: File? = null

            val listener = object : AlertListener {
                override fun types(): IntArray? = null

                override fun alert(alert: Alert<*>) {
                    // Controlliamo periodicamente la cartella cache per individuare il file video in scrittura
                    val found = findLargestVideoFile(saveDir)
                    if (found != null && found.length() > 1024 * 256) {
                        videoFile = found
                        latch.countDown()
                    }
                }
            }

            sessionManager.addListener(listener)
            sessionManager.download(magnetUri, saveDir)

            Thread {
                val success = latch.await(35, TimeUnit.SECONDS)
                sessionManager.removeListener(listener)

                val finalFile = videoFile ?: findLargestVideoFile(saveDir)

                if (finalFile != null) {
                    onReady(finalFile.absolutePath)
                } else {
                    onError("Timeout nel recupero dei metadati del torrent o nessun peer disponibile.")
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Errore avvio streaming torrent", e)
            onError(e.localizedMessage ?: "Errore sconosciuto")
        }
    }

    private fun findLargestVideoFile(dir: File): File? {
        if (!dir.exists()) return null
        val extensions = listOf(".mp4", ".mkv", ".avi", ".ts", ".mov", ".m4v")
        var largest: File? = null
        var maxLen = -1L

        try {
            dir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    val nameLower = file.name.lowercase()
                    if (extensions.any { nameLower.endsWith(it) }) {
                        if (file.length() > maxLen) {
                            maxLen = file.length()
                            largest = file
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return largest
    }

    fun stopSession() {
        try {
            sessionManager.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Errore chiusura sessione torrent", e)
        }
    }
}
