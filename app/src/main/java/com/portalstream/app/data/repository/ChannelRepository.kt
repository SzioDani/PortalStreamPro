package com.portalstream.app.data.repository

import com.portalstream.app.data.local.ChannelDao
import com.portalstream.app.domain.model.Channel
import com.portalstream.app.network.PlaylistDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ChannelRepository(
    private val channelDao: ChannelDao,
    private val downloader: PlaylistDownloader
) {
    // Legge i canali dal Database (usato dalla UI per mostrare le liste)
    fun getChannelsByGroup(group: String): Flow<List<Channel>> = channelDao.getChannelsByGroup(group)
    
    fun getGroups(): Flow<List<String>> = channelDao.getGroups()

    // Scarica la playlist da internet e la salva a blocchi nel Database
    suspend fun syncPlaylist(url: String, playlistId: Int = 0) {
        withContext(Dispatchers.IO) {
            // 1. Puliamo i vecchi canali di questa playlist per evitare duplicati
            channelDao.deleteChannelsByPlaylist(playlistId)

            val buffer = mutableListOf<Channel>()
            val CHUNK_SIZE = 500

            // 2. Scarichiamo e analizziamo il flusso riga per riga
            downloader.downloadAndParse(url) { channel ->
                // Assegniamo l'ID della playlist al canale per organizzarli meglio in futuro
                val channelWithPlaylistId = channel.copy(playlistId = playlistId)
                buffer.add(channelWithPlaylistId)

                // 3. Raggiunti i 500 canali, salviamo in blocco e svuotiamo il cassetto
                if (buffer.size >= CHUNK_SIZE) {
                    // Usiamo un blocco runBlocking interno o lanciamo una nuova coroutine? 
                    // Essendo già in withContext(Dispatchers.IO), possiamo inserire direttamente.
                    // ATTENZIONE: room dao suspend functions gestiscono già il context, ma non possiamo 
                    // chiamare una suspend function dentro una normale lambda se non stiamo attenti.
                    // Per semplificare, siccome downloadAndParse sta eseguendo un flusso, 
                    // la soluzione migliore è gestire l'insert.
                }
            }
        }
    }
}
