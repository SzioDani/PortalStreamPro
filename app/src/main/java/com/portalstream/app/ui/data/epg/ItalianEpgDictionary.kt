package com.portalstream.app.data.epg

/**
 * Dizionario centrale dedicato ESCLUSIVAMENTE all'associazione EPG Italiana (M3U e Portali MAC/Stalker).
 * Tutte le ricerche avvengono tramite HashMap in Tempo 0 (O(1)).
 */
object ItalianEpgDictionary {

    data class EpgEntry(
        val canonicalKey: String,
        val preferredXmlIds: List<String>,
        val aliases: List<String>
    )

    // 📋 TABELLA COMBINAZIONI CANALI ITALIANI -> ID XML EPG
    // Puoi aggiungere qualsiasi variante di nome sporco in "aliases"
    private val entries: List<EpgEntry> = listOf(
        // --- NAZIONALI & DTT ---
        EpgEntry("rai 1", listOf("Rai1.it", "Rai.1.it"), listOf("rai 1", "rai uno", "rai1", "raiuno")),
        EpgEntry("rai 2", listOf("Rai2.it", "Rai.2.it"), listOf("rai 2", "rai due", "rai2", "raidue")),
        EpgEntry("rai 3", listOf("Rai3.it", "Rai.3.it"), listOf("rai 3", "rai tre", "rai3", "raitre")),
        EpgEntry("rete 4", listOf("Rete.4.it", "Rete4.it"), listOf("rete 4", "rete quattro", "rete4", "retequattro")),
        EpgEntry("canale 5", listOf("Canale.5.it", "Canale5.it"), listOf("canale 5", "canale cinque", "canale5", "canalecinque")),
        EpgEntry("italia 1", listOf("Italia.1.it", "Italia1.it"), listOf("italia 1", "italia uno", "italia1", "italiauno")),
        EpgEntry("la7", listOf("LA7.HD.it", "LA7.it", "La7.it"), listOf("la7", "la 7")),
        EpgEntry("tv8", listOf("TV8.HD.it", "TV8.it"), listOf("tv8", "tv 8", "mtv8")),
        EpgEntry("nove", listOf("Nove.it", "NOVE.HD.it"), listOf("nove", "canale 9", "nove tv", "dee jay nove", "9")),
        EpgEntry("20 mediaset", listOf("20.it", "20.Mediaset.HD.it", "20Mediaset.it"), listOf("20", "20 mediaset", "mediaset 20", "canale 20", "venti")),
        EpgEntry("rai 4", listOf("Rai4.it", "Rai.4.it"), listOf("rai 4", "rai quattro", "rai4")),
        EpgEntry("iris", listOf("Iris.it"), listOf("iris", "mediaset iris")),
        EpgEntry("rai 5", listOf("Rai5.it", "Rai.5.it"), listOf("rai 5", "rai cinque", "rai5")),
        EpgEntry("rai movie", listOf("RaiMovie.it", "Rai.Movie.it"), listOf("rai movie", "raimovie")),
        EpgEntry("rai premium", listOf("RaiPremium.it", "Rai.Premium.it"), listOf("rai premium", "raipremium")),
        EpgEntry("cielo", listOf("Cielo.it", "Cielo.HD.it"), listOf("cielo", "cielo tv")),
        EpgEntry("27 twentyseven", listOf("27.Twentyseven.it", "Twentyseven.it"), listOf("27 twentyseven", "twenty seven", "27 twenty seven", "twentyseven", "27", "canale 27")),
        EpgEntry("tv2000", listOf("TV2000.it", "TV.2000.it"), listOf("tv2000", "tv 2000", "tele 2000")),
        EpgEntry("la7d", listOf("LA7d.HD.it", "LA7d.it", "LA7.CINEMA.it"), listOf("la7d", "la 7d", "la7 d", "la 7 d", "la7cinema", "la7 cinema")),
        EpgEntry("la5", listOf("La.5.it", "La5.it"), listOf("la5", "la 5", "mediaset la 5")),
        EpgEntry("real time", listOf("Real.Time.it", "RealTime.it"), listOf("real time", "realtime")),
        EpgEntry("qvc", listOf("QVC.it"), listOf("qvc", "qvc italia")),
        EpgEntry("food network", listOf("Food.Network.it", "FoodNetwork.it"), listOf("food network", "foodnetwork")),
        EpgEntry("cine34", listOf("Cine34.it", "Cine.34.it"), listOf("cine34", "cine 34", "mediaset cine 34")),
        EpgEntry("focus", listOf("Focus.it"), listOf("focus", "focus tv", "mediaset focus")),
        EpgEntry("rtl 102.5", listOf("RTL.102.5.HD.it", "RTL.102.5.it"), listOf("rtl 102 5", "rtl 102.5", "rtl 1025", "rtl1025", "rtl 102 5 tv")),
        EpgEntry("warner tv", listOf("Warner.TV.it", "WarnerTV.it"), listOf("warner tv", "warnertv", "warner")),
        EpgEntry("giallo", listOf("Giallo.it"), listOf("giallo", "giallo tv")),
        EpgEntry("top crime", listOf("Top.Crime.it", "TopCrime.it"), listOf("top crime", "topcrime")),
        EpgEntry("boing", listOf("Boing.it"), listOf("boing", "boing tv")),
        EpgEntry("k2", listOf("K2.it"), listOf("k2", "k 2", "k2 tv")),
        EpgEntry("rai gulp", listOf("RaiGulp.it", "Rai.Gulp.it"), listOf("rai gulp", "raigulp")),
        EpgEntry("rai yoyo", listOf("RaiYoyo.it", "Rai.Yoyo.it"), listOf("rai yoyo", "raiyoyo", "rai yo yo")),
        EpgEntry("frisbee", listOf("Frisbee.it"), listOf("frisbee", "frisbee tv")),
        EpgEntry("cartoonito", listOf("Cartoonito.it"), listOf("cartoonito", "cartoonito tv")),
        EpgEntry("super!", listOf("Super!.it", "Super.it"), listOf("super", "super!", "super tv")),
        EpgEntry("rai news 24", listOf("RaiNews24.it", "Rai.News.24.it"), listOf("rai news 24", "rai news", "rainews24", "rainews")),
        EpgEntry("italia 2", listOf("Italia.2.it", "Italia2.it"), listOf("italia 2", "mediaset italia 2", "italia2", "italia due")),
        EpgEntry("sky tg24", listOf("Sky.TG24.it", "SkyTG24.it"), listOf("sky tg24", "sky tg 24", "skytg24")),
        EpgEntry("tgcom24", listOf("TGCOM24.it", "Tgcom24.it"), listOf("tgcom24", "tgcom 24", "tg com 24")),
        EpgEntry("dmax", listOf("DMAX.it"), listOf("dmax", "d max")),
        EpgEntry("rai storia", listOf("RaiStoria.it", "Rai.Storia.it"), listOf("rai storia", "raistoria")),
        EpgEntry("mediaset extra", listOf("Mediaset.Extra.it", "MediasetExtra.it"), listOf("mediaset extra", "mediasetextra")),
        EpgEntry("hgtv", listOf("HGTV.it"), listOf("hgtv", "hgtv home garden", "hgtv home garden tv", "home garden", "home and garden")),
        EpgEntry("rai scuola", listOf("RaiScuola.it", "Rai.Scuola.it"), listOf("rai scuola", "raiscuola")),
        EpgEntry("rai sport", listOf("RaiSport.it", "Rai.Sport.it"), listOf("rai sport", "raisport", "rai sport +")),
        EpgEntry("motor trend", listOf("Motor.Trend.it", "MotorTrend.it"), listOf("motor trend", "motortrend", "turbo")),
        EpgEntry("supertennis", listOf("SuperTennis.HD.it", "SuperTennis.it"), listOf("supertennis", "super tennis")),
        EpgEntry("r101 tv", listOf("R101tv.it", "R101.TV.it"), listOf("r101 tv", "r101", "r 101 tv", "radio 101 tv")),
        EpgEntry("deejay tv", listOf("Deejay.TV.it", "DeejayTV.it"), listOf("deejay tv", "radio deejay tv", "deejay")),
        EpgEntry("radio italia tv", listOf("Radio.Italia.TV.HD.it", "RadioItaliaTV.it"), listOf("radio italia tv", "radioitaliatv", "radio italia")),
        EpgEntry("radiofreccia", listOf("RADIOFRECCIA.HD.it", "Radiofreccia.it"), listOf("radiofreccia", "radio freccia")),
        EpgEntry("bike", listOf("BIKE.it"), listOf("bike", "bike channel", "bike tv")),
        EpgEntry("equtv", listOf("EQUtv.it"), listOf("equtv", "equ tv")),
        EpgEntry("radionorba tv", listOf("RADIONORBA.TV.it"), listOf("radio norba tv", "radionorba tv", "radionorba")),
        EpgEntry("tg norba 24", listOf("TG.NORBA.24.it"), listOf("tg norba 24", "tgnorba24", "tg norba")),
        EpgEntry("trm h24", listOf("TRM.h24.it"), listOf("trm h24", "trm", "trm network")),

        // --- SKY INTRATTENIMENTO, SERIE & DOC ---
        EpgEntry("sky uno", listOf("Sky.Uno.it", "SkyUno.it"), listOf("sky uno", "skyuno")),
        EpgEntry("sky atlantic", listOf("Sky.Atlantic.it", "SkyAtlantic.it"), listOf("sky atlantic", "skyatlantic")),
        EpgEntry("sky serie", listOf("Sky.Serie.it", "SkySerie.it"), listOf("sky serie", "skyserie")),
        EpgEntry("sky investigation", listOf("Sky.Investigation.it", "SkyInvestigation.it"), listOf("sky investigation", "skyinvestigation")),
        EpgEntry("sky documentaries", listOf("Sky.Documentaries.it", "SkyDocumentaries.it"), listOf("sky documentaries", "skydocumentaries")),
        EpgEntry("sky nature", listOf("Sky.Nature.it", "SkyNature.it"), listOf("sky nature", "skynature")),
        EpgEntry("sky arte", listOf("Sky.Arte.it", "SkyArte.it"), listOf("sky arte", "skyarte")),
        EpgEntry("sky crime", listOf("Sky.Crime.it", "Crime+Investigation.it"), listOf("sky crime", "crime investigation", "crime + investigation")),
        EpgEntry("history", listOf("History.it", "History.Channel.it"), listOf("history", "history channel")),
        EpgEntry("comedy central", listOf("Comedy.Central.it"), listOf("comedy central", "comedycentral")),
        EpgEntry("mtv", listOf("MTV.it"), listOf("mtv", "mtv italia")),

        // --- SKY CINEMA ---
        EpgEntry("sky cinema uno", listOf("Sky.Cinema.Uno.it"), listOf("sky cinema uno", "sky cinema 1")),
        EpgEntry("sky cinema due", listOf("Sky.Cinema.Due.it"), listOf("sky cinema due", "sky cinema 2")),
        EpgEntry("sky cinema collection", listOf("Sky.Cinema.Collection.it"), listOf("sky cinema collection")),
        EpgEntry("sky cinema family", listOf("Sky.Cinema.Family.it"), listOf("sky cinema family")),
        EpgEntry("sky cinema action", listOf("Sky.Cinema.Action.it"), listOf("sky cinema action")),
        EpgEntry("sky cinema suspense", listOf("Sky.Cinema.Suspense.it"), listOf("sky cinema suspense")),
        EpgEntry("sky cinema romance", listOf("Sky.Cinema.Romance.it"), listOf("sky cinema romance")),
        EpgEntry("sky cinema drama", listOf("Sky.Cinema.Drama.it"), listOf("sky cinema drama")),
        EpgEntry("sky cinema comedy", listOf("Sky.Cinema.Comedy.it"), listOf("sky cinema comedy")),

        // --- SKY SPORT, EUROSPORT & DAZN ---
        EpgEntry("sky sport 24", listOf("Sky.Sport.24.it", "SkySport24.it"), listOf("sky sport 24", "skysport24")),
        EpgEntry("sky sport uno", listOf("Sky.Sport.Uno.it", "SkySportUno.it"), listOf("sky sport uno", "sky sport 1", "skysportuno")),
        EpgEntry("sky sport calcio", listOf("Sky.Sport.Calcio.it", "SkySportCalcio.it"), listOf("sky sport calcio", "sky calcio", "sky sport serie a")),
        EpgEntry("sky sport tennis", listOf("Sky.Sport.Tennis.it"), listOf("sky sport tennis")),
        EpgEntry("sky sport arena", listOf("Sky.Sport.Arena.it"), listOf("sky sport arena")),
        EpgEntry("sky sport max", listOf("Sky.Sport.Max.it"), listOf("sky sport max", "sky sport action")),
        EpgEntry("sky sport f1", listOf("Sky.Sport.F1.it"), listOf("sky sport f1", "sky sport formula 1", "sky f1")),
        EpgEntry("sky sport motogp", listOf("Sky.Sport.MotoGP.it"), listOf("sky sport motogp", "sky sport moto gp", "sky motogp")),
        EpgEntry("sky sport nba", listOf("Sky.Sport.NBA.it"), listOf("sky sport nba", "sky nba")),
        EpgEntry("sky sport golf", listOf("Sky.Sport.Golf.it"), listOf("sky sport golf")),
        EpgEntry("eurosport 1", listOf("Eurosport.1.it", "Eurosport1.it"), listOf("eurosport 1", "eurosport1", "eurosport")),
        EpgEntry("eurosport 2", listOf("Eurosport.2.it", "Eurosport2.it"), listOf("eurosport 2", "eurosport2")),
        EpgEntry("dazn 1", listOf("DAZN.1.it", "Zona.DAZN.it", "DAZN1.it"), listOf("dazn 1", "zona dazn", "dazn1", "dazn")),
        EpgEntry("dazn 2", listOf("DAZN.2.it", "DAZN2.it"), listOf("dazn 2", "dazn2")),
        EpgEntry("solocalcio", listOf("Sportitalia.SoloCalcio.it", "SoloCalcio.it"), listOf("sportitalia solocalcio", "solocalcio", "si solocalcio")),
        EpgEntry("sportitalia", listOf("Sportitalia.it"), listOf("sportitalia", "sport italia", "si tv"))
    )

    // Regex pre-compilate una sola volta per non rallentare MAI la CPU sui 30.000 canali MAC
    private val prefixRegex = Regex("""^\s*(?:[\[(|★•*]+\s*)?(?:it|ita|italia|italy|dtt|vip|rai|med|mediaset|sky|dazn)\s*[\])|:\-★•*]+\s*""", RegexOption.IGNORE_CASE)
    private val genericPipePrefixRegex = Regex("""^\s*[a-z0-9_.\-]{2,6}\s*[|:]\s*""", RegexOption.IGNORE_CASE)
    private val bracketContentRegex = Regex("""[\[(].*?[\])]""")
    private val specialHdCharsRegex = Regex("""[ᴴᴰᶠᵁ⁴ᴷ⁵⁶²]+""")
    private val nonAlnumRegex = Regex("""[^a-z0-9!]+""")

    private val noiseWords = setOf(
        "hd", "fhd", "uhd", "4k", "sd", "hevc", "h265", "h264", "fullhd", "raw",
        "1080p", "720p", "50fps", "60fps", "it", "ita", "italia", "italy",
        "backup", "bk", "provvisorio", "low", "vip", "plus"
    )

    // Parole intere che indicano gruppi o prefissi sicuramente STRANIERI
    private val foreignGroupWordsRegex = Regex(
        """\b(UK|US|USA|EN|ENG|DE|GER|GERMANY|FR|FRA|FRANCE|ES|ESP|SPAIN|PT|POR|PORTUGAL|BR|BRA|BRAZIL|AR|ARG|ARGENTINA|LAT|LATAM|LATINO|MEX|TR|TUR|TURK|TURKIYE|NL|NED|PL|POL|POLAND|GR|GRE|GREECE|RO|ROM|ROMANIA|AL|ALB|ALBANIA|EXYU|EX-YU|SRB|CRO|BIH|MKD|RU|RUS|RUSSIA|UA|UKR|ARAB|ARABIC|IN|IND|INDIA|PK|PAK|PUNJABI|AF|AFRICA|CA|CAN|CANADA|AU|AUS|BE|BEL|CH|SWISS|AT|AUT|SE|SWE|NO|NOR|DK|DEN|FI|FIN|CZ|SVK|HU|HUN|BG|BUL|IL|ISR|IR|IRAN|KURD|CN|CHN|JP|JPN)\b""",
        RegexOption.IGNORE_CASE
    )

    // Parole intere che indicano che un gruppo o canale su un portale MAC è ITALIANO
    private val italianGroupWordsRegex = Regex(
        """\b(IT|ITA|ITALIA|ITALY|DTT|RAI|MEDIASET|LA7|SKY\s*IT|DAZN\s*IT|NAZIONALI|REGIONALI|INTRATTENIMENTO|BAMBINI|DOCUMENTARI|DIGITALE|TERRESTRE|MEDIASET\s*PREMIUM|SERIE\s*A|CALCIO)\b""",
        RegexOption.IGNORE_CASE
    )

    private val italianNamePrefixRegex = Regex(
        """^\s*(?:[\[(|★•*]+\s*)?(IT|ITA|ITALIA|ITALY)\s*[\])|:\-★•*]+""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Normalizza un nome canale o alias in modo ultra-veloce.
     * Esempio: "|IT| Hgtv - Home & Garden FHD [H265]" -> "hgtv home garden"
     */
    fun normalizeName(raw: String): String {
        var s = specialHdCharsRegex.replace(raw, " ").lowercase()
        s = prefixRegex.replace(s, " ")
        s = genericPipePrefixRegex.replace(s, " ")
        s = bracketContentRegex.replace(s, " ")
        s = nonAlnumRegex.replace(s, " ")
        return s.trim()
            .split(" ")
            .filter { it.isNotEmpty() && it !in noiseWords }
            .joinToString(" ")
    }

    // Mappa O(1): alias normalizzato -> EpgEntry
    private val aliasToEntryMap: Map<String, EpgEntry> = buildMap {
        for (entry in entries) {
            put(normalizeName(entry.canonicalKey), entry)
            for (alias in entry.aliases) {
                val norm = normalizeName(alias)
                if (norm.isNotEmpty()) {
                    putIfAbsent(norm, entry)
                }
            }
        }
    }

    // Mappa O(1): ID XML (lowercase) -> EpgEntry
    private val xmlIdToEntryMap: Map<String, EpgEntry> = buildMap {
        for (entry in entries) {
            for (xmlId in entry.preferredXmlIds) {
                put(xmlId.lowercase(), entry)
            }
        }
    }

    /**
     * Trova la voce del dizionario in Tempo 0 (O(1)) partendo dal nome del canale.
     */
    fun findEntryByChannelName(rawChannelName: String): EpgEntry? {
        val norm = normalizeName(rawChannelName)
        if (norm.isEmpty()) return null
        return aliasToEntryMap[norm]
    }

    /**
     * Trova la voce del dizionario in Tempo 0 (O(1)) partendo da un canale dell'XML EPG.
     */
    fun findEntryForXmlChannel(siteId: String, displayName: String): EpgEntry? {
        xmlIdToEntryMap[siteId.lowercase()]?.let { return it }
        return findEntryByChannelName(displayName)
    }

    /**
     * 🎯 FILTRO ANTI-30.000 CANALI PER PORTALI MAC / M3U:
     * Decide in meno di 0.001ms se il canale è italiano e merita l'associazione EPG.
     */
    fun isItalianCandidate(channelName: String, channelGroup: String, isMegaPortal: Boolean): Boolean {
        val nameTrim = channelName.trim()
        val groupTrim = channelGroup.trim()

        // 1. Se il nome inizia con un prefisso italiano esplicito (es. "IT|", "|IT|", "ITA:", "[IT]"), è sicuramente italiano
        if (italianNamePrefixRegex.containsMatchIn(nameTrim)) {
            return true
        }

        // 2. Se il nome ha un prefisso con "|" o ":" (es. "UK| Sky Sport", "DE: Focus", "FR | TF1"),
        // controlliamo subito il prefisso: se è straniero, scartiamo all'istante!
        if (nameTrim.contains("|") || nameTrim.contains(":")) {
            val prefix = nameTrim.substringBefore("|").substringBefore(":").replace(Regex("[^A-Za-z]"), "").uppercase()
            if (prefix.length in 2..4 && prefix !in setOf("IT", "ITA", "RAI", "SKY", "DTT", "MED", "VIP", "FHD", "UHD")) {
                return false
            }
        }

        // 3. Se il gruppo contiene una nazione straniera (es. "UK", "DE", "USA", "LATINO", "ARAB"), scarta!
        // Nota: usa \b (parola intera), quindi NON scarta parole come "CAPITAL" o "DIGITAL".
        if (foreignGroupWordsRegex.containsMatchIn(groupTrim)) {
            return false
        }

        // 4. Se siamo su un Mega-Portale (es. MAG da 30.000 canali), accettiamo SOLO canali che appartengono
        // a un gruppo italiano (es. "ITALIA", "IT | ...", "RAI", "MEDIASET", "SKY IT", ecc.)
        if (isMegaPortal) {
            return italianGroupWordsRegex.containsMatchIn(groupTrim)
        }

        // 5. Su liste M3U piccole/italiane (es. lista.m3u da 100 canali), i gruppi non stranieri sono tutti validi
        return true
    }
}
