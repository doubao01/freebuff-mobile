package com.freebuff.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FreebuffDao {

    // ---------- sessions ----------

    /**
     * 只订阅会话行(不带消息)。
     *
     * 首页列表只需要标题/预览/时间 —— 之前用的是带 @Relation 的「会话 + 全部消息」查询,
     * 于是流式回复每写一次消息都会让整张列表(含所有会话的全部消息)重查一遍。
     * 这条查询只依赖 sessions 表,消息写入不再触发它。
     */
    @Query("SELECT * FROM sessions ORDER BY sort DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    /** 单个会话 + 它的消息(会话页专用:只订阅当前打开的那一个会话)。 */
    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    fun observeSessionWithMessages(id: String): Flow<SessionWithMessages?>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun sessionWithMessages(id: String): SessionWithMessages?

    /**
     * 正文命中关键词的会话 id(搜索时按需调一次)。
     * 不为首页常驻加载全库消息:只有用户真的输入了关键词才扫这一遍。
     */
    @Query("SELECT DISTINCT sessionId FROM messages WHERE text LIKE '%' || :keyword || '%'")
    suspend fun sessionIdsMatching(keyword: String): List<String>

    @Query("SELECT * FROM sessions")
    suspend fun allSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun session(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("DELETE FROM sessions")
    suspend fun clearSessions()

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY sort ASC")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    /** 会话里最后一条指定角色的消息(流式写入的目标行)。 */
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId AND role = :role ORDER BY sort DESC LIMIT 1")
    suspend fun lastMessageOfRole(sessionId: String, role: String): MessageEntity?

    /** 单条消息的排序号。替换一条消息不应为它加载整个会话的消息(一次替换 = 一次全表读)。 */
    @Query("SELECT sort FROM messages WHERE sessionId = :sessionId AND id = :id LIMIT 1")
    suspend fun messageSort(sessionId: String, id: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<MessageEntity>)

    /** 按 id 删若干条消息(消息级操作:单条删除、编辑重发时的截断)。限定 sessionId 以免误删同 id 行。 */
    @Query("DELETE FROM messages WHERE sessionId = :sessionId AND id IN (:ids)")
    suspend fun deleteMessageIds(sessionId: String, ids: List<String>)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessages(sessionId: String)

    @Query("DELETE FROM messages")
    suspend fun clearMessages()

    // ---------- custom models ----------
    @Query("SELECT * FROM custom_models ORDER BY sort ASC")
    fun observeCustomModels(): Flow<List<CustomModelEntity>>

    @Query("SELECT * FROM custom_models WHERE id = :id LIMIT 1")
    suspend fun customModel(id: String): CustomModelEntity?

    @Query("SELECT * FROM custom_models")
    suspend fun allCustomModels(): List<CustomModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCustomModel(model: CustomModelEntity)

    @Query("DELETE FROM custom_models WHERE id = :id")
    suspend fun deleteCustomModel(id: String)

    @Query("DELETE FROM custom_models")
    suspend fun clearCustomModels()

    // ---------- settings ----------
    @Query("SELECT * FROM settings")
    suspend fun allSettings(): List<SettingEntity>

    @Query("SELECT * FROM settings WHERE key = :key LIMIT 1")
    suspend fun setting(key: String): SettingEntity?

    @Query("SELECT * FROM settings WHERE key = :key LIMIT 1")
    fun observeSetting(key: String): Flow<SettingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSetting(setting: SettingEntity)

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun deleteSetting(key: String)

    @Query("DELETE FROM settings")
    suspend fun clearSettings()

    // ---------- memories(Letta 式核心记忆块) ----------
    @Query("SELECT * FROM memories ORDER BY sort ASC")
    suspend fun allMemories(): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY sort ASC")
    fun observeMemories(): Flow<List<MemoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE block = :block")
    suspend fun deleteMemory(block: String)

    @Query("DELETE FROM memories")
    suspend fun clearMemories()

    // ---------- memory_entries(检索式记忆库:长/短期条目) ----------
    @Query("SELECT * FROM memory_entries WHERE userId = :userId ORDER BY updatedAt DESC")
    suspend fun memoryEntries(userId: String): List<MemoryEntryEntity>

    @Query("SELECT * FROM memory_entries ORDER BY updatedAt DESC")
    fun observeMemoryEntries(): Flow<List<MemoryEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemoryEntry(entry: MemoryEntryEntity): Long

    @Query("DELETE FROM memory_entries WHERE id = :id")
    suspend fun deleteMemoryEntry(id: Long)

    @Query("DELETE FROM memory_entries")
    suspend fun clearMemoryEntries()
}
