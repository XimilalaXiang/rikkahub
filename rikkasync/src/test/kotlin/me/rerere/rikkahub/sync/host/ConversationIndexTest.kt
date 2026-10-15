package me.rerere.rikkahub.sync.host

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.sync.state.SyncStateDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConversationIndexTest {
    private lateinit var db: SqlDb
    private lateinit var closeDb: AutoCloseable
    private lateinit var syncDb: SyncStateDb
    private val computes = ArrayList<Pair<String, String>>()
    private val busy = HashSet<String>()
    private lateinit var index: ConversationIndex
    private var clock: () -> Long = { System.currentTimeMillis() }

    @Before
    fun setUp() {
        TestAndroidDb.open().let { db = it.first; closeDb = it.second }
        syncDb = SyncStateDb.openOrRecover(BundledSQLiteDriver(), ":memory:")
        index = ConversationIndex(db, syncDb, isBusy = { it in busy }, auditBatch = 2, now = { clock() }, onCompute = { id, why -> computes.add(id to why) })
    }

    @After
    fun tearDown() {
        syncDb.close()
        closeDb.close()
    }

    private fun scan() = runBlocking { index.scan() }.associateBy { it.id }
    private fun seed(id: String, texts: List<String> = listOf("hello")) {
        TestAndroidDb.insertConversation(db, id)
        texts.forEachIndexed { i, t -> TestAndroidDb.insertNode(db, "$id-n$i", id, i, """[{"id":"m$i","text":"$t"}]""") }
    }

    @Test
    fun `first scan computes everything, steady state computes nothing but audit samples`() {
        seed("a"); seed("b"); seed("c")
        val first = scan()
        assertEquals(setOf("a", "b", "c"), first.keys)
        assertEquals(3, computes.count { it.second == "uncached" })
        computes.clear()
        val second = scan()
        assertEquals(first.mapValues { it.value.fingerprint }, second.mapValues { it.value.fingerprint })
        assertTrue("稳态只做抽检(≤ auditBatch)", computes.all { it.second == "audit" } && computes.size <= 2)
    }

    @Test
    fun `any synced change flips the fingerprint, including ones that do not touch update_at`() {
        seed("a", listOf("one", "two"))
        val base = scan().getValue("a").fingerprint
        fun changed(what: String, sql: String, args: List<Any?> = emptyList()) {
            db.exec(sql, args)
            val now = scan().getValue("a").fingerprint
            assertNotEquals("$what 必须改变指纹", base, now)
            // 还原
            seed("a", listOf("one", "two"))
            db.exec("UPDATE ConversationEntity SET title = 't-a', is_pinned = 0, folder_id = '', config = '', custom_system_prompt = '', suggestions = '[]' WHERE id = 'a'")
            assertEquals("$what 还原后指纹回到原值", base, scan().getValue("a").fingerprint)
        }
        changed("置顶", "UPDATE ConversationEntity SET is_pinned = 1 WHERE id = 'a'")
        changed("标题", "UPDATE ConversationEntity SET title = 'x' WHERE id = 'a'")
        changed("文件夹", "UPDATE ConversationEntity SET folder_id = 'f1' WHERE id = 'a'")
        changed("config", "UPDATE ConversationEntity SET config = '{\"chatModelId\":\"m\"}' WHERE id = 'a'")
        changed("系统提示", "UPDATE ConversationEntity SET custom_system_prompt = 'p' WHERE id = 'a'")
        changed("同字节数的消息改写", "UPDATE message_node SET messages = ? WHERE id = 'a-n0'", listOf("""[{"id":"m0","text":"ONE"}]"""))
        changed("切换分支", "UPDATE message_node SET select_index = 1 WHERE id = 'a-n1'")
        changed("删除节点", "DELETE FROM message_node WHERE id = 'a-n1'")
    }

    @Test
    fun `device-local data does not change the fingerprint`() {
        seed("a")
        db.exec("UPDATE ConversationEntity SET config = '{\"chatModelId\":\"m\",\"workspaceId\":\"w1\"}' WHERE id = 'a'")
        val withWorkspace = scan().getValue("a").fingerprint
        db.exec("UPDATE ConversationEntity SET config = '{\"chatModelId\":\"m\",\"workspaceId\":\"w2\"}', workspace_cwd = '/sdcard/x' WHERE id = 'a'")
        assertEquals(withWorkspace, scan().getValue("a").fingerprint)
        db.exec("UPDATE ConversationEntity SET config = '{\"chatModelId\":\"m\"}' WHERE id = 'a'")
        assertEquals("去掉 workspaceId 等同于没有它", withWorkspace, scan().getValue("a").fingerprint)
    }

    @Test
    fun `deleted conversations vanish from the scan and the cache`() {
        seed("a"); seed("b")
        scan()
        db.exec("DELETE FROM ConversationEntity WHERE id = 'a'")
        assertEquals(setOf("b"), scan().keys)
        assertEquals(setOf("b"), syncDb.listFingerprintIds("conversation").toSet())
    }

    @Test
    fun `a never-synced empty conversation is not reported, but a synced one that became empty is`() {
        TestAndroidDb.insertConversation(db, "empty")
        assertTrue(scan().isEmpty())
        seed("a")
        scan()
        db.exec("DELETE FROM message_node WHERE conversation_id = 'a'")
        assertEquals(setOf("a"), scan().keys) // 变空仍是同一个会话,不能被当成删除
    }

    @Test
    fun `a busy conversation keeps its old fingerprint and its dirty mark survives until it is free`() {
        seed("a")
        // 关掉抽检:这样"空闲后重新发现变化"只能靠被保留下来的脏标记
        index = ConversationIndex(db, syncDb, isBusy = { it in busy }, auditBatch = 0)
        val before = scan().getValue("a").fingerprint
        busy.add("a")
        db.exec("UPDATE ConversationEntity SET title = 'typing' WHERE id = 'a'")
        assertEquals(before, scan().getValue("a").fingerprint)
        busy.clear()
        assertNotEquals(before, scan().getValue("a").fingerprint)
    }

    @Test
    fun `the audit rotation reaches every conversation instead of starving some`() {
        clock = { 1_000L } // 恒定时钟:同一毫秒内全部并列——单调戳不能退化成按 id 排序的饥饿
        for (i in 0 until 6) seed("c$i")
        scan()
        computes.clear()
        repeat(6) { scan() }
        val audited = computes.filter { it.second == "audit" }.map { it.first }.toSet()
        assertEquals((0 until 6).map { "c$it" }.toSet(), audited)
    }

    @Test
    fun `an audit finds a change that bypassed the triggers`() {
        seed("a"); seed("b")
        val before = scan().getValue("a").fingerprint
        db.exec("DROP TRIGGER sync_v2_conv_upd") // 模拟触发器失效
        db.exec("UPDATE ConversationEntity SET title = 'sneaky' WHERE id = 'a'")
        // install() 会在下一次 scan 里把触发器装回来,但这一次写入已经漏了标记——靠抽检兜底
        var found = false
        repeat(4) { if (scan().getValue("a").fingerprint != before) found = true }
        assertTrue(found)
    }

    @Test
    fun `a failure while scanning gives the claimed marks back`() {
        seed("a")
        scan()
        db.exec("UPDATE ConversationEntity SET title = 'x' WHERE id = 'a'")
        val failing = ConversationIndex(db, syncDb, onCompute = { _, _ -> throw IllegalStateException("boom") })
        try {
            runBlocking { failing.scan() }
        } catch (_: IllegalStateException) {
        }
        assertEquals(1, DirtyTracker(db).count())
    }
}
