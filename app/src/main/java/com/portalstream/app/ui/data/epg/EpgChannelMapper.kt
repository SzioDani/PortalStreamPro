package com.portalstream.app.data.epg

import android.util.Log
import com.portalstream.app.domain.model.Channel
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class EpgChannelMapping(
    val siteId: String, 
    val name: String    
)

class EpgChannelMapper {

    private val TAG = "EpgChannelMapper"
    private var allMappings: List<EpgChannelMapping> = emptyList()

    fun loadMappingsFromXml(xmlString: String) {
        val mappings = mutableListOf<EpgChannelMapping>()
        try {
            val cleanXml = xmlString.trimStart('\uFEFF', '\u00EF', '\u00BB', '\u00BF', ' ', '\n', '\r', '\t')
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(cleanXml))

            var eventType = parser.eventType
            var currentId: String? = null
            var currentName: String? = null

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "channel") {
                            currentId = parser.getAttributeValue(null, "id")
                            currentName = null
                        } else if (parser.name == "display-name" && currentId != null) {
                            if (currentName.isNullOrBlank()) {
                                currentName = parser.nextText().trim()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "channel") {
                            if (!currentId.isNullOrBlank() && !currentName.isNullOrBlank()) {
                                mappings.add(EpgChannelMapping(currentId!!, currentName!!))
                            }
                            currentId = null
                            currentName = null
                        }
                    }
                }
                eventType = parser.next()
            }
            allMappings = mappings
            Log.d(TAG, "Mappatura dinamica completata: indicizzati ${allMappings.size} canali dall'XML EPG.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante il parsing dei canali dall'XML EPG", e)
        }
    }

    /**
     * Cerca il match ideale passando sia il canale M3U/MAC che il nome
     */
    fun findBestMatch(channel: Channel): EpgChannelMapping? {
        if (allMappings.isEmpty()) return null

        // 1. PRIORITÀ ASSOLUTA: Match diretto su tvgId / customSid dell'M3U/Stalker
        val directTvgId = channel.tvgId.ifBlank { channel.customSid }
        if (directTvgId.isNotBlank()) {
            allMappings.find { it.siteId.equals(directTvgId, ignoreCase = true) }?.let {
                Log.d(TAG, "Match Diretto per tvg-id/custom_sid trovato: '${channel.name}' -> '${it.name}' (ID XML: ${it.siteId})")
                return it
            }
        }

        // 2. SECONDA PRIORITÀ: Match su tvgName se presente
        val searchName = if (channel.tvgName.isNotBlank()) channel.tvgName else channel.name
        return findBestMatchByName(searchName)
    }

    fun findBestMatchByName(channelName: String): EpgChannelMapping? {
        if (allMappings.isEmpty()) return null

        val cleanQuery = cleanName(channelName)

        // 3. MAPPATURA MANUALE PER CANALI CRITICI (Rai, Mediaset, 20, TV2000)
        val manualSiteId = when (cleanQuery) {
            "20 mediaset", "20", "20 hd", "20mediaset" -> "20.it"
            "tv2000", "tv 2000" -> "Tv2000.it"
            "italia 1", "italia1" -> "Italia.1.it"
            "italia 2", "italia2" -> "Italia.2.it"
            "rai 1", "rai1", "rai 1 hd", "rai 1 101" -> "Rai.1.HD..101.it"
            "rai 2", "rai2", "rai 2 hd", "rai 2 102" -> "Rai.2.HD..102.it"
            "rai 3", "rai3", "rai 3 hd", "rai 3 103" -> "Rai.3.HD..103.it"
            "rai 4", "rai4", "rai 4 hd" -> "Rai.4..5021.it"
            "rai 5", "rai5", "rai 5 hd" -> "Rai.5..5023.it"
            "rai scuola" -> "Rai.Scuola..5057.it"
            "twenty seven", "twentyseven" -> "27.Twentyseven.it"
            "r101 tv", "r101" -> "R101tv.it"
            "radioitaliatv", "radio italia tv" -> "Radio.Italia.TV.HD.it"
            else -> null
        }

        if (manualSiteId != null) {
            allMappings.find { it.siteId.equals(manualSiteId, ignoreCase = true) }?.let {
                Log.d(TAG, "Match Manuale Diretto trovato: '$channelName' -> '${it.name}' (ID XML: ${it.siteId})")
                return it
            }
        }

        // 4. Uguaglianza perfetta del nome pulito
        allMappings.find { cleanName(it.name) == cleanQuery }?.let {
            Log.d(TAG, "Match esatto trovato: '$channelName' -> '${it.name}' (ID XML: ${it.siteId})")
            return it
        }

        // 5. Match parziale intelligente
        val partialMatch = allMappings.find { mapping ->
            val cleanMappingName = cleanName(mapping.name)
            if (cleanMappingName.isBlank()) false
            else if (cleanQuery.startsWith("radio") && !cleanMappingName.startsWith(cleanQuery.take(8))) false
            else (cleanQuery == cleanMappingName) ||
                 (cleanQuery.contains(cleanMappingName) && cleanMappingName.length > 3) || 
                 (cleanMappingName.contains(cleanQuery) && cleanQuery.length > 3)
        }

        if (partialMatch != null) {
            Log.d(TAG, "Match parziale trovato: '$channelName' -> '${partialMatch.name}' (ID XML: ${partialMatch.siteId})")
        } else {
            Log.w(TAG, "Nessun match trovato per il canale: '$channelName' (stringa pulita: '$cleanQuery')")
        }

        return partialMatch
    }

    fun cleanName(rawName: String): String {
        var name = rawName
        name = name.replace(Regex("\\(.*?\\)"), "") 
        name = name.replace(Regex("^(\\|?IT\\|?|ITA|IT\\||IT\\s*[-:|])\\s*", RegexOption.IGNORE_CASE), "")
        name = name.replace(Regex("\\s*(HD|SD|UHD|FHD|4K|HbbTV|RAW|PLUS|\\+)\\s*$", RegexOption.IGNORE_CASE), "")
        name = name.replace(Regex("[+|\\-_]"), " ") 
        name = name.replace(Regex("\\s+\\d+$"), "") 
        return name.trim().lowercase()
    }
}
