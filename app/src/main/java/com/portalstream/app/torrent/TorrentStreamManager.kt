package com.portalstream.app.torrent

import android.content.Context
import android.util.Log
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.SessionManager
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

            // Configurazione corretta del magnet tramite AddTorrentParams per libtorrent4j
            val atp = AddTorrentParams.parseMagnetUri(magnetUri)
            atp.savePath(saveDir.absolutePath)

            sessionManager.download(atp)

            // Thread di monitoraggio della cache per individuare non appena il file video è disponibile e in scrittura
            Thread {
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
