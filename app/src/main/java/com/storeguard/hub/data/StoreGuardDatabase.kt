package com.storeguard.hub.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PaymentEntity::class,
        CctvEventEntity::class,
        TimeMatchCandidateEntity::class,
        CollectionLogEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class StoreGuardDatabase : RoomDatabase() {
    abstract fun dao(): StoreGuardDao

    companion object {
        @Volatile private var instance: StoreGuardDatabase? = null

        fun get(context: Context): StoreGuardDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                StoreGuardDatabase::class.java,
                "storeguard.db"
            ).build().also { instance = it }
        }
    }
}
