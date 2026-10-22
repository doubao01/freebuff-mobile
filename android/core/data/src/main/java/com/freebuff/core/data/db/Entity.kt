package com.freebuff.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.TypeConverter
import com.freebuff.core.model.ChatMsg
import com.freebuff.core.model.CustomModel
import com.freebuff.core.model.MemoryEntry
import com.freebuff.core.model.MsgStep
import com.freebuff.core.model.Probe
import com.freebuff.core.model.Session
import com.freebuff.core.model.ToolCard
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话表。sort 为列表展示顺序(新会话在前),createdAt 为创建时刻(epoch ms)。
 * createdAt=0 表示 v4 之前落库、没有时间戳的会话:列表分组时归入「更早」,不猜时间。
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val time: String,
    val preview: String,
    val sort: Long,
    // 列默认值必须与 MIGRATION_4_5 的 ALTER 一致:存量行补 0,Room 迁移后校验才通得过
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0L,
)

/**
 * 消息表。sort 为会话内顺序;steps/tools 等结构化字段用 JSON 文本存储;reasoning 为思考(思维链)内容。
 *
 * sessionId 有索引:流式回复的每一次写入、以及会话页的订阅都按 sessionId 取行。
 * 没有它时每次流式写入都是 messages 全表扫描(会话越多越慢),而流式路径恰好是写入最密的。
 */
@Entity(tableName = "messages", indices = [Index("sessionId")])
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val sort: Long,
    val role: String,
    val text: String,
    val time: String,
    val stepsJson: String,
    val md: String,
    val codeLang: String,
    val code: String,
    val md2: String,
    val ctxRepo: String,
    val ctxModel: String,
    val toolsJson: String,
    // 列默认值必须与 MIGRATION_5_6 的 ALTER 一致:存量行补空串,Room 迁移后校验才通得过
    @ColumnInfo(defaultValue = "") val reasoning: String = "",
)

/** 自定义模型表。key 为加密后的密文,models/probe 为 JSON 文本。 */
@Entity(tableName = "custom_models")
data class CustomModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val apiId: String,
    val base: String,
    val keyCipher: String,
    val ctx: String,
    val timeout: String,
    val headers: String,
    val skipTLS: Boolean,
    val modelsJson: String,
    val probeJson: String,
    val sort: Long,
)

/** 键值设置表(主题/当前模型/Git 状态/版本等)。 */
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/**
 * 记忆条目表(Letta/MemGPT 式核心记忆块的持久化载体)。
 * block=块名(persona/user/project…),content=块内容,sort 决定注入顺序。
 */
@Entity(tableName = "memories")
data class MemoryEntity(
    @PrimaryKey val block: String,
    val content: String,
    val charLimit: Int,
    val sort: Long,
)

/**
 * 检索式记忆条目表(区别于常驻的 [MemoryEntity] 块):
 * type 分长期/短期,userId 隔离多用户,按查询相关度检索后作为「工作记忆」注入。
 */
@Entity(tableName = "memory_entries", indices = [Index("userId")])
data class MemoryEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String,
    val type: String,
    val content: String,
    val sessionId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val hits: Int,
)

/** 会话 + 消息一对多聚合(Room @Relation)。 */
data class SessionWithMessages(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val messages: List<MessageEntity>,
) {
    fun toDomain(): Session =
        session.toDomain(messages.sortedBy { it.sort }.map { it.toDomain() })
}

/* ---------------- 领域对象 <-> 实体 映射 ---------------- */

fun SessionEntity.toDomain(messages: List<ChatMsg>) =
    Session(id, title, time, preview, messages, createdAt)

fun Session.toEntity(sort: Long) = SessionEntity(id, title, time, preview, sort, createdAt)

fun MessageEntity.toDomain(): ChatMsg = ChatMsg(
    id = id, role = role, text = text, reasoning = reasoning, time = time,
    steps = JsonCodec.stepsFromJson(stepsJson),
    md = md, codeLang = codeLang, code = code, md2 = md2,
    ctxRepo = ctxRepo, ctxModel = ctxModel,
    tools = JsonCodec.toolCardsFromJson(toolsJson),
)

fun ChatMsg.toEntity(sessionId: String, sort: Long): MessageEntity = MessageEntity(
    id = id, sessionId = sessionId, sort = sort, role = role, text = text, time = time,
    reasoning = reasoning,
    stepsJson = JsonCodec.stepsToJson(steps),
    md = md, codeLang = codeLang, code = code, md2 = md2,
    ctxRepo = ctxRepo, ctxModel = ctxModel,
    toolsJson = JsonCodec.toolCardsToJson(tools),
)

fun MemoryEntryEntity.toDomain(): MemoryEntry =
    MemoryEntry(id, userId, type, content, sessionId, createdAt, updatedAt, hits)

fun MemoryEntry.toEntity(): MemoryEntryEntity =
    MemoryEntryEntity(id, userId, type, content, sessionId, createdAt, updatedAt, hits)

fun CustomModelEntity.toDomain(): CustomModel = CustomModel(
    id = id, name = name, apiId = apiId, base = base,
    key = keyCipher, ctx = ctx, timeout = timeout, headers = headers,
    skipTLS = skipTLS,
    models = JsonCodec.stringsFromJson(modelsJson),
    probe = JsonCodec.probeFromJson(probeJson),
)

fun CustomModel.toEntity(sort: Long): CustomModelEntity = CustomModelEntity(
    id = id, name = name, apiId = apiId, base = base,
    keyCipher = key, ctx = ctx, timeout = timeout, headers = headers,
    skipTLS = skipTLS,
    modelsJson = JsonCodec.stringsToJson(models),
    probeJson = JsonCodec.probeToJson(probe),
    sort = sort,
)

/** Room 辅助转换:org.json 手写序列化,保持与旧 SharedPreferences 兼容。 */
object JsonCodec {
    fun stringsToJson(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    fun stringsFromJson(s: String): List<String> {
        if (s.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { v -> v.isNotEmpty() } }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun probeToJson(probe: Map<String, Probe>): String {
        val obj = JSONObject()
        probe.forEach { (k, p) -> obj.put(k, JSONObject().apply { put("at", p.at); put("ms", p.ms) }) }
        return obj.toString()
    }

    fun probeFromJson(s: String): Map<String, Probe> {
        if (s.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(s)
            obj.keys().asSequence().mapNotNull { k ->
                val p = obj.optJSONObject(k) ?: return@mapNotNull null
                k to Probe(p.optLong("at"), p.optInt("ms"))
            }.toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun stepsToJson(steps: List<MsgStep>): String {
        val arr = JSONArray()
        steps.forEach { arr.put(JSONObject().apply { put("name", it.name); put("sub", it.sub) }) }
        return arr.toString()
    }

    fun stepsFromJson(s: String): List<MsgStep> {
        if (s.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                MsgStep(o.optString("name"), o.optString("sub"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun toolCardsToJson(cards: List<ToolCard>): String {
        val arr = JSONArray()
        cards.forEach { c ->
            arr.put(
                JSONObject()
                    .put("callId", c.callId)
                    .put("tool", c.tool)
                    .put("input", c.input)
                    .put("output", c.output)
                    .put("state", c.state),
            )
        }
        return arr.toString()
    }

    fun toolCardsFromJson(s: String): List<ToolCard> {
        if (s.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ToolCard(
                    callId = o.optString("callId"),
                    tool = o.optString("tool"),
                    input = o.optString("input"),
                    output = o.optString("output"),
                    state = o.optString("state", "done"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/** Room 类型转换器:全部字段存文本,List/Map 交给 JsonCodec。 */
class Converters {
    @TypeConverter
    fun listToJson(list: List<String>): String = JsonCodec.stringsToJson(list)

    @TypeConverter
    fun jsonToList(s: String): List<String> = JsonCodec.stringsFromJson(s)

    @TypeConverter
    fun mapToJson(map: Map<String, Probe>): String = JsonCodec.probeToJson(map)

    @TypeConverter
    fun jsonToMap(s: String): Map<String, Probe> = JsonCodec.probeFromJson(s)
}
