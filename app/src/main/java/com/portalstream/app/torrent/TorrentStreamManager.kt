package com.portalstream.app.torrent

import android.content.Context
import android.util.Log
import com.sun.net.httpserver.HttpServer
import libtorrent4j.AlertListener
import libtorrent4j.Priority
import libtorrent4j.SessionManager
import libtorrent4j.TorrentHandle
import libtorrent4j.alerts.Alert
import libtorrent4j.alerts.AlertType
import java.io.File
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TorrentStreamManager(private val context: Context) {

    private val TAG = "TorrentStreamManager"
    private val sessionManager = SessionManager()
    private var httpServer: HttpServer? = null
    private var activeTorrentHandle: TorrentHandle? = null
    private val httpPort = 8888 // Porta locale per lo streaming su ExoPlayer/VLC

    fun startSession() {
        if (!sessionManager.isRunning) {
            sessionManager.start()
            sessionManager.applySettings(
                libtorrent4j.settings_pack().apply {
                    enableDht(true)
                    enableLsd(true)
                    enableUpnp(true)
                    enableNatpmp(true)
                }
            )
        }
    }

    /**
     * Aggiunge il magnet link, imposta il download sequenziale e avvia il server locale.
     * Restituisce l'URL locale `http://127.0.0.1:8888/...` da passare al player.
     */
    fun prepareStream(magnetUri: String, onReady: (playbackUrl: String) -> Unit, onError: (String) -> Unit) {
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
                        activeTorrentHandle = (alert as libtorrent4j.alerts.AddTorrentAlert).handle()
                        activeTorrentHandle?.let { handle ->
                            // Attiva il download sequenziale per avviare subito la riproduzione
                            handle.setSequentialDownload(true)
                            
                            val wp = handle.torrentFile()
                            if (wp != null && wp.numFiles() > 0) {
                                var largestFileIndex = 0
                                var maxSz = 0L
                                for (i in 0 until wp.numFiles()) {
                                    val sz = wp.fileSize(i)
                                    if (sz > maxSz) {
                                        maxSz = sz
                                        largestFileIndex = i
                                    }
                                }
                                val priorities = arrayOfNulls<Priority>(wp.numFiles())
                                for (i in priorities.indices) {
                                    priorities[i] = if (i == largestFileIndex) Priority.DEFAULT else Priority.IGNORE
                                }
                                handle.prioritizeFiles(priorities)

                                val relativePath = wp.filePath(largestFileIndex)
                                videoFile = File(saveDir, relativePath)
                            }
                        }
                    }

                    if (alert.type() == AlertType.METADATA_RECEIVED || alert.type() == AlertType.PIECE_FINISHED) {
                        if (videoFile != null && videoFile!!.exists() && videoFile!!.length() > 1024 * 512) {
                            latch.countDown()
                        }
                    }
                }
            }

            sessionManager.addListener(listener)
            sessionManager.download(magnetUri, saveDir)

            startHttpServer(saveDir)

            Thread {
                val success = latch.await(30, TimeUnit.SECONDS)
                sessionManager.removeListener(listener)
                
                if (success && videoFile != null) {
                    val localStreamUrl = "http://127.0.0.1:$httpPort/${videoFile!!.name}"
                    onReady(localStreamUrl)
                } else {
                    if (videoFile != null) {
                        onReady("http://127.0.0.1:$httpPort/${videoFile!!.name}")
                    } else {
                        onError("Timeout nel recupero dei metadati del torrent o peer non trovati.")
                    }
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Errore avvio streaming torrent", e)
            onError(e.localizedMessage ?: "Errore sconosciuto")
        }
    }

    private fun startHttpServer(rootFolder: File) {
        if (httpServer != null) return
        try {
            httpServer = HttpServer.create(InetSocketAddress(httpPort), 0).apply {
                createContext("/") { exchange ->
                    val path = exchange.requestURI.path.substring(1)
                    val file = File(rootFolder, path)

                    if (file.exists()) {
                        val length = file.length()
                        val rangeHeader = exchange.requestHeaders.getFirst("Range")
                        
                        var start = 0L
                        var end = length - 1

                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            val range = rangeHeader.substring(6).split("-")
                            start = range[0].toLongOrNull() ?: 0L
                            if (range.size > 1 && range[1].isNotEmpty()) {
                                end = range[1].toLongOrNull() ?: (length - 1)
                            }
                        }

                        val contentLength = (end - start) + 1
                        exchange.responseHeaders.add("Content-Type", "video/mp4")
                        exchange.responseHeaders.add("Accept-Ranges", "bytes")
                        exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$length")
                        
                        exchange.sendResponseHeaders(206, contentLength)

                        FileInputStream(file).use { fis ->
                            fis.skip(start)
                            val os = exchange.responseBody
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var totalSent = 0L
                            
                            while (fis.read(buffer).also { bytesRead = it } != -1) {
                                if (totalSent + bytesRead > contentLength) {
                                    bytesRead = (contentLength - totalSent).toInt()
                                }
                                os.write(buffer, 0, bytesRead)
                                totalSent += bytesRead
                                if (totalSent >= contentLength) break
                            }
                            os.flush()
                        }
                    } else {
                        val msg = "File non trovato"
                        exchange.sendResponseHeaders(404, msg.length.toLong())
                        exchange.responseBody.use { it.write(msg.toByteArray()) }
                    }
                }
                executor = null
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore avvio HttpServer locale", e)
        }
    }

    fun stopSession() {
        try {
            httpServer?.stop(0)
            httpServer = null
            activeTorrentHandle?.pause()
            sessionManager.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Errore chiusura sessione torrent", e)
        }
    }
}
