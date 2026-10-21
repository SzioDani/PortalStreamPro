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

    /**
     * Legge una stringa XML contenente i canali e filtra SOLO quelli in italiano (lang="it").
     */
    fun parseChannelMappings(xmlString: String): List<EpgChannelMapping> {
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

                    // Consideriamo solo la lingua italiana
                    if (lang.lowercase() == "it") {
                        mappings.add(EpgChannelMapping(site, siteId, lang, xmltvId, name))
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e("EpgChannelMapper", "Errore nel parsing dell'XML di mappatura", e)
        }
        return mappings
    }

    /**
     * Pulisce il nome del canale del portale (rimuove HD, 4K, HEVC, spazi e simboli)
     * e trova la migliore corrispondenza nella lista di iptv-org.
     */
    fun findBestMatch(channelName: String, mappings: List<EpgChannelMapping>): EpgChannelMapping? {
        val cleanQuery = sanitizeChannelName(channelName)

        // 1. Corrispondenza esatta sul nome sanificato
        mappings.find { mapping ->
            sanitizeChannelName(mapping.name) == cleanQuery
        }?.let { return it }

        // 2. Corrispondenza parziale (es. "RAI 1 FHD" -> "rai1")
        return mappings.find { mapping ->
            val cleanMappingName = sanitizeChannelName(mapping.name)
            cleanQuery.contains(cleanMappingName) || cleanMappingName.contains(cleanQuery)
        }
    }

    private fun sanitizeChannelName(name: String): String {
        return name.lowercase()
            .replace(Regex("(hd|fhd|4k|hevc|ita|freetair|\\+|[\\(\\[\\-\\]\\)])"), "")
            .replace(Regex("\\s+"), "")
    }
}
