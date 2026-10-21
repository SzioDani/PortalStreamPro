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
import java.time.format.DateTimeFormatter

data class EpgInfo(
    val currentProgram: String = "",
    val nextProgram: String = "",
    val progress: Float = 0f,
    val timeRange: String = ""
)

class EpgRepository(private val context: Context) {

    private val TAG = "EpgRepository"
    private val downloader = EpgDownloader(context)
    private val parser = EpgParser()
    private val mapper = EpgChannelMapper(context)

    private var cachedPrograms: List<EpgProgram> = emptyList()

    suspend fun getAllCurrentEpg(
        channels: List<Channel>,
        countryCode: String = "it",
        timeZone: ZoneId = ZoneId.of("Europe/Rome")
    ): Map<String, EpgInfo> = withContext(Dispatchers.Default) {
        val resultMap = mutableMapOf<String, EpgInfo>()
        try {
            if (cachedPrograms.isEmpty()) {
                Log.d(TAG, "Cache programmi vuota. Avvio download EPG...")
                val result = downloader.downloadEpg(countryCode)
                result.getOrNull()?.let { xmlContent ->
                    cachedPrograms = parser.parseXmlTv(xmlContent, timeZone)
                    Log.d(TAG, "Totale programmi parsati dal file EPG: ${cachedPrograms.size}")
                }
            }

            if (cachedPrograms.isEmpty()) {
                Log.e(TAG, "ATTENZIONE: cachedPrograms è vuota! Nessun programma disponibile in memoria.")
                return@withContext emptyMap()
            }

            val now = LocalDateTime.now(timeZone)
            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

            channels.forEach { channel ->
                val mapping = mapper.findBestMatch(channel.name)
                val targetId = when {
                    mapping != null && mapping.xmltvId.isNotBlank() -> mapping.xmltvId
                    mapping != null && mapping.siteId.isNotBlank() -> mapping.siteId
                    else -> null
                }

                if (targetId != null) {
                    // 🚀 Normalizzazione flessibile: rimuove @HD/@SD, spazi e maiuscole/minuscole
                    val cleanTargetId = targetId
                        .substringBefore("@")
                        .replace(" ", "")
                        .lowercase()

                    val channelProgs = cachedPrograms.filter { prog ->
                        val cleanProgId = prog.channelId
                            .substringBefore("@")
                            .replace(" ", "")
                            .lowercase()
                        cleanProgId == cleanTargetId
                    }.sortedBy { it.startTime }

                    val currentIndex = channelProgs.indexOfFirst { 
                        now.isAfter(it.startTime) && now.isBefore(it.endTime) 
                    }

                    if (currentIndex != -1) {
                        val prog = channelProgs[currentIndex]
                        val current = prog.title
                        val next = if (currentIndex + 1 < channelProgs.size) channelProgs[currentIndex + 1].title else ""

                        val startEpoch = prog.startTime.atZone(timeZone).toEpochSecond()
                        val endEpoch = prog.endTime.atZone(timeZone).toEpochSecond()
                        val nowEpoch = now.atZone(timeZone).toEpochSecond()

                        val totalDuration = (endEpoch - startEpoch).toFloat()
                        val elapsed = (nowEpoch - startEpoch).toFloat()
                        val progress = if (totalDuration > 0) (elapsed / totalDuration).coerceIn(0f, 1f) else 0f
                        val timeRange = "${prog.startTime.format(timeFormatter)} - ${prog.endTime.format(timeFormatter)}"

                        resultMap[channel.id] = EpgInfo(current, next, progress, timeRange)
                    } else {
                        val upcoming = channelProgs.firstOrNull { it.startTime.isAfter(now) }
                        val current = upcoming?.title ?: channelProgs.firstOrNull()?.title ?: ""
                        val timeRange = if (upcoming != null) "${upcoming.startTime.format(timeFormatter)}" else ""
                        resultMap[channel.id] = EpgInfo(current, "", 0f, timeRange)
                    }
                }
            }
            Log.d(TAG, "Mappa EPG generata con successo per ${resultMap.size} canali.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore critico in getAllCurrentEpg", e)
        }
        resultMap
    }
}
