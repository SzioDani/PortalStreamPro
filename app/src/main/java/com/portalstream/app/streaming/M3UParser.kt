package com.portalstream.app.streaming

import com.portalstream.app.domain.model.Channel

object M3UParser {

    fun parse(content: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingTvgId: String? = null

        for (raw in content.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    pendingTvgId = extractAttr(line, "tvg-id")
                    pendingLogo = extractAttr(line, "tvg-logo")
                    pendingGroup = extractAttr(line, "group-title")
                    pendingName = line.substringAfterLast(",").trim().ifEmpty { "Senza nome" }
                }
                line.isNotEmpty() && !line.startsWith("#") -> {
                    pendingName?.let { name ->
                        channels.add(
                            Channel(
                                name = name,
                                url = line,
                                logo = pendingLogo,
                                group = pendingGroup,
                                tvgId = pendingTvgId
                            )
                        )
                        pendingName = null
                    }
                }
            }
        }
        return channels
    }

    private fun extractAttr(line: String, attr: String): String? {
        val key = "$attr=\""
        val start = line.indexOf(key)
        if (start == -1) return null
        val from = start + key.length
        val end = line.indexOf("\"", from)
        return if (end == -1) null else line.substring(from, end)
    }
}
