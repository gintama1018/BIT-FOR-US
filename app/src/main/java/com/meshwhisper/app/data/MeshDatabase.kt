package com.meshwhisper.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.meshwhisper.app.data.dao.MessageDao
import com.meshwhisper.app.data.dao.PacketLogDao
import com.meshwhisper.app.data.dao.PeerDao
import com.meshwhisper.app.data.dao.StoreForwardDao
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.PacketLogEntity
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.data.model.StoreForwardEntity

@Database(
    entities = [
        PeerEntity::class,
        MessageEntity::class,
        StoreForwardEntity::class,
        PacketLogEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class MeshDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao
    abstract fun storeForwardDao(): StoreForwardDao
    abstract fun packetLogDao(): PacketLogDao

    companion object {
        @Volatile
        private var INSTANCE: MeshDatabase? = null

        fun getInstance(context: Context): MeshDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    MeshDatabase::class.java,
                    "meshwhisper_db"
                ).fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
