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

    private var allMappings: List<EpgChannelMapping> = emptyList()

    init {
        loadMappingsFromAssets()
    }

    // Carica automaticamente il file XML dagli assets all'avvio
    private fun loadMappingsFromAssets() {
        try {
            val inputStream = context.assets.open("channels.xml")
            val xmlString = inputStream.bufferedReader().use { it.readText() }
            allMappings = parseChannelMappings(xmlString)
            Log.d("EpgChannelMapper", "Caricate ${allMappings.size} mappature di canali italiani.")
        } catch (e: Exception) {
            Log.e("EpgChannelMapper", "Impossibile caricare channels.xml dagli assets", e)
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

                    // Filtriamo rigorosamente per la lingua italiana
                    if (lang.lowercase() == "it" && name.isNotBlank()) {
                        mappings.add(EpgChannelMapping(site, siteId, lang, xmltvId, name))
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e("EpgChannelMapper", "Errore nel parsing XML", e)
        }
        return mappings
    }

    /**
     * Trova la corrispondenza esatta o parziale tra il canale del portale 
     * e la lista sterminata di iptv-org.
     */
    fun findBestMatch(channelName: String): EpgChannelMapping? {
        if (allMappings.isEmpty()) return null

        val cleanQuery = sanitizeChannelName(channelName)

        // 1. Cerca per uguaglianza perfetta del nome pulito
        allMappings.find { sanitizeChannelName(it.name) == cleanQuery }?.let { return it }

        // 2. Cerca se il nome del portale contiene il nome ufficiale o viceversa
        return allMappings.find { mapping ->
            val cleanMappingName = sanitizeChannelName(mapping.name)
            cleanMappingName.isNotBlank() && (cleanQuery.contains(cleanMappingName) || cleanMappingName.contains(cleanQuery))
        }
    }

    private fun sanitizeChannelName(name: String): String {
        return name.lowercase()
            .replace(Regex("(hd|fhd|4k|hevc|ita|freetair|\\+|[\\(\\[\\-\\]\\)])"), "")
            .replace(Regex("\\s+"), "")
    }
}
