package com.portalstream.app.data.epg

/**
 * Dizionario Universale per l'associazione EPG Italiana su QUALSIASI portale (M3U, Xtream, MAC/Stalker).
 * Distingue automaticamente tra marchi esclusivi italiani (es. Rai, Mediaset, Sky Calcio)
 * e marchi internazionali (es. Focus, Eurosport, DAZN, Cartoonito) per evitare falsi positivi sui portali mondiali.
 */
object ItalianEpgDictionary {

    data class EpgEntry(
        val canonicalKey: String,
        val preferredXmlIds: List<String>,
        val aliases: List<String>,
        val exclusiveItalian: Boolean = true // true = nome che esiste SOLO in Italia; false = esiste anche all'estero
    )

    // Rinominato in dictionaryEntries per non andare in conflitto con Map.entries di Kotlin
    private val dictionaryEntries: List<EpgEntry> = listOf(
        // --- NAZIONALI & DTT ---
        EpgEntry("rai 1", listOf("Rai1.it", "Rai.1.it"), listOf("rai 1", "rai uno", "rai1", "raiuno"), exclusiveItalian = true),
        EpgEntry("rai 2", listOf("Rai2.it", "Rai.2.it"), listOf("rai 2", "rai due", "rai2", "raidue"), exclusiveItalian = true),
        EpgEntry("rai 3", listOf("Rai3.it", "Rai.3.it"), listOf("rai 3", "rai tre", "rai3", "raitre"), exclusiveItalian = true),
        EpgEntry("rete 4", listOf("Rete.4.it", "Rete4.it"), listOf("rete 4", "rete quattro", "rete4", "retequattro"), exclusiveItalian = true),
        EpgEntry("canale 5", listOf("Canale.5.it", "Canale5.it"), listOf("canale 5", "canale cinque", "canale5", "canalecinque"), exclusiveItalian = true),
        EpgEntry("italia 1", listOf("Italia.1.it", "Italia1.it"), listOf("italia 1", "italia uno", "italia1", "italiauno"), exclusiveItalian = true),
        EpgEntry("la7", listOf("LA7.HD.it", "LA7.it", "La7.it"), listOf("la7", "la 7"), exclusiveItalian = true),
        EpgEntry("tv8", listOf("TV8.HD.it", "TV8.it"), listOf("tv8", "tv 8", "tv8 italia"), exclusiveItalian = false),
        EpgEntry("nove", listOf("Nove.it", "NOVE.HD.it"), listOf("nove", "canale 9", "nove tv", "9"), exclusiveItalian = false),
        EpgEntry("20 mediaset", listOf("20.it", "20.Mediaset.HD.it", "20Mediaset.it"), listOf("20 mediaset", "mediaset 20", "canale 20", "20"), exclusiveItalian = false),
        EpgEntry("rai 4", listOf("Rai4.it", "Rai.4.it"), listOf("rai 4", "rai quattro", "rai4"), exclusiveItalian = true),
        EpgEntry("iris", listOf("Iris.it"), listOf("iris", "mediaset iris"), exclusiveItalian = false),
        EpgEntry("rai 5", listOf("Rai5.it", "Rai.5.it"), listOf("rai 5", "rai cinque", "rai5"), exclusiveItalian = true),
        EpgEntry("rai movie", listOf("RaiMovie.it", "Rai.Movie.it"), listOf("rai movie", "raimovie"), exclusiveItalian = true),
        EpgEntry("rai premium", listOf("RaiPremium.it", "Rai.Premium.it"), listOf("rai premium", "raipremium"), exclusiveItalian = true),
        EpgEntry("cielo", listOf("Cielo.it", "Cielo.HD.it"), listOf("cielo", "cielo tv"), exclusiveItalian = true),
        EpgEntry("27 twentyseven", listOf("27.Twentyseven.it", "Twentyseven.it"), listOf("27 twentyseven", "twenty seven", "27 twenty seven", "twentyseven", "27", "canale 27"), exclusiveItalian = false),
        EpgEntry("tv2000", listOf("TV2000.it", "TV.2000.it"), listOf("tv2000", "tv 2000", "tele 2000"), exclusiveItalian = true),
        EpgEntry("la7d", listOf("LA7d.HD.it", "LA7d.it", "LA7.CINEMA.it"), listOf("la7d", "la 7d", "la7 d", "la 7 d", "la7cinema", "la7 cinema"), exclusiveItalian = true),
        EpgEntry("la5", listOf("La.5.it", "La5.it"), listOf("la5", "la 5", "mediaset la 5"), exclusiveItalian = true),
        EpgEntry("real time", listOf("Real.Time.it", "RealTime.it"), listOf("real time", "realtime"), exclusiveItalian = false),
        EpgEntry("qvc", listOf("QVC.it"), listOf("qvc", "qvc italia"), exclusiveItalian = false),
        EpgEntry("food network", listOf("Food.Network.it", "FoodNetwork.it"), listOf("food network", "foodnetwork"), exclusiveItalian = false),
        EpgEntry("cine34", listOf("Cine34.it", "Cine.34.it"), listOf("cine34", "cine 34", "mediaset cine 34"), exclusiveItalian = true),
        EpgEntry("focus", listOf("Focus.it"), listOf("focus", "focus tv", "mediaset focus"), exclusiveItalian = false),
        EpgEntry("rtl 102.5", listOf("RTL.102.5.HD.it", "RTL.102.5.it"), listOf("rtl 102 5", "rtl 102.5", "rtl 1025", "rtl1025", "rtl 102 5 tv"), exclusiveItalian = true),
        EpgEntry("warner tv", listOf("Warner.TV.it", "WarnerTV.it"), listOf("warner tv", "warnertv"), exclusiveItalian = false),
        EpgEntry("giallo", listOf("Giallo.it"), listOf("giallo", "giallo tv"), exclusiveItalian = true),
        EpgEntry("top crime", listOf("Top.Crime.it", "TopCrime.it"), listOf("top crime", "topcrime"), exclusiveItalian = true),
        EpgEntry("boing", listOf("Boing.it"), listOf("boing", "boing tv"), exclusiveItalian = false),
        EpgEntry("k2", listOf("K2.it"), listOf("k2", "k 2", "k2 tv"), exclusiveItalian = false),
        EpgEntry("rai gulp", listOf("RaiGulp.it", "Rai.Gulp.it"), listOf("rai gulp", "raigulp"), exclusiveItalian = true),
        EpgEntry("rai yoyo", listOf("RaiYoyo.it", "Rai.Yoyo.it"), listOf("rai yoyo", "raiyoyo", "rai yo yo"), exclusiveItalian = true),
        EpgEntry("frisbee", listOf("Frisbee.it"), listOf("frisbee", "frisbee tv"), exclusiveItalian = true),
        EpgEntry("cartoonito", listOf("Cartoonito.it"), listOf("cartoonito", "cartoonito tv"), exclusiveItalian = false),
        EpgEntry("super!", listOf("Super!.it", "Super.it"), listOf("super", "super!", "super tv"), exclusiveItalian = false),
        EpgEntry("rai news 24", listOf("RaiNews24.it", "Rai.News.24.it"), listOf("rai news 24", "rai news", "rainews24", "rainews"), exclusiveItalian = true),
        EpgEntry("italia 2", listOf("Italia.2.it", "Italia2.it"), listOf("italia 2", "mediaset italia 2", "italia2", "italia due"), exclusiveItalian = true),
        EpgEntry("sky tg24", listOf("Sky.TG24.it", "SkyTG24.it"), listOf("sky tg24", "sky tg 24", "skytg24"), exclusiveItalian = true),
        EpgEntry("tgcom24", listOf("TGCOM24.it", "Tgcom24.it"), listOf("tgcom24", "tgcom 24", "tg com 24"), exclusiveItalian = true),
        EpgEntry("dmax", listOf("DMAX.it"), listOf("dmax", "d max"), exclusiveItalian = false),
        EpgEntry("rai storia", listOf("RaiStoria.it", "Rai.Storia.it"), listOf("rai storia", "raistoria"), exclusiveItalian = true),
        EpgEntry("mediaset extra", listOf("Mediaset.Extra.it", "MediasetExtra.it"), listOf("mediaset extra", "mediasetextra"), exclusiveItalian = true),
        EpgEntry("hgtv", listOf("HGTV.it"), listOf("hgtv", "hgtv home garden", "hgtv home garden tv", "home garden", "home and garden"), exclusiveItalian = false),
        EpgEntry("rai scuola", listOf("RaiScuola.it", "Rai.Scuola.it"), listOf("rai scuola", "raiscuola"), exclusiveItalian = true),
        EpgEntry("rai sport", listOf("RaiSport.it", "Rai.Sport.it"), listOf("rai sport", "raisport", "rai sport +"), exclusiveItalian = true),
        EpgEntry("motor trend", listOf("Motor.Trend.it", "MotorTrend.it"), listOf("motor trend", "motortrend", "turbo"), exclusiveItalian = false),
        EpgEntry("supertennis", listOf("SuperTennis.HD.it", "SuperTennis.it"), listOf("supertennis", "super tennis"), exclusiveItalian = true),
        EpgEntry("r101 tv", listOf("R101tv.it", "R101.TV.it"), listOf("r101 tv", "r101", "r 101 tv", "radio 101 tv"), exclusiveItalian = true),
        EpgEntry("deejay tv", listOf("Deejay.TV.it", "DeejayTV.it"), listOf("deejay tv", "radio deejay tv", "deejay"), exclusiveItalian = true),
        EpgEntry("radio italia tv", listOf("Radio.Italia.TV.HD.it", "RadioItaliaTV.it"), listOf("radio italia tv", "radioitaliatv", "radio italia"), exclusiveItalian = true),
        EpgEntry("radiofreccia", listOf("RADIOFRECCIA.HD.it", "Radiofreccia.it"), listOf("radiofreccia", "radio freccia"), exclusiveItalian = true),
        EpgEntry("bike", listOf("BIKE.it"), listOf("bike", "bike channel", "bike tv"), exclusiveItalian = false),
        EpgEntry("equtv", listOf("EQUtv.it"), listOf("equtv", "equ tv"), exclusiveItalian = true),
        EpgEntry("radionorba tv", listOf("RADIONORBA.TV.it"), listOf("radio norba tv", "radionorba tv", "radionorba"), exclusiveItalian = true),
        EpgEntry("tg norba 24", listOf("TG.NORBA.24.it"), listOf("tg norba 24", "tgnorba24", "tg norba"), exclusiveItalian = true),
        EpgEntry("trm h24", listOf("TRM.h24.it"), listOf("trm h24", "trm", "trm network"), exclusiveItalian = true),

        // --- SKY INTRATTENIMENTO, SERIE & DOC ---
        EpgEntry("sky uno", listOf("Sky.Uno.it", "SkyUno.it"), listOf("sky uno", "skyuno"), exclusiveItalian = true),
        EpgEntry("sky atlantic", listOf("Sky.Atlantic.it", "SkyAtlantic.it"), listOf("sky atlantic", "skyatlantic"), exclusiveItalian = false),
        EpgEntry("sky serie", listOf("Sky.Serie.it", "SkySerie.it"), listOf("sky serie", "skyserie"), exclusiveItalian = true),
        EpgEntry("sky investigation", listOf("Sky.Investigation.it", "SkyInvestigation.it"), listOf("sky investigation", "skyinvestigation"), exclusiveItalian = true),
        EpgEntry("sky documentaries", listOf("Sky.Documentaries.it", "SkyDocumentaries.it"), listOf("sky documentaries", "skydocumentaries"), exclusiveItalian = false),
        EpgEntry("sky nature", listOf("Sky.Nature.it", "SkyNature.it"), listOf("sky nature", "skynature"), exclusiveItalian = false),
        EpgEntry("sky arte", listOf("Sky.Arte.it", "SkyArte.it"), listOf("sky arte", "skyarte"), exclusiveItalian = true),
        EpgEntry("sky crime", listOf("Sky.Crime.it", "Crime+Investigation.it"), listOf("sky crime", "crime investigation", "crime + investigation"), exclusiveItalian = false),
        EpgEntry("history", listOf("History.it", "History.Channel.it"), listOf("history", "history channel"), exclusiveItalian = false),
        EpgEntry("comedy central", listOf("Comedy.Central.it"), listOf("comedy central", "comedycentral"), exclusiveItalian = false),
        EpgEntry("mtv", listOf("MTV.it"), listOf("mtv", "mtv italia"), exclusiveItalian = false),

        // --- SKY CINEMA ---
        EpgEntry("sky cinema uno", listOf("Sky.Cinema.Uno.it"), listOf("sky cinema uno", "sky cinema 1"), exclusiveItalian = true),
        EpgEntry("sky cinema due", listOf("Sky.Cinema.Due.it"), listOf("sky cinema due", "sky cinema 2"), exclusiveItalian = true),
        EpgEntry("sky cinema collection", listOf("Sky.Cinema.Collection.it"), listOf("sky cinema collection"), exclusiveItalian = false),
        EpgEntry("sky cinema family", listOf("Sky.Cinema.Family.it"), listOf("sky cinema family"), exclusiveItalian = false),
        EpgEntry("sky cinema action", listOf("Sky.Cinema.Action.it"), listOf("sky cinema action"), exclusiveItalian = false),
        EpgEntry("sky cinema suspense", listOf("Sky.Cinema.Suspense.it"), listOf("sky cinema suspense"), exclusiveItalian = true),
        EpgEntry("sky cinema romance", listOf("Sky.Cinema.Romance.it"), listOf("sky cinema romance"), exclusiveItalian = true),
        EpgEntry("sky cinema drama", listOf("Sky.Cinema.Drama.it"), listOf("sky cinema drama"), exclusiveItalian = false),
        EpgEntry("sky cinema comedy", listOf("Sky.Cinema.Comedy.it"), listOf("sky cinema comedy"), exclusiveItalian = false),

        // --- SKY SPORT, EUROSPORT & DAZN ---
        EpgEntry("sky sport 24", listOf("Sky.Sport.24.it", "SkySport24.it"), listOf("sky sport 24", "skysport24"), exclusiveItalian = true),
        EpgEntry("sky sport uno", listOf("Sky.Sport.Uno.it", "SkySportUno.it"), listOf("sky sport uno", "skysportuno"), exclusiveItalian = true),
        EpgEntry("sky sport calcio", listOf("Sky.Sport.Calcio.it", "SkySportCalcio.it"), listOf("sky sport calcio", "sky calcio", "sky sport serie a"), exclusiveItalian = true),
        EpgEntry("sky sport tennis", listOf("Sky.Sport.Tennis.it"), listOf("sky sport tennis"), exclusiveItalian = false),
        EpgEntry("sky sport arena", listOf("Sky.Sport.Arena.it"), listOf("sky sport arena"), exclusiveItalian = true),
        EpgEntry("sky sport max", listOf("Sky.Sport.Max.it"), listOf("sky sport max", "sky sport action"), exclusiveItalian = true),
        EpgEntry("sky sport f1", listOf("Sky.Sport.F1.it"), listOf("sky sport f1", "sky sport formula 1", "sky f1"), exclusiveItalian = false),
        EpgEntry("sky sport motogp", listOf("Sky.Sport.MotoGP.it"), listOf("sky sport motogp", "sky sport moto gp", "sky motogp"), exclusiveItalian = true),
        EpgEntry("sky sport nba", listOf("Sky.Sport.NBA.it"), listOf("sky sport nba", "sky nba"), exclusiveItalian = false),
        EpgEntry("sky sport golf", listOf("Sky.Sport.Golf.it"), listOf("sky sport golf"), exclusiveItalian = false),
        EpgEntry("eurosport 1", listOf("Eurosport.1.it", "Eurosport1.it"), listOf("eurosport 1", "eurosport1", "eurosport"), exclusiveItalian = false),
        EpgEntry("eurosport 2", listOf("Eurosport.2.it", "Eurosport2.it"), listOf("eurosport 2", "eurosport2"), exclusiveItalian = false),
        EpgEntry("dazn 1", listOf("DAZN.1.it", "Zona.DAZN.it", "DAZN1.it"), listOf("dazn 1", "zona dazn", "dazn1", "dazn"), exclusiveItalian = false),
        EpgEntry("dazn 2", listOf("DAZN.2.it", "DAZN2.it"), listOf("dazn 2", "dazn2"), exclusiveItalian = false),
        EpgEntry("solocalcio", listOf("Sportitalia.SoloCalcio.it", "SoloCalcio.it"), listOf("sportitalia solocalcio", "solocalcio", "si solocalcio"), exclusiveItalian = true),
        EpgEntry("sportitalia", listOf("Sportitalia.it"), listOf("sportitalia", "sport italia", "si tv"), exclusiveItalian = true)
    )

    // Regex universali pre-compilate per qualsiasi stile di portale M3U / Xtream / Stalker MAC
    private val prefixRegex = Regex("""^\s*(?:[\[(|★•*🇮🇹]+\s*)?(?:it|ita|italia|italy|dtt|vip|rai|med|mediaset|sky|dazn)\s*[\])|:\-_★•*🇮🇹]+\s*""", RegexOption.IGNORE_CASE)
    private val genericPipePrefixRegex = Regex("""^\s*[a-z0-9_.\-]{2,6}\s*[|:]\s*""", RegexOption.IGNORE_CASE)
    private val leadingChannelNumberRegex = Regex("""^\s*\d{1,4}\s*[\-.:|]\s*""")
    private val bracketContentRegex = Regex("""[\[(].*?[\])]""")
    private val specialHdCharsRegex = Regex("""[ᴴᴰᶠᵁ⁴ᴷ⁵⁶²🇮🇹]+""")
    private val nonAlnumRegex = Regex("""[^a-z0-9!]+""")

    private val noiseWords = setOf(
        "hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "h264", "fullhd", "raw",
        "1080p", "720p", "50fps", "60fps", "it", "ita", "italia", "italy",
        "backup", "bk", "provvisorio", "low", "vip", "plus"
    )

    // Nazioni estere (parola intera \b per non confondere "ITA" dentro "CAPITAL" o "DIGITAL")
    private val foreignGroupWordsRegex = Regex(
        """\b(UK|US|USA|EN|ENG|DE|GER|GERMANY|DEUTSCHLAND|FR|FRA|FRANCE|ES|ESP|SPAIN|ESPANA|PT|POR|PORTUGAL|BR|BRA|BRAZIL|AR|ARG|ARGENTINA|LAT|LATAM|LATINO|MEX|TR|TUR|TURK|TURKIYE|NL|NED|NETHERLANDS|PL|POL|POLAND|POLSKA|GR|GRE|GREECE|RO|ROM|ROMANIA|AL|ALB|ALBANIA|KOSOVO|EXYU|EX-YU|SRB|SERBIA|CRO|CROATIA|BIH|BOSNIA|MKD|RU|RUS|RUSSIA|UA|UKR|ARAB|ARABIC|IN|IND|INDIA|PK|PAK|PUNJABI|AF|AFRICA|CA|CAN|CANADA|AU|AUS|BE|BEL|BELGIUM|CH|SWISS|AT|AUT|AUSTRIA|SE|SWE|SWEDEN|NO|NOR|DK|DEN|FI|FIN|CZ|SVK|HU|HUN|BG|BUL|IL|ISR|IR|IRAN|KURD|CN|CHN|JP|JPN)\b""",
        RegexOption.IGNORE_CASE
    )

    // Indicatori italiani nel gruppo o nel nome (include anche la bandiera 🇮🇹)
    private val italianGroupWordsRegex = Regex(
        """(🇮🇹|\b(IT|ITA|ITALIA|ITALY|DTT|RAI|MEDIASET|LA7|SKY\s*IT|DAZN\s*IT|NAZIONALI|REGIONALI|INTRATTENIMENTO|BAMBINI|DOCUMENTARI|DIGITALE|TERRESTRE|SERIE\s*A|CALCIO|CINEMA\s*IT|SPORT\s*IT)\b)""",
        RegexOption.IGNORE_CASE
    )

    private val italianNameMarkerRegex = Regex(
        """(🇮🇹|^\s*(?:[\[(|★•*]+\s*)?(IT|ITA|ITALIA|ITALY)\s*[\])|:\-_★•*]+|\b(ITA|ITALIA|ITALY)\b)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Pulisce qualsiasi formato di nome proveniente da portali MAC, Xtream o M3U.
     * Esempio: "01 - |IT| Hgtv - Home & Garden FHD [H265]" -> "hgtv home garden"
     */
    fun normalizeName(raw: String): String {
        var s = specialHdCharsRegex.replace(raw, " ").lowercase()
        s = leadingChannelNumberRegex.replace(s, " ")
        s = prefixRegex.replace(s, " ")
        s = genericPipePrefixRegex.replace(s, " ")
        s = bracketContentRegex.replace(s, " ")
        s = nonAlnumRegex.replace(s, " ")
        return s.trim()
            .split(" ")
            .filter { it.isNotEmpty() && it !in noiseWords }
            .joinToString(" ")
    }

    private val aliasToEntryMap: Map<String, EpgEntry> = run {
        val map = HashMap<String, EpgEntry>()
        for (item in dictionaryEntries) {
            map[normalizeName(item.canonicalKey)] = item
            for (alias in item.aliases) {
                val norm = normalizeName(alias)
                if (norm.isNotEmpty() && !map.containsKey(norm)) {
                    map[norm] = item
                }
            }
        }
        map
    }

    private val xmlIdToEntryMap: Map<String, EpgEntry> = run {
        val map = HashMap<String, EpgEntry>()
        for (item in dictionaryEntries) {
            for (xmlId in item.preferredXmlIds) {
                map[xmlId.lowercase()] = item
            }
        }
        map
    }

    fun findEntryByChannelName(rawChannelName: String): EpgEntry? {
        val norm = normalizeName(rawChannelName)
        if (norm.isEmpty()) return null
        return aliasToEntryMap[norm]
    }

    fun findEntryForXmlChannel(siteId: String, displayName: String): EpgEntry? {
        xmlIdToEntryMap[siteId.lowercase()]?.let { return it }
        return findEntryByChannelName(displayName)
    }

    /**
     * Filtro Universale per qualsiasi portale:
     * 1) Scarta sempre gruppi/prefissi stranieri.
     * 2) Sui Mega-Portali accetta tutti i gruppi/prefissi italiani + i canali con nome 100% esclusivo italiano (es. Rai 1, Canale 5, Cine34).
     */
    fun isItalianCandidate(channelName: String, channelGroup: String, isMegaPortal: Boolean): Boolean {
        val nameTrim = channelName.trim()
        val groupTrim = channelGroup.trim()

        // 1. Se ha un prefisso con "|" o ":" (es. "UK| Sky", "DE: Eurosport"), controlliamo che non sia estero
        if (nameTrim.contains("|") || nameTrim.contains(":")) {
            val prefix = nameTrim.substringBefore("|").substringBefore(":").replace(Regex("[^A-Za-z]"), "").uppercase()
            if (prefix.length in 2..4 && prefix !in setOf("IT", "ITA", "RAI", "SKY", "DTT", "MED", "VIP", "FHD", "UHD", "HD", "SD", "RAW")) {
                return false
            }
        }

        // 2. Se il gruppo o il nome contengono una nazione straniera esplicita (es. UK, DE, FR, USA, ARAB, LATINO), scarta subito!
        if (foreignGroupWordsRegex.containsMatchIn(groupTrim) || foreignGroupWordsRegex.containsMatchIn(nameTrim)) {
            return false
        }

        // 3. Se il nome o il gruppo hanno un marcatore italiano (es. "IT|", "ITALIA", "🇮🇹"), è valido su qualsiasi portale
        if (italianNameMarkerRegex.containsMatchIn(nameTrim) || italianGroupWordsRegex.containsMatchIn(groupTrim)) {
            return true
        }

        // 4. Se siamo su un Mega-Portale e il gruppo si chiama in modo generico (es. "VIP", "FHD", "GENERALE"),
        // accettiamo il canale SOLO se il suo nome è un marchio 100% esclusivo italiano (es. "Rai 1", "Canale 5", "Cine34")!
        if (isMegaPortal) {
            val entry = findEntryByChannelName(nameTrim)
            return entry?.exclusiveItalian == true
        }

        // 5. Su liste M3U normali, qualsiasi canale non straniero è valido
        return true
    }
}
