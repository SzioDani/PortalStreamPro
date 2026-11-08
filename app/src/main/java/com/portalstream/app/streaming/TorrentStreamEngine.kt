package com.portalstream.app.streaming

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.*
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.torrent_handle_vector
import timber.log.Timber
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

sealed class TorrentStreamState {
    object Idle : TorrentStreamState()
    data class Buffering(
        val statusText: String,
        val progressPercent: Int = 0,
        val downloadSpeedKbps: Int = 0,
        val peers: Int = 0
    ) : TorrentStreamState()
    data class Ready(val videoFile: File) : TorrentStreamState()
    data class Error(val message: String) : TorrentStreamState()
}

class TorrentStreamEngine(private val context: Context) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logToFile("FATAL CoroutineExceptionHandler: ${throwable.message}\n${ stackTraceToString(throwable) }")
        Timber.e(throwable, "Eccezione intercettata in TorrentStreamEngine")
        _state.value = TorrentStreamState.Error(
            "Errore motore Torrent: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var monitorJob: Job? = null

    private var selectedFileIndex: Int = -1
    private var selectedVideoFile: File? = null
    private var selectedVideoSize: Long = 0L
    private var firstPieceIndex: Int = 0
    private var lastPieceIndex: Int = 0
    private var isConfigured: Boolean = false
    private var isReadyEmitted: Boolean = false

    private val _state = MutableStateFlow<TorrentStreamState>(TorrentStreamState.Idle)
    val state: StateFlow<TorrentStreamState> = _state.asStateFlow()

    private val baseStreamDir: File by lazy {
        File(context.cacheDir, "torrent_stream").apply { if (!exists()) mkdirs() }
    }

    private var currentSaveDir: File = baseStreamDir

    fun logToFile(message: String) {
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALIAN).format(Date())
            val line = "[$timestamp] $message\n"

            val externalFiles = context.getExternalFilesDir(null) ?: context.filesDir
            File(externalFiles, "torrent_debug.log").appendText(line)

            val externalCache = context.externalCacheDir ?: context.cacheDir
            File(externalCache, "torrent_debug.log").appendText(line)
        } catch (_: Throwable) {}
    }

    private fun clearLogFile() {
        try {
            val header = "=== AVVIO NUOVA SESSIONE TORRENT (${LibTorrent.libtorrent4jVersion()}) ===\n"
            val externalFiles = context.getExternalFilesDir(null) ?: context.filesDir
            File(externalFiles, "torrent_debug.log").writeText(header)

            val externalCache = context.externalCacheDir ?: context.cacheDir
            File(externalCache, "torrent_debug.log").writeText(header)
        } catch (_: Throwable) {}
    }

    fun startStream(rawInput: String) {
        monitorJob?.cancel()
        isReadyEmitted = false
        isConfigured = false
        selectedFileIndex = -1
        selectedVideoFile = null
        selectedVideoSize = 0L
        clearLogFile()
        logToFile("startStream chiamato con input: $rawInput")

        _state.value = TorrentStreamState.Buffering("Avvio motore Torrent interno...")

        monitorJob = scope.launch {
            try {
                val session = getOrStartSession()
                logToFile("SessionManager attivo (DHT running=${session.isDhtRunning})")
                if (!session.isDhtRunning) {
                    session.startDht()
                }

                removeAllActiveTorrents(session)
                delay(250)
                cleanOldDirsExcept(null)

                currentSaveDir = File(baseStreamDir, "session_${System.currentTimeMillis()}").apply {
                    mkdirs()
                }
                logToFile("Cartella download creata: ${currentSaveDir.absolutePath}")

                when (val resolved = resolveTorrentInput(rawInput)) {
                    is ResolvedInput.Magnet -> {
                        logToFile("Avvio download Magnet: ${resolved.magnetUri.take(100)}...")
                        _state.value = TorrentStreamState.Buffering("Ricerca peer e recupero metadati...")
                        session.download(
                            resolved.magnetUri,
                            currentSaveDir,
                            TorrentFlags.SEQUENTIAL_DOWNLOAD
                        )
                    }
                    is ResolvedInput.TorrentFile -> {
                        logToFile("Avvio download File .torrent: ${resolved.file.absolutePath}")
                        _state.value = TorrentStreamState.Buffering("Apertura file .torrent...")
                        val ti = TorrentInfo(resolved.file)
                        pinnedSwigObjects.add(ti)
                        session.download(
                            ti,
                            currentSaveDir,
                            null,
                            null,
                            null,
                            TorrentFlags.SEQUENTIAL_DOWNLOAD
                        )
                    }
                }

                var activeHandle: TorrentHandle? = null
                var ticks = 0

                while (isActive && !isReadyEmitted) {
                    delay(750)
                    ticks++

                    if (activeHandle == null || !activeHandle.isValid) {
                        activeHandle = findActiveTorrentHandle(session)
                        if (activeHandle != null && activeHandle.isValid) {
                            pinnedSwigObjects.add(activeHandle)
                            logToFile("TorrentHandle agganciato correttamente")
                        }
                    }

                    val handle = activeHandle ?: continue
                    if (!handle.isValid) continue

                    val status = handle.status(true) ?: continue
                    val speedKbps = status.downloadPayloadRate() / 1024
                    val peers = status.numPeers()

                    if (!status.hasMetadata()) {
                        _state.value = TorrentStreamState.Buffering(
                            statusText = "Recupero metadati dal Magnet...",
                            progressPercent = 0,
                            downloadSpeedKbps = speedKbps,
                            peers = peers
                        )
                    } else {
                        if (!isConfigured) {
                            logToFile("Metadati ricevuti! Configurazione file video in corso...")
                            configureVideoSelectionOnce(handle)
                        }
                        if (isConfigured) {
                            checkIfReadyToPlay(handle, status, ticks)
                        }
                    }
                }
            } catch (e: CancellationException) {
                logToFile("Monitoraggio cancellato normalmente")
                throw e
            } catch (e: Throwable) {
                logToFile("ERRORE in startStream: ${e.message}\n${stackTraceToString(e)}")
                Timber.e(e, "Errore avvio/monitoraggio stream torrent")
                _state.value = TorrentStreamState.Error(
                    "Impossibile aprire il torrent: ${e.localizedMessage ?: "input non valido"}"
                )
            }
        }
    }

    private fun findActiveTorrentHandle(session: SessionManager): TorrentHandle? {
        val rawSession = session.swig() ?: return null
        val vector: torrent_handle_vector = rawSession.get_torrents()
        pinnedSwigObjects.add(vector)
        val size = vector.size
        for (i in 0 until size) {
            val th = vector.get(i)
            if (th != null && th.is_valid()) {
                return TorrentHandle(th)
            }
        }
        return null
    }

    private fun configureVideoSelectionOnce(handle: TorrentHandle) {
        if (isConfigured || !handle.isValid) return

        val ti = handle.torrentFile() ?: return
        pinnedSwigObjects.add(ti)
        pinnedSwigObjects.add(ti.swig())

        if (!ti.isValid) return
        val numFiles = ti.numFiles()
        if (numFiles <= 0) return

        val files = ti.files()
        pinnedSwigObjects.add(files)

        var bestIndex = 0
        var maxVideoSize = -1L
        val videoExtensions = listOf(".mp4", ".mkv", ".avi", ".ts", ".webm", ".mov", ".m4v", ".flv", ".mpg", ".mpeg")

        for (i in 0 until numFiles) {
            val name = files.fileName(i).lowercase()
            val size = files.fileSize(i)
            val isVideo = videoExtensions.any { name.endsWith(it) }
            if (isVideo && size > maxVideoSize) {
                maxVideoSize = size
                bestIndex = i
            }
        }

        if (maxVideoSize == -1L) {
            for (i in 0 until numFiles) {
                val size = files.fileSize(i)
                if (size > maxVideoSize) {
                    maxVideoSize = size
                    bestIndex = i
                }
            }
        }

        selectedFileIndex = bestIndex
        val relPath = files.filePath(selectedFileIndex)
        selectedVideoFile = File(currentSaveDir, relPath)
        selectedVideoSize = files.fileSize(selectedFileIndex).coerceAtLeast(1L)

        val pieceLength = ti.pieceLength().toLong().coerceAtLeast(1L)
        val fileOffset = files.fileOffset(selectedFileIndex)
        val totalPieces = ti.numPieces().coerceAtLeast(1)

        firstPieceIndex = (fileOffset / pieceLength).toInt().coerceIn(0, totalPieces - 1)
        lastPieceIndex = ((fileOffset + max(0L, selectedVideoSize - 1L)) / pieceLength).toInt()
            .coerceIn(firstPieceIndex, totalPieces - 1)

        if (numFiles > 1) {
            val filePriorities = Array(numFiles) { idx ->
                if (idx == selectedFileIndex) Priority.DEFAULT else Priority.IGNORE
            }
            handle.prioritizeFiles(filePriorities)
        }

        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        handle.resume()

        isConfigured = true
        logToFile(
            "File video selezionato [idx=$selectedFileIndex/$numFiles]: ${selectedVideoFile?.absolutePath} " +
                "(size=${selectedVideoSize / (1024 * 1024)}MB, pieces=$firstPieceIndex..$lastPieceIndex)"
        )
    }

    private fun checkIfReadyToPlay(handle: TorrentHandle, status: TorrentStatus, ticks: Int) {
        if (isReadyEmitted || !handle.isValid) return
        val videoFile = selectedVideoFile ?: return
        val fileSize = selectedVideoSize.coerceAtLeast(1L)

        val requiredHeadPieces = min(5, (lastPieceIndex - firstPieceIndex + 1).coerceAtLeast(1))
        var downloadedHeadPieces = 0
        for (i in 0 until requiredHeadPieces) {
            if (handle.havePiece(firstPieceIndex + i)) {
                downloadedHeadPieces++
            }
        }

        val minBufferBytes = min(15L * 1024 * 1024, max(8L * 1024 * 1024, (fileSize * 0.04).toLong()))
        val downloadedBytes = status.totalWantedDone()

        val headProgress = (downloadedHeadPieces.toFloat() / requiredHeadPieces.toFloat())
        val byteProgress = (downloadedBytes.toFloat() / minBufferBytes.toFloat()).coerceAtMost(1f)
        val bufferPercent = ((headProgress * 0.5f + byteProgress * 0.5f) * 100).toInt().coerceIn(0, 99)

        val speedKbps = status.downloadPayloadRate() / 1024
        val peers = status.numPeers()

        if (ticks % 4 == 0) {
            logToFile(
                "Pre-buffering: $bufferPercent% | headPieces=$downloadedHeadPieces/$requiredHeadPieces | " +
                    "downloaded=${downloadedBytes / 1024}KB / target=${minBufferBytes / 1024}KB | " +
                    "fileExists=${videoFile.exists()} | peers=$peers | speed=${speedKbps}KB/s"
            )
        }

        val isFileReady = videoFile.exists() && videoFile.length() > 0 && (
            status.isFinished ||
                status.isSeeding ||
                (downloadedHeadPieces >= requiredHeadPieces && downloadedBytes >= minBufferBytes)
            )

        if (isFileReady) {
            isReadyEmitted = true
            logToFile("BUFFER PRONTO! Avvio riproduzione sul file: ${videoFile.absolutePath} (length=${videoFile.length()})")
            _state.value = TorrentStreamState.Ready(videoFile)
        } else {
            _state.value = TorrentStreamState.Buffering(
                statusText = "Pre-buffering Torrent ($bufferPercent%)...",
                progressPercent = bufferPercent,
                downloadSpeedKbps = speedKbps,
                peers = peers
            )
        }
    }

    private fun removeAllActiveTorrents(session: SessionManager) {
        try {
            val rawSession = session.swig() ?: return
            val vector: torrent_handle_vector = rawSession.get_torrents()
            val size = vector.size
            for (i in 0 until size) {
                val th = vector.get(i)
                if (th != null && th.is_valid()) {
                    session.remove(TorrentHandle(th))
                }
            }
            pinnedSwigObjects.clear()
        } catch (e: Throwable) {
            logToFile("Errore removeAllActiveTorrents: ${e.message}")
            Timber.e(e, "Errore pulizia torrent attivi")
        }
    }

    private fun cleanOldDirsExcept(keepDir: File?) {
        try {
            baseStreamDir.listFiles()?.forEach { child ->
                if (keepDir == null || child.absolutePath != keepDir.absolutePath) {
                    child.deleteRecursively()
                }
            }
        } catch (_: Throwable) {}
    }

    fun stop() {
        logToFile("stop() chiamato su TorrentStreamEngine")
        monitorJob?.cancel()
        monitorJob = null
        scope.launch {
            val session = sharedSession
            if (session != null && session.isRunning) {
                removeAllActiveTorrents(session)
            }
        }
    }

    private sealed class ResolvedInput {
        data class Magnet(val magnetUri: String) : ResolvedInput()
        data class TorrentFile(val file: File) : ResolvedInput()
    }

    private fun resolveTorrentInput(raw: String): ResolvedInput {
        val trimmed = raw.trim()
            .replace("&amp;", "&")
            .replace("\n", "")
            .replace("\r", "")

        val localFile = when {
            trimmed.startsWith("file://") -> Uri.parse(trimmed).path?.let { File(it) }
            trimmed.startsWith("/") -> File(trimmed)
            else -> null
        }

        if (localFile != null && localFile.exists()) {
            val extractedMagnet = extractMagnetOrHashFromBytes(localFile.readBytes())
            return if (extractedMagnet != null) {
                ResolvedInput.Magnet(appendDefaultTrackers(extractedMagnet))
            } else {
                ResolvedInput.TorrentFile(localFile)
            }
        }

        if (trimmed.startsWith("magnet:?", ignoreCase = true)) {
            return ResolvedInput.Magnet(appendDefaultTrackers(trimmed))
        }

        val cleanHash = trimmed.removePrefix("urn:btih:").trim()
        if (cleanHash.matches(INFOHASH_HEX_REGEX) || cleanHash.matches(INFOHASH_BASE32_REGEX)) {
            val magnet = "magnet:?xt=urn:btih:$cleanHash"
            return ResolvedInput.Magnet(appendDefaultTrackers(magnet))
        }

        throw IllegalArgumentException("Input Torrent non riconosciuto: $trimmed")
    }

    private fun stackTraceToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    companion object {
        private val INFOHASH_HEX_REGEX = Regex("^[0-9a-fA-F]{40}$")
        private val INFOHASH_BASE32_REGEX = Regex("^[a-zA-Z2-7]{32}$")
        private val MAGNET_IN_TEXT_REGEX = Regex("(magnet:\\?[^\\s\"'<>]+)", RegexOption.IGNORE_CASE)

        private val pinnedSwigObjects: MutableList<Any> = Collections.synchronizedList(mutableListOf())

        @Volatile
        private var sharedSession: SessionManager? = null
        private val sessionLock = Any()

        private fun getOrStartSession(): SessionManager {
            synchronized(sessionLock) {
                val existing = sharedSession
                if (existing != null && existing.isRunning) {
                    return existing
                }
                val newSession = SessionManager()
                newSession.start()
                sharedSession = newSession
                return newSession
            }
        }

        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.moeking.me:6969/announce",
            "http://tracker.opentrackr.org:1337/announce"
        )

        fun isTorrentSource(url: String): Boolean {
            val s = url.trim()
            if (s.isBlank()) return false
            val clean = s.removePrefix("urn:btih:").trim()
            return s.startsWith("magnet:?", ignoreCase = true) ||
                s.endsWith(".torrent", ignoreCase = true) ||
                clean.matches(INFOHASH_HEX_REGEX) ||
                clean.matches(INFOHASH_BASE32_REGEX)
        }

        fun extractMagnetOrHashFromBytes(bytes: ByteArray): String? {
            if (bytes.isEmpty()) return null
            val text = try {
                String(bytes, Charsets.UTF_8).trim().replace("&amp;", "&")
            } catch (_: Exception) {
                return null
            }

            val magnetMatch = MAGNET_IN_TEXT_REGEX.find(text)
            if (magnetMatch != null) {
                return magnetMatch.value.trim()
            }

            val firstLine = text.lineSequence().firstOrNull()?.trim()?.removePrefix("urn:btih:")?.trim() ?: ""
            if (firstLine.matches(INFOHASH_HEX_REGEX) || firstLine.matches(INFOHASH_BASE32_REGEX)) {
                return "magnet:?xt=urn:btih:$firstLine"
            }

            return null
        }

        private fun appendDefaultTrackers(magnetUri: String): String {
            val sb = StringBuilder(magnetUri.trim())
            for (tr in DEFAULT_TRACKERS) {
                val encoded = Uri.encode(tr)
                if (!magnetUri.contains(tr, ignoreCase = true) && !magnetUri.contains(encoded, ignoreCase = true)) {
                    sb.append("&tr=").append(encoded)
                }
            }
            return sb.toString()
        }
    }
}
