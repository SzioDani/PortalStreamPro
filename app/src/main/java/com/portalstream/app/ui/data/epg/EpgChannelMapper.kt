package com.portalstream.app.data.epg

import android.content.Context
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class EpgChannelMapping(
    val site: String,
    val siteId: String,
    val lang: String,
    val xmltvId: String,
    val name: String
)

class EpgChannelMapper(private val context: Context) {

    private val TAG = "EpgChannelMapper"
    private var allMappings: List<EpgChannelMapping> = emptyList()

    init {
        loadMappingsFromAssets()
    }

    private fun loadMappingsFromAssets() {
        try {
            val inputStream = context.assets.open("channels.xml")
            val xmlString = inputStream.bufferedReader().use { it.readText() }
            allMappings = parseChannelMappings(xmlString)
            Log.d(TAG, "Caricate ${allMappings.size} mappature di canali da channels.xml negli assets.")
        } catch (e: Exception) {
            Log.e(TAG, "ERRORE CRITICO: Impossibile aprire channels.xml negli assets!", e)
        }
    }

    private fun parseChannelMappings(xmlString: String): List<EpgChannelMapping> {
        val mappings = mutableListOf<EpgChannelMapping>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xmlString))

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "channel") {
                    val site = parser.getAttributeValue(null, "site") ?: ""
                    val siteId = parser.getAttributeValue(null, "site_id") ?: ""
                    val lang = parser.getAttributeValue(null, "lang") ?: ""
                    val xmltvId = parser.getAttributeValue(null, "xmltv_id") ?: ""
                    val name = parser.nextText().trim()

                    if (lang.lowercase() == "it" && name.isNotBlank()) {
                        mappings.add(EpgChannelMapping(site, siteId, lang, xmltvId, name))
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Errore parsing XML channels.xml", e)
        }
        return mappings
    }

    fun findBestMatch(channelName: String): EpgChannelMapping? {
        if (allMappings.isEmpty()) {
            Log.w(TAG, "Attenzione: allMappings è vuoto! channels.xml non è stato letto correttamente.")
            return null
        }

        val cleanQuery = sanitizeChannelName(channelName)

        // 1. Cerca per uguaglianza perfetta
        allMappings.find { sanitizeChannelName(it.name) == cleanQuery }?.let {
            Log.d(TAG, "Match esatto trovato: '$channelName' -> '${it.name}' (ID: ${it.xmltvId.ifBlank { it.siteId }})")
            return it
        }

        // 2. Cerca per contenimento
        val partialMatch = allMappings.find { mapping ->
            val cleanMappingName = sanitizeChannelName(mapping.name)
            cleanMappingName.isNotBlank() && (cleanQuery.contains(cleanMappingName) || cleanMappingName.contains(cleanQuery))
        }

        if (partialMatch != null) {
            Log.d(TAG, "Match parziale trovato: '$channelName' -> '${partialMatch.name}'")
        } else {
            Log.w(TAG, "Nessun match trovato per il canale del portale: '$channelName' (stringa pulita: '$cleanQuery')")
        }

        return partialMatch
    }

    private fun sanitizeChannelName(name: String): String {
        return name.lowercase()
            .replace(Regex("(hd|fhd|4k|hevc|ita|freetair|\\+|[\\(\\[\\-\\]\\)])"), "")
            .replace(Regex("\\s+"), "")
    }
}
