package com.portalstream.app.data

import com.portalstream.app.streaming.TorrentStreamEngine
import com.portalstream.app.streaming.WebStreamExtractor

enum class PortalType(val label: String) {
    M3U("M3U"),
    XSTREAM("XStream"),
    MAG("MAG/MAC"),
    TORRENT("Torrent"),
    WEB_STREAM("Web / YouTube"),
    UNKNOWN("Sconosciuto");

    companion object {
        fun detect(url: String): PortalType = when {
            TorrentStreamEngine.isTorrentSource(url) -> TORRENT
            WebStreamExtractor.isYoutubeUrl(url) -> WEB_STREAM
            url.contains("player_api.php", ignoreCase = true) ||
                (url.contains("get.php", ignoreCase = true) && url.contains("username=", ignoreCase = true)) -> XSTREAM
            url.contains("stalker_portal", ignoreCase = true) ||
                url.contains("/portal.php", ignoreCase = true) ||
                url.contains("/server/load.php", ignoreCase = true) -> MAG
            url.contains(".m3u", ignoreCase = true) ||
                url.contains("playlist", ignoreCase = true) -> M3U
            WebStreamExtractor.isWebExtractorSource(url) -> WEB_STREAM
            else -> UNKNOWN
        }
    }
}

data class Portal(
    val id: Int,
    val name: String = "",
    val type: PortalType = PortalType.UNKNOWN,
    val url: String = "",
    val macAddress: String = "",
    val useVpn: Boolean = false,
    val profile: String = "",
    val useCustomUserAgent: Boolean = false,
    val userAgent: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val streamFormat: String = "m3u8",
    val forceStreamLink: Boolean = false,
    val expireDate: String = "",
    val activationDate: String = ""
)
