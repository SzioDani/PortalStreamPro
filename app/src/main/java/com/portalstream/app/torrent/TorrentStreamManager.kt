package com.portalstream.app.torrent

import android.content.Context
import android.util.Log
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentInfo // Aggiunto import per TorrentInfo
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

            val latch = CountDownLatch(1)
            var videoFile: File? = null

            // Spostiamo la risoluzione del magnet e il download dentro il Thread
            // perché il recupero dei metadati (fetchMagnet) è bloccante.
            Thread {
                try {
                    // 1. Recupera i metadati dal magnet (attende fino a 30 secondi)
                    val torrentData = sessionManager.fetchMagnet(magnetUri, 30)

                    if (torrentData != null) {
                        // 2. Converte i dati scaricati in un oggetto TorrentInfo
                        val torrentInfo = TorrentInfo.bdecode(torrentData)
                        // Nota: se bdecode ti dà errore, usa: val torrentInfo = TorrentInfo(torrentData)

                        // 3. Avvia il download con l'oggetto TorrentInfo corretto
                        sessionManager.download(torrentInfo, saveDir)

                        // 4. Inizia il monitoraggio della cache
                        var attempts = 0
                        while (attempts < 70 && latch.count > 0) {
                            Thread.sleep(500)
                            val found = findLargestVideoFile(saveDir)
                            if (found != null && found.length() > 1024 * 256) {
                                videoFile = found
                                latch.countDown()
                                break
                            }
                            attempts++
                        }

                        val finalFile = videoFile ?: findLargestVideoFile(saveDir)

                        if (finalFile != null) {
                            onReady(finalFile.absolutePath)
                        } else {
                            onError("Timeout nel recupero del file video o nessun peer disponibile.")
                        }
                    } else {
                        onError("Impossibile recuperare i metadati dal magnet link (Timeout). Nessun seed disponibile.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Errore durante il download del magnet", e)
                    onError(e.localizedMessage ?: "Errore sconosciuto nel download")
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
