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
    // Impronta digitale unica: se cambia anche solo il MAC (es. da :03 a :05) o la scadenza, la firma cambia!
    val signature: String
        get() = "${type.uppercase()}|${url.trim()}|${server.trim()}|${mac.trim().uppercase()}|${username.trim()}|${password.trim()}|${expireDate.trim()}"
}

class EpgMappingStore(context: Context) {

    private val TAG = "EpgMappingStore"

    // File JSON visibile in Android/data/com.portalstream.app/files/epg_mappings.json
    private val file: File = run {
        val externalDir = context.getExternalFilesDir(null) ?: context.filesDir
        File(externalDir, "epg_mappings.json")
    }

    /**
     * Restituisce la mappa [channelId -> epgId] salvata per questo portale.
     * Se il portale è stato modificato (es. MAC cambiato da :03 a :05), cancella i vecchi dati e restituisce null.
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
                        Log.d(TAG, "♻️ Firma cambiata per il portale ${identity.portalId} (es. MAC modificato). Pulizia vecchi ID...")
                        deletePortalMappings(identity.portalId)
                        return null
                    }

                    val mappingsObj = obj.optJSONObject("mappings") ?: return emptyMap()
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
     * Salva o sovrascrive l'intero blocco degli ID EPG trovati per uno specifico portale.
     */
    @Synchronized
    fun savePortalMappings(identity: PortalIdentity, channels: List<Channel>) {
        try {
            val arr = if (file.exists()) {
                runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
            } else {
                JSONArray()
            }

            val newArr = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optInt("portalId", -1) != identity.portalId) {
                    newArr.put(obj)
                }
            }

            val mappingsObj = JSONObject()
            channels.forEach { ch ->
                val finalEpgId = ch.tvgId.ifBlank { ch.customSid }
                if (finalEpgId.isNotBlank()) {
                    mappingsObj.put(
                        ch.id,
                        JSONObject().apply {
                            put("rawName", ch.name)
                            put("number", ch.number)
                            put("epgId", finalEpgId)
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
                                put("number", channel.number)
                                put("epgId", newEpgId)
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
                            put("number", channel.number)
                            put("epgId", newEpgId)
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
            Log.d(TAG, "✅ ID manuale '$newEpgId' salvato nel JSON per ${channel.id} (${channel.name})")
        } catch (e: Exception) {
            Log.e(TAG, "Errore salvataggio singolo ID in epg_mappings.json", e)
        }
    }

    /**
     * Cancella tutti gli abbinamenti di un portale quando viene eliminato o modificato.
     */
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
