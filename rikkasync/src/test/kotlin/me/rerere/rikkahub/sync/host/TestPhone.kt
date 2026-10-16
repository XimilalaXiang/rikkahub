package me.rerere.rikkahub.sync.host

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import me.rerere.rikkahub.sync.engine.Keys
import me.rerere.rikkahub.sync.engine.ServerHarness
import me.rerere.rikkahub.sync.engine.SyncClient
import me.rerere.rikkahub.sync.engine.SyncEngine
import me.rerere.rikkahub.sync.state.SyncStateDb

/** 一台"手机":自己的 Room 同结构数据库 + 同步状态库 + 适配器 + 引擎,连到测试服务器。 */
class TestPhone(server: ServerHarness, keys: Keys, name: String, withAttachments: Boolean = false, withFolders: Boolean = false, withAssistants: Boolean = false) : AutoCloseable {
    val store: TestAttachmentStore? = if (withAttachments) TestAttachmentStore(java.nio.file.Files.createTempDirectory("rks-att-").toFile()) else null
    val assistants: TestAssistantSink? = if (withAssistants) TestAssistantSink() else null
    val db: SqlDb
    private val closeDb: AutoCloseable
    val syncDb: SyncStateDb = SyncStateDb.openOrRecover(BundledSQLiteDriver(), ":memory:")
    val sink: RawRowSink
    val busy = HashSet<String>()
    val host: AndroidConversationHost
    val engine: SyncEngine
    var clock = 10_000L
    private var ids = 0

    init {
        val (d, close) = TestAndroidDb.open()
        db = d
        closeDb = close
        sink = RawRowSink(db)
        host = AndroidConversationHost(db, syncDb, sink, isBusy = { it in busy }, appVersion = "test", attachments = store, folderSink = if (withFolders) RawFolderSink(db) else null, assistantSink = assistants, now = { clock }, newId = { "$name-id-${++ids}" })
        val (token, id) = server.createDevice(name)
        engine = SyncEngine(SyncClient(server.url, token), keys, host, syncDb, id)
    }

    fun conv(id: String, vararg texts: String, title: String = "title-$id", updateAt: Long = 1000) {
        TestAndroidDb.insertConversation(db, id, title = title, updateAt = updateAt)
        texts.forEachIndexed { i, t -> TestAndroidDb.insertNode(db, "$id-n$i", id, i, msg("$id-m$i", t)) }
    }

    private fun msg(id: String, text: String) = """[{"id":"$id","role":"user","parts":[{"type":"text","text":"$text"}],"createdAt":"2026-01-01T00:00:00"}]"""

    fun titles(): Map<String, String> = db.query("SELECT id, title FROM ConversationEntity ORDER BY id") { it.text(0) to it.text(1) }.toMap()
    fun ids() = titles().keys
    fun row(id: String) = ConversationRows.readConversation(db, id)!!
    fun nodes(id: String) = ConversationRows.readNodes(db, id)
    fun folders(): Map<String, FolderRow> = db.query("SELECT id, assistant_id, name, sort_index, create_at FROM conversation_folder ORDER BY id") { FolderRow(it.text(0), it.text(1), it.text(2), it.long(3), it.long(4)) }.associateBy { it.id }
    fun nodeCount() = db.query("SELECT COUNT(*) FROM message_node") { it.long(0).toInt() }.single()

    override fun close() {
        runCatching { syncDb.close() }
        runCatching { closeDb.close() }
        store?.root?.deleteRecursively()
    }
}
