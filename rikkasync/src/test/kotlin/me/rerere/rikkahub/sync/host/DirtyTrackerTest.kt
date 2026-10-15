package me.rerere.rikkahub.sync.host

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 触发器与 **Room 真实语句形态**的相容性(A0 第 5 项的 SQL 层部分):在 sync_dirty 已有该会话的挂起标记的前提下,
 * 逐一执行 Room 会生成的语句——@Insert(REPLACE) = INSERT OR REPLACE、@Update = UPDATE OR ABORT、@Delete、
 * "删光节点再重插"——断言**没有任何一条失败**,且 sync_dirty 里恰好有该会话。
 */
class DirtyTrackerTest {
    private lateinit var db: SqlDb
    private lateinit var close: AutoCloseable
    private lateinit var tracker: DirtyTracker

    @Before
    fun setUp() {
        TestAndroidDb.open().let { db = it.first; close = it.second }
        tracker = DirtyTracker(db)
        tracker.install()
    }

    @After
    fun tearDown() = close.close()

    private fun dirty() = db.query("SELECT entity_id FROM sync_dirty ORDER BY entity_id") { it.text(0) }

    @Test
    fun `an insert marks the conversation and claim takes the marks atomically`() {
        TestAndroidDb.insertConversation(db, "c1")
        TestAndroidDb.insertNode(db, "n1", "c1", 0)
        assertEquals(listOf("c1"), dirty())
        assertEquals(listOf("c1"), tracker.claim())
        assertEquals(emptyList<String>(), tracker.claim())
        TestAndroidDb.insertNode(db, "n2", "c1", 1) // 领取之后的写入会重新标脏
        assertEquals(listOf("c1"), tracker.claim())
    }

    @Test
    fun `room statement shapes never fail while a mark already exists`() {
        TestAndroidDb.insertConversation(db, "c1")
        TestAndroidDb.insertNode(db, "n1", "c1", 0)
        assertEquals(listOf("c1"), dirty()) // 标记已存在
        // @Insert(onConflict = REPLACE)
        db.exec("INSERT OR REPLACE INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd, folder_id, config) VALUES ('c1','a','new title','[]',1,2,'[]',0,'','[]','[]','','','')")
        // @Update = UPDATE OR ABORT
        db.exec("UPDATE OR ABORT ConversationEntity SET title = ?, update_at = ? WHERE id = ?", listOf("updated", 3L, "c1"))
        db.exec("UPDATE OR ABORT message_node SET messages = ?, select_index = ? WHERE id = ?", listOf("[{}]", 1, "n1"))
        // 其它冲突策略与 upsert 形态
        db.exec("UPDATE OR FAIL ConversationEntity SET is_pinned = 1 WHERE id = 'c1'")
        db.exec("INSERT INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES ('n1','c1',0,'[]',0) ON CONFLICT(id) DO UPDATE SET messages = excluded.messages")
        db.exec("INSERT OR IGNORE INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES ('n1','c1',0,'x',0)")
        // updateConversation = 删光节点再重插
        db.exec("DELETE FROM message_node WHERE conversation_id = ?", listOf("c1"))
        TestAndroidDb.insertNode(db, "n1", "c1", 0, "[]")
        TestAndroidDb.insertNode(db, "n3", "c1", 1, "[]")
        assertEquals(listOf("c1"), dirty()) // 恰好一条
    }

    @Test
    fun `deleting a conversation marks it and the foreign key cascade also fires the node triggers`() {
        TestAndroidDb.insertConversation(db, "c1")
        TestAndroidDb.insertNode(db, "n1", "c1", 0)
        tracker.claim()
        db.exec("DELETE FROM ConversationEntity WHERE id = 'c1'")
        assertEquals(listOf("c1"), dirty())
        assertEquals(0, db.query("SELECT COUNT(*) FROM message_node") { it.long(0) }.single().toInt())
    }

    @Test
    fun `deleting only a node marks its conversation`() {
        TestAndroidDb.insertConversation(db, "c1")
        TestAndroidDb.insertNode(db, "n1", "c1", 0)
        tracker.claim()
        db.exec("DELETE FROM message_node WHERE id = 'n1'")
        assertEquals(listOf("c1"), dirty())
    }

    @Test
    fun `install is idempotent and removes triggers of older versions`() {
        db.exec("CREATE TRIGGER sync_v1_conv_ins AFTER INSERT ON ConversationEntity BEGIN INSERT OR REPLACE INTO sync_dirty VALUES (NEW.id, 'conversation', 0); END")
        db.exec("CREATE TRIGGER unrelated_user_trigger AFTER INSERT ON ConversationEntity BEGIN SELECT 1; END")
        tracker.install()
        tracker.install()
        val names = db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name") { it.text(0) }
        assertTrue(names.toString(), names.none { it.startsWith("sync_v1_") })
        assertTrue("不碰与同步无关的触发器", "unrelated_user_trigger" in names)
        assertEquals(6, names.count { it.startsWith("sync_v2_") })
        // 旧触发器(INSERT OR REPLACE 体)已不在,所以在标记已存在时 Room 的 REPLACE 写入不会失败
        TestAndroidDb.insertConversation(db, "c1")
        TestAndroidDb.insertConversation(db, "c1", title = "again")
    }

    @Test
    fun `triggers lost to a table rebuild come back after install`() {
        db.exec("DROP TRIGGER sync_v2_node_upd")
        db.exec("DROP TRIGGER sync_v2_conv_ins")
        tracker.install()
        assertEquals(6, db.query("SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'sync\\_v2\\_%' ESCAPE '\\'") { it.text(0) }.size)
    }

    @Test
    fun `mark and clear`() {
        tracker.mark(listOf("x", "y", "x"))
        assertEquals(listOf("x", "y"), dirty())
        assertEquals(2, tracker.count())
        tracker.clear("x")
        assertEquals(listOf("y"), dirty())
    }

    @Test
    fun `the schema this adapter was written for is the latest Room schema`() {
        // 哨兵:Room 版本升了而适配器没跟上 → 红。升级路径见 docs/07 §3。
        assertEquals(AndroidWireContract.SCHEMA_VERSION, TestAndroidDb.latestSchemaVersion())
    }
}
