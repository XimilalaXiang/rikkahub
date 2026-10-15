package me.rerere.rikkahub.sync.host

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.sync.engine.Crypto
import me.rerere.rikkahub.sync.engine.EntitySnapshot
import me.rerere.rikkahub.sync.engine.HostAdapter
import me.rerere.rikkahub.sync.engine.HostContext
import me.rerere.rikkahub.sync.engine.LocalRef
import me.rerere.rikkahub.sync.engine.RemoteEntity
import me.rerere.rikkahub.sync.engine.ApplyIO
import me.rerere.rikkahub.sync.engine.SyncClient
import me.rerere.rikkahub.sync.engine.SyncEngine
import me.rerere.rikkahub.sync.engine.ServerHarness
import me.rerere.rikkahub.sync.state.SyncStateDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Android 适配器 × 真实引擎 × 真实服务器:两台"手机"(各有自己的 Room 同结构数据库)互相同步。 */
class AndroidConversationHostTest {
    private lateinit var server: ServerHarness
    private val keys = Crypto.deriveKeys(Crypto.generateMasterKey())
    private val closers = ArrayList<AutoCloseable>()

    private fun Phone(name: String) = TestPhone(server, keys, name).also { closers.add(it) }

    private fun msg(id: String, text: String) = """[{"id":"$id","role":"user","parts":[{"type":"text","text":"$text"}],"createdAt":"2026-01-01T00:00:00"}]"""

    @Before
    fun setUp() {
        server = ServerHarness.startOrSkip()
    }

    @After
    fun tearDown() {
        closers.reversed().forEach { runCatching { it.close() } }
        if (::server.isInitialized) server.close()
    }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private suspend fun settle(vararg phones: TestPhone) {
        repeat(4) { for (p in phones) p.engine.syncOnce() }
    }

    @Test
    fun `a conversation created on one phone arrives intact on the other, with no echo`() = run {
        val a = Phone("a"); val b = Phone("b")
        a.conv("c1", "hello", "world")
        settle(a, b)
        assertEquals(a.row("c1"), b.row("c1"))
        assertEquals(a.nodes("c1"), b.nodes("c1"))
        val idleA = a.engine.syncOnce(); val idleB = b.engine.syncOnce()
        assertEquals(0, idleA.pushed + idleA.applied + idleB.pushed + idleB.applied)
        assertTrue(idleA.errors.isEmpty() && idleB.errors.isEmpty())
    }

    @Test
    fun `edits that do not touch update_at still propagate - pin, same-length rewrite, branch switch`() = run {
        val a = Phone("a"); val b = Phone("b")
        a.conv("c1", "one", "two")
        settle(a, b)
        a.db.exec("UPDATE ConversationEntity SET is_pinned = 1 WHERE id = 'c1'")
        settle(a, b)
        assertEquals(1L, b.row("c1").isPinned)
        a.db.exec("UPDATE message_node SET messages = ? WHERE id = 'c1-n0'", listOf(msg("c1-m0", "ONE")))
        settle(a, b)
        assertTrue(b.nodes("c1")[0].messages.contains("ONE"))
        b.db.exec("UPDATE message_node SET select_index = 1 WHERE id = 'c1-n1'")
        settle(a, b)
        assertEquals(1L, a.nodes("c1")[1].selectIndex)
    }

    @Test
    fun `unknown fields inside messages travel byte for byte`() = run {
        val a = Phone("a"); val b = Phone("b")
        val raw = """[{"id":"m1","role":"assistant","parts":[{"type":"text","text":"x"}],"modelSnapshot":{"id":"gpt","extra":[1,2,{"z":null}]},"futureField":"ü 你好 \" \\"}]"""
        TestAndroidDb.insertConversation(a.db, "c1")
        TestAndroidDb.insertNode(a.db, "n1", "c1", 0, raw)
        settle(a, b)
        assertEquals(raw, b.nodes("c1").single().messages)
    }

    @Test
    fun `deleting on one phone deletes on the other`() = run {
        val a = Phone("a"); val b = Phone("b")
        a.conv("c1", "x"); a.conv("c2", "y")
        settle(a, b)
        a.db.exec("DELETE FROM ConversationEntity WHERE id = 'c1'")
        settle(a, b)
        assertEquals(setOf("c2"), b.ids())
        assertEquals(1, b.nodeCount())
    }

    @Test
    fun `concurrent edits - first pusher wins, the other keeps a conflict copy with fresh ids, nothing is lost`() = run {
        val a = Phone("a"); val b = Phone("b")
        a.conv("c1", "base")
        settle(a, b)
        a.db.exec("UPDATE ConversationEntity SET title = 'from a' WHERE id = 'c1'")
        b.db.exec("UPDATE ConversationEntity SET title = 'from b', is_pinned = 1 WHERE id = 'c1'") // 副本不能继承置顶
        a.engine.syncOnce()
        b.engine.syncOnce()
        settle(a, b)
        assertEquals("from a", a.row("c1").title)
        assertEquals("from a", b.row("c1").title)
        val copyIds = b.ids() - "c1"
        assertEquals(1, copyIds.size)
        val copy = b.row(copyIds.single())
        assertTrue(copy.title.startsWith("from b (冲突副本"))
        assertEquals(0L, copy.isPinned)
        // 副本里的节点与消息都是新 id,不会与原会话冲突
        assertTrue(b.nodes(copy.id).none { it.id.startsWith("c1-") })
        assertFalse(b.nodes(copy.id).single().messages.contains("c1-m0"))
        assertTrue(b.nodes(copy.id).single().messages.contains("base"))
        // 副本也到达另一台手机,两边收敛到同一个会话集合
        assertEquals(a.ids(), b.ids())
    }

    @Test
    fun `workspace binding is device-local - it survives remote updates and never leaves the phone`() = run {
        val a = Phone("a"); val b = Phone("b")
        a.conv("c1", "x")
        settle(a, b)
        b.db.exec("UPDATE ConversationEntity SET workspace_cwd = '/sdcard/proj', config = ? WHERE id = 'c1'", listOf("""{"chatModelId":"m","workspaceId":"w-b"}"""))
        settle(a, b) // B 的这次改动里,同步内容(去掉 workspaceId 后的 config)确实变了 → 推送
        a.db.exec("UPDATE ConversationEntity SET title = 'renamed on a' WHERE id = 'c1'")
        settle(a, b)
        assertEquals("renamed on a", b.row("c1").title)
        val local = b.db.query("SELECT workspace_cwd, config FROM ConversationEntity WHERE id = 'c1'") { it.text(0) to it.text(1) }.single()
        assertEquals("/sdcard/proj", local.first)
        assertTrue(local.second.contains("w-b") && local.second.contains("chatModelId"))
        // A 从未收到 B 的工作区
        val onA = a.db.query("SELECT workspace_cwd, config FROM ConversationEntity WHERE id = 'c1'") { it.text(0) to it.text(1) }.single()
        assertEquals("", onA.first)
        assertFalse(onA.second.contains("w-b"))
    }

    @Test
    fun `a busy conversation is neither pushed nor overwritten until it is free`() = run {
        val a = Phone("a"); val b = Phone("b")
        a.conv("c1", "x")
        settle(a, b)
        b.busy.add("c1")
        a.db.exec("UPDATE ConversationEntity SET title = 'remote edit' WHERE id = 'c1'")
        a.engine.syncOnce()
        val r = b.engine.syncOnce()
        assertTrue(r.deferredBusy > 0)
        assertEquals("title-c1", b.row("c1").title)
        b.busy.clear()
        b.engine.syncOnce()
        assertEquals("remote edit", b.row("c1").title)
        // 对称:本地忙时不推送流式中间态
        a.busy.add("c1")
        a.db.exec("UPDATE ConversationEntity SET title = 'streaming…' WHERE id = 'c1'")
        assertEquals(0, a.engine.syncOnce().pushed)
        a.busy.clear()
        assertEquals(1, a.engine.syncOnce().pushed)
    }

    @Test
    fun `first contact - the same conversations already present on both phones are adopted, not duplicated`() = run {
        val a = Phone("a"); val b = Phone("b")
        for (p in listOf(a, b)) { p.conv("c1", "same", "text", updateAt = 5000); p.conv("c2", "also same", updateAt = 6000) }
        settle(a, b)
        assertEquals(setOf("c1", "c2"), a.ids())
        assertEquals(setOf("c1", "c2"), b.ids())
        assertEquals(0, a.sink.saved.size + b.sink.saved.size) // 谁也没被覆盖
    }

    @Test
    fun `first contact - a longer lineage wins without a copy`() = run {
        val a = Phone("a"); val b = Phone("b")
        a.conv("c1", "one"); b.conv("c1", "one", "two") // B 多一个节点
        a.engine.syncOnce()
        settle(b, a)
        assertEquals(2, a.nodes("c1").size)
        assertEquals(setOf("c1"), a.ids() + b.ids())
    }

    @Test
    fun `apply returns the fingerprint the next scan reports, even when the sink rewrites the content`() = run {
        val a = Phone("a"); val b = Phone("b")
        a.conv("c1", "x")
        a.engine.syncOnce()
        // B 的 sink 模拟上游的 fillModelSnapshots 等副作用:落库时改写内容
        val rewriting = object : ConversationSink {
            val inner = RawRowSink(b.db)
            override suspend fun save(write: ConversationWrite) = inner.save(
                ConversationWrite(write.id, write.columns + ("title" to JsonPrimitive("${(write.columns.getValue("title") as JsonPrimitive).content} [rewritten]")), write.workspaceCwd, write.nodes, write.isNew),
            )
            override suspend fun delete(id: String) = inner.delete(id)
        }
        val host = AndroidConversationHost(b.db, b.syncDb, rewriting, { false }, "test")
        val engine = SyncEngine(SyncClient(server.url, server.createDevice("b2").first), keys, host, b.syncDb, "b2")
        val r1 = engine.syncOnce()
        assertEquals(1, r1.applied)
        assertEquals("title-c1 [rewritten]", b.row("c1").title)
        val r2 = engine.syncOnce()
        assertEquals("内容被 sink 改写后也不能回声推送", 0, r2.pushed)
    }

    @Test
    fun `apply leaves no dirty mark behind and primes the fingerprint cache with the returned value`() = run {
        val a = Phone("a"); val b = Phone("b")
        a.conv("c1", "x")
        val wireData = ConversationWire.read(a.db, "c1")!!.data
        // 直接调用适配器的 apply(不经引擎,否则引擎随后的 scan 会领走脏标记,看不出区别)
        val fp = b.host.apply(
            RemoteEntity("conversation", "c1", AndroidWireContract.FMT, wireData, 1, 1000L, emptyList()),
            object : ApplyIO { override suspend fun fetchBlob(id: String) = ByteArray(0) },
        )
        assertEquals("apply 之后不留脏标记(同步自己的写入不是用户编辑)", 0, DirtyTracker(b.db).count())
        assertEquals(fp, b.syncDb.getFingerprint("conversation", "c1"))
        assertEquals(fp, b.host.scan().single().fingerprint)
    }

    @Test
    fun `a failing sink is transient - retried, nothing half-written`() = run {
        val a = Phone("a"); val b = Phone("b")
        a.conv("c1", "x")
        a.engine.syncOnce()
        b.sink.failWith = IllegalStateException("database is locked")
        val r = b.engine.syncOnce()
        assertTrue(r.errors.isNotEmpty())
        assertTrue(b.ids().isEmpty())
        b.sink.failWith = null
        b.engine.syncOnce()
        assertEquals(setOf("c1"), b.ids())
    }

    /** 一个会产出畸形线数据的"对端",用来验证接收方不会被卡住。 */
    private class BadPeerHost(private val badId: String) : HostAdapter {
        private val items = LinkedHashMap<String, JsonObject>()
        init {
            items[badId] = buildJsonObject { put("conversation", buildJsonObject { put("id", badId); put("update_at", "NaN") }); put("nodes", kotlinx.serialization.json.buildJsonArray { }) }
            items["good"] = buildJsonObject {
                put("conversation", buildJsonObject { put("id", "good"); put("title", "fine") })
                put("nodes", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("id", "gn"); put("node_index", 0); put("select_index", 0); put("messages", "[]") }) })
            }
        }
        override fun isFmtSupported(type: String, fmt: String) = true
        override fun supportSignature() = "bad"
        override suspend fun scan() = items.keys.map { LocalRef("conversation", it, "fp-$it", 1) }
        override suspend fun read(type: String, id: String, ctx: HostContext) = EntitySnapshot(AndroidWireContract.FMT, items.getValue(id), "fp-$id", 1)
        override suspend fun apply(remote: RemoteEntity, io: ApplyIO) = "x"
        override suspend fun remove(type: String, id: String) {}
        override suspend fun makeConflictCopy(type: String, id: String) {}
    }

    @Test
    fun `a malformed remote record is skipped permanently and does not block the ones after it`() = run {
        val a = Phone("a")
        val badSync = SyncStateDb.openOrRecover(BundledSQLiteDriver(), ":memory:").also { closers.add(it) }
        val (token, id) = server.createDevice("bad")
        SyncEngine(SyncClient(server.url, token), keys, BadPeerHost("aaa-bad"), badSync, id).syncOnce()
        val r = a.engine.syncOnce()
        assertTrue(r.errors.joinToString(), r.errors.any { it.contains("aaa-bad") })
        assertEquals(setOf("good"), a.ids()) // 后面的记录没被挡住
        assertTrue("不会永远重试", a.engine.syncOnce().errors.isEmpty())
    }

    @Test
    fun `restoring an older backup does not turn missing conversations into cloud deletions`() = run {
        val a = Phone("a"); val b = Phone("b")
        a.conv("c1", "keep"); a.conv("c2", "newer than the backup")
        settle(a, b)
        // A 恢复了一份只有 c1 的旧备份:库被整体替换;同步状态必须重置(docs/04 §6)
        a.db.exec("DELETE FROM ConversationEntity WHERE id = 'c2'")
        a.syncDb.reset()
        settle(a, b)
        assertEquals("c2 没有被推成墓碑", setOf("c1", "c2"), b.ids())
        assertEquals("c2 从云端回到了 A", setOf("c1", "c2"), a.ids())
    }

    @Test
    fun `a restore WITHOUT resetting the state would delete - this is why the hook exists`() = run {
        val a = Phone("a"); val b = Phone("b")
        a.conv("c1", "keep"); a.conv("c2", "x")
        settle(a, b)
        a.db.exec("DELETE FROM ConversationEntity WHERE id = 'c2'") // 没有 reset
        settle(a, b)
        assertEquals("没重置状态时,缺失被理解为本地删除——钩子必须存在", setOf("c1"), b.ids())
        assertNotEquals(a.syncDb.getCursor(), 0L)
    }
}
