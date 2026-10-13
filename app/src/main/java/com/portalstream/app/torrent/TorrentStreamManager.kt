package com.portalstream.app.torrent

import android.content.Context
import android.util.Log
import org.libtorrent4j.SessionManager
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.CountDownLatch

class TorrentStreamManager(private val context: Context) {

    private val TAG = "TorrentStreamManager"
    private val sessionManager = SessionManager()

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

            // Aggiungiamo il torrent e otteniamo l'handle per configurare il download sequenziale
            val th = sessionManager.download(magnetUri, saveDir, torrent_flags_t())
            
            // Abilita esplicitamente il download sequenziale per lo streaming video
            if (th != null && th.isValid) {
                th.setSequentialDownload(true)
            }

            val latch = CountDownLatch(1)
            var videoFile: File? = null

            // Thread di monitoraggio per individuare non appena il file video è disponibile e ha ricevuto i primi byte
            Thread {
                var attempts = 0
                while (attempts < 80 && latch.count > 0) {
                    Thread.sleep(500)
                    val found = findLargestVideoFile(saveDir)
                    // Attendiamo che il file abbia accumulato almeno 1 MB di dati iniziali per garantire la lettura dell'header
                    if (found != null && found.length() > 1024 * 1024) {
                        videoFile = found
                        latch.countDown()
                        break
                    }
                    attempts++
                }

                val finalFile = videoFile ?: findLargestVideoFile(saveDir)

                if (finalFile != null && finalFile.length() > 0) {
                    onReady(finalFile.absolutePath)
                } else {
                    onError("Timeout nel recupero dei metadati o nessun peer disponibile per il download.")
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Errore avvio streaming torrent", e)
            onError(e.localizedMessage ?: "Errore sconosciuto")
        }
    }

    private fun findLargestVideoFile(dir: File): File? {
        if (!dir.exists()) return null
        val extensions = listOf(".mp4", ".mkv", ".avi", ".ts", ".mov", ".m4v", ".flv", ".webm")
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
