package com.portalstream.app.data.epg

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class EpgChannelMapping(
    val siteId: String, // L'ID esatto del canale nel file XML (es. "Sky.Uno.it" o l'ID di open-epg)
    val name: String    // Il nome ufficiale del canale letto dal tag <display-name>
)

class EpgChannelMapper {

    private val TAG = "EpgChannelMapper"
    private var allMappings: List<EpgChannelMapping> = emptyList()

    /**
     * Legge e indicizza tutti i canali direttamente dal file XML EPG scaricato
     * (Funziona perfettamente sia con epgshare01 che con www.open-epg.com)
     */
    fun loadMappingsFromXml(xmlString: String) {
        val mappings = mutableListOf<EpgChannelMapping>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlString))

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
            Log.d(TAG, "Mappatura dinamica completata: indicizzati ${allMappings.size} canali direttamente dall'EPG scaricato.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante il parsing dinamico dei canali dall'XML EPG", e)
        }
    }

    fun findBestMatch(channelName: String): EpgChannelMapping? {
        if (allMappings.isEmpty()) {
            Log.w(TAG, "Attenzione: allMappings è vuoto! Nessun canale EPG indicizzato dal file.")
            return null
        }

        val cleanQuery = cleanName(channelName)

        // 1. Cerca per uguaglianza perfetta del nome pulito
        allMappings.find { cleanName(it.name) == cleanQuery }?.let {
            Log.d(TAG, "Match esatto trovato: '$channelName' -> '${it.name}' (ID XML: ${it.siteId})")
            return it
        }

        // 2. Cerca per contenimento (es. il portale chiama il canale "Rai 1 HD" e l'EPG ha "Rai 1")
        val partialMatch = allMappings.find { mapping ->
            val cleanMappingName = cleanName(mapping.name)
            cleanMappingName.isNotBlank() && (cleanQuery.contains(cleanMappingName) || cleanMappingName.contains(cleanQuery))
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
        // Rimuove prefissi del tipo "IT - ", "IT | ", "ITA : ", "IT-"
        name = name.replace(Regex("^(IT|ITA|IT\\||IT\\s*[-:|])\\s*", RegexOption.IGNORE_CASE), "")
        // Rimuove la qualità alla fine
        name = name.replace(Regex("\\s*(HD|SD|UHD|FHD|4K|HbbTV|RAW)\\s*$", RegexOption.IGNORE_CASE), "")
        return name.trim().lowercase()
    }
}
