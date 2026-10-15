package me.rerere.rikkahub.sync

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.sync.host.DirtyTracker
import me.rerere.rikkahub.sync.host.RoomSqlDb
import me.rerere.rikkahub.sync.host.AndroidWireContract
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 任务 A0 的**真机/模拟器部分**(docs/08 A0):在上游真实的 Room 数据库(requery SQLite 驱动、真实 DAO、真实迁移)上验证
 * 同步触发器的相容性。纯 JVM 的 SQL 层部分已由 :rikkasync 的 DirtyTrackerTest 覆盖;这里验证"真的 Room"。
 *
 * 运行:`./gradlew :app:connectedDebugAndroidTest --tests 'me.rerere.rikkahub.sync.*'`(需要设备或模拟器)。
 */
@RunWith(AndroidJUnit4::class)
class SyncRoomCompatTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "sync-a0-test"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @After
    fun cleanup() {
        context.deleteDatabase(name)
        context.deleteDatabase("migration-test")
    }

    private fun conversationRow(id: String, title: String) = arrayOf<Any?>(id, title)

    /** V14 + 第 5 项:Room 真实的 DAO 语句形态(@Insert REPLACE、@Update、删光节点再重插、@Delete)在触发器已装且标记已存在时都不失败。 */
    @Test
    fun realDaosNeverFailWhileDirtyMarksExist() = runBlocking {
        val db = AppDatabaseFactory.create(context, name)
        try {
            val sql = RoomSqlDb(db)
            val tracker = DirtyTracker(sql).also { it.install() }
            val convDao = db.conversationDao()
            val nodeDao = db.messageNodeDao()
            val id = "11111111-1111-4111-8111-111111111111"
            val entity = me.rerere.rikkahub.data.db.entity.ConversationEntity(
                id = id, assistantId = "0950e2dc-9bd5-4801-afa3-aa887aa36b4e", title = "t", nodes = "[]", createAt = 1, updateAt = 1, chatSuggestions = "[]", isPinned = false,
            )
            convDao.insert(entity)
            assertEquals(listOf(id), tracker.claim())
            // 上游 ConversationDAO.insert 是 @Insert(默认 ABORT),不会重复 insert;真实写路径是 @Update 与 message_node 的 REPLACE。
            convDao.update(entity.copy(title = "updated")) // @Update(UPDATE OR ABORT)
            val node = me.rerere.rikkahub.data.db.entity.MessageNodeEntity("n1", id, 0, "[]", 0)
            nodeDao.insertAll(listOf(node))
            nodeDao.insertAll(listOf(node.copy(messages = "[ ]"))) // REPLACE 同主键
            nodeDao.deleteByConversation(id)
            nodeDao.insertAll(listOf(node))
            assertEquals(listOf(id), tracker.claim())
            convDao.delete(entity.copy(title = "x"))
            assertEquals(listOf(id), tracker.claim())
        } finally {
            db.close()
        }
    }

    /** V14:29→30 自动迁移后 sync_dirty 仍在;启动时 DirtyTracker 能恢复 6 个触发器(Room 导出的 schema 不含 sync_dirty,故不用 runMigrationsAndValidate)。 */
    @Test
    fun migrationsSurviveSyncObjectsAndInstallRestoresTriggers() {
        val migrationName = "migration-test-${System.nanoTime()}"
        helper.createDatabase(migrationName, 29).apply {
            execSQL("CREATE TABLE IF NOT EXISTS sync_dirty (entity_id TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, ts INTEGER NOT NULL)")
            execSQL("CREATE TRIGGER IF NOT EXISTS sync_v2_conv_ins AFTER INSERT ON ConversationEntity BEGIN DELETE FROM sync_dirty WHERE entity_id = NEW.id; INSERT INTO sync_dirty VALUES (NEW.id,'conversation',strftime('%s','now')); END")
            close()
        }
        val db = AppDatabaseFactory.create(context, migrationName)
        try {
            assertEquals(AndroidWireContract.SCHEMA_VERSION, db.openHelper.writableDatabase.version)
            db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name='sync_dirty'").use { c ->
                assertTrue(c.moveToFirst())
            }
            val sql = RoomSqlDb(db)
            DirtyTracker(sql).install()
            val triggers = sql.query("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'sync\\_v2\\_%' ESCAPE '\\'") { it.text(0) }
            assertTrue(triggers.toString(), triggers.size == 6)
        } finally {
            db.close()
            context.deleteDatabase(migrationName)
        }
    }
}
