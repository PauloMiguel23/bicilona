package com.bicilona.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [FavoritePlace::class], version = 1, exportSchema = false)
abstract class BicilonaDatabase : RoomDatabase() {

    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile
        private var INSTANCE: BicilonaDatabase? = null

        fun getInstance(context: Context): BicilonaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BicilonaDatabase::class.java,
                    "bicilona_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
