package com.fcarreau.flowplexmail.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class, TrustRuleEntity::class], version = 5, exportSchema = false)
abstract class FlowPlexDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun trustRuleDao(): TrustRuleDao

    companion object {
        @Volatile private var instance: FlowPlexDatabase? = null

        fun getInstance(context: Context): FlowPlexDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FlowPlexDatabase::class.java,
                    "flowplex.db",
                )
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
        }
    }
}
