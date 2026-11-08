package com.portalstream.app.streaming

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.*
import org.libtorrent4j.swig.torrent_handle_vector
import timber.log.Timber
import java.io.File
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

    // CoroutineExceptionHandler impedisce qualsiasi crash dell'Activity in caso di eccezioni impreviste
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Eccezione intercettata in TorrentStreamEngine")
        _state.value = TorrentStreamState.Error(
            "Errore motore Torrent: ${throwable.localizedMessage ?: throwable.javaClass.simpleName}"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private var monitorJob: Job? = null

    private var selectedFileIndex: Int = -1
    private var firstPieceIndex: Int = 0
    private var lastPieceIndex: Int = 0
    private var isConfigured: Boolean = false
    private var isReadyEmitted: Boolean = false
    private var trackersInjected: Boolean = false

    private val _state = MutableStateFlow<TorrentStreamState>(TorrentStreamState.Idle)
    val state: StateFlow<TorrentStreamState> = _state.asStateFlow()

    private val downloadDir: File by lazy {
        File(context.externalCacheDir ?: context.cacheDir, "torrent_stream").apply {
            if (!exists()) mkdirs()
        }
    }

    fun startStream(rawInput: String) {
        monitorJob?.cancel()
        isReadyEmitted = false
        isConfigured = false
        trackersInjected = false
        selectedFileIndex = -1
        _state.value = TorrentStreamState.Buffering("Avvio motore Torrent interno...")

        monitorJob = scope.launch {
            try {
                val session = getOrStartSession()
                if (!session.isDhtRunning) {
                    session.startDht()
                }

                // Rimuove eventuali torrent precedenti dalla sessione prima di avviarne uno nuovo
                removeAllActiveTorrents(session)
                cleanOldCache()

                when (val resolved = resolveTorrentInput(rawInput)) {
                    is ResolvedInput.Magnet -> {
                        _state.value = TorrentStreamState.Buffering("Ricerca peer e recupero metadati...")
                        session.download(
                            resolved.magnetUri,
                            downloadDir,
                            TorrentFlags.SEQUENTIAL_DOWNLOAD
                        )
                    }
                    is ResolvedInput.TorrentFile -> {
                        _state.value = TorrentStreamState.Buffering("Apertura file .torrent...")
                        val ti = TorrentInfo(resolved.file)
                        session.download(
                            ti,
                            downloadDir,
                            null,
                            null,
                            null,
                            TorrentFlags.SEQUENTIAL_DOWNLOAD
                        )
                    }
                }

                // Ciclo di monitoraggio su singolo thread (evita race condition su TorrentHandle)
                var ticks = 0
                while (isActive && !isReadyEmitted) {
                    delay(600)
                    ticks++

                    val handle = findActiveTorrentHandle(session) ?: continue
                    if (!handle.isValid) continue

                    // Inietta i tracker e forza annuncio DHT/LSD appena il torrent entra in sessione
                    if (!trackersInjected) {
                        trackersInjected = true
                        injectDefaultTrackersAndAnnounce(handle)
                    } else if (ticks % 15 == 0) {
                        // Ogni ~9 secondi forza il re-announce se sta ancora cercando peer
                        try {
                            handle.forceDHTAnnounce()
                            handle.forceReannounce()
                        } catch (_: Throwable) {}
                    }

                    // IMPORTANTE: status(true) forza l'aggiornamento ed evita che lastStatus sia null!
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
                            configureVideoPriorities(handle)
                        }
                        if (isConfigured) {
                            checkIfReadyToPlay(handle, status)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
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
        val size = vector.size
        for (i in 0 until size) {
            val th = vector.get(i)
            if (th != null && th.is_valid()) {
                return TorrentHandle(th)
            }
        }
        return null
    }

    private fun injectDefaultTrackersAndAnnounce(handle: TorrentHandle) {
        try {
            if (!handle.isValid) return
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
            handle.resume()
            for (tr in DEFAULT_TRACKERS) {
                handle.addTracker(AnnounceEntry(tr))
            }
            handle.forceDHTAnnounce()
            handle.forceLSDAnnounce()
            handle.forceReannounce()
        } catch (e: Throwable) {
            Timber.w(e, "Impossibile iniettare i tracker di default")
        }
    }

    private fun configureVideoPriorities(handle: TorrentHandle) {
        if (isConfigured || !handle.isValid) return
        val ti = handle.torrentFile() ?: return
        if (!ti.isValid) return

        val numFiles = ti.numFiles()
        if (numFiles <= 0) return

        val files = ti.files()
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

        // Ignora i file non video (.txt, .nfo, sample, ecc.)
        if (numFiles > 1) {
            val filePriorities = Array(numFiles) { idx ->
                if (idx == selectedFileIndex) Priority.DEFAULT else Priority.IGNORE
            }
            handle.prioritizeFiles(filePriorities)
        }

        val pieceLength = ti.pieceLength().toLong().coerceAtLeast(1L)
        val fileOffset = files.fileOffset(selectedFileIndex)
        val fileSize = files.fileSize(selectedFileIndex)
        val totalPieces = ti.numPieces().coerceAtLeast(1)

        firstPieceIndex = (fileOffset / pieceLength).toInt().coerceIn(0, totalPieces - 1)
        lastPieceIndex = ((fileOffset + max(0L, fileSize - 1L)) / pieceLength).toInt()
            .coerceIn(firstPieceIndex, totalPieces - 1)

        // Usa il metodo nativo di TorrentHandle.java (riga 150) per impostare il range sequenziale
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        handle.setSequentialRange(firstPieceIndex, lastPieceIndex)

        boostHeadAndTailPriorities(handle, totalPieces)

        isConfigured = true
        Timber.d("File video configurato: ${files.filePath(selectedFileIndex)} (pezzi: $firstPieceIndex..$lastPieceIndex)")
    }

    private fun boostHeadAndTailPriorities(handle: TorrentHandle, totalPieces: Int) {
        try {
            val endHeadPiece = min(lastPieceIndex, firstPieceIndex + 5)
            for (p in firstPieceIndex..endHeadPiece) {
                if (p in 0 until totalPieces && !handle.havePiece(p)) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                }
            }
            val startTailPiece = max(firstPieceIndex, lastPieceIndex - 1)
            for (p in startTailPiece..lastPieceIndex) {
                if (p in 0 until totalPieces && !handle.havePiece(p)) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                }
            }
        } catch (_: Throwable) {}
    }

    private fun checkIfReadyToPlay(handle: TorrentHandle, status: TorrentStatus) {
        if (isReadyEmitted || !handle.isValid) return
        val ti = handle.torrentFile() ?: return
        if (selectedFileIndex < 0 || selectedFileIndex >= ti.numFiles()) return

        val files = ti.files()
        val videoFile = File(downloadDir, files.filePath(selectedFileIndex))
        val fileSize = files.fileSize(selectedFileIndex).coerceAtLeast(1L)

        // Mantiene alta la priorità dei primi pezzi (perché prioritizeFiles resetta le priorità dei pezzi)
        if (!handle.havePiece(firstPieceIndex)) {
            boostHeadAndTailPriorities(handle, ti.numPieces().coerceAtLeast(1))
        }

        val requiredHeadPieces = min(4, (lastPieceIndex - firstPieceIndex + 1).coerceAtLeast(1))
        var downloadedHeadPieces = 0
        for (i in 0 until requiredHeadPieces) {
            if (handle.havePiece(firstPieceIndex + i)) {
                downloadedHeadPieces++
            }
        }

        // Soglia minima buffer iniziale (5 MB oppure 3% del file, max 12 MB)
        val minBufferBytes = min(12L * 1024 * 1024, max(5L * 1024 * 1024, (fileSize * 0.03).toLong()))
        val downloadedBytes = status.totalWantedDone()

        val headProgress = (downloadedHeadPieces.toFloat() / requiredHeadPieces.toFloat())
        val byteProgress = (downloadedBytes.toFloat() / minBufferBytes.toFloat()).coerceAtMost(1f)
        val bufferPercent = ((headProgress * 0.5f + byteProgress * 0.5f) * 100).toInt().coerceIn(0, 99)

        val isFileReady = videoFile.exists() && videoFile.length() > 0 && (
            status.isFinished ||
                status.isSeeding ||
                (downloadedHeadPieces >= requiredHeadPieces && downloadedBytes >= minBufferBytes)
            )

        if (isFileReady) {
            isReadyEmitted = true
            _state.value = TorrentStreamState.Ready(videoFile)
        } else {
            val speedKbps = status.downloadPayloadRate() / 1024
            val peers = status.numPeers()
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
        } catch (e: Throwable) {
            Timber.e(e, "Errore pulizia torrent attivi")
        }
    }

    private fun cleanOldCache() {
        try {
            downloadDir.listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Throwable) {}
    }

    fun stop() {
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

        // 1. Se è un percorso file locale (.torrent) o file://
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

        // 2. Se è già un link magnet:?
        if (trimmed.startsWith("magnet:?", ignoreCase = true)) {
            return ResolvedInput.Magnet(appendDefaultTrackers(trimmed))
        }

        // 3. Se è un InfoHash puro (40 hex o 32 base32) o urn:btih:
        val cleanHash = trimmed.removePrefix("urn:btih:").trim()
        if (cleanHash.matches(INFOHASH_HEX_REGEX) || cleanHash.matches(INFOHASH_BASE32_REGEX)) {
            val magnet = "magnet:?xt=urn:btih:$cleanHash"
            return ResolvedInput.Magnet(appendDefaultTrackers(magnet))
        }

        throw IllegalArgumentException("Input Torrent non riconosciuto: $trimmed")
    }

    companion object {
        private val INFOHASH_HEX_REGEX = Regex("^[0-9a-fA-F]{40}$")
        private val INFOHASH_BASE32_REGEX = Regex("^[a-zA-Z2-7]{32}$")
        private val MAGNET_IN_TEXT_REGEX = Regex("(magnet:\\?[^\\s\"'<>]+)", RegexOption.IGNORE_CASE)

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
