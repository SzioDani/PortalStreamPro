package com.portalstream.app.torrent

import android.content.Context
import android.util.Log
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
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
                    if (alert.type() == AlertType.ADD_TORRENT) {
                        val handle = (alert as org.libtorrent4j.alerts.AddTorrentAlert).handle()
                        activeTorrentHandle = handle
                        // Imposta il download sequenziale nativo tramite flag/priorità se supportato
                    }

                    // Quando arrivano i metadati o un pezzo viene completato, cerchiamo il file video scaricato
                    if (alert.type() == AlertType.METADATA_RECEIVED || alert.type() == AlertType.PIECE_FINISHED) {
                        val found = findLargestVideoFile(saveDir)
                        if (found != null && found.length() > 1024 * 256) { // Almeno 256KB scritti
                            videoFile = found
                            latch.countDown()
                        }
                    }
                }
            }

            sessionManager.addListener(listener)
            sessionManager.download(magnetUri, saveDir)

            Thread {
                val success = latch.await(35, TimeUnit.SECONDS)
                sessionManager.removeListener(listener)

                // Fallback di sicurezza: cerca comunque il file più grande nella cartella se il latch scade
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
