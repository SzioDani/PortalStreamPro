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
                                currentDesc = ""
                                currentCategory = ""
                                currentIcon = ""
                            }
                            "title" -> {
                                currentTitle = parser.nextText()
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
                            // ✅ IMPORTANTE: Converti UTC → Fuso Orario Locale
                            val startTime = parseEpgTime(currentStart, targetTimeZone)
                            val endTime = parseEpgTime(currentEnd, targetTimeZone)

                            programs.add(
                                EpgProgram(
                                    channelId = currentChannelId,
                                    title = currentTitle,
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

            Log.d("EpgParser", "Parsati ${programs.size} programmi")
            return programs

        } catch (e: Exception) {
            Log.e("EpgParser", "Errore nel parsing EPG", e)
            return emptyList()
        }
    }

    // ✅ CRUCIALE: Converti formato EPG (UTC) al fuso orario locale
    private fun parseEpgTime(timeStr: String, targetTimeZone: ZoneId): LocalDateTime {
        return try {
            // Formato EPG: "20240101120000 +0000" (UTC)
            val cleanTime = timeStr.replace(Regex("[^0-9]"), "").take(14)
            
            val formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            val utcTime = LocalDateTime.parse(cleanTime, formatter)
            
            // Converti da UTC al fuso orario target
            val utcZoned = ZonedDateTime.of(utcTime, ZoneId.of("UTC"))
            val localZoned = utcZoned.withZoneSameInstant(targetTimeZone)
            
            localZoned.toLocalDateTime()
        } catch (e: Exception) {
            Log.e("EpgParser", "Errore parsing tempo: $timeStr", e)
            LocalDateTime.now()
        }
    }
}
