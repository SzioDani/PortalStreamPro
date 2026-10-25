package com.portalstream.app.data.epg

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class EpgProgram(
    val channelId: String,
    val title: String,
    val subTitle: String?,        // 📺 Sottotitolo / Titolo episodio (<sub-title>)
    val episodeNum: String?,      // 🏷️ Stagione ed Episodio (<episode-num system="onscreen">)
    val description: String?,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val category: String?,
    val iconUrl: String?
)

class EpgParser {

    fun parseXmlTv(xmlContent: String, targetTimeZone: ZoneId = ZoneId.systemDefault()): List<EpgProgram> {
        val programs = mutableListOf<EpgProgram>()
        
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(xmlContent.byteInputStream(), "UTF-8")

            var eventType = parser.eventType
            var currentChannelId = ""
            var currentTitle = ""
            var currentSubTitle = ""
            var currentEpisodeNum = ""
            var currentDesc = ""
            var currentStart = ""
            var currentEnd = ""
            var currentCategory = ""
            var currentIcon = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "programme" -> {
                                currentChannelId = parser.getAttributeValue(null, "channel") ?: ""
                                currentStart = parser.getAttributeValue(null, "start") ?: ""
                                currentEnd = parser.getAttributeValue(null, "stop") ?: ""
                                currentTitle = ""
                                currentSubTitle = ""
                                currentEpisodeNum = ""
                                currentDesc = ""
                                currentCategory = ""
                                currentIcon = ""
                            }
                            "title" -> {
                                currentTitle = parser.nextText()
                            }
                            "sub-title" -> {
                                // 📺 Legge il sottotitolo (es. titolo episodio)
                                currentSubTitle = parser.nextText()
                            }
                            "episode-num" -> {
                                // 🏷️ Controlla se il sistema è "onscreen" (es. S2 E13) o raccoglie il testo
                                val systemAttr = parser.getAttributeValue(null, "system")
                                val text = parser.nextText()
                                if (systemAttr == "onscreen" || currentEpisodeNum.isBlank()) {
                                    currentEpisodeNum = text
                                }
                            }
                            "desc" -> {
                                currentDesc = parser.nextText()
                            }
                            "category" -> {
                                currentCategory = parser.nextText()
                            }
                            "icon" -> {
                                currentIcon = parser.getAttributeValue(null, "src") ?: ""
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "programme" && currentChannelId.isNotBlank() && currentTitle.isNotBlank()) {
                            val startTime = parseEpgTime(currentStart, targetTimeZone)
                            val endTime = parseEpgTime(currentEnd, targetTimeZone)

                            programs.add(
                                EpgProgram(
                                    channelId = currentChannelId,
                                    title = currentTitle,
                                    subTitle = currentSubTitle.takeIf { it.isNotBlank() },
                                    episodeNum = currentEpisodeNum.takeIf { it.isNotBlank() },
                                    description = currentDesc.takeIf { it.isNotBlank() },
                                    startTime = startTime,
                                    endTime = endTime,
                                    category = currentCategory.takeIf { it.isNotBlank() },
                                    iconUrl = currentIcon.takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                }
                eventType = parser.next()
            }

            Log.d("EpgParser", "Parsati ${programs.size} programmi con sottotitoli ed episodi")
            return programs

        } catch (e: Exception) {
            Log.e("EpgParser", "Errore nel parsing EPG", e)
            return emptyList()
        }
    }

    private fun parseEpgTime(timeStr: String, targetTimeZone: ZoneId): LocalDateTime {
        return try {
            val cleanTime = timeStr.replace(Regex("[^0-9]"), "").take(14)
            val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            val utcTime = LocalDateTime.parse(cleanTime, formatter)
            val utcZoned = ZonedDateTime.of(utcTime, ZoneId.of("UTC"))
            val localZoned = utcZoned.withZoneSameInstant(targetTimeZone)
            localZoned.toLocalDateTime()
        } catch (e: Exception) {
            Log.e("EpgParser", "Errore parsing tempo: $timeStr", e)
            LocalDateTime.now()
        }
    }
}
