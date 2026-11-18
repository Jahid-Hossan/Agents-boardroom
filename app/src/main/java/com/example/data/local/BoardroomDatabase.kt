package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AgentEntity::class,
        MessageEntity::class,
        SettingsEntity::class,
        BridgeAuthTokenEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class BoardroomDatabase : RoomDatabase() {
    abstract fun boardroomDao(): BoardroomDao
    abstract fun bridgeAuthTokenDao(): BridgeAuthTokenDao

    companion object {
        @Volatile
        private var INSTANCE: BoardroomDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE agents ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE agents ADD COLUMN selectedForBroadcast INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE agents ADD COLUMN bridgeBotId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN parentMessageId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN deliveryTaskId TEXT")
                db.execSQL("ALTER TABLE messages ADD COLUMN dispatchBatchId TEXT")
                db.execSQL("ALTER TABLE boardroom_settings ADD COLUMN bridgeServerUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE boardroom_settings ADD COLUMN bridgeAuthToken TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bridge_auth_tokens (
                        id TEXT NOT NULL PRIMARY KEY,
                        botId TEXT NOT NULL,
                        serverUrl TEXT NOT NULL,
                        encryptedToken TEXT NOT NULL,
                        tokenHash TEXT NOT NULL,
                        tokenPreview TEXT NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        createdAtMillis INTEGER NOT NULL,
                        lastUsedAtMillis INTEGER NOT NULL,
                        expiresAtMillis INTEGER
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bridge_auth_tokens_botId ON bridge_auth_tokens(botId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bridge_auth_tokens_isActive ON bridge_auth_tokens(isActive)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bridge_auth_tokens_tokenHash ON bridge_auth_tokens(tokenHash)")
            }
        }

        fun getInstance(context: Context): BoardroomDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    BoardroomDatabase::class.java,
                    "muse_boardroom.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
