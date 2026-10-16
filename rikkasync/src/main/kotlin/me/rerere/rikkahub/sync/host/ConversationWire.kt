package me.rerere.rikkahub.sync.host

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import me.rerere.rikkahub.sync.engine.BlobUpload
import me.rerere.rikkahub.sync.engine.EntitySnapshot
import me.rerere.rikkahub.sync.engine.HostContext
import me.rerere.rikkahub.sync.engine.PermanentApplyException

/** 线上一个节点:messages 是不透明字符串,绝不解析后重新序列化。 */
data class WireNode(val id: String, val nodeIndex: Long, val selectIndex: Long, val messages: String)

/** 线上一个会话(协议 §5.4):同步列 + 按 node_index 升序的节点。 */
data class WireConversation(val columns: Map<String, JsonElement>, val nodes: List<WireNode>) {
    val id: String get() = (columns.getValue("id") as JsonPrimitive).content
}

/** 会话 ↔ 线格式(协议 §5.4)。Android 的线格式就是它自己的 Room 行,所以这里几乎没有"转换"。 */
object ConversationWire {
    private const val DEFAULT_ASSISTANT = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"

    fun toJson(row: ConversationRow, nodes: List<NodeRow>): JsonObject {
        val conv = linkedMapOf<String, JsonElement>(
            "id" to JsonPrimitive(row.id),
            "assistant_id" to JsonPrimitive(row.assistantId),
            "title" to JsonPrimitive(row.title),
            "create_at" to JsonPrimitive(row.createAt),
            "update_at" to JsonPrimitive(row.updateAt),
            "suggestions" to JsonPrimitive(row.suggestions),
            "is_pinned" to JsonPrimitive(row.isPinned),
            "custom_system_prompt" to JsonPrimitive(row.customSystemPrompt),
            "mode_injection_ids" to JsonPrimitive(row.modeInjectionIds),
            "lorebook_ids" to JsonPrimitive(row.lorebookIds),
            "folder_id" to JsonPrimitive(row.folderId),
            "config" to JsonPrimitive(ConversationRows.stripDeviceLocalConfig(row.config)), // 设备本地字段不出本机
        )
        return JsonObject(
            mapOf(
                "conversation" to JsonObject(conv),
                "nodes" to JsonArray(
                    nodes.map {
                        JsonObject(
                            mapOf(
                                "id" to JsonPrimitive(it.id),
                                "node_index" to JsonPrimitive(it.nodeIndex),
                                "select_index" to JsonPrimitive(it.selectIndex),
                                "messages" to JsonPrimitive(it.messages),
                            ),
                        )
                    },
                ),
            ),
        )
    }

    /**
     * 读取一个会话并序列化;会话不存在返回 null。指纹来自**这次读到的同一批行**(H1/H3)。
     * 提供 [bridge] 时:消息里的本机附件 URL 被换成块令牌,并把用到的块列进 `blobs`(只列本机真有字节的块)。
     */
    fun read(db: SqlDb, conversationId: String, ctx: HostContext? = null, bridge: AttachmentBridge? = null): EntitySnapshot? {
        val row = ConversationRows.readConversation(db, conversationId) ?: return null
        val nodes = ConversationRows.readNodes(db, conversationId)
        val wireNodes = if (bridge != null && ctx != null) nodes.map { it.copy(messages = bridge.tokenize(it.messages, ctx)) } else nodes
        val blobs: List<BlobUpload> = if (bridge == null) emptyList() else
            wireNodes.flatMap { AttachmentBridge.tokensIn(it.messages).keys }.distinct().filter { bridge.hasLocalBlob(it) }.map { id -> BlobUpload(id) { bridge.readBlob(id) } }
        return EntitySnapshot(
            fmt = AndroidWireContract.FMT,
            data = toJson(row, wireNodes),
            fingerprint = ConversationFingerprint.of(row, nodes), // 指纹覆盖本机形态的行,与 scan() 一致
            updatedAt = row.updateAt,
            blobs = blobs,
        )
    }

    /**
     * 校验并解析收到的线数据。永远无法应用的形状抛 [PermanentApplyException](引擎据此跳过并前进,不卡住游标)。
     * 缺失的非主键列用 Room 的默认值;类型不对(数值列不是数字)是畸形数据。
     */
    fun parse(data: JsonElement, recordId: String): WireConversation {
        val d = data as? JsonObject ?: throw PermanentApplyException("线数据不是对象")
        val c = d["conversation"] as? JsonObject ?: throw PermanentApplyException("线数据缺少 conversation 对象")
        val id = (c["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (id != recordId) throw PermanentApplyException("线数据里的会话 id ${c["id"]} 与记录 id $recordId 不一致")
        val rawNodes = d["nodes"] as? JsonArray ?: throw PermanentApplyException("线数据的 nodes 不是数组")

        val cols = linkedMapOf<String, JsonElement>()
        for (name in AndroidWireContract.SYNCED_CONVERSATION_COLUMNS) {
            val v = c[name]
            cols[name] = when {
                name in AndroidWireContract.NUMERIC_COLUMNS -> {
                    if (v == null || v is JsonNull) JsonPrimitive(0L)
                    else JsonPrimitive((v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw PermanentApplyException("列 $name 不是数字"))
                }
                v == null || v is JsonNull -> JsonPrimitive(defaultFor(name))
                v is JsonPrimitive && v.isString -> v
                else -> throw PermanentApplyException("列 $name 不是字符串")
            }
        }

        val seen = HashSet<String>()
        val nodes = rawNodes.mapIndexed { i, n ->
            val o = n as? JsonObject ?: throw PermanentApplyException("节点 #$i 不是对象")
            val nid = (o["id"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: throw PermanentApplyException("节点 #$i 缺少 id")
            val messages = (o["messages"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw PermanentApplyException("节点 $nid 的 messages 不是字符串")
            val select = (o["select_index"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: throw PermanentApplyException("节点 $nid 的 select_index 不是数字")
            val index = (o["node_index"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: i.toLong()
            if (!seen.add(nid)) throw PermanentApplyException("节点 id 重复: $nid")
            WireNode(nid, index, select, messages)
        }
        // 按 node_index 排序后重新编号为 0..n-1(协议要求连续;对不连续的输入宽容处理)。
        val ordered = nodes.sortedBy { it.nodeIndex }.mapIndexed { i, n -> n.copy(nodeIndex = i.toLong()) }
        return WireConversation(cols, ordered)
    }

    private fun defaultFor(column: String): String = when (column) {
        "assistant_id" -> DEFAULT_ASSISTANT
        "suggestions", "mode_injection_ids", "lorebook_ids" -> "[]"
        else -> "" // title / custom_system_prompt / folder_id / config
    }
}
