package me.rerere.rikkahub.sync.host

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.sync.engine.Crypto
import me.rerere.rikkahub.sync.engine.FirstContactRelation
import me.rerere.rikkahub.sync.engine.LocalRef
import me.rerere.rikkahub.sync.engine.PermanentApplyException
import me.rerere.rikkahub.sync.engine.RemoteSnapshotInfo
import me.rerere.rikkahub.sync.engine.ServerHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 文件夹(A6):两台带 conversation_folder 表的"手机" × 真实引擎 × 真实服务器。 */
class FolderSyncTest {
    private lateinit var server: ServerHarness
    private val keys = Crypto.deriveKeys(Crypto.generateMasterKey())
    private val closers = ArrayList<AutoCloseable>()
    private val assistant = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"

    @Before
    fun setUp() {
        server = ServerHarness.startOrSkip()
    }

    @After
    fun tearDown() {
        closers.reversed().forEach { runCatching { it.close() } }
        if (::server.isInitialized) server.close()
    }

    private fun phone(name: String) = TestPhone(server, keys, name, withFolders = true).also { closers.add(it) }
    private fun run(block: suspend () -> Unit) = runBlocking { block() }
    private suspend fun settle(vararg phones: TestPhone) = repeat(4) { for (p in phones) p.engine.syncOnce() }

    @Test
    fun `a folder created on one phone appears on the other, renames and deletes follow, and nothing echoes`() = run {
        val a = phone("a"); val b = phone("b")
        TestAndroidDb.insertFolder(a.db, "f1", "工作", sortIndex = 2, createAt = 1234)
        settle(a, b)
        assertEquals(FolderRow("f1", assistant, "工作", 2, 1234), b.folders()["f1"])
        val idle = b.engine.syncOnce()
        assertEquals(0, idle.pushed + idle.applied)

        a.db.exec("UPDATE conversation_folder SET name = ? WHERE id = 'f1'", listOf("工作 2"))
        settle(a, b)
        assertEquals("工作 2", b.folders().getValue("f1").name)

        b.db.exec("DELETE FROM conversation_folder WHERE id = 'f1'")
        settle(a, b)
        assertTrue(a.folders().isEmpty())
    }

    @Test
    fun `deleting a folder the way the app does (clearing folder_id) leaves no conflict copies on the other phone`() = run {
        val a = phone("a"); val b = phone("b")
        TestAndroidDb.insertFolder(a.db, "f1", "x")
        a.conv("c1", "hi")
        a.db.exec("UPDATE ConversationEntity SET folder_id = 'f1' WHERE id = 'c1'")
        settle(a, b)
        assertEquals("f1", b.row("c1").folderId)

        // 上游 FolderRepository.deleteFolder:先清会话的 folder_id,再删文件夹行
        a.db.exec("UPDATE ConversationEntity SET folder_id = '' WHERE folder_id = 'f1'")
        a.db.exec("DELETE FROM conversation_folder WHERE id = 'f1'")
        settle(a, b)
        assertTrue(b.folders().isEmpty())
        assertEquals("", b.row("c1").folderId)
        assertEquals("没有产生冲突副本", setOf("c1"), b.ids())
        assertEquals(setOf("c1"), a.ids())
    }

    @Test
    fun `when the folder tombstone arrives before the conversation update the conversation is left alone (no false conflict)`() = run {
        val a = phone("a"); val b = phone("b")
        TestAndroidDb.insertFolder(a.db, "f1", "x")
        a.conv("c1", "hi")
        a.db.exec("UPDATE ConversationEntity SET folder_id = 'f1' WHERE id = 'c1'")
        settle(a, b)
        // 第一轮只让 A 删文件夹(会话的 folder_id 还没改):B 先收到墓碑
        a.db.exec("DELETE FROM conversation_folder WHERE id = 'f1'")
        a.engine.syncOnce()
        b.engine.syncOnce()
        assertTrue(b.folders().isEmpty())
        assertEquals("B 不替 A 清会话的 folder_id", "f1", b.row("c1").folderId)
        // 随后 A 清掉会话的 folder_id 并同步:B 干净地应用,没有冲突副本
        a.db.exec("UPDATE ConversationEntity SET folder_id = '' WHERE id = 'c1'")
        settle(a, b)
        assertEquals("", b.row("c1").folderId)
        assertEquals(setOf("c1"), b.ids())
        assertEquals(setOf("c1"), a.ids())
    }

    @Test
    fun `a folder that exists on both phones before the first sync is adopted when identical and the server wins otherwise`() = run {
        val a = phone("a"); val b = phone("b")
        for (p in listOf(a, b)) TestAndroidDb.insertFolder(p.db, "same", "一样")
        TestAndroidDb.insertFolder(a.db, "diff", "A 的名字")
        TestAndroidDb.insertFolder(b.db, "diff", "B 的名字")
        settle(a, b)
        assertEquals(a.folders(), b.folders())
        assertEquals(setOf("same", "diff"), a.folders().keys)
    }

    @Test
    fun `without a folder sink folder records are skipped, not applied`() = run {
        val a = phone("a")
        val b = TestPhone(server, keys, "b", withFolders = false).also { closers.add(it) }
        TestAndroidDb.insertFolder(a.db, "f1", "x")
        a.conv("c1", "hi")
        settle(a, b)
        assertEquals(setOf("c1"), b.ids())
        assertTrue(b.host.supportSignature() != a.host.supportSignature())
    }

    @Test
    fun `parse accepts the wire shape and rejects malformed data permanently`() {
        val ok = buildJsonObject { put("id", "f"); put("assistant_id", "a"); put("name", "n"); put("sort_index", 3); put("create_at", 9); put("future", "ignored") }
        assertEquals(FolderRow("f", "a", "n", 3, 9), FolderHost.parse("f", ok))
        assertEquals(FolderRow("f", "a", "n", 0, 0), FolderHost.parse("f", buildJsonObject { put("id", "f"); put("assistant_id", "a"); put("name", "n") }))
        for (bad in listOf(
            JsonArray(emptyList()), JsonPrimitive("x"),
            buildJsonObject { put("id", "other"); put("assistant_id", "a"); put("name", "n") },
            buildJsonObject { put("id", "f"); put("name", "n") },
            buildJsonObject { put("id", "f"); put("assistant_id", "a"); put("name", 5) },
            buildJsonObject { put("id", "f"); put("assistant_id", "a"); put("name", "n"); put("sort_index", "three") },
        )) assertThrows(PermanentApplyException::class.java) { FolderHost.parse("f", bad) }
    }

    @Test
    fun `fingerprint covers every column and cannot be fooled by field concatenation`() {
        val base = FolderRow("i", "a", "n", 1, 2)
        val fps = setOf(
            FolderHost.fingerprint(base),
            FolderHost.fingerprint(base.copy(id = "j")), FolderHost.fingerprint(base.copy(assistantId = "b")), FolderHost.fingerprint(base.copy(name = "m")),
            FolderHost.fingerprint(base.copy(sortIndex = 2)), FolderHost.fingerprint(base.copy(createAt = 3)),
        )
        assertEquals(6, fps.size)
        assertNotEquals(FolderHost.fingerprint(FolderRow("i", "ab", "c", 1, 2)), FolderHost.fingerprint(FolderRow("i", "a", "bc", 1, 2)))
        assertEquals(FolderHost.fingerprint(base), FolderHost.fingerprint(base.copy()))
    }

    @Test
    fun `first contact compares content`() = run {
        val a = phone("a")
        TestAndroidDb.insertFolder(a.db, "f1", "n")
        val host = FolderHost(a.db, RawFolderSink(a.db))
        val ref = LocalRef("folder", "f1", "x", null)
        fun info(name: String) = RemoteSnapshotInfo(AndroidWireContract.FMT, FolderHost.toJson(FolderRow("f1", assistant, name, 0, 1)), null)
        assertEquals(FirstContactRelation.SAME, host.compareFirstContact(ref, info("n")))
        assertEquals(FirstContactRelation.REMOTE_NEWER, host.compareFirstContact(ref, info("other")))
    }
}
