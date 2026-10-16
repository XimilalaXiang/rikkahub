package me.rerere.rikkahub.sync.state

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import me.rerere.rikkahub.sync.engine.EntityState
import me.rerere.rikkahub.sync.engine.MemoryStateStore
import me.rerere.rikkahub.sync.engine.StateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** StateStore 的同一套契约测试跑两种实现(内存 / SQLite),再加 SQLite 独有的持久化、重置、损坏恢复、版本检查。 */
class SyncStateDbTest {
    private val dirs = ArrayList<File>()
    private val dbs = ArrayList<AutoCloseable>()

    @After
    fun cleanup() {
        dbs.forEach { runCatching { it.close() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun tmp(): File = Files.createTempDirectory("rks-state-").toFile().also { dirs.add(it) }
    private fun open(path: File = File(tmp(), "s.db")) = SyncStateDb.openOrRecover(BundledSQLiteDriver(), path.absolutePath).also { dbs.add(it) }

    private val implementations: Map<String, () -> StateStore> = mapOf("memory" to { MemoryStateStore() }, "sqlite" to { open() })

    private fun forEachImpl(block: (StateStore) -> Unit) = implementations.forEach { (name, make) -> runCatching { block(make()) }.onFailure { throw AssertionError("[$name] ${it.message}", it) } }

    @Test
    fun `cursor and entity states round trip`() = forEachImpl { s ->
        assertEquals(0L, s.getCursor())
        s.setCursor(42)
        assertEquals(42L, s.getCursor())
        assertNull(s.get("conversation", "c1"))
        s.set("conversation", "c1", EntityState(3, 7, "fp", deleted = false, skipped = false))
        s.set("conversation", "c2", EntityState(1, 8, "", deleted = true))
        s.set("conversation", "c3", EntityState(2, 9, "x", skipped = true))
        assertEquals(EntityState(3, 7, "fp"), s.get("conversation", "c1"))
        assertTrue(s.get("conversation", "c2")!!.deleted)
        assertTrue(s.get("conversation", "c3")!!.skipped)
        s.set("conversation", "c1", EntityState(4, 10, "fp2"))
        assertEquals(EntityState(4, 10, "fp2"), s.get("conversation", "c1"))
        assertEquals(listOf("c1", "c2", "c3"), s.entries().map { it.id })
        s.delete("conversation", "c2")
        assertNull(s.get("conversation", "c2"))
    }

    @Test
    fun `ids with odd characters are safe`() = forEachImpl { s ->
        val id = "a'b\"c;--\u0000x/ü中"
        s.set("conversation", id, EntityState(1, 1, "f"))
        assertEquals(EntityState(1, 1, "f"), s.get("conversation", id))
    }

    @Test
    fun `meta round trips and reset forgets everything`() = forEachImpl { s ->
        s.setMeta("support_sig", "a")
        s.setMeta("support_sig", "b")
        assertEquals("b", s.getMeta("support_sig"))
        assertNull(s.getMeta("nope"))
        s.setCursor(5)
        s.set("conversation", "c1", EntityState(1, 1, "f"))
        s.reset()
        assertEquals(0L, s.getCursor())
        assertNull(s.get("conversation", "c1"))
        assertNull(s.getMeta("support_sig"))
        assertTrue(s.entries().isEmpty())
    }

    @Test
    fun `state survives reopening the file`() {
        val file = File(tmp(), "s.db")
        val a = open(file)
        a.setCursor(99)
        a.set("conversation", "c1", EntityState(2, 5, "fp"))
        a.setFingerprint("conversation", "c1", "content", 123)
        a.close()
        val b = open(file)
        assertEquals(99L, b.getCursor())
        assertEquals(EntityState(2, 5, "fp"), b.get("conversation", "c1"))
        assertEquals("content", b.getFingerprint("conversation", "c1"))
    }

    @Test
    fun `fingerprint cache queries and reset clears it`() {
        val db = open()
        db.setFingerprint("conversation", "a", "fa", 30)
        db.setFingerprint("conversation", "b", "fb", 10)
        db.setFingerprint("conversation", "c", "fc", 20)
        db.setFingerprint("other", "z", "fz", 1)
        assertEquals(listOf("b", "c"), db.oldestVerified("conversation", 2))
        assertEquals(30L, db.maxVerifiedAt("conversation"))
        assertEquals(3, db.countFingerprints("conversation"))
        assertEquals(setOf("a", "b", "c"), db.listFingerprintIds("conversation").toSet())
        db.setFingerprint("conversation", "a", "fa2", 5)
        assertEquals("fa2", db.getFingerprint("conversation", "a"))
        assertEquals("a", db.oldestVerified("conversation", 1).single())
        db.deleteFingerprint("conversation", "a")
        assertNull(db.getFingerprint("conversation", "a"))
        db.reset()
        assertEquals(0, db.countFingerprints("conversation"))
        assertEquals(0L, db.maxVerifiedAt("conversation"))
    }

    @Test
    fun `a corrupt file is quarantined and rebuilt`() {
        val dir = tmp()
        val file = File(dir, "s.db")
        file.writeText("this is definitely not a sqlite database ".repeat(100))
        var recovered: String? = null
        val db = SyncStateDb.openOrRecover(BundledSQLiteDriver(), file.absolutePath) { _, q -> recovered = q }.also { dbs.add(it) }
        assertEquals(0L, db.getCursor())
        db.setCursor(1)
        assertEquals(1L, db.getCursor())
        assertTrue(recovered != null && File(recovered!!).exists())
        assertTrue(dir.listFiles()!!.any { it.name.contains(".corrupt-") })
    }

    @Test
    fun `a database whose header is fine but whose pages are damaged is caught by the integrity check`() {
        val file = File(tmp(), "s.db")
        val first = open(file)
        repeat(500) { first.set("conversation", "c$it", EntityState(1, it.toLong(), "x".repeat(200))) }
        first.reset() // 触发 WAL 检查点前先让数据落到主文件
        repeat(500) { first.set("conversation", "c$it", EntityState(1, it.toLong(), "y".repeat(200))) }
        first.close()
        val bytes = file.readBytes()
        check(bytes.size > 3 * 4096) { "test db too small: ${bytes.size}" }
        for (i in 2 * 4096 until 3 * 4096) bytes[i] = 0x5A // 第 3 页整页损坏,第 1 页(表头与 schema)完好
        file.writeBytes(bytes)
        var quarantined: String? = null
        val db = SyncStateDb.openOrRecover(BundledSQLiteDriver(), file.absolutePath) { _, q -> quarantined = q }.also { dbs.add(it) }
        assertTrue("损坏页必须被完整性检查发现并重建", quarantined != null)
        assertEquals(0, db.entries().size)
    }

    @Test
    fun `a database from a newer app is refused and left untouched`() {
        val file = File(tmp(), "s.db")
        val raw = BundledSQLiteDriver().open(file.absolutePath)
        raw.prepare("PRAGMA user_version = 99").also { it.step(); it.close() }
        raw.close()
        val ex = assertThrows(SyncDbTooNewException::class.java) { SyncStateDb.openOrRecover(BundledSQLiteDriver(), file.absolutePath) }
        assertEquals(99, ex.found)
        assertTrue(file.exists())
        assertFalse(file.parentFile.listFiles()!!.any { it.name.contains(".corrupt-") })
    }

    @Test
    fun `concurrent writers do not corrupt state`() {
        val db = open()
        val threads = (0 until 4).map { t -> Thread { repeat(100) { i -> db.set("conversation", "t$t-$i", EntityState(i.toLong(), i.toLong(), "f")) } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(400, db.entries().size)
    }

    @Test
    fun `file to blob cache only hits for the exact size and mtime and is cleared by reset`() {
        val db = open()
        db.setFileBlob("upload/a.png", 10, 100, "b1")
        assertEquals("b1", db.getFileBlob("upload/a.png", 10, 100))
        assertNull(db.getFileBlob("upload/a.png", 11, 100))
        assertNull(db.getFileBlob("upload/a.png", 10, 101))
        db.setFileBlob("upload/a.png", 11, 100, "b2") // 文件被改写:覆盖旧条目
        assertNull(db.getFileBlob("upload/a.png", 10, 100))
        db.setBlob("b2", "upload/a.png")
        assertEquals("upload/a.png", db.localRefOf("b2"))
        db.reset()
        assertNull(db.getFileBlob("upload/a.png", 11, 100))
        assertNull(db.localRefOf("b2"))
    }
}
