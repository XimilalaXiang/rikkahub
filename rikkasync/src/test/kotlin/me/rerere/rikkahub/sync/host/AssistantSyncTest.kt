package me.rerere.rikkahub.sync.host

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.rikkahub.sync.engine.Crypto
import me.rerere.rikkahub.sync.engine.ServerHarness
import me.rerere.rikkahub.sync.host.TestAssistantSink.Companion.assistant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 助手(A7):两台带助手存储的"手机" × 真实引擎 × 真实服务器。 */
class AssistantSyncTest {
    private lateinit var server: ServerHarness
    private val keys = Crypto.deriveKeys(Crypto.generateMasterKey())
    private val closers = ArrayList<AutoCloseable>()
    private val a1 = "11111111-1111-4111-8111-111111111111"
    private val a2 = "22222222-2222-4222-8222-222222222222"
    private val builtIn = "3d47790c-c415-4b90-9388-751128adb0a0"

    @Before
    fun setUp() {
        server = ServerHarness.startOrSkip()
    }

    @After
    fun tearDown() {
        closers.reversed().forEach { runCatching { it.close() } }
        if (::server.isInitialized) server.close()
    }

    private fun phone(name: String) = TestPhone(server, keys, name, withAssistants = true).also { closers.add(it) }
    private fun run(block: suspend () -> Unit) = runBlocking { block() }
    private suspend fun settle(vararg phones: TestPhone) = repeat(4) { for (p in phones) p.engine.syncOnce() }
    private fun str(v: String) = JsonPrimitive(v)
    private fun TestPhone.put(a: JsonObject) { assistants!!.items[a.getValue("id").jsonPrimitive.content] = a }
    private fun TestPhone.get(id: String) = assistants!!.items[id]
    private fun TestPhone.convOf(assistantId: String, convId: String) {
        TestAndroidDb.insertConversation(db, convId)
        TestAndroidDb.insertNode(db, "$convId-n0", convId, 0, """[{"id":"m-$convId","role":"user","parts":[{"type":"text","text":"hi"}],"createdAt":"2026-01-01T00:00:00"}]""")
        db.exec("UPDATE ConversationEntity SET assistant_id = ? WHERE id = ?", listOf(assistantId, convId))
    }

    @Test
    fun `an assistant created on one phone reaches the other with only synced fields, and nothing echoes`() = run {
        val a = phone("a"); val b = phone("b")
        a.put(assistant(a1, "写作助手", "systemPrompt" to str("你是写作助手"), "temperature" to JsonPrimitive(0.7), "mcpServers" to JsonArray(listOf(str("m1"))), "workspaceId" to str("w1"), "background" to str("file:///bg.png")))
        settle(a, b)
        val onB = b.get(a1)!!
        assertEquals("写作助手", onB.getValue("name").jsonPrimitive.content)
        assertEquals("你是写作助手", onB.getValue("systemPrompt").jsonPrimitive.content)
        assertEquals(0.7, onB.getValue("temperature").jsonPrimitive.content.toDouble(), 0.0)
        // 设备本地字段没有跟过来:B 上是默认值
        assertEquals(JsonArray(emptyList()), onB["mcpServers"])
        assertEquals(JsonNull, onB["workspaceId"])
        assertEquals(JsonNull, onB["background"])
        // A 没变,双方都安静
        assertEquals("w1", a.get(a1)!!.getValue("workspaceId").jsonPrimitive.content)
        val idleA = a.engine.syncOnce(); val idleB = b.engine.syncOnce()
        assertEquals(0, idleA.pushed + idleA.applied + idleB.pushed + idleB.applied)
    }

    @Test
    fun `edits follow, including resetting a value back to null, but device-local edits never push`() = run {
        val a = phone("a"); val b = phone("b")
        a.put(assistant(a1, "x", "temperature" to JsonPrimitive(0.7)))
        settle(a, b)
        a.put(a.get(a1)!!.with("temperature" to JsonNull).with("name" to str("y")))
        settle(a, b)
        assertEquals(JsonNull, b.get(a1)!!["temperature"])
        assertEquals("y", b.get(a1)!!.getValue("name").jsonPrimitive.content)
        // 只改设备本地字段:不产生任何推送
        a.put(a.get(a1)!!.with("workspaceId" to str("w2")).with("mcpServers" to JsonArray(listOf(str("m")))))
        val r = a.engine.syncOnce()
        assertEquals(0, r.pushed)
        // B 本机的设备本地字段不会被来自 A 的更新覆盖
        b.put(b.get(a1)!!.with("mcpServers" to JsonArray(listOf(str("b-mcp")))).with("localTools" to JsonArray(listOf(buildJsonObject { put("type", "b_tool") }))))
        a.put(a.get(a1)!!.with("name" to str("z")))
        settle(a, b)
        assertEquals("z", b.get(a1)!!.getValue("name").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(str("b-mcp"))), b.get(a1)!!["mcpServers"])
        assertEquals(JsonArray(listOf(buildJsonObject { put("type", "b_tool") })), b.get(a1)!!["localTools"])
    }

    @Test
    fun `the receiving side's normalisation of dangling references cannot leak back to the sender`() = run {
        val a = phone("a"); val b = phone("b")
        a.assistants!!.knownLorebooks.add("L1")
        a.put(assistant(a1, "x", "lorebookIds" to JsonArray(listOf(str("L1")))))
        settle(a, b)
        assertEquals(JsonArray(emptyList()), b.get(a1)!!["lorebookIds"]) // B 没有这本世界书,本来就不带过来
        b.put(b.get(a1)!!.with("name" to str("renamed on b")))
        settle(a, b)
        assertEquals("renamed on b", a.get(a1)!!.getValue("name").jsonPrimitive.content)
        assertEquals("A 的关联还在", JsonArray(listOf(str("L1"))), a.get(a1)!!["lorebookIds"])
    }

    @Test
    fun `local file avatars stay on their device while emoji avatars sync`() = run {
        val a = phone("a"); val b = phone("b")
        val image = buildJsonObject { put("type", "me.rerere.rikkahub.data.model.Avatar.Image"); put("url", "file:///a/avatar.png") }
        val emoji = buildJsonObject { put("type", "me.rerere.rikkahub.data.model.Avatar.Emoji"); put("content", "🦊") }
        a.put(assistant(a1, "x", "avatar" to image))
        settle(a, b)
        assertEquals(TestAssistantSink.DUMMY_AVATAR, (b.get(a1)!!["avatar"] as JsonObject).getValue("type").jsonPrimitive.content)
        // A 改成 emoji → B 跟着变
        a.put(a.get(a1)!!.with("avatar" to emoji))
        settle(a, b)
        assertEquals(emoji, b.get(a1)!!["avatar"])
        // B 自己换成本地图片(先同步掉这次改动);之后 A 改名字、甚至改 emoji 都不会把 B 的图片覆盖掉
        b.put(b.get(a1)!!.with("avatar" to buildJsonObject { put("type", "me.rerere.rikkahub.data.model.Avatar.Image"); put("url", "file:///b/pic.png") }))
        settle(a, b)
        assertEquals("B 换图片不影响 A 的 emoji", emoji, a.get(a1)!!["avatar"])
        a.put(a.get(a1)!!.with("name" to str("renamed")).with("avatar" to buildJsonObject { put("type", "me.rerere.rikkahub.data.model.Avatar.Emoji"); put("content", "🐼") }))
        settle(a, b)
        assertEquals("renamed", b.get(a1)!!.getValue("name").jsonPrimitive.content)
        assertEquals("file:///b/pic.png", (b.get(a1)!!["avatar"] as JsonObject).getValue("url").jsonPrimitive.content)
    }

    @Test
    fun `a model the receiving phone does not know is not adopted`() = run {
        val a = phone("a"); val b = phone("b")
        a.put(assistant(a1, "x", "chatModelId" to str("model-A")))
        settle(a, b)
        assertEquals("新助手引用了本机没有的模型 → 用全局默认", JsonNull, b.get(a1)!!["chatModelId"])
        b.assistants!!.models.add("model-B")
        b.put(b.get(a1)!!.with("chatModelId" to str("model-B")))
        a.put(a.get(a1)!!.with("name" to str("renamed")))
        settle(a, b)
        assertEquals("已有助手保留本机的模型选择", str("model-B"), b.get(a1)!!["chatModelId"])
        b.assistants!!.models.add("model-A2")
        a.put(a.get(a1)!!.with("chatModelId" to str("model-A2")))
        settle(a, b)
        assertEquals("本机认识的模型会被采用", str("model-A2"), b.get(a1)!!["chatModelId"])
    }

    @Test
    fun `deleting an assistant together with its conversations (the app flow) converges and nothing comes back`() = run {
        val a = phone("a"); val b = phone("b")
        a.put(assistant(a1, "x")); a.convOf(a1, "c1")
        settle(a, b)
        assertEquals(setOf("c1"), b.ids())
        a.assistants!!.items.remove(a1)
        a.db.exec("DELETE FROM ConversationEntity WHERE assistant_id = ?", listOf(a1))
        settle(a, b)
        assertNull(b.get(a1))
        assertTrue(b.ids().isEmpty())
        assertNull(a.get(a1))
        val r = a.engine.syncOnce()
        assertEquals(0, r.pushed)
    }

    @Test
    fun `a remote delete is held back while local conversations still use the assistant, and is never pushed back`() = run {
        val a = phone("a"); val b = phone("b")
        a.put(assistant(a1, "x")); a.convOf(a1, "c1")
        settle(a, b)
        // A 只删助手,不删会话(这个顺序也可能发生:墓碑先于会话的墓碑到达)
        a.assistants!!.items.remove(a1)
        settle(a, b)
        assertEquals("B 仍有会话,助手留着", "x", b.get(a1)!!.getValue("name").jsonPrimitive.content)
        assertNull("没有被推回去复活", a.get(a1))
        // 之后 B 的会话被删掉:助手随即被清理
        b.db.exec("DELETE FROM ConversationEntity WHERE id = 'c1'")
        settle(a, b)
        assertNull(b.get(a1))
        assertNull(a.get(a1))
    }

    @Test
    fun `an assistant that is deleted remotely and then recreated is not removed later when the held-back conversations go away`() = run {
        val a = phone("a"); val b = phone("b")
        a.put(assistant(a1, "x")); a.convOf(a1, "c1")
        settle(a, b)
        val original = a.get(a1)!!
        a.assistants!!.items.remove(a1)
        settle(a, b) // B 留着(有会话),登记为待删
        a.put(original.with("name" to str("back again"))) // A 又把它建回来(编辑胜过删除)
        settle(a, b)
        assertEquals("back again", b.get(a1)!!.getValue("name").jsonPrimitive.content)
        b.db.exec("DELETE FROM ConversationEntity WHERE id = 'c1'")
        settle(a, b)
        assertEquals("会话没了也不能把重新出现的助手删掉", "back again", b.get(a1)!!.getValue("name").jsonPrimitive.content)
        assertEquals("back again", a.get(a1)!!.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `built-in assistants are never deleted by sync`() = run {
        val a = phone("a"); val b = phone("b")
        a.put(assistant(builtIn, "")); b.put(assistant(builtIn, ""))
        settle(a, b)
        a.assistants!!.items.remove(builtIn)
        settle(a, b)
        assertTrue("B 的内置助手还在", b.get(builtIn) != null)
        assertNull(a.get(builtIn))
    }

    @Test
    fun `an assistant edited on both phones while apart keeps the server version and saves the other as a renamed copy`() = run {
        val a = phone("a"); val b = phone("b")
        a.put(assistant(a1, "base")); settle(a, b)
        a.put(a.get(a1)!!.with("name" to str("from A")))
        b.put(b.get(a1)!!.with("name" to str("from B")))
        a.engine.syncOnce() // A 先推
        settle(b, a)
        val names = { p: TestPhone -> p.assistants!!.items.values.map { it.getValue("name").jsonPrimitive.content }.toSet() }
        assertEquals(setOf("from A", "from B (冲突副本)"), names(b))
        assertEquals(names(b), names(a))
    }

    @Test
    fun `first contact adopts identical assistants, replaces a pristine built-in, and keeps a customised one as a copy`() = run {
        val a = phone("a"); val b = phone("b")
        a.put(assistant(a1, "same")); b.put(assistant(a1, "same"))
        a.put(assistant(builtIn, "", "systemPrompt" to str("A 的默认提示词"))); b.put(assistant(builtIn, ""))
        a.put(assistant(a2, "custom A")); b.put(assistant(a2, "custom B"))
        a.engine.syncOnce()
        val first = b.engine.syncOnce()
        settle(a, b)
        assertEquals("A 的默认提示词", b.get(builtIn)!!.getValue("systemPrompt").jsonPrimitive.content) // B 的是出厂内容 → 直接采用
        assertEquals("custom A", b.get(a2)!!.getValue("name").jsonPrimitive.content)
        assertTrue("B 改过的 custom B 另存为副本", b.assistants!!.items.values.any { it.getValue("name").jsonPrimitive.content == "custom B (冲突副本)" })
        assertEquals(1, b.assistants!!.items.values.count { it.getValue("name").jsonPrimitive.content == "same" })
        assertEquals("只有被改过的 custom B 留了副本,没改过的内置助手没有", 1, b.assistants!!.items.values.count { it.getValue("name").jsonPrimitive.content.endsWith("(冲突副本)") })
        assertEquals(a.assistants!!.items.keys, b.assistants!!.items.keys)
        assertTrue(first.errors.isEmpty())
    }

    @Test
    fun `a malformed assistant record is skipped and does not block the conversations after it`() = run {
        val a = phone("a"); val b = phone("b")
        a.put(JsonObject(assistant(a1, "x").filterKeys { it != "name" }))
        a.conv("c1", "hi")
        settle(a, b)
        assertNull(b.get(a1))
        assertEquals(setOf("c1"), b.ids())
    }

    @Test
    fun `a failing write is retried on the next pass`() = run {
        val a = phone("a"); val b = phone("b")
        a.put(assistant(a1, "x"))
        b.assistants!!.failPut = IllegalStateException("disk full")
        a.engine.syncOnce()
        val r = b.engine.syncOnce()
        assertTrue(r.errors.isNotEmpty())
        assertNull(b.get(a1))
        b.assistants!!.failPut = null
        settle(a, b)
        assertEquals("x", b.get(a1)!!.getValue("name").jsonPrimitive.content)
    }

    @Test
    fun `without an assistant sink the type is unsupported and the signature differs`() = run {
        val a = phone("a")
        val b = TestPhone(server, keys, "b").also { closers.add(it) }
        a.put(assistant(a1, "x"))
        settle(a, b)
        assertFalse(b.host.isFmtSupported(AssistantContract.TYPE, AssistantContract.FMT))
        assertTrue(a.host.isFmtSupported(AssistantContract.TYPE, AssistantContract.FMT))
        assertFalse(a.host.isFmtSupported(AssistantContract.TYPE, "android-settings-2"))
        assertTrue(a.host.supportSignature() != b.host.supportSignature())
    }

    @Test
    fun `contract fields are consistent`() {
        assertEquals(AssistantContract.SYNCED_FIELDS.size + AssistantContract.DEVICE_LOCAL_FIELDS.size, AssistantContract.ALL_FIELDS.size) // 无重叠
        assertEquals(AssistantContract.ALL_FIELDS, assistant(a1, "x").keys)
        assertEquals("assistants/$a1", AssistantContract.entityId(a1))
        assertEquals(a1, AssistantContract.assistantIdOf("assistants/$a1"))
        assertNull(AssistantContract.assistantIdOf("providers/$a1"))
        assertNull(AssistantContract.assistantIdOf("assistants/"))
        assertNull(AssistantContract.assistantIdOf("assistants/a/b"))
    }

    private fun JsonObject.with(pair: Pair<String, JsonElement>): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>(this).also { it[pair.first] = pair.second })
}
