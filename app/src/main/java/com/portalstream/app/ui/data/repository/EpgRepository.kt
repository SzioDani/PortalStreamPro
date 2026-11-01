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
    val timeRange: String = "",
    val nextTimeRange: String = ""
)

class EpgRepository(private val context: Context) {

    private val TAG = "EpgRepository"
    private val downloader = EpgDownloader(context)
    private val parser = EpgParser()
    private val mapper = EpgChannelMapper()

    private var cachedPrograms: List<EpgProgram> = emptyList()
    // 🚀 Indice ultra-veloce O(1) per non filtrare mai più l'intera lista per ogni singolo canale
    private var programsByChannelMap: Map<String, List<EpgProgram>> = emptyMap()
    private var loadedCountry: String = ""

    fun getMapper(): EpgChannelMapper = mapper

    private fun normalizeEpgId(id: String): String {
        return id.substringBefore("@").replace(" ", "").lowercase()
    }

    private suspend fun ensureEpgLoaded(countryCode: String, timeZone: ZoneId) {
        val country = countryCode.lowercase()
        if (cachedPrograms.isEmpty() || loadedCountry != country) {
            val result = downloader.downloadEpg(country)
            result.getOrNull()?.let { xmlContent ->
                val parsed = parser.parseXmlTv(xmlContent, timeZone)
                cachedPrograms = parsed
                programsByChannelMap = parsed
                    .groupBy { normalizeEpgId(it.channelId) }
                    .mapValues { entry -> entry.value.sortedBy { it.startTime } }
                mapper.loadMappingsFromXml(xmlContent)
                loadedCountry = country
                Log.d(TAG, "✅ EPG [$country] indicizzato: ${cachedPrograms.size} programmi in ${programsByChannelMap.size} canali.")
            }
        }
    }

    /**
     * Usa direttamente l'ID salvato nel canale (dal nostro JSON).
     * Cerca con findBestMatch SOLO se il canale è sprovvisto di ID.
     */
    private fun resolveTargetEpgId(channel: Channel, countryCode: String, allowAutoSearch: Boolean = false): String? {
        val savedId = channel.tvgId.ifBlank { channel.customSid }
        if (savedId.isNotBlank()) {
            return savedId
        }
        if (allowAutoSearch) {
            return mapper.findBestMatch(channel, countryCode)?.siteId
        }
        return null
    }

    fun getFullScheduleForChannel(
        channel: Channel,
        countryCode: String = "IT",
        timeZone: ZoneId = ZoneId.of("Europe/Rome")
    ): List<EpgProgram> {
        val targetId = resolveTargetEpgId(channel, countryCode, allowAutoSearch = false) ?: return emptyList()
        val cleanTargetId = normalizeEpgId(targetId)
        return programsByChannelMap[cleanTargetId] ?: emptyList()
    }

    suspend fun getProgramsForChannels(
        channels: List<Channel>,
        countryCode: String = "it",
        timeZone: ZoneId = ZoneId.of("Europe/Rome")
    ): Map<String, List<EpgProgram>> = withContext(Dispatchers.Default) {
        val resultMap = mutableMapOf<String, List<EpgProgram>>()
        try {
            ensureEpgLoaded(countryCode, timeZone)
            if (programsByChannelMap.isEmpty()) return@withContext emptyMap()

            channels.forEach { channel ->
                val schedule = getFullScheduleForChannel(channel, countryCode, timeZone)
                if (schedule.isNotEmpty()) {
                    resultMap[channel.id] = schedule
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore in getProgramsForChannels", e)
        }
        resultMap
    }

    suspend fun getAllCurrentEpg(
        channels: List<Channel>,
        countryCode: String = "it",
        timeZone: ZoneId = ZoneId.of("Europe/Rome")
    ): Map<String, EpgInfo> = withContext(Dispatchers.Default) {
        val resultMap = mutableMapOf<String, EpgInfo>()
        try {
            ensureEpgLoaded(countryCode, timeZone)
            if (programsByChannelMap.isEmpty()) return@withContext emptyMap()

            val now = LocalDateTime.now(timeZone)
            val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

            channels.forEach { channel ->
                // ⚡ Usa l'ID già salvato senza rifare la ricerca lenta!
                val targetId = resolveTargetEpgId(channel, countryCode, allowAutoSearch = false)

                if (!targetId.isNullOrBlank()) {
                    val cleanTargetId = normalizeEpgId(targetId)
                    val channelProgs = programsByChannelMap[cleanTargetId] ?: emptyList()

                    if (channelProgs.isNotEmpty()) {
                        val currentIndex = channelProgs.indexOfFirst {
                            now.isAfter(it.startTime) && now.isBefore(it.endTime)
                        }
                        if (!targetId.isNullOrBlank()) {
                val cleanTargetId = normalizeEpgId(targetId)
                val channelProgs = programsByChannelMap[cleanTargetId] ?: emptyList()

                if (channelProgs.isNotEmpty()) {
                    val currentIndex = channelProgs.indexOfFirst {
                        now.isAfter(it.startTime) && now.isBefore(it.endTime)
                    }

                    if (currentIndex != -1) {
                        val prog = channelProgs[currentIndex]
                        var current = prog.title
                        if (!prog.episodeNum.isNullOrBlank()) {
                            current += " (${prog.episodeNum})"
                        }

                        // 👇 Prende SOLO il titolo del programma successivo (SENZA numero episodio)
                        val nextProg = if (currentIndex + 1 < channelProgs.size) channelProgs[currentIndex + 1] else null
                        val next = nextProg?.title ?: ""

                        // 👇 Calcola l'orario di inizio e fine per il programma successivo
                        val nextTimeRange = if (nextProg != null) {
                            "${nextProg.startTime.format(timeFormatter)} - ${nextProg.endTime.format(timeFormatter)}"
                        } else {
                            ""
                        }

                        val startEpoch = prog.startTime.atZone(timeZone).toEpochSecond()
                        val endEpoch = prog.endTime.atZone(timeZone).toEpochSecond()
                        val nowEpoch = now.atZone(timeZone).toEpochSecond()

                        val totalDuration = (endEpoch - startEpoch).toFloat()
                        val elapsed = (nowEpoch - startEpoch).toFloat()
                        val progress = if (totalDuration > 0) (elapsed / totalDuration).coerceIn(0f, 1f) else 0f
                        val timeRange = "${prog.startTime.format(timeFormatter)} - ${prog.endTime.format(timeFormatter)}"

                        // 👇 Passiamo tutti e 5 i valori, incluso nextTimeRange!
                        resultMap[channel.id] = EpgInfo(
                            currentProgram = current,
                            nextProgram = next,
                            progress = progress,
                            timeRange = timeRange,
                            nextTimeRange = nextTimeRange
                        )
                    } else {
                        val upcoming = channelProgs.firstOrNull { it.startTime.isAfter(now) }
                        val firstOrUpcoming = upcoming ?: channelProgs.firstOrNull()
                        var current = firstOrUpcoming?.title ?: ""
                        if (firstOrUpcoming != null && !firstOrUpcoming.episodeNum.isNullOrBlank()) {
                            current += " (${firstOrUpcoming.episodeNum})"
                        }
                        val timeRange = if (upcoming != null) upcoming.startTime.format(timeFormatter) else ""
                        resultMap[channel.id] = EpgInfo(current, "", 0f, timeRange, "")
                        }
                    }
                }
            }
            Log.d(TAG, "⚡ Mappa EPG istantanea generata per ${resultMap.size} canali su ${channels.size}.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore critico in getAllCurrentEpg", e)
        }
        resultMap
    }

    /**
     * Esegue l'auto-associazione EPG SOLO per i canali del portale corrente (e non per tutti i portali del DB!).
     */
    suspend fun autoAssociatePortalChannels(
        portalChannels: List<Channel>,
        countryCode: String = "IT",
        forceOverwrite: Boolean = false
    ): List<Channel> = withContext(Dispatchers.Default) {
        try {
            ensureEpgLoaded(countryCode, ZoneId.of("Europe/Rome"))
            if (portalChannels.isEmpty() || cachedPrograms.isEmpty()) return@withContext portalChannels

            var matchedCount = 0
            val updatedList = portalChannels.map { channel ->
                // Se ha già un ID (es. salvato manualmente) e non stiamo forzando, lo preserviamo!
                if (!forceOverwrite && channel.tvgId.isNotBlank()) {
                    matchedCount++
                    channel
                } else {
                    val mapping = mapper.findBestMatch(channel, countryCode)
                    if (mapping != null) {
                        matchedCount++
                        channel.copy(tvgId = mapping.siteId)
                    } else {
                        channel
                    }
                }
            }
            Log.d(TAG, "✅ [AutoAssocia Portale] Trovati $matchedCount ID EPG su ${portalChannels.size} canali.")
            updatedList
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore durante autoAssociatePortalChannels", e)
            portalChannels
        }
    }
}
