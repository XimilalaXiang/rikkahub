package me.rerere.rikkahub.sync.engine

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * Kotlin 引擎 ↔ **TypeScript 引擎**的互操作:两端经真实服务器互相同步,证明线格式(信封字段名、base64、版本/AAD、
 * 附件令牌)真的一致,而不只是各自跟自己一致。黄金向量只钉住密码学字节,信封结构只能靠互操作钉。
 */
class InteropTest {
    private lateinit var server: ServerHarness
    private val master = Crypto.generateMasterKey()
    private val keys = Crypto.deriveKeys(master)
    private val recovery = Crypto.encodeRecoveryKey(master)
    private lateinit var repo: String

    @Before
    fun setUp() {
        server = ServerHarness.startOrSkip()
        repo = System.getenv("RIKKASYNC_REPO")!!
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.close()
    }

    private fun runPeer(token: String, vararg put: String): kotlinx.serialization.json.JsonObject {
        val cmd = listOf("bun", File(repo, "core/test/interop-peer.ts").absolutePath, server.url, token, recovery) + put
        val p = ProcessBuilder(cmd).directory(File(repo)).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        assumeTrue("bun peer failed to start: $err", p.waitFor() == 0 || out.isNotBlank())
        assertEquals("peer stderr: $err", 0, p.exitValue())
        return Json.parseToJsonElement(out.trim().lines().last()).jsonObject
    }

    @Test
    fun `kotlin and typescript engines exchange records and attachments through a real server`() = runBlocking {
        val (kToken, kId) = server.createDevice("kotlin")
        val client = SyncClient(server.url, kToken)
        val host = FakeHost("kotlin")
        val engine = SyncEngine(client, keys, host, MemoryStateStore(), kId)

        // Kotlin → TypeScript(含一个附件,和一个大到会走 gzip 的文本)
        val file = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 1, 2, 3)
        host.put("n1", "from kotlin ✓ 你好", 1000, mapOf("a.png" to file))
        host.put("big", "lorem ipsum ".repeat(500), 1001)
        engine.syncOnce()

        val (tToken, _) = server.createDevice("ts")
        val first = runPeer(tToken, "n2", "from typescript", "2000")
        assertEquals(emptyList<String>(), first.getValue("errors").jsonArray.map { it.jsonPrimitive.content })
        val items = first.getValue("items").jsonObject
        assertEquals("from kotlin ✓ 你好", items.getValue("n1").jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("lorem ipsum ".repeat(500), items.getValue("big").jsonObject.getValue("text").jsonPrimitive.content)
        assertArrayEquals(file, Base64.getDecoder().decode(items.getValue("n1").jsonObject.getValue("files").jsonObject.getValue("a.png").jsonPrimitive.content))

        // TypeScript → Kotlin
        val r = engine.syncOnce()
        assertTrue(r.errors.toString(), r.errors.isEmpty())
        assertEquals("from typescript", host.items.getValue("n2").text)
        assertEquals(2000L, host.items.getValue("n2").updatedAt)
        // 回声:Kotlin 自己推的记录没有被重复应用或推回
        assertEquals(0, engine.syncOnce().pushed)
    }
}
