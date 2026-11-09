package com.portalstream.app.streaming

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.libtorrent4j.*
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_handle_vector
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.io.StringWriter
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

sealed class TorrentStreamState {
    object Idle : TorrentStreamState()
    data class Buffering(
        val statusText: String,
        val progressPercent: Int = 0,
        val downloadSpeedKbps: Int = 0,
        val peers: Int = 0,
        val torrentTitle: String = ""
    ) : TorrentStreamState()
    data class Ready(
        val videoFile: File,
        val torrentTitle: String = ""
    ) : TorrentStreamState()
    data class Error(val message: String) : TorrentStreamState()
}

class TorrentStreamEngine(private val context: Context) {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logToFile("FATAL CoroutineExceptionHandler: ${throwable.message}\n${stackTraceToString(throwable)}")
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
    private var selectedFileOffset: Long = 0L
    private var currentPieceLength: Long = 256 * 1024L
    private var firstPieceIndex: Int = 0
    private var lastPieceIndex: Int = 0

    private var requiredHeadPieceCount: Int = 6
    private var requiredTailPieceCount: Int = 4
    private var mp4HeaderInspected: Boolean = false

    private var isConfigured: Boolean = false
    private var isReadyEmitted: Boolean = false
    private var readyWaitTicks: Int = 0

    @Volatile
    var currentTorrentTitle: String = ""
        private set

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
        } catch (_: Throwable) {}
    }

    private fun clearLogFile() {
        try {
            val header = "=== AVVIO NUOVA SESSIONE TORRENT (${LibTorrent.libtorrent4jVersion()}) ===\n"
            val externalFiles = context.getExternalFilesDir(null) ?: context.filesDir
            File(externalFiles, "torrent_debug.log").writeText(header)
        } catch (_: Throwable) {}
    }

    fun startStream(rawInput: String) {
        monitorJob?.cancel()
        isReadyEmitted = false
        isConfigured = false
        mp4HeaderInspected = false
        readyWaitTicks = 0
        selectedFileIndex = -1
        selectedVideoFile = null
        selectedVideoSize = 0L
        selectedFileOffset = 0L
        currentTorrentTitle = extractDisplayNameFromMagnet(rawInput)
        clearLogFile()
        logToFile("startStream chiamato con input: $rawInput (titolo iniziale='$currentTorrentTitle')")

        _state.value = TorrentStreamState.Buffering(
            statusText = "Avvio motore Torrent rapido...",
            torrentTitle = currentTorrentTitle
        )

        monitorJob = scope.launch {
            try {
                val session = getOrStartSession()
                if (!session.isDhtRunning) {
                    session.startDht()
                }

                // Rimuove tutti i torrent precedenti ed elimina i relativi file su disco
                removeAllActiveTorrents(session, deleteFiles = true)
                delay(200)
                wipeAllTorrentCacheDirs()

                currentSaveDir = File(baseStreamDir, "session_${System.currentTimeMillis()}").apply {
                    mkdirs()
                }
                logToFile("Cartella download pulita creata: ${currentSaveDir.absolutePath}")

                when (val resolved = resolveTorrentInput(rawInput)) {
                    is ResolvedInput.Magnet -> {
                        val magnetTitle = extractDisplayNameFromMagnet(resolved.magnetUri)
                        if (magnetTitle.isNotBlank()) {
                            currentTorrentTitle = magnetTitle
                        }
                        _state.value = TorrentStreamState.Buffering(
                            statusText = "Ricerca peer e recupero metadati...",
                            torrentTitle = currentTorrentTitle
                        )
                        session.download(
                            resolved.magnetUri,
                            currentSaveDir,
                            TorrentFlags.SEQUENTIAL_DOWNLOAD
                        )
                    }
                    is ResolvedInput.TorrentFile -> {
                        val ti = TorrentInfo(resolved.file)
                        pinnedSwigObjects.add(ti)
                        if (ti.isValid && !ti.name().isNullOrBlank()) {
                            currentTorrentTitle = cleanTorrentTitle(ti.name())
                        }
                        _state.value = TorrentStreamState.Buffering(
                            statusText = "Apertura file .torrent...",
                            torrentTitle = currentTorrentTitle
                        )
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

                while (isActive) {
                    delay(if (isReadyEmitted) 1500L else 450L)
                    ticks++

                    if (activeHandle == null || !activeHandle.isValid) {
                        activeHandle = findActiveTorrentHandle(session)
                        if (activeHandle != null && activeHandle.isValid) {
                            pinnedSwigObjects.add(activeHandle)
                        }
                    }

                    val handle = activeHandle ?: continue
                    if (!handle.isValid) continue

                    val status = handle.status(true) ?: continue
                    val speedKbps = status.downloadPayloadRate() / 1024
                    val peers = status.numPeers()

                    if (currentTorrentTitle.isBlank() && !status.name().isNullOrBlank()) {
                        currentTorrentTitle = cleanTorrentTitle(status.name())
                    }

                    if (!status.hasMetadata()) {
                        _state.value = TorrentStreamState.Buffering(
                            statusText = "Recupero metadati dal Magnet...",
                            progressPercent = 0,
                            downloadSpeedKbps = speedKbps,
                            peers = peers,
                            torrentTitle = currentTorrentTitle
                        )
                    } else {
                        if (!isConfigured) {
                            configureVideoSelectionOnce(handle)
                        }
                        if (isConfigured) {
                            if (!mp4HeaderInspected && handle.havePiece(firstPieceIndex)) {
                                inspectMp4StructureAndBoostMoov(handle)
                            }
                            maintainSlidingWindowPriorities(handle)
                            if (!isReadyEmitted) {
                                checkIfReadyToPlay(handle, status, ticks)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
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
        val videoExtensions = listOf(
            ".mp4", ".mkv", ".avi", ".ts", ".webm", ".mov",
            ".m4v", ".flv", ".mpg", ".mpeg", ".m2ts", ".wmv", ".ogv"
        )

        for (i in 0 until numFiles) {
            val name = files.fileName(i).lowercase()
            val size = files.fileSize(i)
            val isVideo = videoExtensions.any { name.endsWith(it) } && !name.contains("sample")
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
        selectedFileOffset = files.fileOffset(selectedFileIndex)

        val rawMetadataName = ti.name()?.takeIf { it.isNotBlank() }
            ?: files.fileName(selectedFileIndex)
        val cleanedTitle = cleanTorrentTitle(rawMetadataName)
        if (cleanedTitle.isNotBlank()) {
            currentTorrentTitle = cleanedTitle
        }

        currentPieceLength = ti.pieceLength().toLong().coerceAtLeast(16 * 1024L)
        val totalPieces = ti.numPieces().coerceAtLeast(1)

        firstPieceIndex = (selectedFileOffset / currentPieceLength).toInt().coerceIn(0, totalPieces - 1)
        lastPieceIndex = ((selectedFileOffset + max(0L, selectedVideoSize - 1L)) / currentPieceLength).toInt()
            .coerceIn(firstPieceIndex, totalPieces - 1)

        val totalVideoPieces = (lastPieceIndex - firstPieceIndex + 1).coerceAtLeast(1)

        val piecesFor45Mb = ceil((4.5 * 1024 * 1024) / currentPieceLength.toDouble()).toInt()
        requiredHeadPieceCount = piecesFor45Mb.coerceIn(4, min(24, totalVideoPieces))

        val fileNameLower = selectedVideoFile?.name?.lowercase() ?: ""
        val isMp4Family = fileNameLower.endsWith(".mp4") || fileNameLower.endsWith(".m4v") || fileNameLower.endsWith(".mov")
        val tailBytesTarget = if (isMp4Family) (3.5 * 1024 * 1024) else (1.0 * 1024 * 1024)
        val piecesForTail = ceil(tailBytesTarget / currentPieceLength.toDouble()).toInt()
        requiredTailPieceCount = piecesForTail.coerceIn(2, min(18, totalVideoPieces))

        if (numFiles > 1) {
            val filePriorities = Array(numFiles) { idx ->
                if (idx == selectedFileIndex) Priority.DEFAULT else Priority.IGNORE
            }
            handle.prioritizeFiles(filePriorities)
        }

        try {
            handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)

            handle.piecePriority(firstPieceIndex, Priority.TOP_PRIORITY)
            handle.setPieceDeadline(firstPieceIndex, 50)

            val tailStart = max(firstPieceIndex, lastPieceIndex - requiredTailPieceCount + 1)
            for (p in tailStart..lastPieceIndex) {
                handle.piecePriority(p, Priority.TOP_PRIORITY)
                handle.setPieceDeadline(p, 120 + (p - tailStart) * 60)
            }

            val headEnd = min(lastPieceIndex, firstPieceIndex + requiredHeadPieceCount - 1)
            for (p in (firstPieceIndex + 1)..headEnd) {
                handle.piecePriority(p, Priority.TOP_PRIORITY)
                handle.setPieceDeadline(p, 250 + (p - firstPieceIndex) * 100)
            }
        } catch (_: Throwable) {}

        handle.resume()
        isConfigured = true
        readyWaitTicks = 0
    }

    private fun inspectMp4StructureAndBoostMoov(handle: TorrentHandle) {
        val file = selectedVideoFile ?: return
        if (!file.exists() || file.length() < 64) return
        mp4HeaderInspected = true

        val nameLower = file.name.lowercase()
        if (!nameLower.endsWith(".mp4") && !nameLower.endsWith(".m4v") && !nameLower.endsWith(".mov")) {
            return
        }

        try {
            val offsetInFirstPiece = (selectedFileOffset % currentPieceLength).toInt()
            val readableBytesInFirstPiece = currentPieceLength - offsetInFirstPiece
            if (readableBytesInFirstPiece < 64) return

            RandomAccessFile(file, "r").use { raf ->
                var pos = 0L
                val maxScan = min(file.length(), readableBytesInFirstPiece)

                while (pos + 8 <= maxScan) {
                    raf.seek(pos)
                    val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
                    val typeBytes = ByteArray(4)
                    raf.readFully(typeBytes)
                    val boxType = String(typeBytes, Charsets.US_ASCII)

                    val boxSize = when {
                        size32 == 1L -> {
                            if (pos + 16 > maxScan) break
                            raf.readLong()
                        }
                        size32 == 0L -> selectedVideoSize - pos
                        else -> size32
                    }

                    if (boxSize < 8L) break

                    if (boxType == "moov") {
                        val moovEndByte = pos + boxSize + (3L * 1024 * 1024)
                        val neededHeadPieces = ceil(moovEndByte.toDouble() / currentPieceLength.toDouble()).toInt()
                        val totalVideoPieces = (lastPieceIndex - firstPieceIndex + 1).coerceAtLeast(1)
                        requiredHeadPieceCount = max(requiredHeadPieceCount, neededHeadPieces.coerceIn(4, min(30, totalVideoPieces)))
                        requiredTailPieceCount = 1
                        return
                    } else if (boxType == "mdat") {
                        val moovStartOffsetInFile = pos + boxSize
                        if (moovStartOffsetInFile in 1 until selectedVideoSize) {
                            val moovTailBytes = selectedVideoSize - moovStartOffsetInFile
                            val exactMoovPieces = ceil(moovTailBytes.toDouble() / currentPieceLength.toDouble()).toInt() + 1
                            val totalVideoPieces = (lastPieceIndex - firstPieceIndex + 1).coerceAtLeast(1)
                            requiredTailPieceCount = exactMoovPieces.coerceIn(2, min(25, totalVideoPieces))

                            val tailStart = max(firstPieceIndex, lastPieceIndex - requiredTailPieceCount + 1)
                            for (p in tailStart..lastPieceIndex) {
                                if (!handle.havePiece(p)) {
                                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                                    handle.setPieceDeadline(p, 80 + (p - tailStart) * 50)
                                }
                            }
                        }
                        return
                    }

                    pos += boxSize
                }
            }
        } catch (_: Throwable) {}
    }

    private fun maintainSlidingWindowPriorities(handle: TorrentHandle) {
        if (!handle.isValid) return
        try {
            var boosted = 0
            val tailStart = max(firstPieceIndex, lastPieceIndex - requiredTailPieceCount + 1)

            for (p in tailStart..lastPieceIndex) {
                if (!handle.havePiece(p)) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, 100 + boosted * 80)
                    boosted++
                }
            }

            var headBoosted = 0
            for (p in firstPieceIndex..lastPieceIndex) {
                if (!handle.havePiece(p)) {
                    handle.piecePriority(p, Priority.TOP_PRIORITY)
                    handle.setPieceDeadline(p, 250 + headBoosted * 150)
                    headBoosted++
                    if (headBoosted >= 10) break
                }
            }
        } catch (_: Throwable) {}
    }

    private fun checkIfReadyToPlay(handle: TorrentHandle, status: TorrentStatus, ticks: Int) {
        if (isReadyEmitted || !handle.isValid) return
        val videoFile = selectedVideoFile ?: return
        val fileSize = selectedVideoSize.coerceAtLeast(1L)
        readyWaitTicks++

        val headEnd = min(lastPieceIndex, firstPieceIndex + requiredHeadPieceCount - 1)
        var downloadedHeadPieces = 0
        var contiguousHeadPieces = 0
        var brokenContiguity = false

        for (p in firstPieceIndex..headEnd) {
            if (handle.havePiece(p)) {
                downloadedHeadPieces++
                if (!brokenContiguity) {
                    contiguousHeadPieces++
                }
            } else {
                brokenContiguity = true
            }
        }

        val tailStart = max(firstPieceIndex, lastPieceIndex - requiredTailPieceCount + 1)
        var downloadedTailPieces = 0
        for (p in tailStart..lastPieceIndex) {
            if (handle.havePiece(p)) {
                downloadedTailPieces++
            }
        }

        val fileNameLower = videoFile.name.lowercase()
        val isMp4Family = fileNameLower.endsWith(".mp4") || fileNameLower.endsWith(".m4v") || fileNameLower.endsWith(".mov")

        val maxTailWaitTicks = if (isMp4Family) 48 else 22
        val tailSatisfied = (downloadedTailPieces >= requiredTailPieceCount) ||
            (readyWaitTicks >= maxTailWaitTicks && handle.havePiece(lastPieceIndex))

        val minBufferBytes = min(8L * 1024 * 1024, max(4L * 1024 * 1024, (fileSize * 0.012).toLong()))
        val downloadedBytes = status.totalWantedDone()

        val headProgress = (downloadedHeadPieces.toFloat() / requiredHeadPieceCount.toFloat()).coerceAtMost(1f)
        val tailProgress = (downloadedTailPieces.toFloat() / requiredTailPieceCount.toFloat()).coerceAtMost(1f)
        val byteProgress = (downloadedBytes.toFloat() / minBufferBytes.toFloat()).coerceAtMost(1f)

        val bufferPercent = ((headProgress * 0.45f + tailProgress * 0.30f + byteProgress * 0.25f) * 100)
            .toInt()
            .coerceIn(0, 99)

        val speedKbps = status.downloadPayloadRate() / 1024
        val peers = status.numPeers()

        val isFileReady = videoFile.exists() && videoFile.length() > 0 && (
            status.isFinished ||
                status.isSeeding ||
                (contiguousHeadPieces >= requiredHeadPieceCount && tailSatisfied && downloadedBytes >= minBufferBytes)
            )

        if (isFileReady) {
            isReadyEmitted = true
            val finalTitle = currentTorrentTitle.ifBlank { cleanTorrentTitle(videoFile.nameWithoutExtension) }
            currentTorrentTitle = finalTitle
            _state.value = TorrentStreamState.Ready(
                videoFile = videoFile,
                torrentTitle = finalTitle
            )
        } else {
            _state.value = TorrentStreamState.Buffering(
                statusText = "Pre-buffering rapido ($bufferPercent%)...",
                progressPercent = bufferPercent,
                downloadSpeedKbps = speedKbps,
                peers = peers,
                torrentTitle = currentTorrentTitle
            )
        }
    }

    private fun removeAllActiveTorrents(session: SessionManager, deleteFiles: Boolean = true) {
        try {
            val rawSession = session.swig() ?: return
            val vector: torrent_handle_vector = rawSession.get_torrents()
            val size = vector.size
            for (i in 0 until size) {
                val th = vector.get(i)
                if (th != null && th.is_valid()) {
                    if (deleteFiles) {
                        session.remove(TorrentHandle(th), SessionHandle.DELETE_FILES)
                    } else {
                        session.remove(TorrentHandle(th))
                    }
                }
            }
            pinnedSwigObjects.clear()
        } catch (e: Throwable) {
            Timber.e(e, "Errore pulizia torrent attivi")
        }
    }

    /**
     * Svuota completamente la cartella cache dei file video torrent per evitare l'accumulo di Gigabyte.
     */
    private fun wipeAllTorrentCacheDirs() {
        try {
            if (currentSaveDir.exists()) {
                currentSaveDir.deleteRecursively()
            }
            if (baseStreamDir.exists()) {
                baseStreamDir.listFiles()?.forEach { child ->
                    child.deleteRecursively()
                }
            }
            context.externalCacheDir?.let { extCache ->
                File(extCache, "torrent_debug.log").delete()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Ferma lo stream e cancella istantaneamente tutti i Gigabyte allocati nella cache del telefono.
     */
    fun stopAndClearCache() {
        logToFile("stopAndClearCache() chiamato: rimozione torrent ed eliminazione cache su disco")
        monitorJob?.cancel()
        monitorJob = null
        selectedVideoFile = null
        scope.launch {
            try {
                val session = sharedSession
                if (session != null && session.isRunning) {
                    removeAllActiveTorrents(session, deleteFiles = true)
                    delay(250)
                }
            } catch (_: Throwable) {
            } finally {
                wipeAllTorrentCacheDirs()
            }
        }
    }

    fun stop() {
        stopAndClearCache()
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

        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val bytes = downloadWithOkHttpGzip(trimmed)
            val extractedMagnet = extractMagnetOrHashFromBytes(bytes)
            return if (extractedMagnet != null) {
                ResolvedInput.Magnet(appendDefaultTrackers(extractedMagnet))
            } else {
                val tempTorrent = File(currentSaveDir, "remote_${System.currentTimeMillis()}.torrent")
                tempTorrent.writeBytes(bytes)
                ResolvedInput.TorrentFile(tempTorrent)
            }
        }

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

    private fun downloadWithOkHttpGzip(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", "PortalStreamPro/1.0 (Android; TorrentEngine)")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} durante il download del torrent")
            }
            val rawBytes = response.body?.bytes() ?: ByteArray(0)
            if (rawBytes.isEmpty()) {
                throw IllegalStateException("Risposta HTTP vuota")
            }
            val isGzipped = rawBytes.size >= 2 &&
                rawBytes[0] == 0x1f.toByte() &&
                rawBytes[1] == 0x8b.toByte()

            return if (isGzipped) {
                GZIPInputStream(ByteArrayInputStream(rawBytes)).use { it.readBytes() }
            } else {
                rawBytes
            }
        }
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
        private val MAGNET_DN_REGEX = Regex("[?&]dn=([^&]+)", RegexOption.IGNORE_CASE)

        private val pinnedSwigObjects: MutableList<Any> = Collections.synchronizedList(mutableListOf())

        private val okHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .build()
        }

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
                try {
                    val sp = SettingsPack()
                    sp.connectionsLimit(250)
                    sp.maxPeerlistSize(1500)
                    sp.downloadRateLimit(0)
                    sp.uploadRateLimit(0)
                    sp.activeDownloads(4)
                    sp.setString(
                        settings_pack.string_types.dht_bootstrap_nodes.swigValue(),
                        "router.bittorrent.com:6881,router.utorrent.com:6881,dht.transmissionbt.com:6881,dht.aelitis.com:6881"
                    )
                    val params = SessionParams(sp)
                    newSession.start(params)
                } catch (_: Throwable) {
                    newSession.start()
                }
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
            "udp://explodie.org:6969/announce",
            "udp://tracker1.bt.moack.co.kr:80/announce",
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

        fun extractDisplayNameFromMagnet(input: String): String {
            val match = MAGNET_DN_REGEX.find(input) ?: return ""
            val encoded = match.groupValues.getOrNull(1) ?: return ""
            return try {
                cleanTorrentTitle(URLDecoder.decode(encoded, "UTF-8"))
            } catch (_: Exception) {
                cleanTorrentTitle(Uri.decode(encoded))
            }
        }

        fun cleanTorrentTitle(rawName: String?): String {
            if (rawName.isNullOrBlank()) return ""
            return rawName
                .replace(Regex("\\.(mp4|mkv|avi|ts|webm|mov|m4v|flv|mpg|mpeg)$", RegexOption.IGNORE_CASE), "")
                .replace('.', ' ')
                .replace('_', ' ')
                .replace(Regex("\\s+"), " ")
                .trim()
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
        
