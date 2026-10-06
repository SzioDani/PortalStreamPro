package com.portalstream.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.portalstream.app.domain.model.Channel
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<Channel>)

    @Query("SELECT * FROM channels ORDER BY name ASC")
    fun getAllChannels(): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE `group` = :groupName ORDER BY name ASC")
    fun getChannelsByGroup(groupName: String): Flow<List<Channel>>

    @Query("SELECT DISTINCT `group` FROM channels ORDER BY `group` ASC")
    fun getGroups(): Flow<List<String>>

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteChannelsByPlaylist(playlistId: Int)

    @Query("DELETE FROM channels")
    suspend fun clearAll()
}
