package com.portalstream.app.domain

/**
 * Ordine dei canali come sul digitale terrestre italiano.
 * L'ordine è dato dalla POSIZIONE nella lista: per cambiare l'ordine basta spostare le righe.
 * Ogni riga contiene i nomi alternativi con cui lo stesso canale può comparire nelle playlist.
 */
object DttOrder {

    private val order: List<List<String>> = listOf(
        listOf("rai 1", "rai uno", "Rai 1", "Rai Uno", "Rai1"),                          // 1
        listOf("rai 2", "rai due", "Rai 2", "Rai Due", "Rai2"),                          // 2
        listOf("rai 3", "rai tre", "Rai 3", "Rai Tre", "Rai3"),                          // 3
        listOf("rete 4", "Rete 4","Rete4"),                                    // 4
        listOf("canale 5", "Canale 5"),                                  // 5
        listOf("italia 1", "Italia 1"),                                  // 6
        listOf("la7", "la 7" "La7", "La 7"),                               // 7
        listOf("tv8", "tv 8", "Tv8", "Tv 8"),                               // 8
        listOf("nove", "Nove"),                                 // 9
        listOf("20", "20 mediaset", "mediaset 20"),          // 20
        listOf("rai 4", "Rai4", "Rai 4"),
        listOf("iris", "Iris"),
        listOf("rai 5", "Rai5", "Rai 5"),
        listOf("rai movie", "RaiMovie", "Rai Movie"),
        listOf("rai premium", "RaiPremium", "Rai Premium"),
        listOf("cielo", "cielo TV", "Cielo Tv", "Cielo TV"),
        listOf("twenty seven", "27 twenty seven", "27", "27 Twenty Seven", "27 TwentySeven", "Twentyseven"),
        listOf("tv2000", "tv 2000", "TV2000", "TV 2000"),
        listOf("la7d", "la7 d", "LA7 cinema", "La7 Cinema", "La7cinema"),
        listOf("la5", "la 5", "La 5", "La5", "LA5", "LA 5"),
        listOf("real time", "Real Time", "RealTime", "Realtime", "REALTIME", "REAL TIME"),
        listOf("qvc", "Qvc", "QVC"),
        listOf("food network", "Food Network", "Food network"),
        listOf("cine34", "cine 34", "Cine34", "Cine 34", "CINE 34", "CINE34"),
        listOf("focus", "Focus", "focus tv", "FOCUS", "FOCUS TV"),
        listOf("rtl 102 5", "rtl 102.5"),
        listOf("warner tv","Warner Tv", "WARNER TV"),
        listOf("giallo", "Giallo", "GIALLO"),
        listOf("top crime", "topcrime"),
        listOf("boing", "Boing"),
        listOf("k2", "K2"),
        listOf("rai gulp", "Rai Gulp", "Rai gulp"),
        listOf("rai yoyo", "Rai Yoyo", " Rai yoyo"),
        listOf("frisbee", "Frisbee"),
        listOf("cartoonito", "Cartoonito"),
        listOf("super", "Super"),
        listOf("rai news 24", "rai news", "Rai News", "Rai news"),
        listOf("rai storia", "Rai Storia", " Rai storia"),
        listOf("mediaset italia 2", "italia 2"),
        listOf("rai sport", "Rai Sport", "Rai sport"),
        listOf("rai scuola", "Rai Scuola", "Rai scuola"),
        listOf("sky tg24", "sky tg 24", "Sky Tg24", "Sky tg24"),
        listOf("tgcom24", "tgcom 24", "TgCom", " Tgcom"),
        listOf("dmax", "d max", "DMAX", "DMax", "Dmax"),
        listOf("Hgtv-Home & Garden", "HGTV-Home & Garden", "HGTV – Home & Garden Tv"),
        listOf("Motor Trend", "MOTOR TREND", "motor trend", "Turbo", "turbo", "TURBO"),
        listOf("Company Tv", "COMPANY TV", "Company TV", "Company tv"),
        listOf("Deejay TV", "DEEJAY TV", "Deejay Tv", "Deejay tv"),
        listOf("R101", "r101"),
        listOf("Radio 105", "radio 105", "Radio 105 Tv", "Radio 105 TV")
    )
    

    private val noiseWords = setOf(
        "hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "h264", "fullhd", "raw"
    )

    private val countryPrefix =
        Regex("""^\s*[\[(|]?\s*(it|ita|italia|italy)\s*[\])|:\-]+\s*""", RegexOption.IGNORE_CASE)

    // Regex per intercettare tag di paesi esteri (es. (CH), [ES], (SE), (TR), ecc.)
    private val foreignCountryRegex = Regex("""[\[(]\s*(ch|es|se|tr|fr|de|uk|us|al|ro)\s*[\])]""", RegexOption.IGNORE_CASE)
    private val italianTagRegex = Regex("""[\[(]\s*(it|ita|italia|italy)\s*[\])]""", RegexOption.IGNORE_CASE)

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

    /** Posizione DTT del canale, oppure null se non è nella tabella o se è un canale straniero. */
    fun rankOf(channelName: String): Int? {
        // Se il canale contiene un tag straniero e non specifica [IT], viene escluso dal ranking DTT
        if (foreignCountryRegex.containsMatchIn(channelName) && !italianTagRegex.containsMatchIn(channelName)) {
            return null
        }
        return rankByName[normalize(channelName)]
    }
}
