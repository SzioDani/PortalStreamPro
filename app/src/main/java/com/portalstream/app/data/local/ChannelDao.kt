package com.portalstream.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.portalstream.app.domain.model.Channel
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    // Inserisce una lista intera in un colpo solo. Se un canale esiste già, lo aggiorna.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<Channel>)

    // Legge i canali divisi per gruppo. Usiamo Flow per aggiornare la UI in tempo reale!
    @Query("SELECT * FROM channels WHERE `group` = :groupName ORDER BY name ASC")
    fun getChannelsByGroup(groupName: String): Flow<List<Channel>>

    // Estrae solo i nomi dei gruppi (es: "Sport", "Cinema", "Documentari")
    @Query("SELECT DISTINCT `group` FROM channels ORDER BY `group` ASC")
    fun getGroups(): Flow<List<String>>

    // Svuota i canali di una specifica playlist
    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannelsByPlaylist(playlistId: Int)
}
