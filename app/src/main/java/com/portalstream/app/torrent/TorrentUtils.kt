package com.portalstream.app.torrent

import android.net.Uri

object TorrentUtils {

    // Lista di tracker pubblici ad alte prestazioni per accelerare la ricerca dei peer
    private val DEFAULT_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.coppersurfer.tk:6969/announce",
        "udp://tracker.tiny-vps.com:6969/announce"
    )

    /**
     * Analizza l'input inserito (Magnet link, InfoHash HEX a 40 car., InfoHash Base32 a 32 car., o File Uri)
     * e restituisce un URI Magnet valido e pronto per libtorrent4j.
     */
    fun parseToMagnetUri(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        // 1. Caso: Magnet link completo (es. magnet:?xt=urn:btih:...)
        if (trimmed.startsWith("magnet:?", ignoreCase = true)) {
            return trimmed
        }

        // 2. Caso: File .torrent locale o URI di Android (es. content://...)
        if (trimmed.startsWith("content://") || trimmed.startsWith("file://") || trimmed.endsWith(".torrent", ignoreCase = true)) {
            return trimmed
        }

        // 3. Caso: InfoHash HEX (40 caratteri esadecimali)
        if (trimmed.length == 40 && trimmed.matches(Regex("^[a-fA-F0-9]+$"))) {
            return buildMagnetFromHash(trimmed.lowercase())
        }

        // 4. Caso: InfoHash Base32 (32 caratteri, formato classico usato da Elementum/Kodi)
        if (trimmed.length == 32 && trimmed.matches(Regex("^[a-zA-Z2-7]+$"))) {
            return buildMagnetFromHash(trimmed.uppercase())
        }

        return null
    }

    /**
     * Costruisce una stringa Magnet URI aggiungendo i tracker pubblici di default
     */
    private fun buildMagnetFromHash(hash: String): String {
        val builder = StringBuilder("magnet:?xt=urn:btih:$hash")
        DEFAULT_TRACKERS.forEach { tracker ->
            builder.append("&tr=").append(Uri.encode(tracker))
        }
        return builder.toString()
    }
}
