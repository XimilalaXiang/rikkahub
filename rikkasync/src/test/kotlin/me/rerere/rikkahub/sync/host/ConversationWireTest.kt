package me.rerere.rikkahub.sync.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.sync.engine.FirstContactRelation
import me.rerere.rikkahub.sync.engine.LocalRef
import me.rerere.rikkahub.sync.engine.PermanentApplyException
import me.rerere.rikkahub.sync.engine.RemoteSnapshotInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationWireTest {
    private lateinit var db: SqlDb
    private lateinit var close: AutoCloseable

    @Before
    fun setUp() = TestAndroidDb.open().let { db = it.first; close = it.second }

    @After
    fun tearDown() = close.close()

    private fun msg(id: String, text: String) = """[{"id":"$id","role":"user","parts":[{"type":"text","text":"$text"}]}]"""

    @Test
    fun `read produces the protocol 5-4 shape, with raw message strings and no device-local data`() {
        TestAndroidDb.insertConversation(db, "c1", title = "T", updateAt = 777, pinned = 1, config = """{"chatModelId":"m","workspaceId":"w1"}""")
        db.exec("UPDATE ConversationEntity SET workspace_cwd = '/sdcard/secret' WHERE id = 'c1'")
        val raw = """[{"id":"m1","parts":[{"type":"text","text":"héllo \"quoted\" \\ 你好"}],"unknownFutureField":{"a":1}}]"""
        TestAndroidDb.insertNode(db, "n1", "c1", 0, raw, select = 2)
        val snap = ConversationWire.read(db, "c1")!!
        assertEquals("android-room-30", snap.fmt)
        assertEquals(777L, snap.updatedAt)
        val data = snap.data.jsonObject
        val conv = data.getValue("conversation").jsonObject
        assertEquals(AndroidWireContract.SYNCED_CONVERSATION_COLUMNS, conv.keys.toList())
        assertEquals(1L, conv.getValue("is_pinned").jsonPrimitive.content.toLong())
        assertFalse("is_pinned 是整数,不是布尔", conv.getValue("is_pinned").jsonPrimitive.isString)
        assertTrue(conv.getValue("title").jsonPrimitive.isString)
        assertFalse(snap.data.toString().contains("/sdcard/secret"))
        assertFalse("workspaceId 不出本机", conv.getValue("config").jsonPrimitive.content.contains("workspaceId"))
        assertTrue(conv.getValue("config").jsonPrimitive.content.contains("chatModelId"))
        val node = data.getValue("nodes").jsonArray.single().jsonObject
        assertEquals(raw, node.getValue("messages").jsonPrimitive.content) // 逐字节原样,包括不认识的字段
        assertEquals(2L, node.getValue("select_index").jsonPrimitive.content.toLong())
        assertNull(ConversationWire.read(db, "missing"))
    }

    @Test
    fun `read then parse round trips`() {
        TestAndroidDb.insertConversation(db, "c1")
        TestAndroidDb.insertNode(db, "n1", "c1", 0, msg("m1", "a"))
        TestAndroidDb.insertNode(db, "n2", "c1", 1, msg("m2", "b"))
        val parsed = ConversationWire.parse(ConversationWire.read(db, "c1")!!.data, "c1")
        assertEquals("c1", parsed.id)
        assertEquals(listOf("n1", "n2"), parsed.nodes.map { it.id })
        assertEquals(listOf(0L, 1L), parsed.nodes.map { it.nodeIndex })
    }

    private fun wire(vararg conv: Pair<String, Any?>, nodes: List<JsonObject> = emptyList()) = buildJsonObject {
        put("conversation", buildJsonObject {
            for ((k, v) in conv) when (v) {
                null -> put(k, kotlinx.serialization.json.JsonNull)
                is Long -> put(k, v)
                is Int -> put(k, v)
                else -> put(k, v.toString())
            }
        })
        put("nodes", buildJsonArray { nodes.forEach { add(it) } })
    }

    private fun node(id: String, idx: Long, sel: Long = 0, messages: String = "[]") =
        buildJsonObject { put("id", id); put("node_index", idx); put("select_index", sel); put("messages", messages) }

    private fun perm(block: () -> Unit) = assertThrows(PermanentApplyException::class.java) { block() }

    @Test
    fun `malformed wire data is rejected permanently`() {
        perm { ConversationWire.parse(JsonPrimitive("x"), "c1") }
        perm { ConversationWire.parse(buildJsonObject { put("nodes", buildJsonArray { }) }, "c1") }
        perm { ConversationWire.parse(wire("id" to "other"), "c1") }
        perm { ConversationWire.parse(buildJsonObject { put("conversation", buildJsonObject { put("id", "c1") }) }, "c1") }
        perm { ConversationWire.parse(wire("id" to "c1", "update_at" to "not a number"), "c1") }
        perm { ConversationWire.parse(wire("id" to "c1", "title" to 5L), "c1") }
        perm { ConversationWire.parse(wire("id" to "c1", nodes = listOf(buildJsonObject { put("id", "n"); put("select_index", 0); put("messages", 5) })), "c1") }
        perm { ConversationWire.parse(wire("id" to "c1", nodes = listOf(node("n", 0), node("n", 1))), "c1") }
        perm { ConversationWire.parse(wire("id" to "c1", nodes = listOf(buildJsonObject { put("id", ""); put("messages", "[]"); put("select_index", 0) })), "c1") }
    }

    @Test
    fun `missing optional columns get Room defaults, and non-contiguous node indexes are normalised`() {
        val p = ConversationWire.parse(wire("id" to "c1", nodes = listOf(node("b", 5), node("a", 2))), "c1")
        assertEquals("0950e2dc-9bd5-4801-afa3-aa887aa36b4e", p.columns.getValue("assistant_id").jsonPrimitive.content)
        assertEquals("[]", p.columns.getValue("suggestions").jsonPrimitive.content)
        assertEquals(0L, p.columns.getValue("is_pinned").jsonPrimitive.content.toLong())
        assertEquals(listOf("a", "b"), p.nodes.map { it.id })
        assertEquals(listOf(0L, 1L), p.nodes.map { it.nodeIndex })
    }

    // ── 谱系 ──

    private fun lineage(vararg nodes: Pair<String, String>) = nodes.map { Lineage.node(it.first, it.second) }
    private val L = Lineage

    @Test
    fun `lineage rules`() {
        val a = lineage("n1" to msg("M1", "hi"), "n2" to msg("m2", "there"))
        assertEquals(FirstContactRelation.SAME, L.compare(a, lineage("n1" to msg("m1", "hi"), "n2" to msg("m2", "there")), 1, 2)) // id 大小写无关
        // 一方多出后续节点 → 多者较新
        assertEquals(FirstContactRelation.LOCAL_NEWER, L.compare(a, a.take(1), 1, 1))
        assertEquals(FirstContactRelation.REMOTE_NEWER, L.compare(a.take(1), a, 1, 1))
        // 同形不同文本 → 比 update_at
        val edited = lineage("n1" to msg("m1", "hi"), "n2" to msg("m2", "EDITED"))
        assertEquals(FirstContactRelation.REMOTE_NEWER, L.compare(a, edited, 1, 2))
        assertEquals(FirstContactRelation.LOCAL_NEWER, L.compare(a, edited, 3, 2))
        assertEquals(FirstContactRelation.DIVERGED, L.compare(a, edited, 2, 2))
        assertEquals(FirstContactRelation.DIVERGED, L.compare(a, edited, null, 2))
        // 分叉 / id 不同
        assertEquals(FirstContactRelation.DIVERGED, L.compare(a, lineage("x1" to msg("m1", "hi")), 1, 2))
    }

    @Test
    fun `representation-only differences do not matter, only visible text and ids do`() {
        val withExtras = """[{"id":"m1","role":"assistant","parts":[{"type":"text","text":"hi"},{"type":"reasoning","reasoning":"..."}],"modelSnapshot":{"x":1}}]"""
        val plain = """[{"id":"m1","parts":[{"type":"text","text":"hi"}]}]"""
        assertEquals(FirstContactRelation.SAME, L.compare(lineage("n" to withExtras), lineage("n" to plain), 1, 1))
    }

    @Test
    fun `an unreadable remote node equals an empty local node with the same id`() {
        val broken = lineage("n1" to "{not json")
        assertEquals(FirstContactRelation.SAME, L.compare(listOf(Lineage.Node("n1", emptyList())), broken, 1, 1))
        assertEquals(FirstContactRelation.DIVERGED, L.compare(listOf(Lineage.Node("n2", emptyList())), broken, 1, 1))
        assertEquals(FirstContactRelation.DIVERGED, L.compare(lineage("n1" to msg("m", "x")), broken, 1, 1))
    }

    @Test
    fun `compareFirstContact against the database and a malformed remote`() {
        TestAndroidDb.insertConversation(db, "c1", updateAt = 5)
        TestAndroidDb.insertNode(db, "n1", "c1", 0, msg("m1", "hi"))
        val local = LocalRef("conversation", "c1", "fp", 5)
        val same = ConversationWire.read(db, "c1")!!.data
        assertEquals(FirstContactRelation.SAME, Lineage.compareFirstContact(db, local, RemoteSnapshotInfo("android-room-30", same, 5)))
        assertEquals(FirstContactRelation.DIVERGED, Lineage.compareFirstContact(db, local, RemoteSnapshotInfo("android-room-30", JsonPrimitive("junk"), 5)))
        assertNotNull(same)
    }
}
