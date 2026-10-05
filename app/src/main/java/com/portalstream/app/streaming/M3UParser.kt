package com.portalstream.app.streaming

import com.portalstream.app.domain.model.Channel
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

object M3UParser {

    /**
     * Legge lo stream M3U riga per riga senza caricare l'intero file in memoria.
     * Invoca [onChannelParsed] ad ogni canale individuato.
     */
    fun parseStreaming(
        inputStream: InputStream,
        onChannelParsed: (Channel) -> Unit
    ) {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        var currentTitle: String? = null
        var currentGroup: String? = null
        var currentLogo: String? = null
        var currentTvgId: String? = null

        reader.useLines { lines ->
            for (line in lines) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("#EXTINF:", ignoreCase = true) -> {
                        currentTitle = trimmed.substringAfterLast(",").trim()
                        currentGroup = extractAttribute(trimmed, "group-title")
                        currentLogo = extractAttribute(trimmed, "tvg-logo")
                        currentTvgId = extractAttribute(trimmed, "tvg-id")
                    }
                    trimmed.startsWith("#EXTGRP:", ignoreCase = true) -> {
                        if (currentGroup == null) {
                            currentGroup = trimmed.substringAfter("#EXTGRP:").trim()
                        }
                    }
                    trimmed.isNotEmpty() && !trimmed.startsWith("#") -> {
                        if (!currentTitle.isNull_Empty()) {
                            val channel = Channel(
                                id = (trimmed.hashCode() xor System.currentTimeMillis().toInt()).toString(),
                                name = currentTitle,
                                url = trimmed,
                                group = currentGroup ?: "Generale",
                                logoUrl = currentLogo,
                                epgId = currentTvgId
                            )
                            onChannelParsed(channel)
                        }
                        // Reset per il prossimo canale
                        currentTitle = null
                        currentGroup = null
                        currentLogo = null
                        currentTvgId = null
                    }
                }
            }
        }
    }

    private fun extractAttribute(line: String, attribute: String): String? {
        val pattern = """$attribute="([^"]+)"""".toRegex(RegexOption.IGNORE_CASE)
        return pattern.find(line)?.groupValues?.get(1)
    }

    private fun String?.isNull_Empty(): Boolean = this == null || this.trim().isEmpty()
}
