package com.portalstream.app.data.epg

import android.util.Log
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
            Log.e(TAG, "Errore durante il parsing dinamico dei canali dall'XML EPG", e)
        }
    }

    fun findBestMatch(channelName: String): EpgChannelMapping? {
        if (allMappings.isEmpty()) return null

        val cleanQuery = cleanName(channelName)

        // 1. Mappatura manuale diretta per i casi limite noti
        val manualAlias = when (cleanQuery) {
            "twenty seven", "twentyseven" -> "27"
            "tv2000", "tv 2000" -> "tv2000"
            "rai sport +", "rai sport plus" -> "rai sport"
            "rai 4k" -> "rai 4k"
            "rai scuola" -> "rai scuola"
            "donna tv" -> "donna tv"
            "alma tv" -> "alma tv"
            "r101 tv", "r101" -> "r101"
            "radioitaliatv", "radio italia tv" -> "radio italia"
            else -> null
        }

        if (manualAlias != null) {
            allMappings.find { cleanName(it.name).contains(manualAlias) }?.let {
                Log.d(TAG, "Match Alias trovato: '$channelName' -> '${it.name}' (ID XML: ${it.siteId})")
                return it
            }
        }

        // 2. Cerca per uguaglianza perfetta del nome pulito
        allMappings.find { cleanName(it.name) == cleanQuery }?.let {
            Log.d(TAG, "Match esatto trovato: '$channelName' -> '${it.name}' (ID XML: ${it.siteId})")
            return it
        }

        // 3. Cerca per contenimento (con filtro sulla lunghezza minima per evitare TV2000 -> 20)
        val partialMatch = allMappings.find { mapping ->
            val cleanMappingName = cleanName(mapping.name)
            if (cleanMappingName.isBlank()) false
            else if (cleanQuery == "tv2000" && cleanMappingName == "20") false // Ignora falso positivo TV2000
            else (cleanQuery.contains(cleanMappingName) && cleanMappingName.length > 2) || 
                 (cleanMappingName.contains(cleanQuery) && cleanQuery.length > 2)
        }

        if (partialMatch != null) {
            Log.d(TAG, "Match parziale trovato: '$channelName' -> '${partialMatch.name}' (ID XML: ${partialMatch.siteId})")
        } else {
            Log.w(TAG, "Nessun match trovato per il canale del portale: '$channelName' (stringa pulita: '$cleanQuery')")
        }

        return partialMatch
    }

    fun cleanName(rawName: String): String {
        var name = rawName
        // Rimuove la qualità, le parentesi tipo (HbbTV) o (1080p) e simboli inutili
        name = name.replace(Regex("\\(.*?\\)"), "") // Rimuove tutto ciò che sta tra parentesi
        name = name.replace(Regex("^(IT|ITA|IT\\||IT\\s*[-:|])\\s*", RegexOption.IGNORE_CASE), "")
        name = name.replace(Regex("\\s*(HD|SD|UHD|FHD|4K|HbbTV|RAW|PLUS|\\+)\\s*$", RegexOption.IGNORE_CASE), "")
        name = name.replace(Regex("[+|\\-_]"), " ") // Sostituisce +, |, - con uno spazio
        
        // Rimuove eventuali numeri di canale sky/digitale terrestre lasciati in fondo dal provider (es "Rai Scuola 5057" -> "Rai Scuola")
        name = name.replace(Regex("\\s+\\d+$"), "") 
        
        return name.trim().lowercase()
    }
}
