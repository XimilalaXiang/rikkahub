package me.rerere.rikkahub.sync.host

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.sync.engine.PermanentApplyException
import me.rerere.rikkahub.sync.engine.RemoteEntity
import me.rerere.rikkahub.sync.host.TestAssistantSink.Companion.assistant
import me.rerere.rikkahub.sync.state.SyncStateDb
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 助手适配器的单元级契约(不经引擎):校验、读回指纹、合并规则。 */
class AssistantHostUnitTest {
    private val closers = ArrayList<AutoCloseable>()
    private val id = "11111111-1111-4111-8111-111111111111"

    @After
    fun tearDown() = closers.reversed().forEach { runCatching { it.close() } }

    private fun env(): Triple<AssistantHost, TestAssistantSink, SyncStateDb> {
        val (db, close) = TestAndroidDb.open()
        closers.add(close)
        val syncDb = SyncStateDb.openOrRecover(BundledSQLiteDriver(), ":memory:").also { closers.add(it) }
        val sink = TestAssistantSink()
        return Triple(AssistantHost(db, syncDb, sink), sink, syncDb)
    }

    private fun remote(entityId: String, data: kotlinx.serialization.json.JsonElement) = RemoteEntity(AssistantContract.TYPE, entityId, AssistantContract.FMT, data, 1, null, emptyList())

    @Test
    fun `apply rejects records that can never be applied`() = runBlocking {
        val (host, sink, _) = env()
        val good = AssistantWire.synced(assistant(id, "x"))
        assertThrows(PermanentApplyException::class.java) { runBlocking { host.apply(remote("providers/$id", good)) } }
        assertThrows(PermanentApplyException::class.java) { runBlocking { host.apply(remote("assistants/$id", JsonPrimitive("x"))) } }
        assertThrows(PermanentApplyException::class.java) { runBlocking { host.apply(remote("assistants/$id", AssistantWire.synced(assistant("other", "x")))) } }
        assertThrows(PermanentApplyException::class.java) { runBlocking { host.apply(remote("assistants/$id", JsonObject(good.filterKeys { it != "name" }))) } }
        assertTrue(sink.items.isEmpty())
        host.apply(remote("assistants/$id", good))
        assertEquals(setOf(id), sink.items.keys)
    }

    @Test
    fun `apply returns the fingerprint of what was actually stored, so a normalising store does not cause a push-back`() = runBlocking {
        val (host, sink, _) = env()
        sink.normalize = { JsonObject(it + ("name" to JsonPrimitive(it.getValue("name").jsonPrimitive.content.trim()))) }
        val fp = host.apply(remote("assistants/$id", AssistantWire.synced(assistant(id, "  padded  "))))
        val scanned = host.scan().single()
        assertEquals(fp, scanned.fingerprint)
        assertEquals("padded", sink.items.getValue(id).getValue("name").jsonPrimitive.content)
        assertNotEquals(AssistantWire.fingerprint(AssistantWire.synced(assistant(id, "  padded  "))), fp)
    }

    @Test
    fun `merge never takes a local file avatar from the wire`() {
        val image = buildJsonObject { put("type", "me.rerere.rikkahub.data.model.Avatar.Image"); put("url", "file:///x.png") }
        val emoji = buildJsonObject { put("type", "me.rerere.rikkahub.data.model.Avatar.Emoji"); put("content", "🦊") }
        val local = assistant(id, "x", "avatar" to emoji)
        // 即使有实现把 Image 头像塞进了线数据,也不能落进来
        val merged = AssistantWire.merge(local, JsonObject(AssistantWire.synced(local) + ("avatar" to image)), emptySet())
        assertEquals(emoji, merged["avatar"])
        assertEquals(TestAssistantSink.DUMMY_AVATAR, (AssistantWire.merge(null, JsonObject(mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive("n"), "avatar" to image)), emptySet())["avatar"] as? JsonObject)?.get("type")?.jsonPrimitive?.content ?: TestAssistantSink.DUMMY_AVATAR)
    }

    @Test
    fun `synced form drops device-local fields and local file avatars and is stable`() {
        val full = assistant(id, "x", "mcpServers" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("m"))), "avatar" to buildJsonObject { put("type", "x.Avatar.Image"); put("url", "file:///a") })
        val synced = AssistantWire.synced(full)
        assertTrue(synced.keys.none { it in AssistantContract.DEVICE_LOCAL_FIELDS })
        assertTrue("avatar" !in synced.keys)
        assertEquals(AssistantWire.fingerprint(synced), AssistantWire.fingerprint(AssistantWire.synced(full)))
        assertEquals(AssistantContract.SYNCED_FIELDS.filter { it != "avatar" }, synced.keys.toList())
    }
}
