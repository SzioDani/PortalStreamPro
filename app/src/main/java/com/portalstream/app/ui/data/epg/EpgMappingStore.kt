package com.portalstream.app.data.epg

import android.content.Context
import android.util.Log
import com.portalstream.app.domain.model.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class PortalIdentity(
    val portalId: Int,
    val name: String,
    val type: String,
    val url: String,
    val server: String,
    val mac: String,
    val username: String,
    val password: String,
    val expireDate: String
) {
    val signature: String
        get() = "${type.uppercase()}|${url.trim()}|${server.trim()}|${mac.trim().uppercase()}|${username.trim()}|${password.trim()}|${expireDate.trim()}"

    // Chiave del server (indipendente dal MAC address, per riutilizzare gli ID se cambi MAC sullo stesso server)
    val serverKey: String
        get() = (server.ifBlank { url }).trim().lowercase()
}

class EpgMappingStore(context: Context) {

    private val TAG = "EpgMappingStore"

    private val file: File = run {
        val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
        File(externalDir, "epg_mappings.json")
    }

    /**
     * Restituisce la mappa [channelId -> epgId] salvata per questo portale.
     * Se la mappa nel JSON era vuota ("mappings": {}), restituisce null così l'app esegue l'associazione!
     */
    @Synchronized
    fun getSavedMappings(identity: PortalIdentity): Map<String, String>? {
        if (!file.exists()) return null
        return try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optInt("portalId", -1) == identity.portalId) {
                    val savedSignature = obj.optString("signature", "")
                    if (savedSignature != identity.signature) {
                        Log.d(TAG, "♻️ Firma cambiata per il portale ${identity.portalId}. Ricalcolo preservando i nomi grezzi...")
                        return null
                    }

                    val mappingsObj = obj.optJSONObject("mappings") ?: return null
                    if (mappingsObj.length() == 0) {
                        Log.d(TAG, "⚠️ Mappa JSON vuota per il portale [${identity.portalId}], avvio associazione automatica...")
                        return null
                    }

                    val result = mutableMapOf<String, String>()
                    val keys = mappingsObj.keys()
                    while (keys.hasNext()) {
                        val channelId = keys.next()
                        val item = mappingsObj.optJSONObject(channelId)
                        val epgId = item?.optString("epgId", "") ?: ""
                        if (epgId.isNotBlank()) {
                            result[channelId] = epgId
                        }
                    }
                    if (result.isEmpty()) return null
                    Log.d(TAG, "⚡ Caricati ${result.size} ID EPG istantanei dal JSON per il portale [${identity.portalId}]")
                    return result
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Errore lettura epg_mappings.json", e)
            null
        }
    }

    /**
     * 🧠 MEMORIA STORICA PER SERVER E NOME GREZZO (rawName):
     * Recupera tutti gli abbinamenti [rawName -> epgId] già noti nel JSON (inclusi quelli manuali),
     * così anche se cambi MAC address sullo stesso server l'associazione avviene in Tempo 0!
     */
    @Synchronized
    fun getKnownRawNameMappings(serverUrl: String = ""): Map<String, String> {
        if (!file.exists()) return emptyMap()
        val result = mutableMapOf<String, String>()
        return try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val mappingsObj = obj.optJSONObject("mappings") ?: continue
                val keys = mappingsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val item = mappingsObj.optJSONObject(key) ?: continue
                    val rawName = item.optString("rawName", "").trim()
                    val epgId = item.optString("epgId", "").trim()
                    val isManual = item.optBoolean("manual", false)
                    if (rawName.isNotEmpty() && epgId.isNotEmpty()) {
                        if (isManual || !result.containsKey(rawName)) {
                            result[rawName] = epgId
                        }
                    }
                }
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Salva o sovrascrive gli ID EPG trovati per uno specifico portale, preservando eventuali ID manuali.
     */
    @Synchronized
    fun savePortalMappings(identity: PortalIdentity, channels: List<Channel>) {
        try {
            val arr = if (file.exists()) {
                runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
            } else {
                JSONArray()
            }

            // Recuperiamo gli eventuali ID manuali già salvati per questo portale così non vanno mai persi
            val existingManualByChannelId = mutableMapOf<String, String>()
            val existingManualByRawName = mutableMapOf<String, String>()

            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optInt("portalId", -1) == identity.portalId) {
                    val oldMappings = obj.optJSONObject("mappings")
                    if (oldMappings != null) {
                        val keys = oldMappings.keys()
                        while (keys.hasNext()) {
                            val chId = keys.next()
                            val item = oldMappings.optJSONObject(chId)
                            if (item?.optBoolean("manual", false) == true) {
                                val epgId = item.optString("epgId", "")
                                val rawName = item.optString("rawName", "")
                                if (epgId.isNotBlank()) {
                                    existingManualByChannelId[chId] = epgId
                                    if (rawName.isNotBlank()) existingManualByRawName[rawName] = epgId
                                }
                            }
                        }
                    }
                } else {
                    newArr.put(obj)
                }
            }

            val mappingsObj = JSONObject()
            channels.forEach { ch ->
                val manualId = existingManualByChannelId[ch.id] ?: existingManualByRawName[ch.name]
                val finalEpgId = manualId ?: ch.tvgId.ifBlank { ch.customSid }
                val isManual = manualId != null

                if (finalEpgId.isNotBlank()) {
                    mappingsObj.put(
                        ch.id,
                        JSONObject().apply {
                            put("rawName", ch.name)
                            put("group", ch.group)
                            put("epgId", finalEpgId)
                            if (isManual) put("manual", true)
                        }
                    )
                }
            }

            val portalObj = JSONObject().apply {
                put("portalId", identity.portalId)
                put("name", identity.name)
                put("type", identity.type)
                put("url", identity.url)
                put("server", identity.server)
                put("mac", identity.mac)
                put("username", identity.username)
                put("password", identity.password)
                put("expireDate", identity.expireDate)
                put("signature", identity.signature)
                put("mappings", mappingsObj)
            }

            newArr.put(portalObj)
            file.writeText(newArr.toString(2))
            Log.d(TAG, "💾 Salvati ${mappingsObj.length()} ID EPG in epg_mappings.json per il portale [${identity.portalId}]")
        } catch (e: Exception) {
            Log.e(TAG, "Errore salvataggio epg_mappings.json", e)
        }
    }

    /**
     * Aggiorna immediatamente un singolo canale quando l'utente assegna l'ID manualmente col Long Press.
     */
    @Synchronized
    fun saveSingleChannelMapping(identity: PortalIdentity, channel: Channel, newEpgId: String) {
        try {
            val arr = if (file.exists()) {
                runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
            } else {
                JSONArray()
            }

            var foundPortal = false
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optInt("portalId", -1) == identity.portalId) {
                    foundPortal = true
                    obj.put("signature", identity.signature)
                    val mappingsObj = obj.optJSONObject("mappings") ?: JSONObject()
                    if (newEpgId.isBlank()) {
                        mappingsObj.remove(channel.id)
                    } else {
                        mappingsObj.put(
                            channel.id,
                            JSONObject().apply {
                                put("rawName", channel.name)
                                put("group", channel.group)
                                put("epgId", newEpgId)
                                put("manual", true) // 👈 Segna come manuale per proteggerlo sempre!
                            }
                        )
                    }
                    obj.put("mappings", mappingsObj)
                    break
                }
            }

            if (!foundPortal && newEpgId.isNotBlank()) {
                val mappingsObj = JSONObject().apply {
                    put(
                        channel.id,
                        JSONObject().apply {
                            put("rawName", channel.name)
                            put("group", channel.group)
                            put("number", channel.number)
                            put("epgId", newEpgId)
                            put("manual", true)
                        }
                    )
                }
                val portalObj = JSONObject().apply {
                    put("portalId", identity.portalId)
                    put("name", identity.name)
                    put("type", identity.type)
                    put("url", identity.url)
                    put("server", identity.server)
                    put("mac", identity.mac)
                    put("username", identity.username)
                    put("password", identity.password)
                    put("expireDate", identity.expireDate)
                    put("signature", identity.signature)
                    put("mappings", mappingsObj)
                }
                arr.put(portalObj)
            }

            file.writeText(arr.toString(2))
            Log.d(TAG, "✅ ID manuale '$newEpgId' salvato e protetto nel JSON per ${channel.id} (${channel.name})")
        } catch (e: Exception) {
            Log.e(TAG, "Errore salvataggio singolo ID in epg_mappings.json", e)
        }
    }

    @Synchronized
    fun deletePortalMappings(portalId: Int) {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optInt("portalId", -1) != portalId) {
                    newArr.put(obj)
                }
            }
            file.writeText(newArr.toString(2))
            Log.d(TAG, "🗑️ Rimossi tutti i mapping EPG dal JSON per il portale ID=$portalId")
        } catch (e: Exception) {
            Log.e(TAG, "Errore eliminazione mapping portale $portalId", e)
        }
    }
}
