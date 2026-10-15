package me.rerere.rikkahub.sync.controller

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import me.rerere.rikkahub.sync.engine.Crypto
import me.rerere.rikkahub.sync.engine.MemoryStateStore
import me.rerere.rikkahub.sync.engine.ServerHarness
import me.rerere.rikkahub.sync.engine.SyncClient
import me.rerere.rikkahub.sync.engine.SyncEngine
import me.rerere.rikkahub.sync.host.AndroidConversationHost
import me.rerere.rikkahub.sync.host.DirtyTracker
import me.rerere.rikkahub.sync.host.RawRowSink
import me.rerere.rikkahub.sync.host.SqlDb
import me.rerere.rikkahub.sync.host.TestAndroidDb
import me.rerere.rikkahub.sync.state.SecretBox
import me.rerere.rikkahub.sync.state.SyncConfigStore
import me.rerere.rikkahub.sync.state.SyncStateDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SyncControllerTest {
    private lateinit var server: ServerHarness
    private lateinit var root: File
    private val closers = ArrayList<AutoCloseable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private object PlainBox : SecretBox {
        override fun seal(plain: ByteArray) = plain
        override fun open(sealed: ByteArray) = sealed
    }

    @Before
    fun setUp() {
        server = ServerHarness.startOrSkip()
        root = Files.createTempDirectory("rks-ctl-").toFile()
    }

    @After
    fun tearDown() {
        scope.cancel()
        closers.reversed().forEach { runCatching { it.close() } }
        if (::server.isInitialized) server.close()
        if (::root.isInitialized) root.deleteRecursively()
    }

    inner class Env(poll: Long = 40, heartbeat: Long = 400) {
        val config = SyncConfigStore(File(root, "sync"), PlainBox)
        val recovery: String = config.generateKey()
        val pcToken = server.createDevice("pc-${System.nanoTime()}").first
        val db: SqlDb
        val sink: RawRowSink
        val stateFile = File(root, "state.db")
        val logs = ArrayList<String>()
        val controller: SyncController

        init {
            val (d, close) = TestAndroidDb.open()
            db = d
            closers.add(close)
            sink = RawRowSink(db)
            config.update(enabled = true, serverUrl = server.url, deviceToken = pcToken)
            controller = SyncController(
                scope, config,
                openStateDb = { SyncStateDb.openOrRecover(BundledSQLiteDriver(), stateFile.absolutePath) },
                createHost = { sdb -> AndroidConversationHost(db, sdb, sink, { false }, "test") },
                pendingLocalChanges = { runCatching { DirtyTracker(db).count() }.getOrNull() },
                options = SyncControllerOptions(pollMs = poll, heartbeatMs = heartbeat, debounceMs = 5, baseBackoffMs = 30, maxBackoffMs = 120),
                log = { logs.add(it) },
            )
        }

        /** 另一台"手机":用同一把密钥,直接用引擎操作。 */
        fun otherPhone(): Pair<SyncEngine, SqlDb> {
            val (d, close) = TestAndroidDb.open()
            closers.add(close)
            val sdb = SyncStateDb.openOrRecover(BundledSQLiteDriver(), ":memory:").also { closers.add(it) }
            val (token, id) = server.createDevice("other-${System.nanoTime()}")
            val host = AndroidConversationHost(d, sdb, RawRowSink(d), { false }, "test")
            return SyncEngine(SyncClient(server.url, token), Crypto.deriveKeys(Crypto.decodeRecoveryKey(recovery)), host, sdb, id) to d
        }
    }

    private fun seed(db: SqlDb, id: String, title: String = "t-$id") {
        TestAndroidDb.insertConversation(db, id, title = title)
        TestAndroidDb.insertNode(db, "$id-n", id, 0, """[{"id":"m-$id","role":"user","parts":[{"type":"text","text":"hi"}]}]""")
    }

    private fun titles(db: SqlDb) = db.query("SELECT id, title FROM ConversationEntity ORDER BY id") { it.text(0) to it.text(1) }.toMap()

    private suspend fun waitFor(what: String, timeoutMs: Long = 6000, pred: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!pred()) {
            if (System.currentTimeMillis() - t0 > timeoutMs) throw AssertionError("等待超时: $what")
            delay(10)
        }
    }

    private fun test(block: suspend () -> Unit) = runBlocking { block() }

    @Test
    fun `disabled and unconfigured phases`() = test {
        val e = Env()
        e.config.update(enabled = false)
        e.controller.start()
        assertEquals(SyncPhase.DISABLED, e.controller.status.value.phase)
        e.config.update(enabled = true, deviceToken = "")
        e.config.clearKey(); e.config.update(enabled = true)
        e.controller.start()
        assertEquals(SyncPhase.UNCONFIGURED, e.controller.status.value.phase)
        assertThrows(IllegalStateException::class.java) { runBlocking { e.controller.runNow() } }
    }

    @Test
    fun `connects, pulls from another phone and goes idle`() = test {
        val e = Env()
        val (other, otherDb) = e.otherPhone()
        seed(otherDb, "c1")
        other.syncOnce()
        e.controller.start()
        waitFor("conversation arrives") { titles(e.db).size == 1 }
        waitFor("idle") { e.controller.status.value.phase == SyncPhase.IDLE }
        val s = e.controller.status.value
        assertNotNull(s.deviceId)
        assertNotNull(s.lastSyncAt)
        assertNull(s.lastError)
        assertEquals(1, s.lastApplied.coerceAtLeast(1))
    }

    @Test
    fun `the push channel wakes the controller even with polling and heartbeat effectively off`() = test {
        val e = Env(poll = 60_000, heartbeat = 600_000)
        e.controller.start()
        waitFor("live") { e.controller.status.value.live }
        val (other, otherDb) = e.otherPhone()
        seed(otherDb, "c1")
        other.syncOnce()
        waitFor("arrives via the push channel", 3000) { titles(e.db).size == 1 }
    }

    @Test
    fun `local edits are noticed through the dirty marks and pushed`() = test {
        val e = Env(poll = 40, heartbeat = 600_000) // 心跳关掉:只能靠脏标记发现本地改动
        val (other, otherDb) = e.otherPhone()
        seed(e.db, "c1")
        e.controller.start()
        waitFor("idle") { e.controller.status.value.phase == SyncPhase.IDLE && e.controller.status.value.live }
        e.db.exec("UPDATE ConversationEntity SET title = 'edited here' WHERE id = 'c1'")
        var seen = false
        for (i in 0 until 100) {
            other.syncOnce()
            if (titles(otherDb)["c1"] == "edited here") { seen = true; break }
            delay(30)
        }
        assertTrue(seen)
    }

    @Test
    fun `runNow includes changes made just before the call`() = test {
        val e = Env(poll = 60_000, heartbeat = 600_000)
        e.controller.start()
        waitFor("idle") { e.controller.status.value.phase == SyncPhase.IDLE }
        seed(e.db, "new")
        val report = e.controller.runNow()
        assertEquals(1, report.pushed)
    }

    @Test
    fun `a wrong token gives a readable error and fixing the config recovers`() = test {
        val e = Env()
        val good = e.config.get().deviceToken
        e.config.update(deviceToken = "wrong")
        e.controller.start()
        waitFor("error") { e.controller.status.value.phase == SyncPhase.ERROR }
        assertEquals("设备令牌无效或已被撤销", e.controller.status.value.lastError)
        e.config.update(deviceToken = good)
        e.controller.start()
        waitFor("recovered") { e.controller.status.value.phase == SyncPhase.IDLE }
        assertNull(e.controller.status.value.lastError)
    }

    @Test
    fun `an unreachable server backs off and keeps retrying`() = test {
        val e = Env()
        e.config.update(serverUrl = "http://127.0.0.1:1")
        e.controller.start()
        waitFor("error") { e.controller.status.value.phase == SyncPhase.ERROR }
        assertThrows(Exception::class.java) { runBlocking { e.controller.runNow() } }
    }

    @Test
    fun `testConnection reports success and readable failures without touching state`() = test {
        val e = Env()
        assertTrue(e.controller.testConnection() is ConnectionTest.Ok)
        assertEquals(ConnectionTest.Failed("设备令牌无效或已被撤销"), e.controller.testConnection(deviceToken = "nope"))
        assertTrue(e.controller.testConnection(serverUrl = "http://127.0.0.1:1") is ConnectionTest.Failed)
        assertEquals(SyncPhase.DISABLED, e.controller.status.value.phase)
    }

    @Test
    fun `switching to another server resets the sync state`() = test {
        val e = Env()
        seed(e.db, "c1")
        e.controller.start()
        waitFor("synced") { e.controller.status.value.lastPushed == 1 }
        e.controller.stop()
        val other = ServerHarness.startOrSkip().also { closers.add(it) }
        e.config.update(serverUrl = other.url, deviceToken = other.createDevice("pc").first)
        e.controller.start()
        waitFor("idle on the new server") { e.controller.status.value.phase == SyncPhase.IDLE }
        assertTrue(e.logs.any { it.contains("服务器或密钥已更换") })
    }

    @Test
    fun `syncOnceNow works without the loop (background worker) and shares the same state`() = test {
        val e = Env()
        seed(e.db, "c1")
        val r = e.controller.syncOnceNow()
        assertEquals(1, r.pushed)
        val again = e.controller.syncOnceNow()
        assertEquals(0, again.pushed + again.applied)
        // 之后前台循环接着用同一份状态:不会重复推送
        e.controller.start()
        waitFor("idle") { e.controller.status.value.phase == SyncPhase.IDLE }
        assertEquals(0, e.controller.status.value.lastPushed)
    }

    @Test
    fun `resetState forgets everything and the next pass reconciles again without losing data`() = test {
        val e = Env()
        val (other, otherDb) = e.otherPhone()
        seed(e.db, "c1")
        e.controller.syncOnceNow()
        other.syncOnce()
        e.controller.resetState()
        SyncStateDb.openOrRecover(BundledSQLiteDriver(), e.stateFile.absolutePath).use { db ->
            assertEquals(0L, db.getCursor())
            assertTrue(db.entries().isEmpty())
            assertEquals(0, db.countFingerprints("conversation"))
        }
        // 同一数据首次接触:相同谱系 → 采纳,没有副本
        e.controller.syncOnceNow()
        assertEquals(setOf("c1"), titles(e.db).keys)
        other.syncOnce()
        assertEquals(setOf("c1"), titles(otherDb).keys)
    }

    @Test
    fun `runNow waits for a pass that started after the call, not for the pass already in flight`() = test {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val scans = java.util.concurrent.atomic.AtomicInteger()
        val reached = kotlinx.coroutines.CompletableDeferred<Unit>()
        val e = Env(poll = 60_000, heartbeat = 600_000)
        val gated = SyncController(
            scope, e.config,
            openStateDb = { SyncStateDb.openOrRecover(BundledSQLiteDriver(), e.stateFile.absolutePath) },
            createHost = { sdb ->
                val inner = AndroidConversationHost(e.db, sdb, e.sink, { false }, "test")
                object : me.rerere.rikkahub.sync.engine.HostAdapter by inner {
                    override suspend fun scan(): List<me.rerere.rikkahub.sync.engine.LocalRef> {
                        val r = inner.scan()
                        // 第 2 次 scan 是第一轮 push 开头的那次:卡在这里,之后落的改动不在这轮里
                        if (scans.incrementAndGet() == 2) { reached.complete(Unit); gate.await() }
                        return r
                    }
                }
            },
            pendingLocalChanges = { 0 },
            options = SyncControllerOptions(pollMs = 60_000, heartbeatMs = 600_000, debounceMs = 5, baseBackoffMs = 30, maxBackoffMs = 120),
        )
        gated.start()
        reached.await() // 第一轮正卡在 push 的 scan 之后
        seed(e.db, "late") // 这次改动不会被进行中的这一轮看到
        val waiting = scope.async { gated.runNow() }
        delay(100)
        assertTrue("进行中的那一轮结束前 runNow 不能返回", !waiting.isCompleted)
        gate.complete(Unit)
        val report = kotlinx.coroutines.withTimeout(5000) { waiting.await() }
        assertEquals("runNow 的报告必须来自调用之后才开始的一轮,所以包含 late", 1, report.pushed)
        gated.stop()
    }

    @Test
    fun `stop is idempotent and rejects pending runNow`() = test {
        val e = Env(poll = 60_000, heartbeat = 600_000)
        e.controller.start()
        waitFor("idle") { e.controller.status.value.phase == SyncPhase.IDLE }
        e.controller.stop()
        e.controller.stop()
        assertEquals(false, e.controller.status.value.live)
        assertThrows(IllegalStateException::class.java) { runBlocking { e.controller.runNow() } }
    }
}
