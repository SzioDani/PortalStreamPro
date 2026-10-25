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
     * Metodo di controllo diagnostico: stampa log puliti per capire cosa legge l'M3U vs XML
     */
    fun findBestMatch(channel: Channel): EpgChannelMapping? {
        if (allMappings.isEmpty()) {
            Log.w(TAG, "⚠️ [DIAG] Nessuna mappatura XML disponibile.")
            return null
        }

        // 1. Controllo ID Diretto
        val directTvgId = channel.tvgId.ifBlank { channel.customSid }
        if (directTvgId.isNotBlank()) {
            val match = allMappings.find { it.siteId.equals(directTvgId, ignoreCase = true) }
            if (match != null) {
                Log.d(TAG, "✅ [ID Diretto] OK: '${channel.name}' -> ${match.siteId}")
                return match
            } else {
                Log.w(TAG, "❌ [ID Diretto] FALLITO: Il canale '${channel.name}' ha ID '${directTvgId}' ma non esiste nell'XML.")
            }
        } else {
            // Se non ha un ID, loggiamo in modo pulito il nome del canale M3U che sta fallendo l'associazione
            Log.w(TAG, "🔍 [Senza ID] Canale M3U senza tvg-id: '${channel.name}' (Gruppo: '${channel.group}')")
            
            // Verifichiamo se per caso esiste un canale nell'XML che ha lo stesso identico nome (senza filtri)
            val exactNameMatch = allMappings.find { it.name.equals(channel.name, ignoreCase = true) }
            if (exactNameMatch != null) {
                Log.i(TAG, "💡 [Suggerimento] Trovato match esatto per nome nell'XML! '${channel.name}' -> SiteId: ${exactNameMatch.siteId}")
            }
        }

        return null
    }
}
