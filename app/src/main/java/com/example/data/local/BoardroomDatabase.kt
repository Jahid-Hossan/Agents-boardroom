package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [AgentEntity::class, MessageEntity::class, SettingsEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BoardroomDatabase : RoomDatabase() {
    abstract fun boardroomDao(): BoardroomDao

    companion object {
        @Volatile
        private var INSTANCE: BoardroomDatabase? = null

        fun getInstance(context: Context): BoardroomDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BoardroomDatabase::class.java,
                    "muse_boardroom.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
