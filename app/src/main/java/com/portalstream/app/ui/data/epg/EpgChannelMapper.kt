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

    fun getAllMappings(): List<EpgChannelMapping> = allMappings

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
            Log.d(TAG, "Mappatura caricata: indicizzati ${allMappings.size} canali dall'XML EPG.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante il parsing dei canali dall'XML EPG", e)
        }
    }

    /**
     * Cerca il miglior riscontro filtrando rigorosamente per nazionalità a monte.
     */
    fun findBestMatch(channel: Channel, countryCode: String = "IT"): EpgChannelMapping? {
        if (allMappings.isEmpty()) {
            Log.w(TAG, "⚠️ [DIAG] Nessuna mappatura XML disponibile per '${channel.name}'.")
            return null
        }

        val targetCountry = countryCode.lowercase()
        Log.d(TAG, "🌍 [Filtro Nazione] Applicazione filtro per '$targetCountry' sul canale '${channel.name}' (Gruppo: '${channel.group}')")

        // 1. Filtriamo a monte i mapping EPG mantenendo SOLO quelli della nazione scelta
        val nationalMappings = allMappings.filter { mapping ->
            val idLower = mapping.siteId.lowercase()
            idLower.contains(".$targetCountry") || 
            idLower.contains("_${targetCountry}") || 
            idLower.startsWith("$targetCountry") ||
            idLower.contains("/$targetCountry")
        }

        Log.d(TAG, "📊 Trovati ${nationalMappings.size} canali EPG per la nazione [$countryCode] su un totale di ${allMappings.size}")

        // 2. Controllo ID Diretto tra quelli filtrati per nazione
        val directTvgId = channel.tvgId.ifBlank { channel.customSid }
        if (directTvgId.isNotBlank()) {
            val match = nationalMappings.find { it.siteId.equals(directTvgId, ignoreCase = true) }
            if (match != null) {
                Log.d(TAG, "✅ [ID Diretto - Nazione $countryCode] OK: '${channel.name}' -> ${match.siteId}")
                return match
            } else {
                Log.w(TAG, "❌ [ID Diretto] L'ID '$directTvgId' del canale '${channel.name}' non rientra tra i canali della nazione [$countryCode].")
            }
        }

        // 3. Ricerca per nome ESCLUSIVAMENTE tra i canali della nazione selezionata
        val cleanChannelName = channel.name
            .replace(Regex("(?i)\\b(HD|FHD|HEVC|4K|ITA|IT)\\b"), "")
            .replace(Regex("[^a-zA-Z0-9]"), "")
            .lowercase()

        Log.v(TAG, "🔍 Ricerca nome pulito '${cleanChannelName}' tra i canali [$countryCode]...")

        val nameMatch = nationalMappings.find { mapping ->
            val cleanMapName = mapping.name
                .replace(Regex("[^a-zA-Z0-9]"), "")
                .lowercase()
            cleanMapName == cleanChannelName
        }

        if (nameMatch != null) {
            Log.d(TAG, "🎯 [Match per Nome - Nazione $countryCode] Trovato: '${channel.name}' -> SiteId: ${nameMatch.siteId} (Nome XML: '${nameMatch.name}')")
            return nameMatch
        } else {
            Log.w(TAG, "⚪ [Nessun Match] Impossibile trovare una corrispondenza per '${channel.name}' nella nazione [$countryCode]")
        }

        return null
    }
}
