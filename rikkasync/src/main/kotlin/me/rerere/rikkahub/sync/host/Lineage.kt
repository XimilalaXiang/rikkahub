package me.rerere.rikkahub.sync.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.sync.engine.FirstContactRelation
import me.rerere.rikkahub.sync.engine.LocalRef
import me.rerere.rikkahub.sync.engine.RemoteSnapshotInfo
import java.security.MessageDigest

/**
 * HostAdapter.compareFirstContact():判断"本地与远端是不是同一个会话的同一谱系"(docs/03 H12,docs/04 §4.4b)。
 *
 * 为什么需要:手动导入对齐后,两端的会话**内容相同但序列化不同**(桌面导入有损)。如果引擎自己比较序列化数据,
 * 每个会话都会被判成不同 → 满屏冲突副本(实测 5 个会话变成 10 个)。这里只比较**两端都能表示**的内容:
 * 节点 id 序列、每个节点里的消息 id、以及消息可见文本(text part)的哈希——忽略 modelSnapshot 等表示差异。
 *
 * 规则(拿不准一律 diverged:宁可多一份副本,不静默丢一侧的改动):
 *  - 完全相同                                   → same
 *  - 一方的节点序列是另一方的前缀(只是少了后续) → 节点多的一方较新
 *  - 节点 id 序列相同但内容不同(编辑/重新生成)   → 比 update_at;相等或缺失 → diverged
 *  - 其它(分叉、id 不同)                         → diverged
 * 与桌面的 sync/lineage.ts 是同一份规则。
 */
object Lineage {
    data class Message(val id: String, val textHash: String)
    data class Node(val id: String, val messages: List<Message>)

    private const val UNREADABLE = "\u0000unreadable:"
    private val json = Json

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** 一条消息的可见文本:把 text part 的文本按顺序拼起来。 */
    private fun visibleText(message: JsonObject): String {
        val parts = message["parts"] as? JsonArray ?: return ""
        return parts.mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            if ((o["type"] as? JsonPrimitive)?.content == "text") (o["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content else null
        }.joinToString("\n")
    }

    fun node(id: String, messagesJson: String): Node = try {
        val parsed = json.parseToJsonElement(messagesJson) as? JsonArray ?: throw IllegalArgumentException("not an array")
        Node(
            id,
            parsed.mapNotNull { m ->
                val o = m as? JsonObject ?: return@mapNotNull null
                // Android 的消息 id 是 Uuid(序列化为小写);比较时统一小写,避免大小写差异造成误判。
                Message(((o["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").lowercase(), sha(visibleText(o)))
            },
        )
    } catch (_: Exception) {
        // 无法解析的节点:只有"原始字节完全相同"才算相同。
        Node(id, listOf(Message("$UNREADABLE${sha(messagesJson)}", sha(messagesJson))))
    }

    fun ofLocal(db: SqlDb, conversationId: String): List<Node> = ConversationRows.readNodes(db, conversationId).map { node(it.id, it.messages) }

    fun ofWire(data: JsonElement): List<Node>? {
        val nodes = ((data as? JsonObject)?.get("nodes") as? JsonArray) ?: return null
        return nodes.map {
            val o = it as? JsonObject ?: return null
            val id = (o["id"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null
            val messages = (o["messages"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null
            node(id, messages)
        }
    }

    private fun isUnreadable(n: Node) = n.messages.size == 1 && n.messages[0].id.startsWith(UNREADABLE)

    // 线上无法解析的节点,在对端表现为"空节点"。两边谁也看不到里面的内容,所以视为相同。
    private fun same(a: Node, b: Node) =
        a.id == b.id && (
            (isUnreadable(a) && b.messages.isEmpty()) || (isUnreadable(b) && a.messages.isEmpty()) ||
                (a.messages.size == b.messages.size && a.messages.indices.all { a.messages[it] == b.messages[it] })
            )

    fun compare(local: List<Node>, remote: List<Node>, localUpdatedAt: Long?, remoteUpdatedAt: Long?): FirstContactRelation {
        val n = minOf(local.size, remote.size)
        var prefixEqual = true
        for (i in 0 until n) if (!same(local[i], remote[i])) {
            prefixEqual = false
            break
        }
        if (prefixEqual) {
            if (local.size == remote.size) return FirstContactRelation.SAME
            return if (local.size > remote.size) FirstContactRelation.LOCAL_NEWER else FirstContactRelation.REMOTE_NEWER
        }
        // 节点 id 序列相同、内容不同:同一条对话里被编辑/重新生成过,按修改时间裁决。
        val sameShape = local.size == remote.size && local.indices.all { local[it].id == remote[it].id }
        if (sameShape && localUpdatedAt != null && remoteUpdatedAt != null) {
            if (remoteUpdatedAt > localUpdatedAt) return FirstContactRelation.REMOTE_NEWER
            if (localUpdatedAt > remoteUpdatedAt) return FirstContactRelation.LOCAL_NEWER
        }
        return FirstContactRelation.DIVERGED
    }

    /** 组装成 HostAdapter.compareFirstContact。远端数据形状不对一律 diverged(保守)。 */
    fun compareFirstContact(db: SqlDb, local: LocalRef, remote: RemoteSnapshotInfo): FirstContactRelation {
        val remoteNodes = ofWire(remote.data) ?: return FirstContactRelation.DIVERGED
        return compare(ofLocal(db, local.id), remoteNodes, local.updatedAt, remote.updatedAt)
    }
}
