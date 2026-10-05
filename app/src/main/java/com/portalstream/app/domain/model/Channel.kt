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
    val playlistId: Int = 0 // Utile in futuro se avrai più playlist contemporaneamente
)
