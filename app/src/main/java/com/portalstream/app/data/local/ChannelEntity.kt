package com.portalstream.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val group: String,
    val logoUrl: String?,
    val epgId: String?
)
