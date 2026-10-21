package com.portalstream.app.data.repository

import android.content.Context
import com.portalstream.app.data.epg.EpgDownloader
import com.portalstream.app.data.epg.EpgParser
import com.portalstream.app.data.epg.EpgProgram
import com.portalstream.app.data.epg.EpgChannelMapper
import com.portalstream.app.data.epg.EpgChannelMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

class EpgRepository(private val context: Context) {

    private val downloader = EpgDownloader(context)
    private val parser = EpgParser()
    private val mapper = EpgChannelMapper(context)

    private var cachedPrograms: List<EpgProgram> = emptyList()
    private var mappings: List<EpgChannelMapping> = emptyList()

    /**
     * Inizializza la lista delle mappature italiane.
     * Puoi passare l'XML contenente i canali italiani.
     */
    fun loadMappings(xmlMappingsContent: String) {
        mappings = mapper.parseChannelMappings(xmlMappingsContent)
    }

    /**
     * Recupera la guida TV per un canale del portale partendo dal suo nome visibile.
     */
    suspend fun getEpgForChannelByName(
        channelName: String,
        countryCode: String = "it",
        timeZone: ZoneId = ZoneId.of("Europe/Rome")
    ): List<EpgProgram> = withContext(Dispatchers.Default) {
        // 1. Trova il matching con l'ID di iptv-org
        val matchedChannel = mapper.findBestMatch(channelName, mappings)
        
        // Determina l'ID da cercare nel file XMLTV (usa xmltvId o siteId)
        val targetId = when {
            matchedChannel != null && matchedChannel.xmltvId.isNotBlank() -> matchedChannel.xmltvId
            matchedChannel != null && matchedChannel.siteId.isNotBlank() -> matchedChannel.siteId
            else -> channelName
        }

        // 2. Scarica e leggi la guida se la cache non è ancora popolata
        if (cachedPrograms.isEmpty()) {
            val result = downloader.downloadEpg(countryCode)
            result.getOrNull()?.let { xmlContent ->
                cachedPrograms = parser.parseXmlTv(xmlContent, timeZone)
            }
        }

        // 3. Filtra la programmazione per l'ID trovato
        cachedPrograms.filter { it.channelId.equals(targetId, ignoreCase = true) }
            .sortedBy { it.startTime }
    }
}
