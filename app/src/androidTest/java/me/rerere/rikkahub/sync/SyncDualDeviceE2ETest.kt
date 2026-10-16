package me.rerere.rikkahub.sync

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.sync.engine.Crypto
import me.rerere.rikkahub.sync.engine.SyncClient
import me.rerere.rikkahub.sync.engine.SyncEngine
import me.rerere.rikkahub.sync.host.AndroidConversationHost
import me.rerere.rikkahub.sync.host.ConversationSink
import me.rerere.rikkahub.sync.host.ConversationWrite
import me.rerere.rikkahub.sync.host.DirtyTracker
import me.rerere.rikkahub.sync.host.RoomSqlDb
import me.rerere.rikkahub.sync.state.SyncStateDb
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import kotlinx.serialization.json.jsonPrimitive as jPrim

/** 同进程双库双 token 模拟两台设备(X1 子集)。runner: serverUrl, adminToken;adb reverse 8787。 */
@RunWith(AndroidJUnit4::class)
class SyncDualDeviceE2ETest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private val assistantId = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e"

    private class DirectSqlSink(private val db: me.rerere.rikkahub.sync.host.SqlDb) : ConversationSink {
        override suspend fun save(write: ConversationWrite) {
            val c = write.columns
            fun s(k: String) = c.getValue(k).jPrim.content
            fun n(k: String) = c.getValue(k).jPrim.content.toLong()
            db.transaction {
                db.exec(
                    "INSERT OR REPLACE INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd, folder_id, config) VALUES (?, ?, ?, '[]', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    listOf(write.id, s("assistant_id"), s("title"), n("create_at"), n("update_at"), s("suggestions"), n("is_pinned"), s("custom_system_prompt"), s("mode_injection_ids"), s("lorebook_ids"), write.workspaceCwd, s("folder_id"), s("config")),
                )
                db.exec("DELETE FROM message_node WHERE conversation_id = ?", listOf(write.id))
                for (node in write.nodes) {
                    db.exec("INSERT OR REPLACE INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, ?)", listOf(node.id, write.id, node.nodeIndex, node.messages, node.selectIndex))
                }
            }
        }

        override suspend fun delete(id: String) {
            db.exec("DELETE FROM ConversationEntity WHERE id = ?", listOf(id))
        }
    }

    private data class Peer(val engine: SyncEngine, val sql: RoomSqlDb, val close: () -> Unit)

    private fun deviceToken(base: String, admin: String, name: String): Pair<String, String> {
        val body = buildJsonObject { put("name", name) }.toString().toRequestBody("application/json".toMediaType())
        OkHttpClient().newCall(
            Request.Builder().url("$base/v1/devices").header("authorization", "Bearer $admin").post(body).build(),
        ).execute().use { r ->
            check(r.isSuccessful)
            val j = JsonInstant.parseToJsonElement(r.body!!.string()).jsonObject
            val token = j["token"]!!.jsonPrimitive.content
            val devId = j["device"]!!.jsonObject["id"]!!.jsonPrimitive.content
            return token to devId
        }
    }

    private fun setup(): Pair<SyncRuntime, Peer> = runBlocking {
        val serverUrl = args.getString("serverUrl")!!
        val adminToken = args.getString("adminToken")!!
        val base = serverUrl.trimEnd('/')
        val recovery = Crypto.encodeRecoveryKey(Crypto.generateMasterKey())
        val keys = Crypto.deriveKeys(Crypto.decodeRecoveryKey(recovery))
        val (phoneToken, _) = deviceToken(base, adminToken, "phone-e2e")
        val (peerToken, peerDevId) = deviceToken(base, adminToken, "peer-e2e")
        val runtime = GlobalContext.get().get<SyncRuntime>()
        runtime.config.clearKey()
        runtime.config.importKey(recovery)
        runtime.config.update(enabled = false, serverUrl = base, deviceToken = phoneToken)
        runtime.config.update(enabled = true)
        runtime.reload()
        val peerDb = AppDatabaseFactory.create(app, "peer-${System.nanoTime()}")
        val peerSql = RoomSqlDb(peerDb)
        DirtyTracker(peerSql).install()
        val statePath = File(app.cacheDir, "peer-st-${System.nanoTime()}.db").absolutePath
        val syncDb = SyncStateDb.openOrRecover(AndroidSQLiteDriver(), statePath) { _, _ -> }
        val host = AndroidConversationHost(peerSql, syncDb, DirectSqlSink(peerSql), { false }, "e2e")
        val engine = SyncEngine(SyncClient(base, peerToken), keys, host, syncDb, peerDevId)
        runtime to Peer(engine, peerSql) {
            syncDb.close()
            peerDb.close()
            File(statePath).delete()
        }
    }

    /** Android 落库路径会完整解码消息;message id 必须是标准 UUID(见 android-contract 事故)。 */
    private fun messageJson(messageId: String, text: String) =
        """[{"id":"$messageId","role":"user","parts":[{"type":"text","text":"$text"}],"createdAt":"2026-01-01T00:00:00"}]"""

    private suspend fun seedMain(id: String, title: String, text: String, updateAt: Long, messageId: String, nodeId: String) {
        val db = GlobalContext.get().get<AppDatabase>()
        DirtyTracker(RoomSqlDb(db)).install()
        db.conversationDao().insert(ConversationEntity(id, assistantId, title, "[]", 1, updateAt, "[]", false))
        db.messageNodeDao().insertAll(listOf(MessageNodeEntity(nodeId, id, 0, messageJson(messageId, text), 0)))
    }

    @Test
    fun alignedFirstContact_doesNotDuplicate() = runBlocking {
        assumeTrue(!args.getString("serverUrl").isNullOrBlank() && !args.getString("adminToken").isNullOrBlank())
        val (runtime, peer) = setup()
        try {
            val id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            val messageId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa01"
            val nodeId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa02"
            val msg = messageJson(messageId, "same")
            seedMain(id, "aligned", "same", 1000, messageId, nodeId)
            peer.sql.exec(
                "INSERT OR REPLACE INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd, folder_id, config) VALUES (?, ?, ?, '[]', 1, 1000, '[]', 0, '', '[]', '[]', '', '', '')",
                listOf(id, assistantId, "aligned"),
            )
            peer.sql.exec("INSERT OR REPLACE INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, 0, ?, 0)", listOf(nodeId, id, msg))
            runtime.controller.syncOnceNow()
            peer.engine.syncOnce()
            runtime.controller.syncOnceNow()
            val ids = GlobalContext.get().get<AppDatabase>().conversationDao().getAllIds()
            assertEquals(1, ids.size)
            assertEquals(id, ids.single())
        } finally {
            peer.close()
        }
    }

    @Test
    fun peerTitleEdit_reachesPhoneDatabase() = runBlocking {
        assumeTrue(!args.getString("serverUrl").isNullOrBlank() && !args.getString("adminToken").isNullOrBlank())
        val (runtime, peer) = setup()
        try {
            val id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
            seedMain(id, "phone", "body", 1000, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb01", "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb02")
            runtime.controller.syncOnceNow()
            peer.engine.syncOnce()
            peer.sql.exec("UPDATE ConversationEntity SET title = ?, update_at = ? WHERE id = ?", listOf("from-peer", 9000L, id))
            assertTrue(DirtyTracker(peer.sql).claim().contains(id))
            val push = peer.engine.syncOnce()
            assertTrue("peer push errors: ${push.errors}", push.errors.isEmpty())
            val pull = runtime.controller.syncOnceNow()
            assertTrue("phone pull errors: ${pull.errors}", pull.errors.isEmpty())
            val title = GlobalContext.get().get<AppDatabase>().conversationDao().getConversationById(id)?.title
            assertEquals("from-peer", title)
        } finally {
            peer.close()
        }
    }
}
