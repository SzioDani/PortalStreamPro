package com.portalstream.app.streaming

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.libtorrent4j.*
import org.libtorrent4j.alerts.*
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionManager: SessionManager? = null
    private var currentHandle: TorrentHandle? = null
    private var monitorJob: Job? = null

    private var selectedFileIndex: Int = -1
    private var firstPieceIndex: Int = 0
    private var lastPieceIndex: Int = 0
    private var isReadyEmitted: Boolean = false

    private val _state = MutableStateFlow<TorrentStreamState>(TorrentStreamState.Idle)
    val state: StateFlow<TorrentStreamState> = _state.asStateFlow()

    private val downloadDir: File by lazy {
        File(context.externalCacheDir ?: context.cacheDir, "torrent_stream").apply {
            if (!exists()) mkdirs()
        }
    }

    private val alertListener = object : AlertListener {
        override fun types(): IntArray? = null

        override fun alert(alert: Alert<*>) {
            try {
                when (alert.type()) {
                    AlertType.ADD_TORRENT -> {
                        val handle = (alert as AddTorrentAlert).handle()
                        if (handle.isValid) {
                            currentHandle = handle
                            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                            handle.resume()
                            if (handle.torrentFile() != null) {
                                configureVideoPriorities(handle)
                            }
                        }
                    }
                    AlertType.METADATA_RECEIVED -> {
                        val handle = (alert as MetadataReceivedAlert).handle()
                        if (handle.isValid) {
                            currentHandle = handle
                            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                            configureVideoPriorities(handle)
                        }
                    }
                    AlertType.PIECE_FINISHED -> {
                        val handle = (alert as PieceFinishedAlert).handle()
                        if (handle.isValid && !isReadyEmitted) {
                            checkIfReadyToPlay(handle)
                        }
                    }
                    AlertType.TORRENT_ERROR -> {
                        val errAlert = alert as TorrentErrorAlert
                        Timber.e("TorrentErrorAlert: ${errAlert.error().message}")
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Timber.e(e, "Errore gestione alert libtorrent4j")
            }
        }
    }

    fun startStream(rawInput: String) {
        stop()
        isReadyEmitted = false
        selectedFileIndex = -1
        _state.value = TorrentStreamState.Buffering("Avvio motore Torrent interno...")

        scope.launch {
            try {
                cleanOldCache()

                val session = SessionManager()
                session.addListener(alertListener)
                session.start()
                sessionManager = session

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

                startProgressMonitor()
            } catch (e: Exception) {
                Timber.e(e, "Errore avvio stream torrent")
                _state.value = TorrentStreamState.Error(
                    "Impossibile avviare il torrent: ${e.localizedMessage ?: "input non valido"}"
                )
            }
        }
    }

    private fun configureVideoPriorities(handle: TorrentHandle) {
        val ti = handle.torrentFile() ?: return
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

        // Fallback al file più grande se nessun file ha estensione standard
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

        // Ignora tutti i file secondari (.txt, .nfo, sample, ecc.)
        if (numFiles > 1) {
            val filePriorities = Array(numFiles) { idx ->
                if (idx == selectedFileIndex) Priority.DEFAULT else Priority.IGNORE
            }
            handle.prioritizeFiles(filePriorities)
        }

        val pieceLength = ti.pieceLength().toLong().coerceAtLeast(1L)
        val fileOffset = files.fileOffset(selectedFileIndex)
        val fileSize = files.fileSize(selectedFileIndex)
        val totalPieces = ti.numPieces()

        firstPieceIndex = (fileOffset / pieceLength).toInt().coerceIn(0, totalPieces - 1)
        lastPieceIndex = ((fileOffset + max(0L, fileSize - 1L)) / pieceLength).toInt()
            .coerceIn(firstPieceIndex, totalPieces - 1)

        // Priorità massima ai primi ~8MB (Head) e agli ultimi 2 pezzi (Tail per atomo moov MP4 / Cues MKV)
        val bytesForHead = 8L * 1024L * 1024L
        val headPiecesCount = max(6, (bytesForHead / pieceLength).toInt())
        val endHeadPiece = min(lastPieceIndex, firstPieceIndex + headPiecesCount - 1)

        for (p in firstPieceIndex..endHeadPiece) {
            handle.piecePriority(p, Priority.TOP_PRIORITY)
            handle.setPieceDeadline(p, (p - firstPieceIndex + 1) * 800)
        }

        val startTailPiece = max(firstPieceIndex, lastPieceIndex - 1)
        for (p in startTailPiece..lastPieceIndex) {
            handle.piecePriority(p, Priority.TOP_PRIORITY)
            handle.setPieceDeadline(p, 2000)
        }

        Timber.d("File video selezionato: ${files.filePath(selectedFileIndex)} (pezzi: $firstPieceIndex..$lastPieceIndex)")
    }

    private fun startProgressMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive && !isReadyEmitted) {
                val handle = currentHandle
                if (handle != null && handle.isValid) {
                    val status = handle.status()
                    val ti = handle.torrentFile()
                    val speedKbps = status.downloadPayloadRate() / 1024
                    val peers = status.numPeers()

                    if (ti == null || selectedFileIndex < 0) {
                        _state.value = TorrentStreamState.Buffering(
                            statusText = "Recupero metadati dal Magnet...",
                            progressPercent = 0,
                            downloadSpeedKbps = speedKbps,
                            peers = peers
                        )
                    } else {
                        checkIfReadyToPlay(handle)
                    }
                }
                delay(500)
            }
        }
    }

    @Synchronized
    private fun checkIfReadyToPlay(handle: TorrentHandle) {
        if (isReadyEmitted) return
        val ti = handle.torrentFile() ?: return
        if (selectedFileIndex < 0) return

        val status = handle.status()
        val files = ti.files()
        val videoFile = File(downloadDir, files.filePath(selectedFileIndex))
        val fileSize = files.fileSize(selectedFileIndex).coerceAtLeast(1L)

        // Calcoliamo quanti dei primi pezzi obbligatori sono già scaricati
        val requiredHeadPieces = min(4, (lastPieceIndex - firstPieceIndex + 1).coerceAtLeast(1))
        var downloadedHeadPieces = 0
        for (i in 0 until requiredHeadPieces) {
            if (handle.havePiece(firstPieceIndex + i)) {
                downloadedHeadPieces++
            }
        }

        // Soglia minima buffer (4 MB oppure 3% del file, max 12 MB)
        val minBufferBytes = min(12L * 1024 * 1024, max(4L * 1024 * 1024, (fileSize * 0.03).toLong()))
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

    private fun cleanOldCache() {
        try {
            downloadDir.listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Exception) {}
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        val session = sessionManager
        sessionManager = null
        currentHandle = null
        if (session != null) {
            scope.launch {
                try {
                    session.removeListener(alertListener)
                    if (session.isRunning) {
                        session.stop()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Errore arresto SessionManager")
                }
            }
        }
    }

    private sealed class ResolvedInput {
        data class Magnet(val magnetUri: String) : ResolvedInput()
        data class TorrentFile(val file: File) : ResolvedInput()
    }

    private fun resolveTorrentInput(raw: String): ResolvedInput {
        val trimmed = raw.trim()

        // 1. Se è un percorso file locale (.torrent) o file://
        val localFile = when {
            trimmed.startsWith("file://") -> Uri.parse(trimmed).path?.let { File(it) }
            trimmed.startsWith("/") -> File(trimmed)
            else -> null
        }

        if (localFile != null && localFile.exists()) {
            // Controlliamo se il file .torrent contiene al suo interno un magnet o infohash testuale
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
        private val MAGNET_IN_TEXT_REGEX = Regex("(magnet:\\?xt=urn:btih:[^\\s\"'<>]+)", RegexOption.IGNORE_CASE)

        private val DEFAULT_TRACKERS = listOf(
            "udp://tracker.opentrackr.org:1337/announce",
            "udp://open.stealth.si:80/announce",
            "udp://tracker.torrent.eu.org:451/announce",
            "udp://exodus.desync.com:6969/announce",
            "udp://tracker.moeking.me:6969/announce",
            "http://tracker.opentrackr.org:1337/announce"
        )

        /**
         * Riconosce se una stringa/URL è un InfoHash, un Magnet o un file .torrent
         */
        fun isTorrentSource(url: String): Boolean {
            val s = url.trim()
            if (s.isBlank()) return false
            val clean = s.removePrefix("urn:btih:").trim()
            return s.startsWith("magnet:?", ignoreCase = true) ||
                s.endsWith(".torrent", ignoreCase = true) ||
                clean.matches(INFOHASH_HEX_REGEX) ||
                clean.matches(INFOHASH_BASE32_REGEX)
        }

        /**
         * Legge i byte di un file .torrent:
         * - Se dentro c'è scritto un link magnet:? o un InfoHash testuale, restituisce il magnet/infohash!
         * - Se è un file .torrent binario bencoded, restituisce null (così verrà salvato come file .torrent).
         */
        fun extractMagnetOrHashFromBytes(bytes: ByteArray): String? {
            if (bytes.isEmpty()) return null
            val text = try {
                String(bytes, Charsets.UTF_8).trim()
            } catch (_: Exception) {
                return null
            }

            // Cerca magnet:? dentro il file
            val magnetMatch = MAGNET_IN_TEXT_REGEX.find(text)
            if (magnetMatch != null) {
                return magnetMatch.value.trim()
            }

            // Controlla se il file contiene direttamente un InfoHash puro
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
