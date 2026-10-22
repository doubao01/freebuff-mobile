package com.freebuff.core.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        CustomModelEntity::class,
        SettingEntity::class,
        MemoryEntity::class,
        MemoryEntryEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FreebuffDatabase : RoomDatabase() {
    abstract fun dao(): FreebuffDao

    companion object {
        @Volatile
        private var instance: FreebuffDatabase? = null

        /** v2:messages 增加 toolsJson 列(工具卡片,agent-architecture.md §4)。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toolsJson TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v3:memories 表(Letta 式核心记忆块持久化)。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memories (" +
                        "block TEXT NOT NULL PRIMARY KEY, " +
                        "content TEXT NOT NULL, " +
                        "charLimit INTEGER NOT NULL, " +
                        "sort INTEGER NOT NULL)",
                )
            }
        }

        /** v4:memory_entries 表(检索式记忆库:长/短期条目 + user_id 隔离)。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS memory_entries (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "userId TEXT NOT NULL, " +
                        "type TEXT NOT NULL, " +
                        "content TEXT NOT NULL, " +
                        "sessionId TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "hits INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_entries_userId ON memory_entries(userId)")
            }
        }

        /**
         * v5:sessions 增加 createdAt(创建时刻),会话列表按它分「今天/昨天/7 天内/更早」。
         * 存量行默认 0,分桶时归入「更早」——旧版本没有记过时间,不能编造。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN createdAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v6:messages 增加 reasoning(模型输出的思考/思维链内容)。
         * 单独一列而非塞进正文:思考内容不进后续请求上下文,也不进「复制正文」。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN reasoning TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v7:messages 按 sessionId 建索引。
         *
         * 流式回复写入最密(节流后仍是每秒数次),而每一次写入/订阅都按 sessionId 取行;
         * 无索引时走全表扫描,且会话一多、消息一长就线性变慢。存量库用 CREATE INDEX 补上,
         * 不重建表(消息是用户数据,重建有丢数据风险且代价与库大小成正比)。
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_sessionId ON messages(sessionId)")
            }
        }

        fun get(context: Context): FreebuffDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FreebuffDatabase::class.java,
                "freebuff.db",
            )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
                )
                .build().also { instance = it }
        }
    }
}
