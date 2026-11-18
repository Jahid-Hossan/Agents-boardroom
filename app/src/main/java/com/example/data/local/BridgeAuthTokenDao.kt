package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BridgeAuthTokenDao {
    @Query("SELECT * FROM bridge_auth_tokens WHERE isActive = 1 ORDER BY createdAtMillis DESC")
    fun observeActiveTokens(): Flow<List<BridgeAuthTokenEntity>>

    @Query("SELECT * FROM bridge_auth_tokens ORDER BY createdAtMillis DESC")
    fun observeAllTokens(): Flow<List<BridgeAuthTokenEntity>>

    @Query("SELECT * FROM bridge_auth_tokens WHERE botId = :botId AND isActive = 1 ORDER BY createdAtMillis DESC LIMIT 1")
    fun observeActiveTokenForBot(botId: String): Flow<BridgeAuthTokenEntity?>

    @Query("SELECT * FROM bridge_auth_tokens WHERE isActive = 1 ORDER BY createdAtMillis DESC")
    suspend fun getActiveTokensOnce(): List<BridgeAuthTokenEntity>

    @Query("SELECT * FROM bridge_auth_tokens WHERE botId = :botId AND isActive = 1 ORDER BY createdAtMillis DESC LIMIT 1")
    suspend fun getActiveTokenForBotOnce(botId: String): BridgeAuthTokenEntity?

    @Query("SELECT * FROM bridge_auth_tokens WHERE tokenHash = :tokenHash AND isActive = 1 LIMIT 1")
    suspend fun getByTokenHash(tokenHash: String): BridgeAuthTokenEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertToken(entity: BridgeAuthTokenEntity)

    @Query("UPDATE bridge_auth_tokens SET isActive = 0 WHERE botId = :botId AND isActive = 1")
    suspend fun revokeTokensForBot(botId: String)

    @Query("UPDATE bridge_auth_tokens SET isActive = 0 WHERE isActive = 1")
    suspend fun revokeAllTokens()

    @Query("UPDATE bridge_auth_tokens SET lastUsedAtMillis = :timestampMillis WHERE id = :tokenId")
    suspend fun touchLastUsed(tokenId: String, timestampMillis: Long)

    @Query("DELETE FROM bridge_auth_tokens WHERE id = :tokenId")
    suspend fun deleteTokenById(tokenId: String)

    @Query("DELETE FROM bridge_auth_tokens WHERE expiresAtMillis IS NOT NULL AND expiresAtMillis <= :nowMillis")
    suspend fun deleteExpiredTokens(nowMillis: Long)

    @Transaction
    suspend fun replaceActiveTokenForBot(entity: BridgeAuthTokenEntity) {
        revokeTokensForBot(entity.botId)
        upsertToken(entity)
    }
}
