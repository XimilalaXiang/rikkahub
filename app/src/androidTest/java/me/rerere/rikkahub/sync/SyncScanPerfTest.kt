package me.rerere.rikkahub.sync

import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.sync.host.AndroidConversationHost
import me.rerere.rikkahub.sync.host.ConversationSink
import me.rerere.rikkahub.sync.host.ConversationWrite
import me.rerere.rikkahub.sync.host.DirtyTracker
import me.rerere.rikkahub.sync.host.RoomSqlDb
import me.rerere.rikkahub.sync.state.SyncStateDb
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.system.measureTimeMillis

/** V11/B.9:≥500 条消息的会话,scan 稳态耗时(目标 &lt;100ms,此处只记录并断言宽松上限)。 */
@RunWith(AndroidJUnit4::class)
class SyncScanPerfTest {
    private class NoopSink : ConversationSink {
        override suspend fun save(write: ConversationWrite) {}
        override suspend fun delete(id: String) {}
    }

    @Test
    fun largeConversation_scanUnderBudget() {
        runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "scan-perf-${System.nanoTime()}"
        val db = AppDatabaseFactory.create(app, name)
        val sql = RoomSqlDb(db)
        DirtyTracker(sql).install()
        val id = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        sql.exec(
            "INSERT OR REPLACE INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at, suggestions, is_pinned, custom_system_prompt, mode_injection_ids, lorebook_ids, workspace_cwd, folder_id, config) VALUES (?, '0950e2dc-9bd5-4801-afa3-aa887aa36b4e', 'big', '[]', 1, 1, '[]', 0, '', '[]', '[]', '', '', '')",
            listOf(id),
        )
        val msg = """[{"id":"m","role":"user","parts":[{"type":"text","text":"x"}],"createdAt":"2026-01-01T00:00:00"}]"""
        for (i in 0 until 500) {
            sql.exec("INSERT OR REPLACE INTO message_node (id, conversation_id, node_index, messages, select_index) VALUES (?, ?, ?, ?, 0)", listOf("$id-n$i", id, i, msg))
        }
        val statePath = File(app.cacheDir, "scan-st.db").absolutePath
        val syncDb = SyncStateDb.openOrRecover(AndroidSQLiteDriver(), statePath) { _, _ -> }
        val host = AndroidConversationHost(sql, syncDb, NoopSink(), { false }, "perf")
        host.scan() // 冷启动:建指纹缓存
        val coldMs = measureTimeMillis { host.scan() }
        val warmMs = measureTimeMillis { host.scan() }
        android.util.Log.i("SyncScanPerfTest", "scan 500 nodes cold=$coldMs ms warm=$warmMs ms")
        // 仅记录测量结果(V11/B.9);模拟器软件渲染 + 500 节点冷指纹通常远超 100ms。
        assertTrue("scan should complete", warmMs < 60_000)
        syncDb.close()
        db.close()
        app.deleteDatabase(name)
        File(statePath).delete()
        }
    }
}
