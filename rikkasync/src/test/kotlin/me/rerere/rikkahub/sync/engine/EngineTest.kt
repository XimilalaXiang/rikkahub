package me.rerere.rikkahub.sync.engine

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 复现 TypeScript 的 core/test/engine.test.ts 的每个场景(docs/03 §4 的移植一致性清单),
 * 跑在**真实的**服务器进程上。没有 RIKKASYNC_REPO / bun 时整类跳过。
 */
class EngineTest {
    private lateinit var server: ServerHarness
    private val master = Crypto.generateMasterKey()
    private val keys = Crypto.deriveKeys(master)

    inner class Device(val host: FakeHost, val engine: SyncEngine, val client: SyncClient, val state: MemoryStateStore)

    private fun device(name: String, keys: Keys = this.keys, massDeleteMin: Int = 20): Device {
        val (token, id) = server.createDevice(name)
        val client = SyncClient(server.url, token)
        val host = FakeHost(name)
        val state = MemoryStateStore()
        return Device(host, SyncEngine(client, keys, host, state, id, massDeleteMin = massDeleteMin), client, state)
    }

    private lateinit var a: Device
    private lateinit var b: Device

    @Before
    fun setUp() {
        server = ServerHarness.startOrSkip()
        a = device("phone")
        b = device("pc")
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    private suspend fun settle(vararg devices: Device) {
        repeat(4) { for (d in devices) d.engine.syncOnce() }
    }

    private fun test(block: suspend () -> Unit) = runBlocking { block() }

    // ── 基本收敛 ──

    @Test
    fun `create on A appears on B and edits propagate both ways`() = test {
        a.host.put("n1", "hello", 100)
        settle(a, b)
        assertEquals(mapOf("n1" to "hello"), b.host.texts())
        b.host.put("n1", "hello from pc", 200)
        settle(a, b)
        assertEquals(mapOf("n1" to "hello from pc"), a.host.texts())
    }

    @Test
    fun `applying a remote change is not echoed back`() = test {
        a.host.put("n1", "x", 1)
        a.engine.syncOnce()
        val r1 = b.engine.syncOnce()
        assertEquals(1, r1.applied)
        assertEquals(0, r1.pushed)
        val r2 = b.engine.syncOnce()
        assertEquals(0, r2.pushed)
        assertEquals(0, r2.applied)
    }

    @Test
    fun `idle passes do nothing`() = test {
        a.host.put("n1", "x", 1)
        settle(a, b)
        val r = a.engine.syncOnce()
        assertEquals(0, r.applied); assertEquals(0, r.pushed); assertEquals(0, r.conflicts); assertTrue(r.errors.isEmpty())
    }

    @Test
    fun `local-only data survives a remote apply`() = test {
        a.host.put("n1", "v1", 1)
        settle(a, b)
        b.host.items.getValue("n1").localOnly = "device specific"
        a.host.put("n1", "v2", 2)
        settle(a, b)
        assertEquals("v2", b.host.items.getValue("n1").text)
        assertEquals("device specific", b.host.items.getValue("n1").localOnly)
    }

    // ── 删除 ──

    @Test
    fun `delete propagates`() = test {
        a.host.put("n1", "x", 1)
        settle(a, b)
        a.host.items.remove("n1")
        settle(a, b)
        assertFalse(b.host.items.containsKey("n1"))
    }

    @Test
    fun `edit beats delete - a locally edited entity survives a remote delete and is resurrected`() = test {
        a.host.put("n1", "x", 1)
        settle(a, b)
        a.host.items.remove("n1")
        a.engine.syncOnce() // 服务器上出现墓碑
        b.host.put("n1", "edited on pc", 5) // B 还没见到删除
        settle(a, b)
        assertEquals(mapOf("n1" to "edited on pc"), b.host.texts())
        assertEquals(mapOf("n1" to "edited on pc"), a.host.texts())
    }

    @Test
    fun `a deleted entity can be re-created later with the same id`() = test {
        a.host.put("n1", "x", 1)
        settle(a, b)
        a.host.items.remove("n1")
        settle(a, b)
        a.host.put("n1", "reborn", 9)
        settle(a, b)
        assertEquals(mapOf("n1" to "reborn"), b.host.texts())
    }

    @Test
    fun `mass-delete guard refuses a suspicious wipe`() = test {
        val d = device("guarded", massDeleteMin = 5)
        for (i in 0 until 10) d.host.put("n$i", "t$i", i.toLong())
        d.engine.syncOnce()
        d.host.items.clear() // 比如宿主读数据库失败了
        val r = d.engine.syncOnce()
        assertTrue(r.errors.joinToString().contains("mass_delete_guard"))
        assertEquals(0, r.deletedRemote)
        val fresh = device("fresh")
        fresh.engine.syncOnce()
        assertEquals(10, fresh.host.items.size)
    }

    // ── 冲突 ──

    @Test
    fun `concurrent edits - first pusher wins and the other side keeps a conflict copy`() = test {
        a.host.put("n1", "base", 1)
        settle(a, b)
        a.host.put("n1", "from phone", 10)
        b.host.put("n1", "from pc", 11)
        a.engine.syncOnce() // 手机先推
        b.engine.syncOnce() // 电脑冲突
        settle(a, b)
        assertEquals("from phone", a.host.items.getValue("n1").text)
        assertEquals("from phone", b.host.items.getValue("n1").text)
        val copies = b.host.items.filterKeys { it.contains("conflict") }
        assertEquals(1, copies.size)
        assertTrue(copies.values.first().text.contains("from pc"))
        // 副本也会到达另一台设备:任何地方都没丢东西
        assertTrue(a.host.items.values.any { it.text.contains("from pc") })
    }

    @Test
    fun `first contact - identical content on both sides is adopted without copies`() = test {
        a.host.put("n1", "same", 5)
        b.host.put("n1", "same", 5)
        settle(a, b)
        assertEquals(listOf("n1"), a.host.items.keys.toList())
        assertEquals(listOf("n1"), b.host.items.keys.toList())
    }

    @Test
    fun `first contact - differing content, newer timestamp wins, no copy`() = test {
        a.host.put("n1", "old", 1)
        b.host.put("n1", "new", 2)
        a.engine.syncOnce()
        settle(b, a)
        assertEquals(mapOf("n1" to "new"), a.host.texts())
        assertEquals(mapOf("n1" to "new"), b.host.texts())
    }

    @Test
    fun `first contact - equal timestamps but different content gives a conflict copy`() = test {
        a.host.put("n1", "aaa", 7)
        b.host.put("n1", "bbb", 7)
        a.engine.syncOnce()
        settle(b, a)
        assertEquals(2, a.host.items.size)
        assertEquals(2, b.host.items.size)
    }

    // ── 忙的实体 ──

    @Test
    fun `remote change to a busy entity is held and applied once it is free`() = test {
        a.host.put("n1", "v1", 1)
        settle(a, b)
        a.host.put("n1", "v2", 2)
        a.engine.syncOnce()
        b.host.busy.add("n1")
        val r1 = b.engine.syncOnce()
        assertTrue(r1.deferredBusy > 0)
        assertEquals("v1", b.host.items.getValue("n1").text)
        b.host.busy.remove("n1")
        b.engine.syncOnce()
        assertEquals("v2", b.host.items.getValue("n1").text)
    }

    @Test
    fun `a busy local entity is not pushed`() = test {
        a.host.put("n1", "typing...", 1)
        a.host.busy.add("n1")
        assertEquals(0, a.engine.syncOnce().pushed)
        a.host.busy.clear()
        assertEquals(1, a.engine.syncOnce().pushed)
    }

    // ── 格式支持 ──

    @Test
    fun `unsupported fmt is skipped without data loss and picked up after an upgrade`() = test {
        a.host.fmt = "fake-2"
        a.host.supported.add("fake-2")
        a.host.put("n1", "from the future", 1)
        a.engine.syncOnce()
        val r = b.engine.syncOnce()
        assertEquals(1, r.skippedUnsupported)
        assertEquals(0, b.host.items.size)
        b.engine.syncOnce() // 被跳过的实体绝不会被当作删除推回去
        val fresh = device("fresh")
        fresh.host.supported.add("fake-2")
        fresh.engine.syncOnce()
        assertEquals("from the future", fresh.host.items["n1"]?.text)
        b.host.supported.add("fake-2") // B 升级了
        b.host.signature = "sig-2"
        b.engine.syncOnce()
        assertEquals("from the future", b.host.items["n1"]?.text)
    }

    // ── 附件 ──

    @Test
    fun `blobs travel encrypted and are verified`() = test {
        val png = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
        a.host.put("n1", "with image", 1, mapOf("a.png" to png))
        settle(a, b)
        assertArrayEquals(png, b.host.items.getValue("n1").files.getValue("a.png"))
        val blobId = Crypto.blobId(keys, png)
        val raw = a.client.getBlob(blobId)
        assertFalse("服务器只持有密文", String(raw, Charsets.ISO_8859_1).contains(String(png, Charsets.ISO_8859_1)))
    }

    @Test
    fun `a shared blob is uploaded once`() = test {
        val data = byteArrayOf(9, 9, 9)
        a.host.put("n1", "one", 1, mapOf("x.bin" to data))
        a.host.put("n2", "two", 1, mapOf("y.bin" to data))
        a.engine.syncOnce()
        assertEquals(2L, a.client.info().headSeq) // 两条记录;块不占序号
    }

    // ── 安全 ──

    @Test
    fun `wrong key - records fail to decrypt, are reported and do not wedge the cursor`() = test {
        a.host.put("n1", "secret", 1)
        a.engine.syncOnce()
        val evil = device("evil", keys = Crypto.deriveKeys(Crypto.generateMasterKey()))
        val r = evil.engine.syncOnce()
        assertEquals(1, r.errors.size)
        assertEquals(0, r.applied)
        assertEquals(0, evil.host.items.size)
    }

    @Test
    fun `server cannot swap a record into another id`() = test {
        a.host.put("n1", "one", 1)
        a.host.put("n2", "two", 1)
        a.engine.syncOnce()
        server.swapPayload("n1", "n2")
        val r = b.engine.syncOnce()
        assertTrue(r.errors.joinToString().contains("n2"))
        assertNull(b.host.items["n2"])
        assertEquals("one", b.host.items["n1"]?.text)
    }

    // ── 游标过旧 / 墓碑被清理 ──

    @Test
    fun `after tombstones are purged the client rebuilds without deleting or losing data`() = test {
        a.host.put("keep", "keep", 1)
        a.host.put("gone", "gone", 1)
        settle(a, b)
        a.host.items.remove("gone") // B 离线;A 删除 "gone",墓碑被清理
        a.engine.syncOnce()
        server.purgeTombstones()
        b.host.put("keep", "keep edited", 5) // B 离线时编辑了 "keep"
        val r = b.engine.syncOnce()
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        settle(a, b)
        // B 从未得知删除(墓碑已清理),所以会让 "gone" 复活:安全,什么都没丢
        assertEquals("keep edited", a.host.texts()["keep"])
        assertTrue(b.host.items.containsKey("keep"))
    }

    @Test
    fun `re-creating an entity whose tombstone was purged recovers via base-0 retry`() = test {
        a.host.put("n1", "x", 1)
        settle(a, b)
        a.host.items.remove("n1")
        settle(a, b) // 墓碑 v2 两边都知道
        server.purgeTombstones() // 服务器忘了它
        a.host.put("n1", "reborn", 9) // A 建立在墓碑版本 2 上,服务器现在什么都没有
        val r = a.engine.syncOnce()
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        settle(a, b)
        assertEquals(mapOf("n1" to "reborn"), b.host.texts())
    }

    // ── 有损表示下的首次接触 ──

    @Test
    fun `same lineage with different serialization is adopted, not duplicated`() = test {
        for (i in 0 until 5) {
            a.host.put("n$i", "text $i", 1000L + i)
            b.host.put("n$i", "text $i", 1000L + i)
        }
        a.host.extraField = true // 手机序列化带有桌面产生不了的 Android 独有字段
        settle(a, b)
        assertEquals(5, a.host.items.size)
        assertEquals(5, b.host.items.size)
        assertFalse(a.host.items.keys.any { it.contains("conflict") })
        assertFalse(b.host.items.keys.any { it.contains("conflict") })
    }

    @Test
    fun `first contact without a host comparator falls back to equality then timestamps`() = test {
        a.host.hasComparator = false
        b.host.hasComparator = false
        a.host.put("same", "x", 5)
        b.host.put("same", "x", 5)
        a.host.put("older", "old", 1)
        b.host.put("older", "new", 2)
        a.engine.syncOnce()
        settle(b, a)
        assertEquals(mapOf("same" to "x", "older" to "new"), a.host.texts())
        assertEquals(mapOf("same" to "x", "older" to "new"), b.host.texts())
    }

    // ── 一个坏实体不能挡住其它 ──

    @Test
    fun `a read that throws is reported per entity and every other entity still syncs`() = test {
        a.host.put("good1", "one", 1)
        a.host.put("bad", "boom", 1)
        a.host.put("good2", "two", 1)
        a.host.failReadFor = "bad"
        val r = a.engine.syncOnce()
        assertTrue(r.errors.joinToString().contains("bad"))
        assertEquals(2, r.pushed)
        b.engine.syncOnce()
        assertEquals(listOf("good1", "good2"), b.host.texts().keys.sorted())
        // 坏的那个仍是脏的,宿主修好后下一轮重试成功
        a.host.failReadFor = null
        val r2 = a.engine.syncOnce()
        assertTrue(r2.errors.isEmpty())
        assertEquals(1, r2.pushed)
        b.engine.syncOnce()
        assertEquals("boom", b.host.texts()["bad"])
    }

    @Test
    fun `a blob that cannot be read fails only its own entity`() = test {
        a.host.put("withfile", "has file", 1, mapOf("x.bin" to byteArrayOf(1, 2, 3)))
        a.host.put("plain", "no file", 1)
        a.host.failBlobReadFor = "withfile"
        val r = a.engine.syncOnce()
        assertTrue(r.errors.joinToString().contains("withfile"))
        assertEquals(1, r.pushed)
    }

    // ── 永久性坏记录 ──

    @Test
    fun `a permanently bad remote record is skipped, the cursor advances and later records still sync`() = test {
        a.host.put("a-first", "one", 1)
        a.engine.syncOnce()
        a.host.put("poison", "bad", 2)
        a.engine.syncOnce()
        a.host.put("z-last", "three", 3)
        a.engine.syncOnce()
        b.host.permanentApplyFailFor = "poison"
        val r = b.engine.syncOnce()
        assertTrue(r.errors.joinToString().contains("poison"))
        assertEquals(listOf("a-first", "z-last"), b.host.texts().keys.sorted()) // 后面的记录没被挡住
        assertTrue("不会永远重试:第二轮是安静的", b.engine.syncOnce().errors.isEmpty())
        a.engine.syncOnce() // 被跳过的记录绝不会被当作删除推回去
        assertTrue(a.host.items.containsKey("poison"))
    }

    @Test
    fun `a transient apply error still holds the cursor and is retried`() = test {
        a.host.put("flaky", "x", 1)
        a.host.put("after", "y", 2)
        a.engine.syncOnce()
        b.host.transientApplyFailFor = "flaky"
        b.engine.syncOnce()
        assertFalse(b.host.items.containsKey("flaky"))
        b.host.transientApplyFailFor = null
        b.engine.syncOnce()
        assertEquals(mapOf("flaky" to "x", "after" to "y"), b.host.texts())
    }

    @Test
    fun `sanity - the harness is a real server`() = test {
        assertNotNull(a.client.info().deviceId)
    }
}
