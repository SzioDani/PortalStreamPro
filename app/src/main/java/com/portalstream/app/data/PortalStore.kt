package com.portalstream.app.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// Portali in JSON interno. Campi nuovi con opt* + default: retrocompatibile.
class PortalStore(context: Context) {

    private val file = File(context.filesDir, "portals.json")

    fun load(): List<Portal> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<Portal>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Portal(
                        id = o.getInt("id"),
                        name = o.optString("name"),
                        type = runCatching { PortalType.valueOf(o.getString("type")) }
                            .getOrDefault(PortalType.UNKNOWN),
                        url = o.optString("url"),
                        macAddress = o.optString("mac", ""),
                        useVpn = o.optBoolean("vpn", false),
                        profile = o.optString("profile", ""),
                        useCustomUserAgent = o.optBoolean("customUa", false),
                        userAgent = o.optString("ua", ""),
                        server = o.optString("server", ""),
                        username = o.optString("user", ""),
                        password = o.optString("pass", ""),
                        streamFormat = o.optString("format", "m3u8"),
                        forceStreamLink = o.optBoolean("forceLink", false)
                    )
                )
            }
            list.sortedBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // Assegna la numerazione progressiva. Nome vuoto = viene mostrato solo il numero.
    fun add(portal: Portal): Portal {
        val portals = load().toMutableList()
        val assigned = portal.copy(id = (portals.maxOfOrNull { it.id } ?: 0) + 1)
        portals.add(assigned)
        write(portals)
        return assigned
    }

    fun update(portal: Portal) {
        write(load().map { if (it.id == portal.id) portal else it })
    }

    fun delete(id: Int) {
        write(load().filterNot { it.id == id })
    }

    private fun write(portals: List<Portal>) {
        val arr = JSONArray()
        portals.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("type", p.type.name)
                    put("url", p.url)
                    put("mac", p.macAddress)
                    put("vpn", p.useVpn)
                    put("profile", p.profile)
                    put("customUa", p.useCustomUserAgent)
                    put("ua", p.userAgent)
                    put("server", p.server)
                    put("user", p.username)
                    put("pass", p.password)
                    put("format", p.streamFormat)
                    put("forceLink", p.forceStreamLink)
                }
            )
        }
        file.writeText(arr.toString())
    }
}
