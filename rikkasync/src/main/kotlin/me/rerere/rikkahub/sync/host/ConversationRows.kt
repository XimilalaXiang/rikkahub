package me.rerere.rikkahub.sync.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/** 会话主表里被同步的列(Room 存储类型:数值列是整数,其余是字符串)。 */
data class ConversationRow(
    val id: String,
    val assistantId: String,
    val title: String,
    val createAt: Long,
    val updateAt: Long,
    val suggestions: String,
    val isPinned: Long,
    val customSystemPrompt: String,
    val modeInjectionIds: String,
    val lorebookIds: String,
    val folderId: String,
    /** DB 里的原始 config 字符串(**含**设备本地字段)。 */
    val config: String,
)

data class NodeRow(val id: String, val nodeIndex: Long, val selectIndex: Long, /** 不透明 JSON 字符串,绝不解析后重新序列化。 */ val messages: String)

object ConversationRows {
    private const val CONV_SELECT =
        "SELECT id, assistant_id, title, create_at, update_at, suggestions, is_pinned, custom_system_prompt, mode_injection_ids, lorebook_ids, folder_id, config FROM ConversationEntity"
    private const val NODE_SQL = "SELECT id, node_index, select_index, messages FROM message_node WHERE conversation_id = ? ORDER BY node_index ASC"

    private fun map(r: SqlRow) = ConversationRow(
        id = r.text(0), assistantId = r.text(1), title = r.text(2), createAt = r.long(3), updateAt = r.long(4),
        suggestions = r.text(5), isPinned = r.long(6), customSystemPrompt = r.text(7), modeInjectionIds = r.text(8),
        lorebookIds = r.text(9), folderId = r.text(10), config = r.text(11),
    )

    fun readConversation(db: SqlDb, id: String): ConversationRow? = db.queryOne("$CONV_SELECT WHERE id = ?", listOf(id), ::map)

    fun readNodes(db: SqlDb, conversationId: String): List<NodeRow> =
        db.query(NODE_SQL, listOf(conversationId)) { NodeRow(it.text(0), it.long(1), it.long(2), it.text(3)) }

    private val json = Json

    /**
     * 把 config(ConversationConfig 的 JSON 字符串)里的设备本地字段 workspaceId 剔掉。无法解析或没有该字段时原样返回。
     * 推送前与算指纹前都用它:切换工作区不应被当成"会话内容变化"。
     */
    fun stripDeviceLocalConfig(config: String): String {
        if (config.isEmpty() || !config.contains(AndroidWireContract.CONFIG_DEVICE_LOCAL_KEY)) return config
        return try {
            val o = json.parseToJsonElement(config).jsonObject
            if (AndroidWireContract.CONFIG_DEVICE_LOCAL_KEY !in o) config else JsonObject(o - AndroidWireContract.CONFIG_DEVICE_LOCAL_KEY).toString()
        } catch (_: Exception) {
            config
        }
    }
}

/**
 * 会话内容指纹(docs/03 H1–H3)。覆盖**被同步的全部内容**:会话的同步列(config 已剔除设备本地字段)+ 全部节点
 * (id、顺序、select_index、messages 全文)。用长度前缀编码每个字段,避免 ("ab","c") 与 ("a","bc") 这类拼接碰撞。
 * 带版本前缀 "fp1:"。改算法 = 所有已同步实体的指纹都变 = 全库重推一次,所以不要轻易改。
 * (指纹是每台设备对自己数据的摘要,不跨设备比较,所以与桌面的算法无需一致。)
 */
object ConversationFingerprint {
    const val VERSION = "fp1"

    fun of(row: ConversationRow, nodes: List<NodeRow>): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun feed(v: Any?) {
            val s = v?.toString() ?: "\u0000null"
            md.update("${s.toByteArray(Charsets.UTF_8).size}:".toByteArray())
            md.update(s.toByteArray(Charsets.UTF_8))
            md.update('\n'.code.toByte())
        }
        listOf(
            row.id, row.assistantId, row.title, row.createAt, row.updateAt, row.suggestions, row.isPinned, row.customSystemPrompt,
            row.modeInjectionIds, row.lorebookIds, row.folderId, ConversationRows.stripDeviceLocalConfig(row.config), nodes.size,
        ).forEach(::feed)
        for (n in nodes) listOf(n.id, n.nodeIndex, n.selectIndex, n.messages).forEach(::feed)
        return "$VERSION:" + md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 读取并计算某会话当前的指纹;会话不存在返回 null。 */
    fun compute(db: SqlDb, conversationId: String): Pair<String, Long>? {
        val row = ConversationRows.readConversation(db, conversationId) ?: return null
        return of(row, ConversationRows.readNodes(db, conversationId)) to row.updateAt
    }
}
