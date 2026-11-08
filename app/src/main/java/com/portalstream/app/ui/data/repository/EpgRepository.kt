package com.portalstream.app.data.repository

import android.content.Context
import android.util.Log
import com.portalstream.app.data.epg.EpgChannelMapper
import com.portalstream.app.data.epg.EpgDownloader
import com.portalstream.app.data.epg.EpgMappingStore
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
    private val mappingStore = EpgMappingStore(context)

    private var cachedPrograms: List<EpgProgram> = emptyList()
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

    private fun resolveTargetEpgId(
        channel: Channel,
        countryCode: String,
        allowAutoSearch: Boolean = true,
        isMegaPortal: Boolean = false
    ): String? {
        val savedId = channel.tvgId.ifBlank { channel.customSid }
        if (savedId.isNotBlank()) {
            val cleanSaved = normalizeEpgId(savedId)
            if (programsByChannelMap.containsKey(cleanSaved)) {
                return savedId
            }
        }
        if (allowAutoSearch) {
            return mapper.findBestMatch(channel, countryCode, isMegaPortal)?.siteId
        }
        return savedId.takeIf { it.isNotBlank() }
    }

    fun getFullScheduleForChannel(
        channel: Channel,
        countryCode: String = "IT",
        timeZone: ZoneId = ZoneId.of("Europe/Rome")
    ): List<EpgProgram> {
        val targetId = resolveTargetEpgId(channel, countryCode, allowAutoSearch = true) ?: return emptyList()
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
            val isMegaPortal = channels.size > 500

            channels.forEach { channel ->
                val targetId = resolveTargetEpgId(channel, countryCode, allowAutoSearch = true, isMegaPortal = isMegaPortal)

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

                            val nextProg = if (currentIndex + 1 < channelProgs.size) channelProgs[currentIndex + 1] else null
                            val next = nextProg?.title ?: ""

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
     * Esegue l'auto-associazione EPG in 4 Passi a Tempo 0:
     * 1) ID valido già presente -> 2) Memoria storica rawName nel JSON -> 3) Filtro Gruppi + Dizionario -> 4) XML
     */
    suspend fun autoAssociatePortalChannels(
        portalChannels: List<Channel>,
        countryCode: String = "IT",
        forceOverwrite: Boolean = false
    ): List<Channel> = withContext(Dispatchers.Default) {
        try {
            ensureEpgLoaded(countryCode, ZoneId.of("Europe/Rome"))
            if (portalChannels.isEmpty() || cachedPrograms.isEmpty()) return@withContext portalChannels

            val isMegaPortal = portalChannels.size > 500
            val knownRawNames = mappingStore.getKnownRawNameMappings()
            var matchedCount = 0

            val updatedList = portalChannels.map { channel ->
                val existingId = channel.tvgId.ifBlank { channel.customSid }
                val hasValidXmlId = existingId.isNotBlank() &&
                    programsByChannelMap.containsKey(normalizeEpgId(existingId))

                // PASSO 1: Ha già un ID valido nel nostro XML
                if (!forceOverwrite && hasValidXmlId) {
                    matchedCount++
                    return@map channel
                }

                // PASSO 2: Memoria storica rawName nel JSON (es. stesso Server con nuovo MAC)
                val rememberedId = knownRawNames[channel.name.trim()]
                if (!forceOverwrite && !rememberedId.isNullOrBlank() &&
                    programsByChannelMap.containsKey(normalizeEpgId(rememberedId))
                ) {
                    matchedCount++
                    return@map channel.copy(tvgId = rememberedId)
                }

                // PASSO 3 & 4: Filtro Gruppi Italiani + ItalianEpgDictionary + Mappa XML (Tutto in O(1)!)
                val mapping = mapper.findBestMatch(channel, countryCode, isMegaPortal)
                if (mapping != null) {
                    matchedCount++
                    channel.copy(tvgId = mapping.siteId)
                } else {
                    channel
                }
            }
            Log.d(TAG, "✅ [AutoAssocia Portale] Trovati $matchedCount ID EPG su ${portalChannels.size} canali (MegaPortal=$isMegaPortal).")
            updatedList
        } catch (e: Exception) {
            Log.e(TAG, "❌ Errore durante autoAssociatePortalChannels", e)
            portalChannels
        }
    }
}
