package com.portalstream.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.portalstream.app.domain.model.Channel

// Il parametro version = 1 andrà aumentato se in futuro aggiungerai nuove colonne alla tabella
@Database(entities = [Channel::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun channelDao(): ChannelDao

    companion object {
        // Volatile assicura che il valore di INSTANCE sia sempre aggiornato per tutti i thread
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "portalstream_database"
                )
                // Se durante lo sviluppo cambi la struttura di Channel.kt, 
                // questo cancella e ricrea il DB evitando crash
                .fallbackToDestructiveMigration() 
                .build()
                
                INSTANCE = instance
                instance
            }
        }
    }
}
