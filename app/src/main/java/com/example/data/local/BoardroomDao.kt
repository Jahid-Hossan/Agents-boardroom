package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BoardroomDao {
    @Query("SELECT * FROM agents ORDER BY sortOrder ASC, name ASC")
    fun observeAgents(): Flow<List<AgentEntity>>

    @Query("SELECT COUNT(*) FROM agents")
    suspend fun getAgentCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgent(agent: AgentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgents(agents: List<AgentEntity>)

    @Query("DELETE FROM agents WHERE id = :agentId")
    suspend fun deleteAgentById(agentId: String)

    @Query("UPDATE agents SET isSupervisor = CASE WHEN id = :supervisorId THEN 1 ELSE 0 END")
    suspend fun setSupervisorFlag(supervisorId: String?)

    @Query("SELECT * FROM messages ORDER BY timestampMillis ASC")
    fun observeMessages(): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun getMessageCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM messages")
    suspend fun clearAllMessages()

    @Query("SELECT * FROM boardroom_settings WHERE id = 1 LIMIT 1")
    fun observeSettings(): Flow<SettingsEntity?>

    @Query("SELECT * FROM boardroom_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettingsOnce(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettings(settings: SettingsEntity)
}
