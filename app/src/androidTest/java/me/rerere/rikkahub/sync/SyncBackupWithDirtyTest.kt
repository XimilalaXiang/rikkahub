package me.rerere.rikkahub.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.AppDatabaseFactory
import me.rerere.rikkahub.data.sync.BackupManager
import me.rerere.rikkahub.data.sync.DatabaseBackup
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.sync.host.DirtyTracker
import me.rerere.rikkahub.sync.host.RoomSqlDb
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

/** V14:含 sync_dirty 与触发器的活库能否备份/恢复;备份 zip 不含同步密钥。 */
@RunWith(AndroidJUnit4::class)
class SyncBackupWithDirtyTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory: File
    private lateinit var database: AppDatabase
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "sync-backup-dirty-").toFile()
        database = AppDatabaseFactory.create(app, "sync-backup-dirty-${System.nanoTime()}")
        val sql = RoomSqlDb(database)
        DirtyTracker(sql).install()
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_dirty(entity_id, kind, ts) VALUES ('probe-id','conversation',1)",
        )
        manager = BackupManager(app, database, GlobalContext.get().get<SettingsStore>(), JsonInstant)
    }

    @After
    fun tearDown() {
        database.close()
        directory.deleteRecursively()
    }

    @Test
    fun databaseSnapshotKeepsSyncDirtyTable() {
        val snapshot = File(directory, "snap.db")
        DatabaseBackup.createSnapshot(database.openHelper.writableDatabase, snapshot)
        val dbName = "snap-probe-${System.nanoTime()}"
        snapshot.copyTo(app.getDatabasePath(dbName), overwrite = true)
        val probe = AppDatabaseFactory.create(app, dbName)
        try {
            probe.openHelper.writableDatabase.query("SELECT entity_id FROM sync_dirty").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.getString(0)!!.contains("probe"))
            }
        } finally {
            probe.close()
        }
        app.deleteDatabase(dbName)
    }

    @Test
    fun backupZipDoesNotContainDeviceTokenOrRecoveryKey() = runBlocking {
        val syncDir = File(app.noBackupFilesDir, "sync-test-scan-${System.nanoTime()}").apply { mkdirs() }
        syncDir.resolve("config.json").writeText("""{"enabled":true,"serverUrl":"http://127.0.0.1:8787"}""")
        syncDir.resolve("secrets.bin").writeBytes("fake-sealed-secret".toByteArray())
        try {
            val archive = manager.createBackup(includeDatabase = true, includeFiles = false)
            val haystack = buildString {
                ZipFile(archive).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        haystackAppend(entry.name)
                        zip.getInputStream(entry).use { input ->
                            haystackAppend(input.readBytes().decodeToString())
                        }
                    }
                }
                haystackAppend(database.openHelper.writableDatabase.path.orEmpty())
            }
            assertFalse(haystack.contains("fake-sealed-secret"))
            assertFalse(haystack.contains("device-token-placeholder"))
        } finally {
            syncDir.deleteRecursively()
        }
    }

    private fun StringBuilder.haystackAppend(text: String) {
        if (text.isNotEmpty()) append(text).append('\n')
    }
}
