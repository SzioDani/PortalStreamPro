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

    /**
     * Restituisce la lista completa di tutti gli ID/Nomi indicizzati dall'XML EPG.
     * Utilizzato dall'interfaccia utente per la ricerca manuale dei canali nel dialog.
     */
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
            Log.d(TAG, "Mappatura dinamica completata: indicizzati ${allMappings.size} canali dall'XML EPG.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante il parsing dei canali dall'XML EPG", e)
        }
    }

    /**
     * Cerca il match ESCLUSIVAMENTE tramite ID diretto (tvgId o customSid).
     * L'associazione basata sui nomi è disattivata per evitare falsi positivi.
     */
    fun findBestMatch(channel: Channel): EpgChannelMapping? {
        if (allMappings.isEmpty()) return null

        // PRIORITÀ ASSOLUTA E UNICA: Match diretto su tvgId / customSid dell'M3U/Stalker
        val directTvgId = channel.tvgId.ifBlank { channel.customSid }
        if (directTvgId.isNotBlank()) {
            allMappings.find { it.siteId.equals(directTvgId, ignoreCase = true) }?.let {
                Log.d(TAG, "Match Diretto per ID trovato: '${channel.name}' -> '${it.name}' (ID XML: ${it.siteId})")
                return it
            }
        }

        // Se l'ID diretto non è presente o non trova riscontro nell'XML, restituisce null
        Log.w(TAG, "Nessun ID valido (tvgId/customSid) per il canale: '${channel.name}'")
        return null
    }
}
