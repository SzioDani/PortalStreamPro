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

        // 1. MAPPATURA MANUALE DIRETTA PER CANALI CRITICI E ALIAS
        val manualSiteId = when {
            cleanQuery == "20 mediaset" || cleanQuery == "20" || cleanQuery == "20 hd" -> "20.it"
            cleanQuery == "tv2000" || cleanQuery == "tv 2000" -> "Tv2000.it"
            cleanQuery == "italia 1" || cleanQuery == "italia1" -> "Italia.1.it"
            cleanQuery == "italia 2" || cleanQuery == "italia2" -> "Italia.2.it"
            cleanQuery == "rai 1" || cleanQuery == "rai1" -> "Rai.1.HD..101.it"
            cleanQuery == "rai 2" || cleanQuery == "rai2" -> "Rai.2.HD..102.it"
            cleanQuery == "rai 3" || cleanQuery == "rai3" -> "Rai.3.HD..103.it"
            cleanQuery == "rai 4" || cleanQuery == "rai4" -> "Rai.4..5021.it"
            cleanQuery == "rai 5" || cleanQuery == "rai5" -> "Rai.5..5023.it"
            cleanQuery == "rai scuola" -> "Rai.Scuola..5057.it"
            cleanQuery == "twenty seven" || cleanQuery == "twentyseven" -> "27.Twentyseven.it"
            cleanQuery == "r101 tv" || cleanQuery == "r101" -> "R101tv.it"
            cleanQuery == "radioitaliatv" || cleanQuery == "radio italia tv" -> "Radio.Italia.TV.HD.it"
            else -> null
        }

        if (manualSiteId != null) {
            allMappings.find { it.siteId.equals(manualSiteId, ignoreCase = true) }?.let {
                Log.d(TAG, "Match Diretto Sicuro trovato: '$channelName' -> '${it.name}' (ID XML: ${it.siteId})")
                return it
            }
        }

        // 2. Cerca per uguaglianza perfetta del nome pulito
        allMappings.find { cleanName(it.name) == cleanQuery }?.let {
            Log.d(TAG, "Match esatto trovato: '$channelName' -> '${it.name}' (ID XML: ${it.siteId})")
            return it
        }

        // 3. Cerca per contenimento (con regole per evitare falsi positivi su Radio o canali corti)
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
            Log.w(TAG, "Nessun match trovato per il canale del portale: '$channelName' (stringa pulita: '$cleanQuery')")
        }

        return partialMatch
    }

    fun cleanName(rawName: String): String {
        var name = rawName
        // 1. Rimuove parentesi e contenuto (es. "(HbbTV)", "(1080p)")
    name = name.replace(Regex("\\(.*?\\)"), "") 
    
    // 2. Rimuove prefissi nazionali in tutti i formati possibili: "IT - ", "|IT|", "IT|", "ITA - ", "IT:" ecc.
    name = name.replace(Regex("^(\\|?IT\\|?|ITA|IT\\||IT\\s*[-:|])\\s*", RegexOption.IGNORE_CASE), "")
    
    // 3. Rimuove qualità finali (HD, SD, UHD, FHD, 4K, HbbTV, RAW, PLUS, +)
    name = name.replace(Regex("\\s*(HD|SD|UHD|FHD|4K|HbbTV|RAW|PLUS|\\+)\\s*$", RegexOption.IGNORE_CASE), "")
    
    // 4. Converte simboli come +, -, |, _ in uno spazio unico
    name = name.replace(Regex("[+|\\-_]"), " ") 
    
    // 5. Rimuove numeri ordinali o ID di canale in coda (es. "Rai Scuola 5057")
    name = name.replace(Regex("\\s+\\d+$"), "") 
        return name.trim().lowercase()
    }
}
