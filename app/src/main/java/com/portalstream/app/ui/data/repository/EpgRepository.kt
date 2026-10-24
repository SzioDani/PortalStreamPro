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
    private val mapper = EpgChannelMapper()

    private var cachedPrograms: List<EpgProgram> = emptyList()

    /**
     * 🚀 NUOVO METODO: Espone il mapper per consentire la ricerca manuale degli ID nel dialog.
     */
    fun getMapper(): EpgChannelMapper {
        return mapper
    }

    /**
     * Restituisce l'elenco completo dei programmi EPG della giornata per un canale
     */
    fun getFullScheduleForChannel(
        channel: Channel,
        timeZone: ZoneId = ZoneId.of("Europe/Rome")
    ): List<EpgProgram> {
        val mapping = mapper.findBestMatch(channel)
        val targetId = mapping?.siteId ?: channel.tvgId.ifBlank { return emptyList() }

        val cleanTargetId = targetId
            .substringBefore("@")
            .replace(" ", "")
            .lowercase()

        return cachedPrograms.filter { prog ->
            val cleanProgId = prog.channelId
                .substringBefore("@")
                .replace(" ", "")
                .lowercase()
            cleanProgId == cleanTargetId
        }.sortedBy { it.startTime }
    }

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
                    // 1. Parsa i programmi TV passando correttamente il fuso orario
                    cachedPrograms = parser.parseXmlTv(xmlContent, timeZone)
                    
                    // 2. Inizializza i canali ed effettua il mapping direttamente dall'XML scaricato
                    mapper.loadMappingsFromXml(xmlContent)
                    
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
                val mapping = mapper.findBestMatch(channel)
                val targetId = mapping?.siteId

                if (targetId != null) {
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

    /**
     * Esegue l'auto-associazione EPG di massa per tutti i canali presenti nel DB
     */
    suspend fun autoAssociateAllChannels(
        channelDao: com.portalstream.app.data.local.ChannelDao, 
        countryCode: String = "it"
    ): Int = withContext(Dispatchers.Default) {
        var matchedCount = 0
        try {
            if (cachedPrograms.isEmpty()) {
                val result = downloader.downloadEpg(countryCode)
                result.getOrNull()?.let { xmlContent ->
                    cachedPrograms = parser.parseXmlTv(xmlContent, ZoneId.of("Europe/Rome"))
                    mapper.loadMappingsFromXml(xmlContent)
                }
            }

            val channels = channelDao.getAllChannelsList()
            if (channels.isNotEmpty() && cachedPrograms.isNotEmpty()) {
                val updatedList = channels.map { channel ->
                    val mapping = mapper.findBestMatch(channel)
                    if (mapping != null) {
                        matchedCount++
                        channel.copy(tvgId = mapping.siteId)
                    } else {
                        channel
                    }
                }
                channelDao.insertChannels(updatedList)
                Log.d(TAG, "Auto-associazione completata: aggiornati $matchedCount canali.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante l'auto-associazione EPG massiva", e)
        }
        matchedCount
    }
}
