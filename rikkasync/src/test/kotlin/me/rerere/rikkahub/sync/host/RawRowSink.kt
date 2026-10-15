package me.rerere.rikkahub.sync.host

import kotlinx.serialization.json.jsonPrimitive

/** 测试用的 Sink:直接写表(语义与 Room 的 updateConversation 相同:upsert 会话行 + 删光节点再按序重插)。 */
class RawRowSink(private val db: SqlDb) : ConversationSink {
    val saved = ArrayList<String>()
    var failWith: Exception? = null

    override suspend fun save(write: ConversationWrite) {
        failWith?.let { throw it }
        saved.add(write.id)
        val c = write.columns
        fun s(k: String) = c.getValue(k).jsonPrimitive.content
        fun n(k: String) = c.getValue(k).jsonPrimitive.content.toLong()
        db.transaction {
            db.exec(
                "INSERT OR REPLACE INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd, folder_id, config) " +
                    "VALUES (?, ?, ?, '[]', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(write.id, s("assistant_id"), s("title"), n("create_at"), n("update_at"), s("suggestions"), n("is_pinned"), s("custom_system_prompt"), s("mode_injection_ids"), s("lorebook_ids"), write.workspaceCwd, s("folder_id"), s("config")),
            )
            db.exec("DELETE FROM message_node WHERE conversation_id = ?", listOf(write.id))
            for (node in write.nodes) {
                db.exec("INSERT OR REPLACE INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)", listOf(node.id, write.id, node.nodeIndex, node.messages, node.selectIndex))
            }
        }
    }

    override suspend fun delete(id: String) {
        db.exec("DELETE FROM ConversationEntity WHERE id = ?", listOf(id)) // 外键级联删除节点
    }
}
