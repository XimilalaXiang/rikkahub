package me.rerere.rikkahub.sync.host

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 测试用的"Android 主库":在纯 JVM 的 SQLite 上,用 **Room 导出的真实 schema(app/schemas/…/<版本>.json)** 建表,
 * 所以表结构(列、默认值、外键、索引)就是上游真实的,随 Room 版本自动跟随。
 */
object TestAndroidDb {
    val schemasDir: File = File("../app/schemas/me.rerere.rikkahub.data.db.AppDatabase")

    fun schemaFile(version: Int = AndroidWireContract.SCHEMA_VERSION) = File(schemasDir, "$version.json")

    fun latestSchemaVersion(): Int = schemasDir.listFiles()!!.mapNotNull { it.nameWithoutExtension.toIntOrNull() }.max()

    /** 返回 (db, close)。foreign_keys 打开,与 Room 一致(Room 默认开启外键)。 */
    fun open(tables: Set<String> = setOf(AndroidWireContract.CONVERSATION_TABLE, AndroidWireContract.NODE_TABLE, "conversation_folder")): Pair<SqlDb, AutoCloseable> {
        val conn = BundledSQLiteDriver().open(":memory:")
        val db = ConnectionSqlDb(conn)
        db.exec("PRAGMA foreign_keys = ON")
        val entities = Json.parseToJsonElement(schemaFile().readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
        for (e in entities.map { it.jsonObject }) {
            val name = e["tableName"]!!.jsonPrimitive.content
            if (name !in tables) continue
            db.exec(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
            for (idx in e["indices"]?.jsonArray.orEmpty()) db.exec(idx.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", name))
        }
        return db to AutoCloseable { conn.close() }
    }

    fun insertConversation(db: SqlDb, id: String, title: String = "t-$id", updateAt: Long = 1000, pinned: Int = 0, config: String = "") {
        db.exec(
            "INSERT OR REPLACE INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd, folder_id, config) " +
                "VALUES (?, '0950e2dc-9bd5-4801-afa3-aa887aa36b4e', ?, '[]', 1, ?, '[]', ?, '', '[]', '[]', '', '', ?)",
            listOf(id, title, updateAt, pinned, config),
        )
    }

    fun insertNode(db: SqlDb, id: String, conversationId: String, index: Int, messages: String = "[]", select: Int = 0) {
        db.exec("INSERT OR REPLACE INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)", listOf(id, conversationId, index, messages, select))
    }

    fun insertFolder(db: SqlDb, id: String, name: String, assistantId: String = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e", sortIndex: Int = 0, createAt: Long = 1) {
        db.exec("INSERT OR REPLACE INTO conversation_folder (id, assistant_id, name, sort_index, create_at) VALUES (?, ?, ?, ?, ?)", listOf(id, assistantId, name, sortIndex, createAt))
    }
}
