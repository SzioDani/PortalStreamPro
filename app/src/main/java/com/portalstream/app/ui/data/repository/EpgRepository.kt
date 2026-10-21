package com.portalstream.app.data.repository

import android.content.Context
import android.util.Log
import com.portalstream.app.data.epg.EpgChannelMapper
import com.portalstream.app.data.epg.EpgDownloader
import com.portalstream.app.data.epg.EpgParser
import com.portalstream.app.data.epg.EpgProgram
import com.portalstream.app.domain.model.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId

data class EpgInfo(
    val currentProgram: String = "",
    val nextProgram: String = ""
)

class EpgRepository(private val context: Context) {

    private val downloader = EpgDownloader(context)
    private val parser = EpgParser()
    private val mapper = EpgChannelMapper(context)

    private var cachedPrograms: List<EpgProgram> = emptyList()

    /**
     * Scarica (se necessario) e mappa l'EPG per l'intera lista dei canali in background.
     */
    suspend fun getAllCurrentEpg(
        channels: List<Channel>,
        countryCode: String = "it",
        timeZone: ZoneId = ZoneId.of("Europe/Rome")
    ): Map<String, EpgInfo> = withContext(Dispatchers.Default) {
        val resultMap = mutableMapOf<String, EpgInfo>()
        try {
            if (cachedPrograms.isEmpty()) {
                val result = downloader.downloadEpg(countryCode)
                result.getOrNull()?.let { xmlContent ->
                    cachedPrograms = parser.parseXmlTv(xmlContent, timeZone)
                }
            }

            val now = LocalDateTime.now(timeZone)

            channels.forEach { channel ->
                val mapping = mapper.findBestMatch(channel.name)
                val targetId = when {
                    mapping != null && mapping.xmltvId.isNotBlank() -> mapping.xmltvId
                    mapping != null && mapping.siteId.isNotBlank() -> mapping.siteId
                    else -> null
                }

                if (targetId != null) {
                    val channelProgs = cachedPrograms.filter { 
                        it.channelId.equals(targetId, ignoreCase = true) 
                    }.sortedBy { it.startTime }

                    val currentIndex = channelProgs.indexOfFirst { 
                        now.isAfter(it.startTime) && now.isBefore(it.endTime) 
                    }

                    if (currentIndex != -1) {
                        val current = channelProgs[currentIndex].title
                        val next = if (currentIndex + 1 < channelProgs.size) channelProgs[currentIndex + 1].title else ""
                        resultMap[channel.id] = EpgInfo(current, next)
                    } else {
                        val upcoming = channelProgs.firstOrNull { it.startTime.isAfter(now) }
                        val current = upcoming?.title ?: channelProgs.firstOrNull()?.title ?: ""
                        resultMap[channel.id] = EpgInfo(current, "")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("EpgRepository", "Errore nel caricamento batch EPG iptv-org", e)
        }
        resultMap
    }
}
