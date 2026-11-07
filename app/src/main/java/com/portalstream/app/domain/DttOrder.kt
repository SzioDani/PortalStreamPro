package com.portalstream.app.domain

/**
 * Ordine dei canali come sul digitale terrestre italiano.
 * L'ordine è dato dalla POSIZIONE nella lista: per cambiare l'ordine basta spostare le righe.
 * Ogni riga contiene i nomi alternativi con cui lo stesso canale può comparire nelle playlist.
 */
object DttOrder {

    private val order: List<List<String>> = listOf(
        listOf("rai 1", "rai uno"),                          // 1
        listOf("rai 2", "rai due"),                          // 2
        listOf("rai 3", "rai tre"),                          // 3
        listOf("rete 4"),                                    // 4
        listOf("canale 5"),                                  // 5
        listOf("italia 1"),                                  // 6
        listOf("la7", "la 7"),                               // 7
        listOf("tv8", "tv 8"),                               // 8
        listOf("nove", "9"),                                 // 9
        listOf("20", "20 mediaset", "mediaset 20"),          // 20
        listOf("rai 4"),
        listOf("iris"),
        listOf("rai 5"),
        listOf("rai movie"),
        listOf("rai premium"),
        listOf("cielo", "cielo TV", "Cielo Tv", "Cielo TV"),
        listOf("twenty seven", "27 twenty seven", "27", "27 Twenty Seven", "27 TwentySeven", "Twentyseven"),
        listOf("tv2000", "tv 2000"),
        listOf("la7d", "la7 d"),
        listOf("la5", "la 5"),
        listOf("real time"),
        listOf("qvc"),
        listOf("food network"),
        listOf("cine34", "cine 34"),
        listOf("focus"),
        listOf("rtl 102 5", "rtl 102.5"),
        listOf("warner tv"),
        listOf("giallo"),
        listOf("top crime", "topcrime"),
        listOf("boing"),
        listOf("k2"),
        listOf("rai gulp"),
        listOf("rai yoyo"),
        listOf("frisbee"),
        listOf("cartoonito"),
        listOf("super"),
        listOf("rai news 24", "rai news"),
        listOf("rai storia"),
        listOf("mediaset italia 2", "italia 2"),
        listOf("rai sport"),
        listOf("rai scuola"),
        listOf("sky tg24", "sky tg 24"),
        listOf("tgcom24", "tgcom 24"),
        listOf("dmax", "d max", "DMAX", "DMax", "Dmax"),
        listOf("Hgtv-Home & Garden", "HGTV-Home & Garden"),
        listOf("Motor Trend", "MOTOR TREND", "motor trend", "Turbo", "turbo", "TURBO")
        
        // 👆 Aggiungi/sposta qui i canali per raggiungere l'ordine che vuoi
    )

    private val noiseWords = setOf(
        "hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "h264", "fullhd", "raw"
    )

    private val countryPrefix =
        Regex("""^\s*[\[(|]?\s*(it|ita|italia|italy)\s*[\])|:\-]+\s*""", RegexOption.IGNORE_CASE)
    private val bracketContent = Regex("""[\[(].*?[\])]""")
    private val nonAlnum = Regex("""[^a-z0-9]+""")

    /** "|IT| Rai 1 HD [FHD]" -> "rai 1" */
    fun normalize(raw: String): String {
        var s = raw.lowercase()
        s = countryPrefix.replace(s, "")
        s = bracketContent.replace(s, " ")
        s = nonAlnum.replace(s, " ")
        return s.trim()
            .split(" ")
            .filter { it.isNotEmpty() && it !in noiseWords }
            .joinToString(" ")
    }

    private val rankByName: Map<String, Int> = buildMap {
        order.forEachIndexed { index, aliases ->
            aliases.forEach { alias -> putIfAbsent(normalize(alias), index) }
        }
    }

    /** Posizione DTT del canale, oppure null se non è nella tabella. */
    fun rankOf(channelName: String): Int? = rankByName[normalize(channelName)]
}
