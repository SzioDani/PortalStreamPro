package com.portalstream.app.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class Channel(
    @PrimaryKey 
    val id: String,
    val name: String,
    val url: String,
    val group: String,
    val logoUrl: String?,
    val epgId: String?,
    val playlistId: Int = 0, // Utile in futuro se avrai più playlist contemporaneamente
    val number: Int = 0,               // 👈 Numero canale nel portale/playlist
    val customSid: String = "",        // 👈 SID personalizzato per l'associazione EPG
    val cmd: String = "",              // 👈 Comando o URL di streaming nativo (es. Stalker/Xtream)
    val tvgId: String = "",            // 👈 ID EPG nativo per file M3U (tvg-id)
    val tvgName: String = ""           // 👈 Nome EPG nativo per file M3U (tvg-name)
)
