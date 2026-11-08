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

    // 🚀 Indici O(1) pre-calcolati una sola volta al caricamento dell'XML (Tempo 0!)
    private var bySiteIdMap: Map<String, EpgChannelMapping> = emptyMap()
    private var byCanonicalKeyMap: Map<String, EpgChannelMapping> = emptyMap()
    private var byNormalizedNameMap: Map<String, EpgChannelMapping> = emptyMap()

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
            buildFastIndexes(mappings)
            Log.d(TAG, "⚡ Mappatura caricata e indicizzata in Tempo 0: ${allMappings.size} canali XML.")
        } catch (e: Exception) {
            Log.e(TAG, "Errore durante il parsing dei canali dall'XML EPG", e)
        }
    }

    private fun buildFastIndexes(mappings: List<EpgChannelMapping>) {
        val siteMap = HashMap<String, EpgChannelMapping>(mappings.size)
        val canonicalMap = HashMap<String, EpgChannelMapping>(mappings.size)
        val normNameMap = HashMap<String, EpgChannelMapping>(mappings.size)

        for (m in mappings) {
            siteMap.putIfAbsent(m.siteId.lowercase(), m)

            val norm = ItalianEpgDictionary.normalizeName(m.name)
            if (norm.isNotEmpty()) {
                normNameMap.putIfAbsent(norm, m)
            }

            val dictEntry = ItalianEpgDictionary.findEntryForXmlChannel(m.siteId, m.name)
            if (dictEntry != null) {
                // Diamo priorità al primo ID preferito se presente
                val existing = canonicalMap[dictEntry.canonicalKey]
                if (existing == null || dictEntry.preferredXmlIds.firstOrNull()?.equals(m.siteId, ignoreCase = true) == true) {
                    canonicalMap[dictEntry.canonicalKey] = m
                }
            }
        }

        bySiteIdMap = siteMap
        byCanonicalKeyMap = canonicalMap
        byNormalizedNameMap = normNameMap
    }

    /**
     * Cerca il miglior riscontro in Tempo 0 (O(1)) senza fare cicli lenti.
     */
    fun findBestMatch(
        channel: Channel,
        countryCode: String = "IT",
        isMegaPortal: Boolean = false
    ): EpgChannelMapping? {
        if (allMappings.isEmpty()) return null

        val targetCountry = countryCode.lowercase()

        // 1. Filtro veloce Gruppi/Prefissi Italiani (scarta i 29.000 canali stranieri sui portali MAC)
        if (targetCountry == "it") {
            if (!ItalianEpgDictionary.isItalianCandidate(channel.name, channel.group, isMegaPortal)) {
                return null
            }
        }

        // 2. Controllo ID Diretto O(1) (se la lista M3U ha già un tvg-id compatibile con l'XML)
        val directTvgId = channel.tvgId.ifBlank { channel.customSid }
        if (directTvgId.isNotBlank()) {
            bySiteIdMap[directTvgId.lowercase()]?.let { return it }
        }

        // 3. Controllo nel nostro Dizionario Italiano O(1) (ItalianEpgDictionary)
        if (targetCountry == "it") {
            val entry = ItalianEpgDictionary.findEntryByChannelName(channel.name)
            if (entry != null) {
                // Cerca prima tra gli ID XML ufficiali configurati nel dizionario
                for (prefId in entry.preferredXmlIds) {
                    bySiteIdMap[prefId.lowercase()]?.let { return it }
                }
                // Altrimenti usa la chiave canonica indicizzata dall'XML
                byCanonicalKeyMap[entry.canonicalKey]?.let { return it }
            }
        }

        // 4. Controllo per nome normalizzato O(1) (per tutti gli altri canali presenti nell'XML)
        val normChannelName = ItalianEpgDictionary.normalizeName(channel.name)
        if (normChannelName.isNotEmpty()) {
            byNormalizedNameMap[normChannelName]?.let { return it }
        }

        return null
    }
}
