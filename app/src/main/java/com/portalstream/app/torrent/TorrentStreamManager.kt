package com.portalstream.app.torrent

import android.content.Context
import android.util.Log
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
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

                        if (handle.isValid) {
                            val ti = handle.torrentFile()
                            if (ti != null && ti.numFiles() > 0) {
                                var largestFileIndex = 0
                                var maxSz = 0L
                                for (i in 0 until ti.numFiles()) {
                                    val sz = ti.fileSize(i)
                                    if (sz > maxSz) {
                                        maxSz = sz
                                        largestFileIndex = i
                                    }
                                }
                                val priorities = arrayOfNulls<Priority>(ti.numFiles())
                                for (i in priorities.indices) {
                                    priorities[i] = if (i == largestFileIndex) Priority.DEFAULT else Priority.IGNORE
                                }
                                handle.prioritizeFiles(priorities)

                                val relativePath = ti.filePath(largestFileIndex)
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

            Thread {
                val success = latch.await(30, TimeUnit.SECONDS)
                sessionManager.removeListener(listener)

                if (videoFile != null) {
                    onReady(videoFile!!.absolutePath)
                } else {
                    onError("Timeout nel recupero dei metadati del torrent o peer non trovati.")
                }
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Errore avvio streaming torrent", e)
            onError(e.localizedMessage ?: "Errore sconosciuto")
        }
    }

    fun stopSession() {
        try {
            sessionManager.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Errore chiusura sessione torrent", e)
        }
    }
}
